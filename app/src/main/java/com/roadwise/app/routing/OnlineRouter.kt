package com.roadwise.app.routing

import com.roadwise.app.core.Geo
import com.roadwise.app.core.LatLon
import com.roadwise.app.core.Net
import com.roadwise.app.core.Settings
import org.json.JSONArray
import org.json.JSONObject

/**
 * Online routing through the public Valhalla server run by FOSSGIS (OpenStreetMap data).
 * Gives street-name turn instructions, alternate routes, toll/highway/ferry avoidance and
 * the ability to steer around crowd-reported closures (exclude_locations).
 */
class OnlineRouter(private val net: Net) {

    suspend fun route(
        from: LatLon, to: LatLon, mode: TravelMode, s: Settings,
        heading: Double? = null, avoid: List<LatLon> = emptyList(), alternates: Int = 2,
    ): List<Route> {
        val body = buildRequest(from, to, mode, s, heading, avoid, alternates)
        val text = try {
            net.postJson("$BASE/route", body.toString())
        } catch (e: Exception) {
            // A closure point that can't be matched to a road makes Valhalla reject the request.
            if (avoid.isNotEmpty()) net.postJson("$BASE/route", buildRequest(from, to, mode, s, heading, emptyList(), alternates).toString())
            else throw e
        }
        val json = JSONObject(text)
        val out = ArrayList<Route>()
        out += parseTrip(json.getJSONObject("trip"), mode, to)
        json.optJSONArray("alternates")?.let { alts ->
            for (i in 0 until alts.length()) {
                runCatching { out += parseTrip(alts.getJSONObject(i).getJSONObject("trip"), mode, to) }
            }
        }
        // Drop alternatives that mostly follow a route we already have.
        return out.fold(ArrayList<Route>()) { acc, r ->
            if (acc.none { overlap(r, it) > 0.85 }) acc += r
            acc
        }
    }

    /** Fraction of [a]'s length that runs within ~30 m of [b]. */
    private fun overlap(a: Route, b: Route): Double {
        val step = maxOf(1, a.points.size / 120)
        val samples = a.points.indices.step(step).map { a.points[it] }
        val bs = b.points.filterIndexed { i, _ -> i % maxOf(1, b.points.size / 600) == 0 } + b.points.last()
        val near = samples.count { p ->
            bs.any { q -> Math.abs(q.lat - p.lat) < 0.001 && Math.abs(q.lon - p.lon) < 0.0013 && Geo.dist(p, q) < 60 } ||
                (0 until b.points.size - 1).any { i ->
                    val q = b.points[i]
                    Math.abs(q.lat - p.lat) < 0.003 && Math.abs(q.lon - p.lon) < 0.004 &&
                        Geo.projectOnSegment(p, q, b.points[i + 1]).second < 30
                }
        }
        return near.toDouble() / samples.size.coerceAtLeast(1)
    }

    private fun buildRequest(
        from: LatLon, to: LatLon, mode: TravelMode, s: Settings,
        heading: Double?, avoid: List<LatLon>, alternates: Int,
    ) = JSONObject().apply {
        put("locations", JSONArray().apply {
            put(JSONObject().apply {
                put("lat", from.lat); put("lon", from.lon)
                if (heading != null) { put("heading", heading.toInt()); put("heading_tolerance", 60) }
            })
            put(JSONObject().apply { put("lat", to.lat); put("lon", to.lon) })
        })
        put("costing", mode.valhalla)
        val opts = JSONObject()
        when (mode) {
            TravelMode.DRIVE -> opts.put("auto", JSONObject().apply {
                put("use_tolls", if (s.avoidTolls) 0.0 else 0.5)
                put("use_highways", if (s.avoidHighways) 0.0 else 1.0)
                put("use_ferry", if (s.avoidFerries) 0.0 else 0.5)
            })
            TravelMode.BIKE -> opts.put("bicycle", JSONObject().apply { put("bicycle_type", "hybrid") })
            TravelMode.WALK -> opts.put("pedestrian", JSONObject())
        }
        put("costing_options", opts)
        if (avoid.isNotEmpty()) put("exclude_locations", JSONArray().apply {
            avoid.forEach { put(JSONObject().apply { put("lat", it.lat); put("lon", it.lon) }) }
        })
        if (alternates > 0) put("alternates", alternates)
        put("units", "kilometers")
        put("language", "en-US")
        put("id", "roadwise")
    }

