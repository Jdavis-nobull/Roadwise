package com.roadwise.app.nav

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.roadwise.app.MainActivity
import com.roadwise.app.R
import com.roadwise.app.RoadwiseApp
import com.roadwise.app.core.Units
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Keeps GPS + guidance running with the screen off or another app in front. */
class NavService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val listener: (Location) -> Unit = { (application as RoadwiseApp).c.nav.onLocation(it) }
    private var lastText = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Navigation", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(
            this, NOTIF_ID, build("Navigating", ""),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        )
        val c = (application as RoadwiseApp).c
        c.location.addListener(listener)
        c.location.acquire("nav", 1000)
        scope.launch {
            c.nav.state.collectLatest { s ->
                if (!s.active) { stopSelf(); return@collectLatest }
                val imperial = c.prefs.current.imperial
                val title = when {
                    s.arrived -> "You have arrived"
                    s.rerouting -> "Rerouting..."
                    s.next != null -> "${Units.distance(s.distToNextM, imperial)} · ${s.next.text}"
                    else -> "Navigating"
                }
                val sub = "${Units.distance(s.remainingM, imperial)} · ${Units.duration(s.remainingS)} · arrive ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(s.etaMillis))}"
                if (title + sub != lastText) {
                    lastText = title + sub
                    getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build(title, sub))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            (application as RoadwiseApp).c.nav.stop()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun build(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, NavService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_nav)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setContentIntent(open)
            .addAction(0, "End", stop)
            .build()
    }

    override fun onDestroy() {
        val c = (application as RoadwiseApp).c
        c.location.removeListener(listener)
        c.location.release("nav")
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "nav"
        const val NOTIF_ID = 7
        const val ACTION_STOP = "com.roadwise.app.STOP_NAV"
    }
}
