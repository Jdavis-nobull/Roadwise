package com.roadwise.app.reports

import android.util.Log
import com.roadwise.app.core.BBox
import com.roadwise.app.core.Db
import com.roadwise.app.core.LatLon
import com.roadwise.app.core.Net
import com.roadwise.app.core.Prefs
import com.roadwise.app.core.Report
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

enum class ReportType(val key: String, val label: String, val emoji: String, val color: Long, val ttlMin: Int, val spoken: String) {
    POLICE("police", "Police", "👮", 0xFF1E88E5, 120, "Police reported ahead"),
    ACCIDENT("accident", "Crash", "💥", 0xFFE53935, 180, "Crash reported ahead"),
    HAZARD("hazard", "Hazard", "⚠️", 0xFFFFA000, 240, "Hazard on the road ahead"),
    JAM("jam", "Traffic", "🚗", 0xFFD81B60, 60, "Heavy traffic reported ahead"),
    CLOSURE("closure", "Closure", "⛔", 0xFF6D4C41, 24 * 60, "Road closure reported ahead"),
    CAMERA("camera", "Speed trap", "📸", 0xFF8E24AA, 240, "Speed trap reported ahead"),
    CONSTRUCTION("construction", "Roadwork", "🚧", 0xFFF4511E, 7 * 24 * 60, "Roadwork reported ahead"),
    WEATHER("weather", "Weather", "🌧️", 0xFF546E7A, 180, "Bad weather reported ahead");

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key } ?: HAZARD
    }
}

/**
 * Waze-style crowd reports. Reports are always stored on the phone (so they work offline);
 * when a report server is configured they are shared with everyone using the same server.
 */
class ReportRepo(private val db: Db, private val net: Net, private val prefs: Prefs) {
    private val _reports = MutableStateFlow<List<Report>>(emptyList())
    val reports: StateFlow<List<Report>> = _reports
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status
    private val syncLock = Mutex()

    suspend fun reload() = withContext(Dispatchers.IO) {
        _reports.value = db.activeReports().filter { it.down < 2 }
    }

    suspend fun add(type: ReportType, at: LatLon, heading: Double, note: String = "") {
        val now = System.currentTimeMillis()
        val r = Report(
            id = UUID.randomUUID().toString(), serverId = null, type = type.key,
            lat = at.lat, lon = at.lon, heading = heading, note = note,
            created = now, expires = now + type.ttlMin * 60_000L, up = 0, down = 0,
            mine = true, pending = true, by = prefs.current.nickname,
        )
        withContext(Dispatchers.IO) { db.upsertReport(r) }
        reload()
        sync(at)
    }

    suspend fun vote(r: Report, stillThere: Boolean) {
        val now = System.currentTimeMillis()
        val updated = if (stillThere) r.copy(up = r.up + 1, expires = maxOf(r.expires, now + 30 * 60_000L))
        else r.copy(down = r.down + 1)
        withContext(Dispatchers.IO) {
            if (!stillThere && (r.mine || updated.down >= 2 || r.serverId == null)) db.deleteReport(r.id) else db.upsertReport(updated)
        }
        reload()
        val server = prefs.current.reportServer
        if (server.isNotBlank() && r.serverId != null && net.isOnline) {
            runCatching {
                net.postJson("$server/api/reports/${r.serverId}/vote", JSONObject().apply {
                    put("vote", if (stillThere) "up" else "down"); put("clientId", prefs.clientId)
                }.toString())
            }.onFailure { Log.w(TAG, "vote", it) }
        }
    }

    suspend fun delete(r: Report) {
        withContext(Dispatchers.IO) { db.deleteReport(r.id) }
        reload()
    }

    /** Pushes queued reports and pulls everyone's reports around [center]. */
    suspend fun sync(center: LatLon?) = syncLock.withLock {
        val server = prefs.current.reportServer
        if (server.isBlank() || !net.isOnline) {
            _status.value = if (server.isBlank()) "Reports stay on this phone (no server set)" else "Offline - reports will sync later"
            reload(); return@withLock
        }
        try {
            for (p in withContext(Dispatchers.IO) { db.pendingReports() }) {
                val body = JSONObject().apply {
                    put("type", p.type); put("lat", p.lat); put("lon", p.lon); put("heading", p.heading)
                    put("note", p.note); put("by", p.by); put("clientId", prefs.clientId)
                }
                val res = JSONObject(net.postJson("$server/api/reports", body.toString()))
                withContext(Dispatchers.IO) {
                    db.upsertReport(p.copy(serverId = res.getString("id"), pending = false, expires = res.optLong("expires", p.expires)))
                }
            }
            if (center != null) {
                val box = BBox.around(center, 80_000.0)
                val txt = net.get("$server/api/reports?bbox=${box.minLon},${box.minLat},${box.maxLon},${box.maxLat}")
                val arr = JSONObject(txt).getJSONArray("reports")
                val seen = HashSet<String>()
                withContext(Dispatchers.IO) {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val sid = o.getString("id")
                        seen += sid
                        val existing = db.reportByServerId(sid)
                        db.upsertReport(
                            Report(
                                id = existing?.id ?: "s-$sid", serverId = sid, type = o.optString("type", "hazard"),
                                lat = o.getDouble("lat"), lon = o.getDouble("lon"), heading = o.optDouble("heading", 0.0),
                                note = o.optString("note"), created = o.optLong("created"), expires = o.optLong("expires"),
                                up = o.optInt("up"), down = o.optInt("down"),
                                mine = existing?.mine ?: (o.optString("clientId") == prefs.clientId),
                                pending = false, by = o.optString("by"),
                            )
                        )
                    }
                    db.deleteServerReportsNotIn(box, seen)
                }
            }
            _status.value = "Live reports synced"
        } catch (e: Exception) {
            Log.w(TAG, "sync", e)
            _status.value = "Report server unreachable"
        }
        reload()
    }

    suspend fun testServer(url: String): String = try {
        val o = JSONObject(net.get("${url.trim().trimEnd('/')}/api/health"))
        "Connected - ${o.optInt("active")} active reports on server"
    } catch (e: Exception) {
        "Couldn't connect: ${e.message}"
    }

    companion object { const val TAG = "ReportRepo" }
}
