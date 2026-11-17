package com.roadwise.app.nav

import android.location.Location
import com.roadwise.app.core.LatLon
import com.roadwise.app.offline.StreetNamer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Where a GPS fix sits on the road network: the road point and the road's direction of travel. */
data class RoadMatch(val fixNanos: Long, val point: LatLon, val bearing: Double)

/**
 * Snaps your position onto the road you're driving on (from the map tiles stored on the phone),
 * so the arrow sits on the road and points along it, like Google Maps / Waze.
 * Only used when moving at driving speed with a decent fix; otherwise the raw position is shown.
 */
class MapMatcher(location: LocationRepo, private val streets: StreetNamer) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _match = MutableStateFlow<RoadMatch?>(null)
    val match: StateFlow<RoadMatch?> = _match
    @Volatile private var busy = false
    private var misses = 0

    init { location.addListener(::onFix) }

    private fun onFix(loc: Location) {
        val moving = loc.hasSpeed() && loc.speed > 4f && loc.hasBearing()
        if (!moving || loc.accuracy > 35f) { _match.value = null; return }
        if (busy) return
        busy = true
        scope.launch {
            try {
                val maxDist = maxOf(12.0, loc.accuracy * 1.2)
                val hit = streets.snap(LatLon(loc.latitude, loc.longitude), loc.bearing.toDouble(), maxDist)
                if (hit != null) {
                    misses = 0
                    _match.value = RoadMatch(loc.elapsedRealtimeNanos, hit.first, hit.second)
                } else if (++misses >= 2) {
                    _match.value = null // probably off-road / parking lot: show the raw position
                }
            } catch (_: Exception) {
                _match.value = null
            } finally {
                busy = false
            }
        }
    }
}
