package com.roadwise.app.nav

import android.location.Location
import android.os.SystemClock
import com.roadwise.app.core.Geo
import com.roadwise.app.routing.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Drives a route virtually (demo / testing) by feeding fake GPS fixes. */
class Simulator(private val location: LocationRepo) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running
    var speedFactor = 1.0

    fun start(route: Route) {
        stop()
        location.simulating = true
        _running.value = true
        job = scope.launch {
            var d = 0.0
            val total = route.distanceM
            var seg = 0
            while (isActive && d < total) {
                while (seg < route.points.size - 2 && route.cumDist[seg + 1] < d) seg++
                val segLen = (route.cumDist[seg + 1] - route.cumDist[seg]).coerceAtLeast(0.01)
                val t = ((d - route.cumDist[seg]) / segLen).coerceIn(0.0, 1.0)
                val p = Geo.interpolate(route.points[seg], route.points[seg + 1], t)
                val segTime = (route.cumTime[seg + 1] - route.cumTime[seg]).coerceAtLeast(0.01)
                val speed = (segLen / segTime).coerceIn(1.2, 40.0)
                val loc = Location("sim").apply {
                    latitude = p.lat; longitude = p.lon
                    this.speed = speed.toFloat()
                    bearing = Geo.bearing(route.points[seg], route.points[seg + 1]).toFloat()
                    accuracy = 5f
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                }
                location.inject(loc)
                delay(1000)
                d += speed * speedFactor
            }
            stop()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        location.simulating = false
        _running.value = false
    }
}
