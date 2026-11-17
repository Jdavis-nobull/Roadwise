package com.roadwise.app.core

import kotlin.math.*

data class LatLon(val lat: Double, val lon: Double)

data class BBox(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double) {
    fun contains(p: LatLon) = p.lat in minLat..maxLat && p.lon in minLon..maxLon
    fun expand(meters: Double): BBox {
        val dLat = meters / 111_320.0
        val dLon = meters / (111_320.0 * cos(Math.toRadians((minLat + maxLat) / 2)).coerceAtLeast(0.05))
        return BBox(minLat - dLat, minLon - dLon, maxLat + dLat, maxLon + dLon)
    }
    val center get() = LatLon((minLat + maxLat) / 2, (minLon + maxLon) / 2)

    companion object {
        fun of(points: List<LatLon>): BBox = BBox(
            points.minOf { it.lat }, points.minOf { it.lon },
            points.maxOf { it.lat }, points.maxOf { it.lon })
        fun around(p: LatLon, meters: Double) = BBox(p.lat, p.lon, p.lat, p.lon).expand(meters)
    }
}

object Geo {
    private const val R = 6_371_008.8

    fun dist(a: LatLon, b: LatLon): Double = dist(a.lat, a.lon, b.lat, b.lon)

    fun dist(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * R * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun bearing(a: LatLon, b: LatLon): Double {
        val p1 = Math.toRadians(a.lat); val p2 = Math.toRadians(b.lat)
        val dl = Math.toRadians(b.lon - a.lon)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    fun angleDiff(a: Double, b: Double): Double {
        val d = abs(a - b) % 360
        return if (d > 180) 360 - d else d
    }

    /** Projection of p on segment a-b in a local flat frame. Returns (t in 0..1, distance meters). */
    fun projectOnSegment(p: LatLon, a: LatLon, b: LatLon): Pair<Double, Double> {
        val k = cos(Math.toRadians(p.lat)) * 111_320.0
        val ax = a.lon * k; val ay = a.lat * 111_320.0
        val bx = b.lon * k; val by = b.lat * 111_320.0
        val px = p.lon * k; val py = p.lat * 111_320.0
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 < 1e-9) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        val qx = ax + t * dx; val qy = ay + t * dy
        return t to hypot(px - qx, py - qy)
    }

    fun interpolate(a: LatLon, b: LatLon, t: Double) =
        LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)

    /** Decodes a Google-style encoded polyline with the given precision (5 for OSRM, 6 for Valhalla). */
    fun decodePolyline(s: String, precision: Int = 6): List<LatLon> {
        val factor = 10.0.pow(precision)
        val out = ArrayList<LatLon>(s.length / 4)
        var i = 0; var lat = 0L; var lon = 0L
        while (i < s.length) {
            var shift = 0; var result = 0L; var b: Int
            do { b = s[i++].code - 63; result = result or ((b and 0x1f).toLong() shl shift); shift += 5 } while (b >= 0x20)
            lat += if (result and 1L != 0L) (result shr 1).inv() else result shr 1
            shift = 0; result = 0L
            do { b = s[i++].code - 63; result = result or ((b and 0x1f).toLong() shl shift); shift += 5 } while (b >= 0x20)
            lon += if (result and 1L != 0L) (result shr 1).inv() else result shr 1
            out.add(LatLon(lat / factor, lon / factor))
        }
        return out
    }

    fun encodePolyline(points: List<LatLon>, precision: Int = 6): String {
        val factor = 10.0.pow(precision)
        val sb = StringBuilder()
        var pLat = 0L; var pLon = 0L
        fun enc(v: Long) {
            var x = if (v < 0) (v shl 1).inv() else v shl 1
            while (x >= 0x20) { sb.append(((0x20 or (x and 0x1f).toInt()) + 63).toChar()); x = x shr 5 }
            sb.append((x + 63).toInt().toChar())
        }
        for (p in points) {
            val lat = (p.lat * factor).roundToLong(); val lon = (p.lon * factor).roundToLong()
            enc(lat - pLat); enc(lon - pLon); pLat = lat; pLon = lon
        }
        return sb.toString()
    }

    fun cumulative(points: List<LatLon>): DoubleArray {
        val c = DoubleArray(points.size)
        for (i in 1 until points.size) c[i] = c[i - 1] + dist(points[i - 1], points[i])
        return c
    }

    // Web-mercator tile math
    fun lonToTileX(lon: Double, z: Int): Int = floor((lon + 180.0) / 360.0 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)
    fun latToTileY(lat: Double, z: Int): Int {
        val r = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        return floor((1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)
    }
    fun tileXToLon(x: Double, z: Int) = x / (1 shl z) * 360.0 - 180.0
    fun tileYToLat(y: Double, z: Int): Double {
        val n = PI - 2.0 * PI * y / (1 shl z)
        return Math.toDegrees(atan(sinh(n)))
    }

    fun tileCount(b: BBox, minZ: Int, maxZ: Int): Long {
        var n = 0L
        for (z in minZ..maxZ) {
            val x0 = lonToTileX(b.minLon, z); val x1 = lonToTileX(b.maxLon, z)
            val y0 = latToTileY(b.maxLat, z); val y1 = latToTileY(b.minLat, z)
            n += (x1 - x0 + 1).toLong() * (y1 - y0 + 1).toLong()
        }
        return n
    }
}
