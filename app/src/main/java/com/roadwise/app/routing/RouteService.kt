package com.roadwise.app.routing

import android.util.Log
import com.roadwise.app.core.LatLon
import com.roadwise.app.core.Net
import com.roadwise.app.core.Prefs
import com.roadwise.app.core.RoutingPreference

/** Picks online (Valhalla) or offline (BRouter) routing and falls back between them. */
class RouteService(
    private val net: Net,
    private val prefs: Prefs,
    val online: OnlineRouter,
    val offline: OfflineRouter,
) {
    suspend fun routes(
        from: LatLon, to: LatLon, mode: TravelMode,
        heading: Double? = null, avoid: List<LatLon> = emptyList(), alternates: Boolean = true,
    ): List<Route> {
        val s = prefs.current
        val tryOnline = s.routing != RoutingPreference.OFFLINE_ONLY && net.isOnline
        val canOffline = s.routing != RoutingPreference.ONLINE_ONLY
        var onlineError: Exception? = null
        if (tryOnline) {
            try {
                val r = online.route(from, to, mode, s, heading, avoid, if (alternates) 2 else 0)
                if (r.isNotEmpty()) return r
            } catch (e: Exception) {
                Log.w(TAG, "online routing failed", e)
                onlineError = e
            }
        }
        if (canOffline) {
            try {
                return listOf(offline.route(from, to, mode, s, heading, avoid))
            } catch (e: Exception) {
                Log.w(TAG, "offline routing failed", e)
                if (onlineError == null && !net.isOnline && s.routing != RoutingPreference.OFFLINE_ONLY) {
                    throw RoutingException("You're offline. ${e.message}")
                }
                throw RoutingException(e.message ?: "Routing failed")
            }
        }
        throw RoutingException(
            if (!net.isOnline) "You're offline and offline routing is turned off in Settings."
            else "Couldn't reach the routing server: ${onlineError?.message ?: "unknown error"}"
        )
    }

    suspend fun enrich(route: Route) {
        if (route.source == "online" && net.isOnline) {
            runCatching { online.fetchSpeedLimits(route) }.onFailure { Log.w(TAG, "speed limits", it) }
        }
    }

    companion object { const val TAG = "RouteService" }
}
