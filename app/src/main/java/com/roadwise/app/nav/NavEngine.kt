package com.roadwise.app.nav

import android.location.Location
import android.util.Log
import com.roadwise.app.core.BBox
import com.roadwise.app.core.Db
import com.roadwise.app.core.Geo
import com.roadwise.app.core.LatLon
import com.roadwise.app.core.Place
import com.roadwise.app.core.Prefs
import com.roadwise.app.core.Report
import com.roadwise.app.core.SpeedCamera
import com.roadwise.app.core.Units
import com.roadwise.app.reports.ReportRepo
import com.roadwise.app.reports.ReportType
import com.roadwise.app.routing.Maneuver
import com.roadwise.app.routing.Route
import com.roadwise.app.routing.RouteService
import com.roadwise.app.routing.Turn
import com.roadwise.app.routing.TravelMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class NavAlert(val key: String, val emoji: String, val title: String, val detail: String, val until: Long, val report: Report? = null)

data class NavState(
    val active: Boolean = false,
    val route: Route? = null,
    val destination: Place? = null,
    val progressM: Double = 0.0,
    val remainingM: Double = 0.0,
    val remainingS: Double = 0.0,
    val etaMillis: Long = 0,
    val next: Maneuver? = null,
    val nextIndex: Int = -1,
    val distToNextM: Double = 0.0,
    val then: Maneuver? = null,
    val currentStreet: String? = null,
    val speedMps: Double = 0.0,
    val speedLimitKmh: Int = 0,
    val overspeed: Boolean = false,
    val offRoute: Boolean = false,
    val rerouting: Boolean = false,
    val arrived: Boolean = false,
    val alert: NavAlert? = null,
    val message: String? = null,
    val snapped: LatLon? = null,
    val bearing: Double = 0.0,
    val routeVersion: Int = 0,
)

/**
 * Turn-by-turn engine: snaps GPS to the route, tracks progress, speaks instructions,
 * warns about crowd reports / speed cameras / speeding, and reroutes when you leave the route.
 */
