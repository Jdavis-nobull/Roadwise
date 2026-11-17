package com.roadwise.app.nav

import android.location.Location
import androidx.test.core.app.ApplicationProvider
import com.roadwise.app.RoadwiseApp
import com.roadwise.app.core.Geo
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.cos
import kotlin.random.Random

/** Checks the GPS clean-up that feeds the map arrow. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = RoadwiseApp::class)
class LocationFilterTest {
    private val repo get() = LocationRepo(ApplicationProvider.getApplicationContext(), ApplicationProvider.getApplicationContext<RoadwiseApp>().c.prefs)

    private fun fix(lat: Double, lon: Double, tSec: Double, acc: Float, speed: Float = 0f, bearing: Float = 0f) =
        Location("gps").apply {
            latitude = lat; longitude = lon; accuracy = acc
            elapsedRealtimeNanos = (tSec * 1e9).toLong()
            if (speed > 0) { this.speed = speed; this.bearing = bearing }
        }

    private fun offset(lat: Double, lon: Double, north: Double, east: Double) =
        Pair(lat + north / 111_320.0, lon + east / (111_320.0 * cos(Math.toRadians(lat))))

    @Test
    fun standingStill_jitterIsSmoothed() {
        val r = repo; val rnd = Random(7)
        val lat0 = 39.96; val lon0 = -83.0
        var rawErr = 0.0; var outErr = 0.0; var n = 0
        for (i in 0 until 40) {
            val (lat, lon) = offset(lat0, lon0, rnd.nextDouble(-10.0, 10.0), rnd.nextDouble(-10.0, 10.0))
            val out = r.clean(fix(lat, lon, i.toDouble(), 10f)) ?: continue
            if (i >= 10) {
                rawErr += Geo.dist(lat0, lon0, lat, lon)
                outErr += Geo.dist(lat0, lon0, out.latitude, out.longitude); n++
            }
        }
        println("still: raw avg error %.1f m, smoothed %.1f m".format(rawErr / n, outErr / n))
        assertTrue("smoothed error should be well below raw", outErr < rawErr * 0.5)
    }

    @Test
    fun gpsGlitch_isDropped() {
        val r = repo
        assertNotNull(r.clean(fix(39.96, -83.0, 0.0, 8f)))
        // 2 km jump one second later with a loose fix: physically impossible for a car.
        val (lat, lon) = offset(39.96, -83.0, 2000.0, 0.0)
        assertNull(r.clean(fix(lat, lon, 1.0, 25f)))
        // Very poor fix right after good ones is ignored too.
        assertNull(r.clean(fix(39.96, -83.0, 2.0, 300f)))
        // A normal next fix is accepted.
        assertNotNull(r.clean(fix(39.96001, -83.0, 3.0, 8f)))
    }

    @Test
    fun driving_filterKeepsUpWithTheCar() {
        val r = repo; val rnd = Random(3)
        var worst = 0.0
        for (i in 0 until 30) {
            val north = i * 25.0 // 25 m/s ≈ 56 mph
            val (lat, lon) = offset(39.96, -83.0, north + rnd.nextDouble(-4.0, 4.0), rnd.nextDouble(-4.0, 4.0))
            val out = r.clean(fix(lat, lon, i.toDouble(), 6f, 25f, 0f)) ?: continue
            val (tl, tn) = offset(39.96, -83.0, north, 0.0)
            if (i > 3) worst = maxOf(worst, Geo.dist(tl, tn, out.latitude, out.longitude))
        }
        println("driving: worst error vs true position %.1f m".format(worst))
        assertTrue("filter must not lag behind a fast car (worst $worst m)", worst < 8)
    }
}
