package com.roadwise.app.car

import android.app.Presentation
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.location.Location
import android.util.Log
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.coroutineScope
import com.roadwise.app.Container
import com.roadwise.app.core.Place
import com.roadwise.app.routing.Route
import com.roadwise.app.ui.MapController
import com.roadwise.app.ui.MapStyles
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.maplibre.android.maps.MapView

/**
 * Draws the same live MapLibre map on the car's screen: the car gives us a Surface, we put a
 * virtual display on it and show a MapView there, driven by the same MapController as the phone.
 */
class CarMapRenderer(
    private val car: CarContext,
    private val c: Container,
    private val lifecycle: Lifecycle,
) : SurfaceCallback, DefaultLifecycleObserver {

    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var mapView: MapView? = null
    var controller: MapController? = null
        private set
    private var visible: Rect? = null
    private var jobs = mutableListOf<Job>()
    private var lastRouteVersion = -1
    private var preview: Pair<List<Route>, Int>? = null
    private var previewDest: Place? = null

    private val locListener: (Location) -> Unit = { controller?.updateLocation(it) }

    init { lifecycle.addObserver(this) }

    override fun onCreate(owner: LifecycleOwner) {
        car.getCarService(AppManager::class.java).setSurfaceCallback(this)
    }

    override fun onDestroy(owner: LifecycleOwner) { release() }

    override fun onSurfaceAvailable(container: SurfaceContainer) {
        val surface = container.surface ?: return
        release()
        try {
            val dm = car.getSystemService(DisplayManager::class.java)
            val vd = dm.createVirtualDisplay("RoadwiseCar", container.width, container.height, container.dpi, surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
            display = vd
            val pres = Presentation(car, vd.display)
            presentation = pres
            val mv = MapView(pres.context)
            mv.onCreate(null)
            pres.setContentView(mv)
            pres.show()
            mv.onStart(); mv.onResume()
            mapView = mv
            mv.getMapAsync { map ->
                val ctl = MapController(pres.context, map)
                controller = ctl
                ctl.viewport = visible
                map.uiSettings.isCompassEnabled = false
                ctl.setMapMode(if (car.isDarkMode) MapStyles.NIGHT else MapStyles.DAY, c.prefs.current.satellite)
                c.location.location.value?.let { ctl.updateLocation(it); ctl.moveTo(com.roadwise.app.core.LatLon(it.latitude, it.longitude), 15.0) }
                ctl.setReports(c.reports.reports.value)
                attachFeeds(ctl)
                ctl.recenter(false)
            }
            mv.addOnDidFinishLoadingStyleListener { controller?.onStyleFinishedLoading() }
        } catch (e: Exception) {
            Log.e(TAG, "car surface", e)
        }
    }

    private fun attachFeeds(ctl: MapController) {
        c.location.addListener(locListener)
        c.location.acquire("car", 1000)
        lastRouteVersion = -1
        jobs += lifecycle.coroutineScope.launch {
            var wasActive = false
            c.nav.state.collect { s ->
                if (s.active != wasActive) {
                    wasActive = s.active
                    if (s.active) {
                        preview = null
                        ctl.setPin(s.destination)
                        ctl.setNavMode(true, c.prefs.current.tilt3d)
                    } else {
                        ctl.setNavMode(false, false)
                        ctl.setRoutes(emptyList(), 0); ctl.setTraveled(null); ctl.setPin(null)
                        ctl.recenter(false)
                    }
                    lastRouteVersion = -1
                }
                if (s.active && s.route != null && s.routeVersion != lastRouteVersion) {
                    lastRouteVersion = s.routeVersion
                    ctl.setRoutes(listOf(s.route), 0)
                }
                if (s.active) ctl.setTraveled(s.snapped)
                ctl.setNavProgress(if (s.active && !s.arrived) s.route else null, s.progressM, s.speedMps, !s.offRoute && s.snapped != null)
            }
        }
        jobs += lifecycle.coroutineScope.launch { c.reports.reports.collect { ctl.setReports(it) } }
        jobs += lifecycle.coroutineScope.launch { c.prefs.settings.collect { setDark(car.isDarkMode) } }
        jobs += lifecycle.coroutineScope.launch { c.matcher.match.collect { ctl.setRoadMatch(it) } }
        preview?.let { (r, i) -> showPreview(r, i, previewDest) }
    }

    /** Route choices shown while the driver picks one (RoutePreviewScreen). */
    fun showPreview(routes: List<Route>, selected: Int, dest: Place?) {
        preview = routes to selected; previewDest = dest
        val ctl = controller ?: return
        ctl.setRoutes(routes, selected)
        ctl.setPin(dest)
        routes.getOrNull(selected)?.let { r -> ctl.fit(r.points.filterIndexed { i, _ -> i % 8 == 0 } + r.destination, 0) }
    }

    fun clearPreview() {
        preview = null; previewDest = null
        if (!c.nav.state.value.active) controller?.let { it.setRoutes(emptyList(), 0); it.setPin(null); it.recenter(false) }
    }

    fun zoom(delta: Double) = controller?.zoomBy(delta)
    fun recenter() = controller?.recenter(c.prefs.current.tilt3d)

    fun setDark(dark: Boolean) = controller?.setMapMode(if (dark) MapStyles.NIGHT else MapStyles.DAY, c.prefs.current.satellite)

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        visible = Rect(visibleArea)
        controller?.viewport = visible
    }

    override fun onStableAreaChanged(stableArea: Rect) {}

    override fun onScroll(distanceX: Float, distanceY: Float) {
        val ctl = controller ?: return
        ctl.stopTracking()
        ctl.map.scrollBy(-distanceX, -distanceY, 0)
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        val ctl = controller ?: return
        val delta = Math.log(scaleFactor.toDouble()) / Math.log(2.0)
        ctl.map.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.zoomBy(delta, android.graphics.Point(focusX.toInt(), focusY.toInt())))
    }

    override fun onSurfaceDestroyed(container: SurfaceContainer) { release() }

    private fun release() {
        jobs.forEach { it.cancel() }; jobs.clear()
        c.location.removeListener(locListener)
        c.location.release("car")
        controller?.dispose(); controller = null
        mapView?.let { runCatching { it.onPause(); it.onStop(); it.onDestroy() } }
        mapView = null
        runCatching { presentation?.dismiss() }
        presentation = null
        display?.release(); display = null
    }

    companion object { const val TAG = "CarMapRenderer" }
}
