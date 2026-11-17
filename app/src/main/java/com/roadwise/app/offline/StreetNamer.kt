package com.roadwise.app.offline

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import android.util.LruCache
import com.roadwise.app.core.Geo
import com.roadwise.app.core.LatLon
import org.maplibre.android.storage.FileSource
import java.io.File

/**
 * Finds street names with no internet, from the road-name lines inside the vector map tiles
 * MapLibre has saved on the phone (offline downloads and recently viewed areas).
 */
class StreetNamer(private val ctx: Context) {
    private class Road(val name: String, val pts: List<LatLon>, val cls: String = "")

    private val cache = LruCache<Long, List<Road>>(48)
    private var db: SQLiteDatabase? = null

    private fun open(): SQLiteDatabase? {
        db?.let { if (it.isOpen) return it }
        val f = File(FileSource.getResourcesCachePath(ctx), "mbgl-offline.db")
        if (!f.exists()) return null
        return runCatching { SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY) }
            .onFailure { Log.w(TAG, "open db", it) }.getOrNull()?.also { db = it }
    }

    /**
     * Name of the road at [p] heading roughly [bearing] (degrees), or null if unknown.
     * Roads within ~35 m whose direction matches (either way) are considered.
     */
    @Synchronized
    fun nameAt(p: LatLon, bearing: Double?): String? {
        val x = Geo.lonToTileX(p.lon, Z); val y = Geo.latToTileY(p.lat, Z)
        var best: String? = null; var bestScore = Double.MAX_VALUE
        // Check this tile and neighbours (the point may sit near a tile edge).
        for (dx in -1..1) for (dy in -1..1) {
            val roads = roadsIn(x + dx, y + dy)
            for (r in roads) if (r.name.isNotEmpty()) for (i in 0 until r.pts.size - 1) {
                val a = r.pts[i]; val b = r.pts[i + 1]
                if (Math.abs(a.lat - p.lat) > 0.01 || Math.abs(a.lon - p.lon) > 0.01) continue
                val d = Geo.projectOnSegment(p, a, b).second
                if (d > 35) continue
                var score = d
                if (bearing != null) {
                    val sb = Geo.bearing(a, b)
                    val diff = minOf(Geo.angleDiff(sb, bearing), Geo.angleDiff((sb + 180) % 360, bearing))
                    if (diff > 40) continue
                    score += diff * 0.4
                }
                if (score < bestScore) { bestScore = score; best = r.name }
            }
        }
        return best
    }

    private fun roadsIn(x: Int, y: Int): List<Road> {
        val key = x.toLong() * 100_000 + y
        cache.get(key)?.let { return it }
        val db = open() ?: return emptyList()
        val roads = runCatching {
            db.rawQuery(
                "SELECT data, compressed FROM tiles WHERE z=? AND x=? AND y=? AND url_template LIKE '%planet%' ORDER BY modified DESC LIMIT 1",
                arrayOf(Z.toString(), x.toString(), y.toString())
            ).use { c ->
                if (!c.moveToFirst()) emptyList()
                else decode(MvtIndexer.unpack(c.getBlob(0), c.getInt(1) == 1), x, y)
            }
        }.onFailure { Log.w(TAG, "tile $x/$y", it) }.getOrDefault(emptyList())
        cache.put(key, roads)
        return roads
    }

    private fun decode(bytes: ByteArray, tx: Int, ty: Int): List<Road> {
        val out = ArrayList<Road>()
        val pb = MvtIndexer.Pb(bytes, 0, bytes.size)
        while (pb.more()) {
            val (f, w) = pb.key()
            if (f == 3 && w == 2) decodeLayer(pb.sub(), tx, ty, out) else pb.skip(w)
        }
        return out
    }

    private fun decodeLayer(pb: MvtIndexer.Pb, tx: Int, ty: Int, out: MutableList<Road>) {
        var name = ""
        val keys = ArrayList<String>(); val values = ArrayList<String>()
        val feats = ArrayList<MvtIndexer.Pb>()
        var extent = 4096
        while (pb.more()) {
            val (f, w) = pb.key()
            when {
                f == 1 && w == 2 -> name = pb.string()
                f == 2 && w == 2 -> feats += pb.sub()
                f == 3 && w == 2 -> keys += pb.string()
                f == 4 && w == 2 -> values += MvtIndexer.decodeValue(pb.sub())
                f == 5 && w == 0 -> extent = pb.varint().toInt()
                else -> pb.skip(w)
            }
        }
        if (name != "transportation_name" && name != "transportation") return
        val geometryOnly = name == "transportation"
        for (fp in feats) {
            var tags = IntArray(0); var geom = IntArray(0)
            while (fp.more()) {
                val (f, w) = fp.key()
                when {
                    f == 2 && w == 2 -> tags = fp.packed()
                    f == 4 && w == 2 -> geom = fp.packed()
                    else -> fp.skip(w)
                }
            }
            val props = HashMap<String, String>()
            var i = 0
            while (i + 1 < tags.size) {
                val k = keys.getOrNull(tags[i]); val v = values.getOrNull(tags[i + 1])
                if (k != null && v != null) props[k] = v
                i += 2
            }
            val cls = props["class"] ?: ""
            if (geometryOnly && cls in NOT_ROADS) continue
            val label = if (geometryOnly) "" else (props["name"]?.takeIf { it.isNotBlank() }
                ?: props["name_en"]?.takeIf { it.isNotBlank() }
                ?: props["ref"]?.takeIf { it.isNotBlank() }?.let { refLabel(it, props["network"]) }
                ?: continue)
            // Geometry: MoveTo / LineTo commands with zig-zag deltas (cursor persists within a feature).
            var cx = 0; var cy = 0; var k = 0
            var part = ArrayList<LatLon>()
            while (k < geom.size) {
                val cmd = geom[k] and 7; val count = geom[k] ushr 3; k++
                if (cmd == 7) continue
                for (n in 0 until count) {
                    if (k + 1 >= geom.size) break
                    cx += MvtIndexer.zigzag(geom[k]); cy += MvtIndexer.zigzag(geom[k + 1]); k += 2
                    val pt = LatLon(Geo.tileYToLat(ty + cy.toDouble() / extent, Z), Geo.tileXToLon(tx + cx.toDouble() / extent, Z))
                    if (cmd == 1) {
                        if (part.size > 1) out += Road(label, part, cls)
                        part = ArrayList()
                    }
                    part += pt
                }
            }
            if (part.size > 1) out += Road(label, part, cls)
        }
    }

    /**
     * Nearest drivable road to [p] whose direction matches [bearing] (either way along the road),
     * within [maxDist] metres. Returns the point on the road and the road's direction of travel.
     */
    @Synchronized
    fun snap(p: LatLon, bearing: Double, maxDist: Double): Pair<LatLon, Double>? {
        val x = Geo.lonToTileX(p.lon, Z); val y = Geo.latToTileY(p.lat, Z)
        var best: Pair<LatLon, Double>? = null; var bestScore = Double.MAX_VALUE
        for (dx in -1..1) for (dy in -1..1) {
            for (r in roadsIn(x + dx, y + dy)) {
                if (r.name.isNotEmpty()) continue // use the full road network geometry
                for (i in 0 until r.pts.size - 1) {
                    val a = r.pts[i]; val b = r.pts[i + 1]
                    if (Math.abs(a.lat - p.lat) > 0.005 || Math.abs(a.lon - p.lon) > 0.007) continue
                    val (t, d) = Geo.projectOnSegment(p, a, b)
                    if (d > maxDist) continue
                    val sb = Geo.bearing(a, b)
                    val fwd = Geo.angleDiff(sb, bearing); val back = Geo.angleDiff((sb + 180) % 360, bearing)
                    val diff = minOf(fwd, back)
                    if (diff > 35) continue
                    val score = d + diff * 0.25
                    if (score < bestScore) {
                        bestScore = score
                        best = Geo.interpolate(a, b, t) to (if (fwd <= back) sb else (sb + 180) % 360)
                    }
                }
            }
        }
        return best
    }

    private fun refLabel(ref: String, network: String?): String {
        val r = ref.split(';').first().trim()
        return when {
            network?.contains("interstate") == true && !r.startsWith("I") -> "I-$r"
            network?.contains("us-highway") == true && !r.startsWith("US") -> "US $r"
            else -> r
        }
    }

    companion object {
        private const val TAG = "StreetNamer"
        private const val Z = 14
        private val NOT_ROADS = setOf("path", "rail", "transit", "ferry", "bus_guideway", "aerialway", "pier")
    }
}
