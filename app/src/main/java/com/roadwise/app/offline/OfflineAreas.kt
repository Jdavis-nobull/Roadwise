package com.roadwise.app.offline

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.roadwise.app.core.BBox
import com.roadwise.app.core.Db
import com.roadwise.app.core.Geo
import com.roadwise.app.core.Net
import com.roadwise.app.core.OfflineArea
import com.roadwise.app.core.SpeedCamera
import com.roadwise.app.routing.OfflineRouter
import com.roadwise.app.ui.MapStyles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import org.maplibre.android.storage.FileSource
import java.io.File
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class AreaEstimate(val tiles: Long, val mapMb: Double, val routingFiles: List<String>, val routingMbGuess: Double)

/**
 * Downloads everything needed to use an area with no signal: map tiles (day + night styles),
 * BRouter routing data, speed cameras, and a searchable index of places.
 */
class OfflineAreas(
    private val ctx: Context,
    private val db: Db,
    private val net: Net,
    private val router: OfflineRouter,
) {
    private val _areas = MutableStateFlow<List<OfflineArea>>(emptyList())
    val areas: StateFlow<List<OfflineArea>> = _areas
    private val om by lazy { OfflineManager.getInstance(ctx) }

    fun refresh() { _areas.value = db.areas() }

    fun estimate(b: BBox): AreaEstimate {
        val tiles = Geo.tileCount(b, 0, MAX_ZOOM)
        val segs = OfflineRouter.segmentsFor(b)
        val missing = segs.filter { !File(router.segmentDir, it).exists() }
        // Big areas are mostly countryside, whose tiles are much smaller than city tiles.
        val kbPerTile = when { tiles > 200_000 -> 9.0; tiles > 40_000 -> 13.0; else -> 22.0 }
        return AreaEstimate(tiles, tiles * kbPerTile / 1024.0, segs, missing.size * 120.0)
    }

    /** Queues whole-state downloads (skips states already downloaded or queued). */
    fun startRegions(list: List<Region>): Int {
        val have = db.areas().map { it.name }.toSet()
        val todo = list.filter { it.name !in have }
        todo.forEach { db.insertArea(it.name, it.bbox) }
        refresh()
        if (todo.isNotEmpty()) ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
        return todo.size
    }

    fun start(name: String, b: BBox) {
        db.insertArea(name, b)
        refresh()
        ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
    }

    /** The download currently running in [DownloadService], so it can be cancelled. */
    @Volatile var activeJob: kotlinx.coroutines.Job? = null
    @Volatile var activeId: Long = -1

    /** Stops a queued or running download and removes whatever it had saved so far. */
    suspend fun cancel(a: OfflineArea) {
        // Mark it first so the download queue never picks it up again.
        db.updateArea(a.id) { put("status", "cancelled") }
        refresh()
        if (activeId == a.id) activeJob?.let { it.cancel(); it.join() }
        withContext(Dispatchers.IO) { router.segmentDir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() } }
        delete(db.areas().firstOrNull { it.id == a.id } ?: a)
    }

    fun nextQueued(): OfflineArea? = db.areas().lastOrNull { it.status == "queued" || it.status == "downloading" }

    fun retry(a: OfflineArea) {
        db.updateArea(a.id) { put("status", "queued"); put("error", null as String?); put("step", "Waiting") }
        refresh()
        ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
    }

    private fun set(id: Long, step: String, progress: Double) {
        db.updateArea(id) { put("status", "downloading"); put("step", step); put("progress", progress) }
        refresh()
    }

    /** Runs the full download for one area. Called from [DownloadService]. */
    suspend fun download(a: OfflineArea, onStep: (String, Double) -> Unit) {
        val id = a.id
        fun report(step: String, p: Double) { set(id, step, p); onStep(step, p) }
        try {
            // Re-downloading (update/retry): drop the previous map regions first.
            deleteRegions(a.regionIds.split(',').mapNotNull { it.trim().toLongOrNull() }.toSet())

            // 1) Map tiles for day and night styles (the night style reuses the same tiles).
            report("Map tiles", 0.0)
            val regionIds = ArrayList<Long>()
            var bytes = 0L
            val r1 = downloadRegion(MapStyles.DAY, a.bbox, a.name) { done, req, b ->
                bytes = b
                report("Map tiles ${done}/${req}", 0.65 * done / req.coerceAtLeast(1))
            }
            regionIds += r1
            val r2 = downloadRegion(MapStyles.NIGHT, a.bbox, a.name + " (night)") { done, req, _ ->
                report("Night map ${done}/${req}", 0.65 + 0.05 * done / req.coerceAtLeast(1))
            }
            regionIds += r2
            db.updateArea(id) { put("regionIds", regionIds.joinToString(",")); put("mapBytes", bytes) }

            // 2) Routing data (5x5 degree BRouter segments).
            val segs = OfflineRouter.segmentsFor(a.bbox)
            segs.forEachIndexed { i, name ->
                val f = File(router.segmentDir, name)
                if (!f.exists()) {
                    val ok = net.download(OfflineRouter.SEGMENT_BASE + name, f) { d, t ->
                        val frac = if (t > 0) d.toDouble() / t else 0.0
                        report("Routing data ${i + 1}/${segs.size} (${d / 1_048_576} MB)", 0.70 + 0.2 * (i + frac) / segs.size)
                    }
                    if (!ok) Log.w(TAG, "no routing segment $name (ocean?)")
                }
            }
            db.updateArea(id) { put("segments", segs.joinToString(",")) }

            // 3) Speed cameras from OpenStreetMap.
            report("Speed cameras", 0.91)
            val cams = runCatching { fetchCameras(a.bbox) }.onFailure { Log.w(TAG, "cameras", it) }.getOrDefault(emptyList())
            db.putCameras(cams)
            db.updateArea(id) { put("cameras", cams.size) }

            // 4) Offline search index from the downloaded vector tiles.
            report("Building search index", 0.93)
            val dbFile = File(FileSource.getResourcesCachePath(ctx), "mbgl-offline.db")
            val places = withContext(Dispatchers.IO) {
                MvtIndexer.index(dbFile, a.bbox) { p -> report("Building search index", 0.93 + 0.06 * p) }
            }
            withContext(Dispatchers.IO) {
                db.clearIndex(id)
                db.indexPlaces(id, places)
            }
            db.updateArea(id) {
                put("pois", places.size); put("status", "ready"); put("progress", 1.0); put("step", "Ready")
            }
            refresh()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // cancelled by the user; cancel() cleans up
        } catch (e: Exception) {
            Log.e(TAG, "download failed", e)
            db.updateArea(id) { put("status", "error"); put("error", e.message ?: e.javaClass.simpleName); put("step", "Failed") }
            refresh()
        }
    }

    private suspend fun downloadRegion(
        style: String, b: BBox, name: String, progress: (Long, Long, Long) -> Unit,
    ): Long = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val bounds = LatLngBounds.Builder().include(LatLng(b.maxLat, b.maxLon)).include(LatLng(b.minLat, b.minLon)).build()
            val def = OfflineTilePyramidRegionDefinition(style, bounds, 0.0, MAX_ZOOM.toDouble(), ctx.resources.displayMetrics.density)
            val meta = JSONObject().put("name", name).toString().toByteArray()
            om.createOfflineRegion(def, meta, object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    offlineRegion.setDeliverInactiveMessages(false)
                    offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                        override fun onStatusChanged(status: OfflineRegionStatus) {
                            progress(status.completedResourceCount, status.requiredResourceCount, status.completedResourceSize)
                            if (status.isComplete && cont.isActive) {
                                offlineRegion.setObserver(null)
                                offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                cont.resume(offlineRegion.id)
                            }
                        }
                        override fun onError(error: OfflineRegionError) {
                            Log.w(TAG, "region error ${error.reason}: ${error.message}")
                        }
                        override fun mapboxTileCountLimitExceeded(limit: Long) {
                            if (cont.isActive) cont.resumeWithException(IllegalStateException("Tile limit exceeded ($limit)"))
                        }
                    })
                    offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                    cont.invokeOnCancellation { offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE) }
                }
                override fun onError(error: String) {
                    if (cont.isActive) cont.resumeWithException(IllegalStateException(error))
                }
            })
        }
    }

    private suspend fun fetchCameras(b: BBox): List<SpeedCamera> {
        val q = "[out:json][timeout:60];node[highway=speed_camera](${b.minLat},${b.minLon},${b.maxLat},${b.maxLon});out;"
        val json = JSONObject(net.overpass(q))
        val els = json.getJSONArray("elements")
        return (0 until els.length()).map {
            val e = els.getJSONObject(it)
            val tags = e.optJSONObject("tags")
            SpeedCamera(e.getLong("id"), e.getDouble("lat"), e.getDouble("lon"), parseMaxspeed(tags?.optString("maxspeed")))
        }
    }

    /** Fetch cameras along a route bounding box when online (cached for later offline use). */
    suspend fun cacheCamerasFor(b: BBox) {
        if (!net.isOnline) return
        runCatching { db.putCameras(fetchCameras(b)) }.onFailure { Log.w(TAG, "route cameras", it) }
    }

    suspend fun delete(a: OfflineArea) {
        deleteRegions(a.regionIds.split(',').mapNotNull { it.trim().toLongOrNull() }.toSet())
        withContext(Dispatchers.IO) {
            val stillUsed = db.areas().filter { it.id != a.id }.flatMap { it.segments.split(',') }.toSet()
            a.segments.split(',').filter { it.isNotBlank() && it !in stillUsed }.forEach { File(router.segmentDir, it).delete() }
            db.deleteArea(a.id)
        }
        refresh()
    }

    private suspend fun deleteRegions(ids: Set<Long>) {
        if (ids.isNotEmpty()) withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Unit> { cont ->
                om.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
                    override fun onList(offlineRegions: Array<OfflineRegion>?) {
                        val targets = offlineRegions.orEmpty().filter { it.id in ids }
                        if (targets.isEmpty()) { cont.resume(Unit); return }
                        var left = targets.size
                        for (r in targets) r.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                            override fun onDelete() { if (--left == 0 && cont.isActive) cont.resume(Unit) }
                            override fun onError(error: String) { if (--left == 0 && cont.isActive) cont.resume(Unit) }
                        })
                    }
                    override fun onError(error: String) { if (cont.isActive) cont.resume(Unit) }
                })
            }
        }
    }

    fun storageUsedBytes(): Long {
        val seg = router.segmentDir.listFiles()?.sumOf { it.length() } ?: 0L
        val map = File(FileSource.getResourcesCachePath(ctx), "mbgl-offline.db").length()
        return seg + map
    }

    companion object {
        const val TAG = "OfflineAreas"
        const val MAX_ZOOM = 14

        fun parseMaxspeed(s: String?): Int {
            if (s.isNullOrBlank()) return 0
            val n = Regex("(\\d+)").find(s)?.groupValues?.get(1)?.toIntOrNull() ?: return 0
            return if (s.contains("mph")) (n * 1.609344).toInt() else n
        }
    }
}
