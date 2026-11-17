package com.roadwise.app.routing

import android.content.Context
import btools.router.FormatJson
import btools.router.RoutingContext
import btools.router.RoutingEngine
import btools.router.RoutingParamCollector
import com.roadwise.app.core.BBox
import com.roadwise.app.core.Geo
import com.roadwise.app.core.LatLon
import com.roadwise.app.core.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.math.floor

/**
 * Fully on-device routing with the BRouter engine over downloaded OpenStreetMap
 * routing segments (5x5 degree .rd5 files from brouter.de).
 */
class OfflineRouter(
    private val ctx: Context,
    private val namer: com.roadwise.app.offline.StreetNamer = com.roadwise.app.offline.StreetNamer(ctx),
) {
    val segmentDir = File(ctx.filesDir, "brouter/segments4").apply { mkdirs() }
    private val profileDir = File(ctx.filesDir, "brouter/profiles2")
    private val lock = Mutex()

    private fun ensureProfiles() {
        val marker = File(profileDir, ".v1")
        if (marker.exists()) return
        profileDir.mkdirs()
        for (name in ctx.assets.list("brouter") ?: emptyArray()) {
            ctx.assets.open("brouter/$name").use { inp -> File(profileDir, name).outputStream().use { inp.copyTo(it) } }
        }
        marker.writeText("ok")
    }

    fun hasDataFor(p: LatLon) = File(segmentDir, segmentName(p.lat, p.lon)).exists()

    fun missingSegments(points: List<LatLon>): List<String> =
        points.map { segmentName(it.lat, it.lon) }.distinct().filter { !File(segmentDir, it).exists() }

    suspend fun route(
        from: LatLon, to: LatLon, mode: TravelMode, s: Settings,
        heading: Double? = null, avoid: List<LatLon> = emptyList(),
    ): Route = withContext(Dispatchers.Default) {
        lock.withLock {
            ensureProfiles()
            val missing = missingSegments(straightLineSamples(from, to))
            if (missing.isNotEmpty()) {
                throw RoutingException("Offline routing data missing for this trip. Download the area in Offline maps (${missing.joinToString()}).")
            }
            val rc = RoutingContext()
            rc.localFunction = File(profileDir, "${mode.brouterProfile}.brf").absolutePath
            rc.memoryclass = 256
            val collector = RoutingParamCollector()
            val wps = collector.getWayPointList("${from.lon},${from.lat}|${to.lon},${to.lat}")
            val params = HashMap<String, String>()
            params["timode"] = "2"
            if (heading != null) params["direction"] = heading.toInt().toString()
            if (avoid.isNotEmpty()) params["nogos"] = avoid.joinToString("|") { "${it.lon},${it.lat},25" }
            collector.setParams(rc, wps, params)
            val profileParams = HashMap<String, String>()
            profileParams["turnInstructionMode"] = "2"
            if (mode == TravelMode.DRIVE) {
                profileParams["avoid_toll"] = if (s.avoidTolls) "1" else "0"
                profileParams["avoid_motorways"] = if (s.avoidHighways) "1" else "0"
            }
            collector.setProfileParams(rc, profileParams)

            val engine = RoutingEngine(null, null, segmentDir, wps, rc)
            engine.quite = true
            engine.doRun(90_000)
            engine.errorMessage?.let { throw RoutingException("Offline route failed: $it") }
            val track = engine.foundTrack ?: throw RoutingException("No offline route found")
            parse(JSONObject(FormatJson(rc).format(track)), mode, to)
        }
    }

    private fun straightLineSamples(a: LatLon, b: LatLon): List<LatLon> {
        val n = (Geo.dist(a, b) / 20_000).toInt().coerceIn(1, 200)
        return (0..n).map { Geo.interpolate(a, b, it / n.toDouble()) }
    }

    private fun parse(json: JSONObject, mode: TravelMode, dest: LatLon): Route {
        val f = json.getJSONArray("features").getJSONObject(0)
        val props = f.getJSONObject("properties")
        val coords = f.getJSONObject("geometry").getJSONArray("coordinates")
        val points = ArrayList<LatLon>(coords.length())
        for (i in 0 until coords.length()) {
            val c = coords.getJSONArray(i)
            points += LatLon(c.getDouble(1), c.getDouble(0))
        }
        val cum = Geo.cumulative(points)
        val totalTime = props.optString("total-time").toDoubleOrNull() ?: 0.0
        val cumTime = DoubleArray(points.size)
        val times = props.optJSONArray("times")
        if (times != null && times.length() == points.size) {
            for (i in points.indices) cumTime[i] = times.optDouble(i, 0.0)
        } else {
            val speed = when (mode) { TravelMode.DRIVE -> 13.0; TravelMode.BIKE -> 4.5; TravelMode.WALK -> 1.3 }
            for (i in points.indices) cumTime[i] = cum[i] / speed
        }

        // Speed limits from the per-way message table (WayTags contain maxspeed=..).
        val limits = IntArray(points.size)
        props.optJSONArray("messages")?.let { msgs ->
            var startDist = 0.0
            var p = 0
            for (r in 1 until msgs.length()) {
                val row = msgs.getJSONArray(r)
                val d = row.optString(3).toDoubleOrNull() ?: continue
                val tags = row.optString(9)
                val lim = Regex("maxspeed=(\\d+)").find(tags)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val endDist = startDist + d
                while (p < points.size - 1 && cum[p] < endDist) { limits[p] = lim; p++ }
                startDist = endDist
            }
        }

        val maneuvers = ArrayList<Maneuver>()
        maneuvers += Maneuver(Turn.DEPART, "Head out", "Head out on the highlighted route", null, 0)
        props.optJSONArray("voicehints")?.let { hints ->
            for (i in 0 until hints.length()) {
                val h = hints.getJSONArray(i)
                val idx = h.getInt(0).coerceIn(0, points.size - 1)
                val turn = brouterTurn(h.getInt(1)) ?: continue
                val exit = h.optInt(2, 0)
                val text = genericInstruction(turn, exit, null)
                maneuvers += Maneuver(turn, text, text, null, idx, exit)
            }
        }
        maneuvers += Maneuver(Turn.ARRIVE, "Arrive at your destination", "You have arrived at your destination", null, points.size - 1)
        maneuvers.sortBy { it.pointIndex }
        nameStreets(maneuvers, points, cum)

        return Route(
            points = points,
            maneuvers = maneuvers,
            durationS = if (totalTime > 0) totalTime else cumTime.last(),
            cumTime = cumTime,
            speedLimits = limits,
            source = "offline",
            label = "via downloaded map",
            mode = mode,
            destination = dest,
        )
    }

    /** Adds street names (from the downloaded map) to each instruction: the road you turn onto. */
    private fun nameStreets(ms: MutableList<Maneuver>, pts: List<LatLon>, cum: DoubleArray) {
        for (i in ms.indices) {
            val m = ms[i]
            if (m.turn == Turn.ARRIVE) continue
            val street = runCatching {
                // Look a little past the turn so we read the new road, not the one we're leaving.
                listOf(35.0, 60.0, 20.0).firstNotNullOfOrNull { ahead ->
                    val (p, b) = pointAfter(pts, cum, m.pointIndex, ahead)
                    namer.nameAt(p, b)
                }
            }.getOrNull() ?: continue
            val text = genericInstruction(m.turn, m.exit, street)
            ms[i] = m.copy(text = text, spoken = text, street = street)
        }
    }

    private fun pointAfter(pts: List<LatLon>, cum: DoubleArray, idx: Int, meters: Double): Pair<LatLon, Double> {
        val target = cum[idx] + meters
        var j = idx + 1
        while (j < pts.size - 1 && cum[j] < target) j++
        j = j.coerceAtMost(pts.size - 1)
        val a = pts[(j - 1).coerceAtLeast(0)]; val b = pts[j]
        val seg = (cum[j] - cum[(j - 1).coerceAtLeast(0)]).coerceAtLeast(0.01)
        val t = ((target - cum[(j - 1).coerceAtLeast(0)]) / seg).coerceIn(0.0, 1.0)
        return Geo.interpolate(a, b, t) to Geo.bearing(a, b)
    }

    private fun brouterTurn(cmd: Int): Turn? = when (cmd) {
        1 -> Turn.STRAIGHT
        2 -> Turn.LEFT
        3 -> Turn.SLIGHT_LEFT
        4 -> Turn.SHARP_LEFT
        5 -> Turn.RIGHT
        6 -> Turn.SLIGHT_RIGHT
        7 -> Turn.SHARP_RIGHT
        8 -> Turn.KEEP_LEFT
        9 -> Turn.KEEP_RIGHT
        10, 11, 15 -> Turn.UTURN
        13, 14 -> Turn.ROUNDABOUT
        17 -> Turn.EXIT_LEFT
        18 -> Turn.EXIT_RIGHT
        else -> null // off-route / beeline markers
    }

    companion object {
        const val SEGMENT_BASE = "https://brouter.de/brouter/segments4/"

        fun segmentName(lat: Double, lon: Double): String {
            val lonBase = (floor(lon / 5.0) * 5).toInt()
            val latBase = (floor(lat / 5.0) * 5).toInt()
            val lo = if (lonBase < 0) "W${-lonBase}" else "E$lonBase"
            val la = if (latBase < 0) "S${-latBase}" else "N$latBase"
            return "${lo}_$la.rd5"
        }

        fun segmentsFor(b: BBox): List<String> {
            val out = LinkedHashSet<String>()
            var lat = floor(b.minLat / 5.0) * 5
            while (lat <= b.maxLat) {
                var lon = floor(b.minLon / 5.0) * 5
                while (lon <= b.maxLon) { out += segmentName(lat + 0.1, lon + 0.1); lon += 5 }
                lat += 5
            }
            return out.toList()
        }
    }
}