class NavEngine(
    private val prefs: Prefs,
    private val voice: Voice,
    private val routes: RouteService,
    private val reports: ReportRepo,
    private val db: Db,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(NavState())
    val state: StateFlow<NavState> = _state

    private var route: Route? = null
    private var segIndex = 0
    private val spoken = HashMap<Int, Int>()  // maneuver index -> highest stage announced
    private val postSpoken = HashSet<Int>()
    private var offCount = 0
    private var lastReroute = 0L
    private var rerouteJob: Job? = null
    private var hazards: List<Pair<Report, Double>> = emptyList()
    private var cameras: List<Pair<SpeedCamera, Double>> = emptyList()
    private val alerted = HashSet<String>()
    private var overSince = 0L
    private var lastChime = 0L
    private var mode = TravelMode.DRIVE
    private var dest: Place? = null
    private var version = 0

    fun start(r: Route, destination: Place) {
        route = r; dest = destination; mode = r.mode
        resetProgress()
        version++
        _state.value = NavState(active = true, route = r, destination = destination, routeVersion = version,
            remainingM = r.distanceM, remainingS = r.durationS, etaMillis = System.currentTimeMillis() + (r.durationS * 1000).toLong(),
            next = r.maneuvers.getOrNull(1), nextIndex = 1)
        rebuildHazards()
        scope.launch { routes.enrich(r) }
        val first = r.maneuvers.firstOrNull()
        val units = prefs.current.imperial
        voice.speak(buildString {
            append("Starting route to ${destination.name}. ")
            append(Units.spokenDistance(r.distanceM, units)).append(", about ").append(Units.duration(r.durationS).replace("hr", "hours").replace("min", "minutes")).append(". ")
            if (first != null && first.turn == Turn.DEPART) append(first.spoken)
        }, interrupt = true)
        if (r.source == "offline") _state.value = _state.value.copy(message = "Offline route (no live traffic)")
    }

    fun stop() {
        route = null
        rerouteJob?.cancel()
        voice.stop()
        _state.value = NavState()
    }

    private fun resetProgress() {
        segIndex = 0; spoken.clear(); postSpoken.clear(); offCount = 0; alerted.clear(); overSince = 0
    }

    fun onReportsChanged() { if (route != null) rebuildHazards() }

    private fun rebuildHazards() {
        val r = route ?: return
        val box = BBox.of(r.points).expand(300.0)
        val reps = reports.reports.value.filter { box.contains(LatLon(it.lat, it.lon)) }
        scope.launch(Dispatchers.Default) {
            val h = reps.mapNotNull { rep -> along(r, LatLon(rep.lat, rep.lon), 45.0)?.let { rep to it } }
            val cams = withContext(Dispatchers.IO) { runCatching { db.cameras(box) }.getOrDefault(emptyList()) }
            val c = cams.mapNotNull { cam -> along(r, LatLon(cam.lat, cam.lon), 35.0)?.let { cam to it } }
            withContext(Dispatchers.Main) { if (route === r) { hazards = h; cameras = c } }
        }
    }

    /** Distance along the route of the point nearest to p, if p is within maxOff meters of it. */
    private fun along(r: Route, p: LatLon, maxOff: Double): Double? {
        var best = Double.MAX_VALUE; var bestAlong = 0.0
        val pts = r.points
        for (i in 0 until pts.size - 1) {
            // cheap reject
            val a = pts[i]
            if (Math.abs(a.lat - p.lat) > 0.05 || Math.abs(a.lon - p.lon) > 0.05) continue
            val (t, d) = Geo.projectOnSegment(p, a, pts[i + 1])
            if (d < best) { best = d; bestAlong = r.cumDist[i] + t * (r.cumDist[i + 1] - r.cumDist[i]) }
        }
        return if (best <= maxOff) bestAlong else null
    }

    fun onLocation(loc: Location) {
        val r = route ?: return
        if (_state.value.arrived) return
        val p = LatLon(loc.latitude, loc.longitude)
        val pts = r.points

        // --- snap to route (search a window ahead of the last known segment) ---
        var bestI = segIndex; var bestT = 0.0; var bestD = Double.MAX_VALUE
        val from = (segIndex - 3).coerceAtLeast(0)
        val to = (segIndex + 120).coerceAtMost(pts.size - 2)
        for (i in from..to) {
            val (t, d) = Geo.projectOnSegment(p, pts[i], pts[i + 1])
            var score = d
            if (loc.hasBearing() && loc.speed > 3) {
                val segBearing = Geo.bearing(pts[i], pts[i + 1])
                if (Geo.angleDiff(segBearing, loc.bearing.toDouble()) > 100) score += 30
            }
            if (score < bestD) { bestD = score; bestI = i; bestT = t }
        }
        val offDist = Geo.projectOnSegment(p, pts[bestI], pts[bestI + 1]).second
        val threshold = maxOf(if (mode == TravelMode.DRIVE) 45.0 else 30.0, loc.accuracy * 1.3)
        if (offDist > threshold) {
            offCount++
            if (offCount >= 3) triggerReroute(loc)
            _state.value = _state.value.copy(offRoute = offCount >= 3, speedMps = loc.speed.toDouble(), snapped = p)
            return
        }
        offCount = 0
        segIndex = bestI
        val progress = r.cumDist[bestI] + bestT * (r.cumDist[bestI + 1] - r.cumDist[bestI])
        val snapped = Geo.interpolate(pts[bestI], pts[bestI + 1], bestT)
        val remaining = (r.distanceM - progress).coerceAtLeast(0.0)
        val remainingS = (r.cumTime.last() - r.timeAt(bestI, bestT)).coerceAtLeast(0.0)

        // --- next maneuver ---
        var ni = r.maneuvers.indexOfFirst { r.cumDist[it.pointIndex] > progress + 8 }
        if (ni < 0) ni = r.maneuvers.size - 1
        val next = r.maneuvers[ni]
        val distToNext = (r.cumDist[next.pointIndex] - progress).coerceAtLeast(0.0)
        val then = r.maneuvers.getOrNull(ni + 1)?.takeIf { r.cumDist[it.pointIndex] - r.cumDist[next.pointIndex] < 250 }
        val current = r.maneuvers.getOrNull(ni - 1)?.street

        val speed = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0
        val limit = r.speedLimits.getOrElse(bestI) { 0 }

        // --- arrival ---
        if (remaining < 25 || (Geo.dist(p, r.destination) < 30 && progress > r.distanceM * 0.8)) {
            voice.speak("You have arrived at ${dest?.name ?: "your destination"}", interrupt = true)
            _state.value = _state.value.copy(arrived = true, remainingM = 0.0, remainingS = 0.0, next = r.maneuvers.last(), distToNextM = 0.0)
            return
        }

        announce(r, ni, next, distToNext, speed, then)
        val alert = checkAlerts(progress, speed)
        val over = checkSpeed(speed, limit)

        _state.value = _state.value.copy(
            progressM = progress, remainingM = remaining, remainingS = remainingS,
            etaMillis = System.currentTimeMillis() + (remainingS * 1000).toLong(),
            next = next, nextIndex = ni, distToNextM = distToNext, then = then,
            currentStreet = current, speedMps = speed, speedLimitKmh = limit, overspeed = over,
            offRoute = false, snapped = snapped,
            bearing = Geo.bearing(pts[bestI], pts[bestI + 1]),
            alert = alert ?: _state.value.alert?.takeIf { it.until > System.currentTimeMillis() },
        )
    }

    private fun announce(r: Route, ni: Int, m: Maneuver, dist: Double, speed: Double, then: Maneuver?) {
        if (m.turn == Turn.DEPART) return
        val v = maxOf(speed, when (mode) { TravelMode.DRIVE -> 12.0; TravelMode.BIKE -> 4.0; TravelMode.WALK -> 1.3 })
        val far = (v * 50).coerceIn(250.0, 2600.0)
        val near = (v * 15).coerceIn(60.0, 700.0)
        val now = (v * 4.5).coerceIn(15.0, 110.0)
        val prevGap = if (ni > 0) r.cumDist[m.pointIndex] - r.cumDist[r.maneuvers[ni - 1].pointIndex] else Double.MAX_VALUE
        val stage = spoken[ni] ?: -1
        val imperial = prefs.current.imperial
        val sentence = lowerFirst(m.spoken)
        when {
            dist <= now && stage < 2 -> {
                spoken[ni] = 2
                val thenTxt = then?.let { ", then ${lowerFirst(it.spoken)}" } ?: ""
                voice.speak(m.spoken.replaceFirstChar { it.uppercase() } + thenTxt)
            }
            dist <= near && stage < 1 && dist > now * 1.5 -> {
                spoken[ni] = 1
                voice.speak("In ${Units.spokenDistance(dist, imperial)}, $sentence")
            }
            dist <= far && stage < 0 && dist > near * 1.6 && prevGap > near * 2 -> {
                spoken[ni] = 0
                voice.speak("In ${Units.spokenDistance(dist, imperial)}, $sentence")
            }
            stage < 0 && dist > far * 1.3 && ni > 1 && (ni - 1) !in postSpoken && dist > 3000 -> {
                postSpoken += ni - 1
                spoken[ni] = -1
                val road = r.maneuvers[ni - 1].street?.substringBefore(" / ")
                voice.speak("Continue ${if (road != null) "on $road " else ""}for ${Units.spokenDistance(dist, imperial)}")
            }
        }
    }

    private fun lowerFirst(s: String) = if (s.length > 1 && s[1].isLowerCase()) s.replaceFirstChar { it.lowercase() } else s

    private fun checkAlerts(progress: Double, speed: Double): NavAlert? {
        val s = prefs.current
        val now = System.currentTimeMillis()
        val lookahead = when (mode) { TravelMode.DRIVE -> (speed * 40).coerceIn(500.0, 1200.0); else -> 200.0 }
        if (s.reportAlerts) for ((rep, at) in hazards) {
            val ahead = at - progress
            if (ahead in 0.0..lookahead && alerted.add("r:${rep.id}")) {
                val t = ReportType.of(rep.type)
                voice.speak(t.spoken)
                return NavAlert("r:${rep.id}", t.emoji, t.label, "${Units.distance(ahead, s.imperial)} ahead" + (if (rep.by.isNotBlank()) " · by ${rep.by}" else ""), now + 20_000, rep)
            }
        }
        if (s.cameraAlerts && mode == TravelMode.DRIVE) for ((cam, at) in cameras) {
            val ahead = at - progress
            if (ahead in 0.0..(lookahead * 0.7) && alerted.add("c:${cam.id}")) {
                voice.speak("Speed camera ahead")
                val lim = if (cam.maxspeedKmh > 0) " · limit ${Units.limitFromKmh(cam.maxspeedKmh, s.imperial)} ${Units.speedUnit(s.imperial)}" else ""
                return NavAlert("c:${cam.id}", "📷", "Speed camera", "${Units.distance(ahead, s.imperial)} ahead$lim", now + 15_000)
            }
        }
        return null
    }

    private fun checkSpeed(speed: Double, limitKmh: Int): Boolean {
        val s = prefs.current
        if (!s.speedAlert || limitKmh <= 0 || mode != TravelMode.DRIVE) { overSince = 0; return false }
        val shown = Units.speed(speed, s.imperial)
        val lim = Units.limitFromKmh(limitKmh, s.imperial)
        val over = shown > lim + s.speedTolerance
        val now = System.currentTimeMillis()
        if (over) {
            if (overSince == 0L) overSince = now
            if (now - overSince > 2500 && now - lastChime > 30_000) { lastChime = now; voice.chime() }
        } else overSince = 0
        return over
    }

    private fun triggerReroute(loc: Location) {
        val r = route ?: return
        val now = System.currentTimeMillis()
        if (rerouteJob?.isActive == true || now - lastReroute < 12_000) return
        lastReroute = now
        val destination = dest ?: return
        _state.value = _state.value.copy(rerouting = true)
        voice.speak("Rerouting", interrupt = true)
        rerouteJob = scope.launch {
            try {
                val heading = if (loc.hasBearing() && loc.speed > 2) loc.bearing.toDouble() else null
                val result = routes.routes(LatLon(loc.latitude, loc.longitude), r.destination, mode, heading, closures(), alternates = false)
                val nr = result.first()
                route = nr
                resetProgress()
                version++
                _state.value = _state.value.copy(route = nr, rerouting = false, offRoute = false, routeVersion = version,
                    message = if (nr.source == "offline") "Offline route (no live traffic)" else null)
                rebuildHazards()
                routes.enrich(nr)
            } catch (e: Exception) {
                Log.w("NavEngine", "reroute failed", e)
                _state.value = _state.value.copy(rerouting = false, message = "Couldn't reroute: ${e.message}")
            }
        }
    }

    /** Active road-closure reports, used to route around them (Waze-style). */
    fun closures(): List<LatLon> = reports.reports.value
        .filter { it.type == ReportType.CLOSURE.key || it.type == ReportType.ACCIDENT.key && it.up >= 2 }
        .map { LatLon(it.lat, it.lon) }

    fun dismissAlert() { _state.value = _state.value.copy(alert = null) }
    fun clearMessage() { _state.value = _state.value.copy(message = null) }

    fun switchRoute(r: Route) {
        if (route == null) return
        route = r
        resetProgress()
        version++
        _state.value = _state.value.copy(route = r, routeVersion = version)
        rebuildHazards()
        scope.launch { routes.enrich(r) }
    }
}
