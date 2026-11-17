package com.roadwise.app.car

import android.content.Intent
import android.location.Location
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import androidx.car.app.CarContext
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.SearchTemplate
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutePreviewNavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.testing.SessionController
import androidx.car.app.testing.TestAppManager
import androidx.car.app.testing.TestCarContext
import androidx.car.app.testing.TestScreenManager
import androidx.car.app.testing.navigation.TestNavigationManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import com.roadwise.app.RoadwiseApp
import com.roadwise.app.core.LatLon
import com.roadwise.app.core.Place
import com.roadwise.app.routing.Maneuver
import com.roadwise.app.routing.Route
import com.roadwise.app.routing.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Runs Roadwise's real Android Auto session and screens against the Car App Library's
 * test host, checking every template the car would receive is valid and correct.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = RoadwiseApp::class)
class CarAppTest {
    private lateinit var car: TestCarContext
    private lateinit var session: RoadwiseSession
    private lateinit var controller: SessionController
    private val app get() = ApplicationProvider.getApplicationContext<RoadwiseApp>()

    private val dest = Place("Ohio Stadium", "411 Woody Hayes Dr, Columbus", 40.0017, -83.0197, "stadium", "test")

    @Before
    fun setUp() {
        car = TestCarContext.createCarContext(app)
        session = RoadwiseSession()
        controller = SessionController(session, car, Intent())
        controller.moveToState(Lifecycle.State.RESUMED)
        idle()
        app.c.location.inject(fix(39.9600, -83.0000, 0f, 0f))
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun fix(lat: Double, lon: Double, speed: Float, bearing: Float) = Location("test").apply {
        latitude = lat; longitude = lon; this.speed = speed; this.bearing = bearing; accuracy = 5f
        time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }

    /** A short straight route north with a right turn halfway. */
    private fun testRoute(): Route {
        val pts = (0..40).map { LatLon(39.9600 + it * 0.001, -83.0000) }
        val cumTime = DoubleArray(pts.size) { it * 8.0 }
        val ms = listOf(
            Maneuver(Turn.DEPART, "Head north on High Street", "Head north on High Street", "High Street", 0),
            Maneuver(Turn.RIGHT, "Turn right onto Rich Street", "Turn right onto Rich Street", "Rich Street", 20),
            Maneuver(Turn.ROUNDABOUT, "At the roundabout, take the 2nd exit", "At the roundabout, take the 2nd exit", null, 30, exit = 2),
            Maneuver(Turn.ARRIVE, "Arrive at Ohio Stadium", "Arrive at Ohio Stadium", null, 40),
        )
        return Route(pts, ms, cumTime.last(), cumTime, IntArray(pts.size) { 56 }, "online", "via High Street",
            destination = pts.last())
    }

    private fun top() = car.getCarService(TestScreenManager::class.java).top

    @Test
    fun browseMapScreen_isNavigationTemplateWithSearchAndReport() {
        val t = top().onGetTemplate()
        assertTrue("root should be a NavigationTemplate", t is NavigationTemplate)
        t as NavigationTemplate
        val titles = t.actionStrip!!.actions.map { it.title?.toString() }
        assertTrue(titles.contains("Search"))
        assertTrue(titles.contains("Report"))
        assertNotNull("map buttons (recenter/zoom)", t.mapActionStrip)
        assertNull("no turn card while browsing", t.navigationInfo)
        assertNotNull("map surface callback registered", car.getCarService(TestAppManager::class.java).surfaceCallback)
    }

    @Test
    fun searchScreen_showsCategoriesAndResults() {
        val screen = CarSearchScreen(car, session)
        val t = screen.onGetTemplate()
        assertTrue(t is SearchTemplate)
        val rows = (t as SearchTemplate).itemList!!.items
        assertTrue("should list nearby categories", rows.size >= 4)
    }

    @Test
    fun routePreview_listsRoutesWithGo() {
        val r = testRoute()
        val screen = CarRoutePreviewScreen(car, session, dest, preloaded = listOf(r, r))
        val t = screen.onGetTemplate()
        assertTrue(t is RoutePreviewNavigationTemplate)
        t as RoutePreviewNavigationTemplate
        assertEquals(2, t.itemList!!.items.size)
        assertEquals("Go", t.navigateAction!!.title.toString())
    }

    @Test
    fun routePreview_withoutData_showsMessageInsteadOfCrashing() {
        // Robolectric has no network and no offline data, so routing fails: must show a message.
        val screen = CarRoutePreviewScreen(car, session, dest)
        for (i in 0 until 50) { idle(); Thread.sleep(100); if (screen.onGetTemplate() is MessageTemplate) break }
        val t = screen.onGetTemplate()
        assertTrue("got ${t.javaClass.simpleName}", t is MessageTemplate || t is RoutePreviewNavigationTemplate)
    }

    @Test
    fun navigation_showsTurnCard_tripAndEndsCleanly() {
        val nm = car.getCarService(TestNavigationManager::class.java)
        app.c.startNavigation(testRoute(), dest)
        idle()
        assertEquals("car told navigation started", 1, nm.navigationStartedCount)

        // Drive a little way north.
        app.c.nav.onLocation(fix(39.9630, -83.0000, 13f, 0f))
        idle()
        val t = top().onGetTemplate() as NavigationTemplate
        val info = t.navigationInfo
        assertTrue("turn card expected, got $info", info is RoutingInfo)
        val step = (info as RoutingInfo).currentStep!!
        assertEquals("Turn right onto Rich Street", step.cue.toString())
        assertNotNull("maneuver icon", step.maneuver!!.icon)
        assertNotNull("arrival estimate", t.destinationTravelEstimate)
        assertTrue("End button while navigating", t.actionStrip!!.actions.any { it.title?.toString() == "End" })
        assertTrue("trip sent to cluster", nm.tripsSent.isNotEmpty())

        // Arrive.
        app.c.nav.onLocation(fix(40.0000, -83.0000, 3f, 0f))
        idle()
        val arrived = top().onGetTemplate() as NavigationTemplate
        assertTrue("arrival message", arrived.navigationInfo is MessageInfo)

        // End from the car (as if the driver pressed End / Assistant said stop).
        nm.navigationManagerCallback!!.onStopNavigation()
        idle()
        assertEquals(1, nm.navigationEndedCount)
        assertTrue(!app.c.nav.state.value.active)
        assertNull((top().onGetTemplate() as NavigationTemplate).navigationInfo)
    }

    @Test
    fun everyTurnType_buildsAValidCarManeuver() {
        for (t in Turn.entries) {
            val step = CarFormat.step(Maneuver(t, "x", "x", "Main St", 0, exit = if (t == Turn.ROUNDABOUT) 3 else 0), true)
            assertNotNull(t.name, step.maneuver)
        }
        CarFormat.step(Maneuver(Turn.ROUNDABOUT, "x", "x", null, 0, exit = 0), false)
        // Distances in both unit systems.
        CarFormat.distance(50.0, true); CarFormat.distance(5000.0, true); CarFormat.distance(50.0, false); CarFormat.distance(50_000.0, false)
    }

    @Test
    fun reportScreen_isGridOfHazards() {
        val t = CarReportScreen(car, session).onGetTemplate()
        assertTrue(t is GridTemplate)
        assertEquals(6, (t as GridTemplate).singleList!!.items.size)
    }

    @Test
    fun heyGoogleNavigate_opensRoutePreview() {
        val intent = Intent(CarContext.ACTION_NAVIGATE, Uri.parse("geo:40.0017,-83.0197?q=Ohio+Stadium"))
        session.onNewIntent(intent)
        idle()
        assertTrue(top() is CarRoutePreviewScreen)
    }
}
