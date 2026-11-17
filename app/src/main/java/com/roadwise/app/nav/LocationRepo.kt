package com.roadwise.app.nav

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.roadwise.app.core.LatLon
import com.roadwise.app.core.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Single source of GPS fixes for the map and the navigation engine (GPS works without data). */
class LocationRepo(private val ctx: Context, private val prefs: Prefs) {
    private val client = LocationServices.getFusedLocationProviderClient(ctx)
    private val _location = MutableStateFlow<Location?>(null)
    val location: StateFlow<Location?> = _location
    private val listeners = mutableSetOf<(Location) -> Unit>()
    private val users = HashMap<String, Long>()
    private var activeInterval = -1L
    private var lastSaved = 0L

    val hasPermission get() = ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    val last: LatLon? get() = _location.value?.let { LatLon(it.latitude, it.longitude) }

    /** While the route simulator runs, real GPS fixes are ignored. */
    @Volatile var simulating = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            if (simulating) return
            for (raw in result.locations) {
                val loc = clean(raw) ?: continue
                _location.value = loc
                listeners.toList().forEach { it(loc) }
            }
            result.lastLocation?.let {
                if (System.currentTimeMillis() - lastSaved > 60_000) {
                    prefs.lastLat = it.latitude; prefs.lastLon = it.longitude; lastSaved = System.currentTimeMillis()
                }
            }
        }
    }

    // ---------- accuracy: outlier rejection + Kalman smoothing ----------
    private var good: Location? = null      // last accepted raw fix
    private var kLat = 0.0
    private var kLon = 0.0
    private var kVar = -1.0                   // position variance, m²
    private var kTime = 0L                    // elapsedRealtimeNanos of the filter state

    /**
     * Drops fixes that are clearly wrong (teleporting jumps, very poor accuracy right after good ones)
     * and smooths GPS jitter with a Kalman filter whose process noise grows with speed, so the
     * position is steady when standing still but still follows a fast car closely.
     */
    internal fun clean(loc: Location): Location? {
        val t = loc.elapsedRealtimeNanos
        val prev = good
        if (prev != null) {
            val dt = (t - prev.elapsedRealtimeNanos) / 1e9
            if (dt <= 0) return null // out of order / duplicate
            val jump = com.roadwise.app.core.Geo.dist(prev.latitude, prev.longitude, loc.latitude, loc.longitude)
            val implied = jump / dt
            // Faster than ~180 mph between fixes, with a non-tight fix: a GPS glitch.
            if (implied > 80 && jump > loc.accuracy * 2 && dt < 20) return null
            // A very rough fix shortly after good ones (tunnels, urban canyons): keep the good one.
            if (loc.accuracy > 120 && prev.accuracy < 50 && dt < 15) return null
        }
        good = loc

        val acc = loc.accuracy.coerceAtLeast(3f).toDouble()
        if (kVar < 0 || (t - kTime) / 1e9 > 30) {
            kLat = loc.latitude; kLon = loc.longitude; kVar = acc * acc; kTime = t
            return loc
        }
        val dt = ((t - kTime) / 1e9).coerceAtLeast(0.0)
        val q = maxOf(if (loc.hasSpeed()) loc.speed.toDouble() else 0.0, 1.0) // m/s of uncertainty growth
        kVar += dt * q * q
        val k = kVar / (kVar + acc * acc)
        kLat += k * (loc.latitude - kLat)
        kLon += k * (loc.longitude - kLon)
        kVar *= (1 - k)
        kTime = t
        return Location(loc).apply {
            latitude = kLat; longitude = kLon
            accuracy = kotlin.math.sqrt(kVar).toFloat()
        }
    }

    fun addListener(l: (Location) -> Unit) { listeners += l }
    fun removeListener(l: (Location) -> Unit) { listeners -= l }

    fun acquire(user: String, intervalMs: Long) { users[user] = intervalMs; apply() }
    fun release(user: String) { users.remove(user); apply() }

    @SuppressLint("MissingPermission")
    fun apply() {
        if (!hasPermission) return
        val want = users.values.minOrNull() ?: -1L
        if (want == activeInterval) return
        client.removeLocationUpdates(callback)
        activeInterval = want
        if (want < 0) return
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, want)
            .setMinUpdateIntervalMillis(want / 2)
            .setMinUpdateDistanceMeters(0f)
            .setMaxUpdateDelayMillis(0)                       // no batching: deliver each fix immediately
            .setGranularity(com.google.android.gms.location.Granularity.GRANULARITY_FINE)
            .setWaitForAccurateLocation(false)
            .build()
        client.requestLocationUpdates(req, callback, Looper.getMainLooper())
        client.lastLocation.addOnSuccessListener { if (it != null && _location.value == null) _location.value = it }
    }

    /** Lets a test/demo feed fixes (used by the route simulator). */
    fun inject(loc: Location) {
        _location.value = loc
        listeners.toList().forEach { it(loc) }
    }
}
