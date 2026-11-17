package com.roadwise.app

import android.app.Application
import android.util.Log
import com.roadwise.app.core.Db
import com.roadwise.app.core.LatLon
import com.roadwise.app.core.Net
import com.roadwise.app.core.Prefs
import com.roadwise.app.nav.LocationRepo
import com.roadwise.app.nav.NavEngine
import com.roadwise.app.nav.Simulator
import com.roadwise.app.nav.Voice
import com.roadwise.app.offline.OfflineAreas
import com.roadwise.app.reports.ReportRepo
import com.roadwise.app.routing.OfflineRouter
import com.roadwise.app.routing.OnlineRouter
import com.roadwise.app.routing.RouteService
import com.roadwise.app.search.SearchRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.offline.OfflineManager

class RoadwiseApp : Application() {
    lateinit var c: Container
        private set

    override fun onCreate() {
        super.onCreate()
        // The native map library isn't available in JVM unit tests (Robolectric).
        val underTest = android.os.Build.FINGERPRINT == "robolectric"
        if (!underTest) MapLibre.getInstance(this)
        c = Container(this)
        if (!underTest) runCatching {
            OfflineManager.getInstance(this).setMaximumAmbientCacheSize(400L * 1024 * 1024, object : OfflineManager.FileSourceCallback {
                override fun onSuccess() {}
                override fun onError(message: String) { Log.w("RoadwiseApp", "ambient cache: $message") }
            })
        }
        c.start()
    }
}

/** Simple manual dependency container. */
class Container(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val prefs = Prefs(app)
    val db = Db(app)
    val net = Net(app)
    val location = LocationRepo(app, prefs)
    val voice = Voice(app, prefs)
    val compass = com.roadwise.app.nav.Compass(app, location)
    val onlineRouter = OnlineRouter(net)
    val streets = com.roadwise.app.offline.StreetNamer(app)
    val offlineRouter = OfflineRouter(app, streets)
    val matcher = com.roadwise.app.nav.MapMatcher(location, streets)
    val routing = RouteService(net, prefs, onlineRouter, offlineRouter)
    val search = SearchRepo(net, db)
    val reports = ReportRepo(db, net, prefs)
    val offlineAreas = OfflineAreas(app, db, net, offlineRouter)
    val nav = NavEngine(prefs, voice, routing, reports, db)
    val simulator = Simulator(location)
    val share = com.roadwise.app.share.LiveShare(net, prefs, location) { nav.state.value }

    private val navFallback: (android.location.Location) -> Unit = { nav.onLocation(it) }
    private var usingFallback = false

    /** Starts turn-by-turn, from the phone or from Android Auto. */
    fun startNavigation(route: com.roadwise.app.routing.Route, dest: com.roadwise.app.core.Place, simulate: Boolean = false) {
        nav.start(route, dest)
        try {
            androidx.core.content.ContextCompat.startForegroundService(
                app, android.content.Intent(app, com.roadwise.app.nav.NavService::class.java))
        } catch (e: Exception) {
            // Some Android versions refuse background service starts; feed GPS to the engine directly instead.
            android.util.Log.w("Container", "nav service start refused", e)
            location.addListener(navFallback)
            location.acquire("nav-fallback", 1000)
            usingFallback = true
        }
        if (simulate) simulator.start(route)
    }

    fun endNavigation() {
        simulator.stop()
        nav.stop()
        if (usingFallback) {
            location.removeListener(navFallback)
            location.release("nav-fallback")
            usingFallback = false
        }
    }

    fun start() {
        scope.launch {
            reports.reload()
            offlineAreas.refresh()
            // Keep crowd reports fresh while the app is alive.
            while (true) {
                val here = location.last ?: LatLon(prefs.lastLat, prefs.lastLon)
                reports.sync(here)
                nav.onReportsChanged()
                delay(if (nav.state.value.active) 60_000 else 150_000)
            }
        }
    }
}
