package com.roadwise.app.offline

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.roadwise.app.core.BBox
import com.roadwise.app.core.Geo
import com.roadwise.app.core.Place
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater

/**
 * Builds the offline search index by reading the vector tiles MapLibre already downloaded
 * (its offline SQLite database) and pulling out named places, businesses and streets.
 */
object MvtIndexer {
    private const val TAG = "MvtIndexer"
    private const val Z = 14

    private class Raw(val name: String, val category: String, val lat: Double, val lon: Double)

    fun index(dbFile: File, bbox: BBox, onProgress: (Double) -> Unit): List<Place> {
        if (!dbFile.exists()) { Log.w(TAG, "no offline db at $dbFile"); return emptyList() }
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
        val raw = ArrayList<Raw>()
        try {
            val x0 = Geo.lonToTileX(bbox.minLon, Z); val x1 = Geo.lonToTileX(bbox.maxLon, Z)
            val y0 = Geo.latToTileY(bbox.maxLat, Z); val y1 = Geo.latToTileY(bbox.minLat, Z)
            val total = ((x1 - x0 + 1).toLong() * (y1 - y0 + 1)).coerceAtLeast(1)
            val seen = HashSet<Long>()
            var done = 0
            db.rawQuery(
                "SELECT x, y, data, compressed FROM tiles WHERE z=? AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND url_template LIKE '%planet%' ORDER BY modified DESC",
                arrayOf(Z.toString(), x0.toString(), x1.toString(), y0.toString(), y1.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    val x = c.getInt(0); val y = c.getInt(1)
                    if (!seen.add(x.toLong() * 100_000 + y)) continue
                    val blob = c.getBlob(2) ?: continue
                    val bytes = runCatching { unpack(blob, c.getInt(3) == 1) }.getOrNull() ?: continue
                    runCatching { decodeTile(bytes, x, y, raw) }.onFailure { Log.w(TAG, "tile $x/$y", it) }
                    if (++done % 200 == 0) onProgress(done.toDouble() / total)
                }
            }
            Log.i(TAG, "decoded $done tiles, ${raw.size} named features")
        } finally {
            db.close()
        }
        return withAddresses(raw)
    }

