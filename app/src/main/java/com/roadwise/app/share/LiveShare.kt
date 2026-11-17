package com.roadwise.app.share

import android.content.Context
import android.content.Intent
import android.location.Location
import android.util.Log
import com.roadwise.app.core.Net
import com.roadwise.app.core.Prefs
import com.roadwise.app.core.Units
import com.roadwise.app.nav.LocationRepo
import com.roadwise.app.nav.NavState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

data class LiveShareState(
    val active: Boolean = false,
    val url: String = "",
    val expiresAt: Long = 0,
    val untilArrival: Boolean = false,
    val lastSent: Long = 0,
    val error: String? = null,
)

/**
 * Location sharing:
 *  - one-shot "here's where I am" links (work with no server, any messaging app)
 *  - trip/ETA messages while navigating
 *  - live sharing: the phone posts its position to the Roadwise server, and anyone with the
 *    link can watch it move on a web map (no app needed) until it expires or you stop it.
 */
class LiveShare(
    private val net: Net,
    private val prefs: Prefs,
    private val location: LocationRepo,
    private val navState: () -> NavState,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(LiveShareState())
    val state: StateFlow<LiveShareState> = _state
    private var id = ""
    private var token = ""
    private var job: Job? = null

    val serverConfigured get() = prefs.current.reportServer.isNotBlank()

    fun mapLink(lat: Double, lon: Double) = "https://maps.google.com/?q=%.6f,%.6f".format(lat, lon)

    fun locationText(loc: Location): String {
        val lat = loc.latitude; val lon = loc.longitude
        return buildString {
            append("📍 Here's my location: ").append(mapLink(lat, lon))
            append("\n(OpenStreetMap: https://www.openstreetmap.org/?mlat=%.6f&mlon=%.6f#map=17/%.6f/%.6f)".format(lat, lon, lat, lon))
            if (_state.value.active) append("\n🔴 Live: ").append(_state.value.url)
        }
    }

    fun tripText(s: NavState): String {
        val eta = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(s.etaMillis))
        val dest = s.destination?.name ?: "my destination"
        return buildString {
            append("🚗 On my way to ").append(dest)
            append(" - arriving around ").append(eta)
            append(" (").append(Units.duration(s.remainingS)).append(", ")
            append(Units.distance(s.remainingM, prefs.current.imperial)).append(" away).")
            if (_state.value.active) append("\nFollow along live: ").append(_state.value.url)
            else location.location.value?.let { append("\nI'm here now: ").append(mapLink(it.latitude, it.longitude)) }
        }
    }

    fun shareIntent(ctx: Context, text: String) {
        ctx.startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share location"
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Starts live sharing for [minutes] (or until arrival when navigating). Returns the viewer URL. */
    suspend fun start(minutes: Int, untilArrival: Boolean = false): String {
        val server = prefs.current.reportServer
        if (server.isBlank()) throw IllegalStateException("Set a Roadwise server in Settings to share live location")
        if (_state.value.active) stop()
        val body = JSONObject().apply {
            put("name", prefs.current.nickname)
            put("minutes", if (untilArrival) 24 * 60 else minutes)
            put("clientId", prefs.clientId)
        }
        val res = JSONObject(net.postJson("$server/api/live", body.toString()))
        id = res.getString("id"); token = res.getString("token")
        val url = "$server/live/$id"
        val expires = if (untilArrival) Long.MAX_VALUE else System.currentTimeMillis() + minutes * 60_000L
        _state.value = LiveShareState(true, url, expires, untilArrival)
        job?.cancel()
        job = scope.launch { loop(server) }
        return url
    }

    private suspend fun loop(server: String) {
        var lastLoc: Location? = null
        while (scope.isActive && _state.value.active) {
            val st = _state.value
            val nav = navState()
            if (System.currentTimeMillis() > st.expiresAt || (st.untilArrival && (nav.arrived || !nav.active))) {
                if (st.untilArrival && nav.arrived) runCatching { push(server, location.location.value, nav, arrived = true) }
                stop(); return
            }
            val loc = location.location.value
            if (loc != null && (loc !== lastLoc || System.currentTimeMillis() - st.lastSent > 60_000)) {
                try {
                    push(server, loc, nav, arrived = false)
                    lastLoc = loc
                    _state.value = _state.value.copy(lastSent = System.currentTimeMillis(), error = null)
                } catch (e: Exception) {
                    Log.w("LiveShare", "push", e)
                    _state.value = _state.value.copy(error = if (net.isOnline) "Server unreachable" else "Offline - will resume when connected")
                }
            }
            delay(if (nav.active) 8_000 else 15_000)
        }
    }

    private suspend fun push(server: String, loc: Location?, nav: NavState, arrived: Boolean) {
        val body = JSONObject().apply {
            put("token", token)
            if (loc != null) {
                put("lat", loc.latitude); put("lon", loc.longitude)
                put("speed", if (loc.hasSpeed()) loc.speed.toDouble() else 0.0)
                put("heading", if (loc.hasBearing()) loc.bearing.toDouble() else 0.0)
                put("accuracy", loc.accuracy.toDouble())
            }
            if (nav.active) {
                put("eta", nav.etaMillis)
                put("remainingM", nav.remainingM)
                nav.destination?.let { d -> put("destName", d.name); put("destLat", d.lat); put("destLon", d.lon) }
            }
            put("arrived", arrived)
            put("imperial", prefs.current.imperial)
        }
        net.postJson("$server/api/live/$id", body.toString())
    }

    fun stop() {
        val server = prefs.current.reportServer
        val wasId = id; val wasToken = token
        job?.cancel(); job = null
        _state.value = LiveShareState()
        if (wasId.isNotBlank() && server.isNotBlank()) scope.launch {
            runCatching { net.postJson("$server/api/live/$wasId/stop", JSONObject().put("token", wasToken).toString()) }
        }
        id = ""; token = ""
    }
}
