package com.roadwise.app.core

import android.content.Context
import com.roadwise.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import kotlin.math.roundToInt

enum class ThemeMode { AUTO, DAY, NIGHT }
enum class RoutingPreference { AUTO, ONLINE_ONLY, OFFLINE_ONLY }

data class Settings(
    val imperial: Boolean = true,
    val voice: Boolean = true,
    val voiceVolumeBoost: Boolean = false,
    val avoidTolls: Boolean = false,
    val avoidHighways: Boolean = false,
    val avoidFerries: Boolean = false,
    val theme: ThemeMode = ThemeMode.AUTO,
    val routing: RoutingPreference = RoutingPreference.AUTO,
    val speedAlert: Boolean = true,
    val speedTolerance: Int = 5,          // in display units (mph or km/h)
    val reportAlerts: Boolean = true,
    val cameraAlerts: Boolean = true,
    val reportServer: String = BuildConfig.DEFAULT_REPORT_SERVER,
    val nickname: String = "Driver",
    val keepScreenOn: Boolean = true,
    val tilt3d: Boolean = true,
    val satellite: Boolean = false,
)

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("roadwise", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings
    val current get() = _settings.value

    val clientId: String = sp.getString("clientId", null) ?: UUID.randomUUID().toString().also {
        sp.edit().putString("clientId", it).apply()
    }

    var lastLat: Double
        get() = sp.getFloat("lastLat", 39.8283f).toDouble()
        set(v) = sp.edit().putFloat("lastLat", v.toFloat()).apply()
    var lastLon: Double
        get() = sp.getFloat("lastLon", -98.5795f).toDouble()
        set(v) = sp.edit().putFloat("lastLon", v.toFloat()).apply()
    /** User tapped "Not now" on the "Get all of <state> offline" prompt (applies to every state). */
    var offlinePromptDismissed: Boolean
        get() = sp.getBoolean("offlinePromptDismissed", false)
        set(v) = sp.edit().putBoolean("offlinePromptDismissed", v).apply()
    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) = sp.edit().putBoolean("onboarded", v).apply()

    private fun load() = Settings(
        imperial = sp.getBoolean("imperial", true),
        voice = sp.getBoolean("voice", true),
        voiceVolumeBoost = sp.getBoolean("voiceBoost", false),
        avoidTolls = sp.getBoolean("avoidTolls", false),
        avoidHighways = sp.getBoolean("avoidHighways", false),
        avoidFerries = sp.getBoolean("avoidFerries", false),
        theme = runCatching { ThemeMode.valueOf(sp.getString("theme", "AUTO")!!) }.getOrDefault(ThemeMode.AUTO),
        routing = runCatching { RoutingPreference.valueOf(sp.getString("routing", "AUTO")!!) }.getOrDefault(RoutingPreference.AUTO),
        speedAlert = sp.getBoolean("speedAlert", true),
        speedTolerance = sp.getInt("speedTolerance", 5),
        reportAlerts = sp.getBoolean("reportAlerts", true),
        cameraAlerts = sp.getBoolean("cameraAlerts", true),
        reportServer = sp.getString("reportServer", BuildConfig.DEFAULT_REPORT_SERVER) ?: "",
        nickname = sp.getString("nickname", "Driver") ?: "Driver",
        keepScreenOn = sp.getBoolean("keepScreenOn", true),
        tilt3d = sp.getBoolean("tilt3d", true),
        satellite = sp.getBoolean("satellite", false),
    )

    fun update(f: (Settings) -> Settings) {
        val s = f(_settings.value)
        sp.edit()
            .putBoolean("imperial", s.imperial)
            .putBoolean("voice", s.voice)
            .putBoolean("voiceBoost", s.voiceVolumeBoost)
            .putBoolean("avoidTolls", s.avoidTolls)
            .putBoolean("avoidHighways", s.avoidHighways)
            .putBoolean("avoidFerries", s.avoidFerries)
            .putString("theme", s.theme.name)
            .putString("routing", s.routing.name)
            .putBoolean("speedAlert", s.speedAlert)
            .putInt("speedTolerance", s.speedTolerance)
            .putBoolean("reportAlerts", s.reportAlerts)
            .putBoolean("cameraAlerts", s.cameraAlerts)
            .putString("reportServer", s.reportServer.trim().trimEnd('/'))
            .putString("nickname", s.nickname)
            .putBoolean("keepScreenOn", s.keepScreenOn)
            .putBoolean("tilt3d", s.tilt3d)
            .putBoolean("satellite", s.satellite)
            .apply()
        _settings.value = s.copy(reportServer = s.reportServer.trim().trimEnd('/'))
    }
}

object Units {
    fun distance(m: Double, imperial: Boolean): String {
        if (imperial) {
            val ft = m * 3.28084
            val mi = m / 1609.344
            return when {
                mi >= 10 -> "${mi.roundToInt()} mi"
                mi >= 0.19 -> String.format("%.1f mi", mi)
                ft >= 100 -> "${(ft / 50).roundToInt() * 50} ft"
                else -> "${(ft / 10).roundToInt() * 10} ft"
            }
        }
        return when {
            m >= 10_000 -> "${(m / 1000).roundToInt()} km"
            m >= 1000 -> String.format("%.1f km", m / 1000)
            m >= 100 -> "${(m / 50).roundToInt() * 50} m"
            else -> "${(m / 10).roundToInt() * 10} m"
        }
    }

    /** Distance phrased for speech: "half a mile", "800 feet". */
    fun spokenDistance(m: Double, imperial: Boolean): String {
        if (imperial) {
            val mi = m / 1609.344
            val ft = m * 3.28084
            return when {
                mi >= 1.75 -> "${mi.roundToInt()} miles"
                mi >= 0.85 -> "1 mile"
                mi >= 0.6 -> "three quarters of a mile"
                mi >= 0.4 -> "half a mile"
                mi >= 0.2 -> "a quarter mile"
                else -> "${((ft / 100).roundToInt() * 100).coerceAtLeast(100)} feet"
            }
        }
        return if (m >= 1000) {
            val km = m / 1000
            if (km >= 2) "${km.roundToInt()} kilometers" else String.format("%.1f kilometers", km)
        } else "${((m / 50).roundToInt() * 50).coerceAtLeast(50)} meters"
    }

    fun duration(sec: Double): String {
        val min = (sec / 60).roundToInt()
        return if (min < 60) "$min min" else "${min / 60} hr ${min % 60} min"
    }

    fun speed(mps: Double, imperial: Boolean): Int =
        if (imperial) (mps * 2.236936).roundToInt() else (mps * 3.6).roundToInt()

    fun limitFromKmh(kmh: Int, imperial: Boolean): Int =
        if (imperial) ((kmh / 1.609344) / 5).roundToInt() * 5 else kmh

    fun speedUnit(imperial: Boolean) = if (imperial) "mph" else "km/h"
}