    private fun parseTrip(trip: JSONObject, mode: TravelMode, dest: LatLon): Route {
        val legs = trip.getJSONArray("legs")
        val points = ArrayList<LatLon>()
        val maneuvers = ArrayList<Maneuver>()
        val maneuverTimes = ArrayList<Triple<Int, Int, Double>>() // begin, end, seconds
        for (l in 0 until legs.length()) {
            val leg = legs.getJSONObject(l)
            val offset = if (points.isEmpty()) 0 else points.size - 1
            val shape = Geo.decodePolyline(leg.getString("shape"), 6)
            if (points.isEmpty()) points.addAll(shape) else points.addAll(shape.drop(1))
            val ms = leg.getJSONArray("maneuvers")
            for (i in 0 until ms.length()) {
                val m = ms.getJSONObject(i)
                val type = m.optInt("type")
                val begin = m.optInt("begin_shape_index") + offset
                val end = m.optInt("end_shape_index") + offset
                maneuverTimes += Triple(begin, end, m.optDouble("time", 0.0))
                if (l > 0 && (type == 1 || type == 2 || type == 3)) continue
                if (l < legs.length() - 1 && (type in 4..6)) continue
                val streets = m.optJSONArray("begin_street_names") ?: m.optJSONArray("street_names")
                val street = streets?.let { a -> (0 until a.length()).joinToString(" / ") { a.getString(it) } }?.takeIf { it.isNotBlank() }
                // Short banner text ("Turn right onto West Rich Street"), full text as fallback.
                val text = m.optString("verbal_transition_alert_instruction").ifBlank { m.optString("instruction") }.trimEnd('.')
                val spoken = m.optString("verbal_transition_alert_instruction").ifBlank {
                    m.optString("verbal_pre_transition_instruction").ifBlank { text }
                }.trimEnd('.')
                maneuvers += Maneuver(
                    turn = valhallaTurn(type),
                    text = text,
                    spoken = spoken,
                    street = street,
                    pointIndex = begin.coerceIn(0, points.size - 1),
                    exit = m.optInt("roundabout_exit_count", 0),
                )
            }
        }
        // Spread each maneuver's time along its shape, proportional to segment length.
        val cumTime = DoubleArray(points.size)
        val segTime = DoubleArray(points.size)
        for ((b, e, t) in maneuverTimes) {
            if (e <= b) continue
            var len = 0.0
            for (i in b until e.coerceAtMost(points.size - 1)) len += Geo.dist(points[i], points[i + 1])
            if (len <= 0) continue
            for (i in b until e.coerceAtMost(points.size - 1)) segTime[i] = t * Geo.dist(points[i], points[i + 1]) / len
        }
        for (i in 1 until points.size) cumTime[i] = cumTime[i - 1] + segTime[i - 1]
        val summary = trip.optJSONObject("summary") ?: JSONObject()
        val label = longestStreet(maneuvers, points)
        return Route(
            points = points,
            maneuvers = maneuvers,
            durationS = summary.optDouble("time", cumTime.last()),
            cumTime = cumTime,
            speedLimits = IntArray(points.size),
            source = "online",
            label = label,
            hasTolls = summary.optBoolean("has_toll"),
            hasHighways = summary.optBoolean("has_highway"),
            hasFerry = summary.optBoolean("has_ferry"),
            mode = mode,
            destination = dest,
        )
    }

    private fun longestStreet(ms: List<Maneuver>, pts: List<LatLon>): String {
        val cum = Geo.cumulative(pts)
        var best = ""; var bestLen = -1.0
        for (i in ms.indices) {
            val st = ms[i].street ?: continue
            val end = if (i + 1 < ms.size) ms[i + 1].pointIndex else pts.size - 1
            val len = cum[end] - cum[ms[i].pointIndex]
            if (len > bestLen) { bestLen = len; best = st }
        }
        return if (best.isBlank()) "Fastest route" else "via ${best.substringBefore(" / ")}"
    }

    /** Fills per-segment speed limits using Valhalla's map matching of the route shape. */
    suspend fun fetchSpeedLimits(route: Route) {
        if (route.mode != TravelMode.DRIVE || route.points.size < 2) return
        val body = JSONObject().apply {
            put("encoded_polyline", Geo.encodePolyline(route.points, 6))
            put("shape_match", "edge_walk")
            put("costing", "auto")
            put("filters", JSONObject().apply {
                put("attributes", JSONArray(listOf("edge.speed_limit", "edge.begin_shape_index", "edge.end_shape_index")))
                put("action", "include")
            })
        }
        val json = JSONObject(net.postJson("$BASE/trace_attributes", body.toString()))
        val edges = json.optJSONArray("edges") ?: return
        val limits = IntArray(route.points.size)
        for (i in 0 until edges.length()) {
            val e = edges.getJSONObject(i)
            val lim = e.optInt("speed_limit", 0)
            if (lim <= 0 || lim > 200) continue
            val b = e.optInt("begin_shape_index"); val en = e.optInt("end_shape_index")
            for (k in b until en.coerceAtMost(limits.size)) if (k >= 0) limits[k] = lim
        }
        route.speedLimits = limits
    }

    private fun valhallaTurn(type: Int): Turn = when (type) {
        1, 2, 3 -> Turn.DEPART
        4, 5, 6 -> Turn.ARRIVE
        7, 8, 22 -> Turn.STRAIGHT
        9 -> Turn.SLIGHT_RIGHT
        10 -> Turn.RIGHT
        11 -> Turn.SHARP_RIGHT
        12, 13 -> Turn.UTURN
        14 -> Turn.SHARP_LEFT
        15 -> Turn.LEFT
        16 -> Turn.SLIGHT_LEFT
        17 -> Turn.STRAIGHT
        18 -> Turn.RAMP_RIGHT
        19 -> Turn.RAMP_LEFT
        20 -> Turn.EXIT_RIGHT
        21 -> Turn.EXIT_LEFT
        23 -> Turn.KEEP_RIGHT
        24 -> Turn.KEEP_LEFT
        25, 37, 38 -> Turn.MERGE
        26, 27 -> Turn.ROUNDABOUT
        28, 29 -> Turn.FERRY
        else -> Turn.STRAIGHT
    }

    companion object {
        const val BASE = "https://valhalla1.openstreetmap.de"
    }
}