    internal fun unpack(blob: ByteArray, compressed: Boolean): ByteArray {
        var b = blob
        if (compressed) {
            val inf = Inflater()
            inf.setInput(b)
            val out = ByteArrayOutputStream(b.size * 4)
            val buf = ByteArray(32 * 1024)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                out.write(buf, 0, n)
            }
            inf.end()
            b = out.toByteArray()
        }
        if (b.size > 2 && b[0] == 0x1f.toByte() && b[1] == 0x8b.toByte()) {
            b = GZIPInputStream(ByteArrayInputStream(b)).readBytes()
        }
        return b
    }

    // ---------------- minimal protobuf reader ----------------
    internal class Pb(val buf: ByteArray, var pos: Int, val end: Int) {
        fun more() = pos < end
        fun varint(): Long {
            var shift = 0; var r = 0L
            while (true) {
                val b = buf[pos++].toInt()
                r = r or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return r
                shift += 7
            }
        }
        fun key(): Pair<Int, Int> { val k = varint().toInt(); return (k ushr 3) to (k and 7) }
        fun sub(): Pb { val len = varint().toInt(); val p = Pb(buf, pos, pos + len); pos += len; return p }
        fun string(): String { val len = varint().toInt(); val s = String(buf, pos, len, Charsets.UTF_8); pos += len; return s }
        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> pos += 8
                2 -> pos += varint().toInt()
                5 -> pos += 4
                else -> throw IllegalStateException("wire $wire")
            }
        }
        fun packed(): IntArray {
            val p = sub(); val out = ArrayList<Int>()
            while (p.more()) out += p.varint().toInt()
            return out.toIntArray()
        }
    }

    private val wanted = setOf("poi", "place", "transportation_name", "aerodrome_label", "mountain_peak", "water_name", "park")

    private fun decodeTile(bytes: ByteArray, tx: Int, ty: Int, out: MutableList<Raw>) {
        val pb = Pb(bytes, 0, bytes.size)
        while (pb.more()) {
            val (f, w) = pb.key()
            if (f == 3 && w == 2) decodeLayer(pb.sub(), tx, ty, out) else pb.skip(w)
        }
    }

    private fun decodeLayer(pb: Pb, tx: Int, ty: Int, out: MutableList<Raw>) {
        var name = ""
        val keys = ArrayList<String>()
        val values = ArrayList<String>()
        val features = ArrayList<Pb>()
        var extent = 4096
        while (pb.more()) {
            val (f, w) = pb.key()
            when {
                f == 1 && w == 2 -> name = pb.string()
                f == 2 && w == 2 -> features += pb.sub()
                f == 3 && w == 2 -> keys += pb.string()
                f == 4 && w == 2 -> values += decodeValue(pb.sub())
                f == 5 && w == 0 -> extent = pb.varint().toInt()
                else -> pb.skip(w)
            }
        }
        if (name !in wanted) return
        val roadNames = HashSet<String>()
        for (fp in features) {
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
            val fname = props["name"]?.takeIf { it.isNotBlank() } ?: props["name_en"]?.takeIf { it.isNotBlank() } ?: continue
            if (geom.size < 3) continue
            val px = zigzag(geom[1]); val py = zigzag(geom[2])
            val lon = Geo.tileXToLon(tx + px.toDouble() / extent, Z)
            val lat = Geo.tileYToLat(ty + py.toDouble() / extent, Z)
            val cat = when (name) {
                "poi" -> listOfNotNull(props["class"], props["subclass"]).distinct().joinToString(" ")
                "place" -> "place ${props["class"] ?: ""}".trim()
                "transportation_name" -> {
                    if (!roadNames.add(fname)) continue
                    "road ${props["class"] ?: ""}".trim()
                }
                "aerodrome_label" -> "airport"
                "mountain_peak" -> "peak"
                "water_name" -> "water"
                "park" -> "park"
                else -> name
            }
            out += Raw(fname, cat, lat, lon)
        }
    }

    internal fun zigzag(n: Int) = (n ushr 1) xor -(n and 1)

    internal fun decodeValue(pb: Pb): String {
        var s = ""
        while (pb.more()) {
            val (f, w) = pb.key()
            when (f) {
                1 -> s = pb.string()
                2 -> { s = java.lang.Float.intBitsToFloat(readLE32(pb)).toString() }
                3 -> { s = java.lang.Double.longBitsToDouble(readLE64(pb)).toString() }
                4, 5 -> s = pb.varint().toString()
                6 -> { val v = pb.varint(); s = ((v ushr 1) xor -(v and 1)).toString() }
                7 -> s = (pb.varint() != 0L).toString()
                else -> pb.skip(w)
            }
        }
        return s
    }

    private fun readLE32(pb: Pb): Int {
        var r = 0
        for (i in 0 until 4) r = r or ((pb.buf[pb.pos + i].toInt() and 0xff) shl (8 * i))
        pb.pos += 4
        return r
    }

    private fun readLE64(pb: Pb): Long {
        var r = 0L
        for (i in 0 until 8) r = r or ((pb.buf[pb.pos + i].toLong() and 0xff) shl (8 * i))
        pb.pos += 8
        return r
    }

    /** Dedupes features and labels each one with the nearest town so results read like addresses. */
    private fun withAddresses(raw: List<Raw>): List<Place> {
        val towns = raw.filter { it.category.startsWith("place") && it.category.split(' ').getOrNull(1) in setOf("city", "town", "village", "suburb", "hamlet") }
        val grid = HashMap<Long, MutableList<Raw>>()
        fun cell(lat: Double, lon: Double) = (Math.floor(lat * 10).toLong() shl 32) or (Math.floor(lon * 10).toLong() and 0xffffffffL)
        for (t in towns) grid.getOrPut(cell(t.lat, t.lon)) { ArrayList() } += t
        val dedup = LinkedHashMap<String, Raw>()
        for (r in raw) {
            val k = "${r.name.lowercase()}|${r.category}|${(r.lat * 300).toInt()}|${(r.lon * 300).toInt()}"
            dedup.putIfAbsent(k, r)
        }
        return dedup.values.map { r ->
            var best: Raw? = null; var bestD = Double.MAX_VALUE
            for (dy in -1..1) for (dx in -1..1) {
                grid[cell(r.lat + dy * 0.1, r.lon + dx * 0.1)]?.forEach { t ->
                    val d = Geo.dist(r.lat, r.lon, t.lat, t.lon)
                    if (d < bestD) { bestD = d; best = t }
                }
            }
            val addr = if (r.category.startsWith("place")) "" else best?.takeIf { bestD < 15_000 }?.name ?: ""
            Place(r.name, addr, r.lat, r.lon, r.category, "offline")
        }
    }
}
