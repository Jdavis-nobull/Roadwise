package com.roadwise.app.nav

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Which way the phone is pointing (degrees from true north), from the rotation-vector sensor.
 * Works whether the phone lies flat or stands upright in a car mount.
 */
class Compass(ctx: Context, private val location: LocationRepo) : SensorEventListener {
    private val sm = ctx.getSystemService(SensorManager::class.java)
    private val dm = ctx.getSystemService(DisplayManager::class.java)
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: sm.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
    private val _heading = MutableStateFlow<Float?>(null)
    val heading: StateFlow<Float?> = _heading
    val available get() = sensor != null

    /** Estimated heading error in degrees (smaller = more accurate). */
    private val _errorDeg = MutableStateFlow(45f)
    val errorDeg: StateFlow<Float> = _errorDeg
    private var status = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
    private var upright = false

    private val rot = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orient = FloatArray(3)
    private var smoothed = Float.NaN
    private var declination = 0f
    private var declinationAt = 0L
    private var users = 0

    fun start() {
        if (sensor == null) return
        if (users++ == 0) sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        if (sensor == null || users == 0) return
        if (--users == 0) { sm.unregisterListener(this); _heading.value = null; smoothed = Float.NaN }
    }

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        // Pitch tells us if the phone is upright (car mount) or flat in the hand.
        SensorManager.getOrientation(rot, orient)
        // Upright (car mount) vs flat, with hysteresis so it doesn't flicker around 50 degrees.
        val pitch = Math.toDegrees(Math.abs(orient[1].toDouble()))
        upright = if (upright) pitch > 40 else pitch > 58
        val rotation = dm.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
        val (ax, ay) = when (rotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        if (upright) {
            // Point along the back camera's direction instead of the top edge.
            val (ux, uz) = if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270)
                ay to SensorManager.AXIS_Z else ax to SensorManager.AXIS_Z
            SensorManager.remapCoordinateSystem(rot, ux, uz, remapped)
        } else {
            SensorManager.remapCoordinateSystem(rot, ax, ay, remapped)
        }
        SensorManager.getOrientation(remapped, orient)
        var deg = Math.toDegrees(orient[0].toDouble()).toFloat() + declination()
        deg = (deg + 360f) % 360f
        // Adaptive low-pass: steady when you hold still, quick when you actually turn.
        smoothed = if (smoothed.isNaN()) deg else {
            var d = deg - smoothed
            if (d > 180) d -= 360f
            if (d < -180) d += 360f
            val alpha = (Math.abs(d) / 40f).coerceIn(0.08f, 0.55f)
            (smoothed + d * alpha + 360f) % 360f
        }
        // Heading accuracy: the sensor's own estimate when it gives one, else its calibration status.
        val est = if (e.values.size > 4 && e.values[4] > 0) Math.toDegrees(e.values[4].toDouble()).toFloat() else Float.NaN
        val fromStatus = when (status) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> 12f
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> 25f
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> 45f
            else -> 70f
        }
        _errorDeg.value = if (est.isNaN()) fromStatus else est.coerceIn(5f, 90f)
        _heading.value = smoothed
    }

    private fun declination(): Float {
        val now = System.currentTimeMillis()
        if (now - declinationAt > 10 * 60_000) {
            location.location.value?.let {
                declination = GeomagneticField(it.latitude.toFloat(), it.longitude.toFloat(), it.altitude.toFloat(), now).declination
                declinationAt = now
            }
        }
        return declination
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { status = accuracy }

    /** True when the compass needs a figure-8 calibration. */
    val needsCalibration get() = status <= SensorManager.SENSOR_STATUS_ACCURACY_LOW
}
