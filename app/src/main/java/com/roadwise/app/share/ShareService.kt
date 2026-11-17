package com.roadwise.app.share

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.roadwise.app.MainActivity
import com.roadwise.app.R
import com.roadwise.app.RoadwiseApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Keeps GPS on (and shows a "sharing location" notification) while live sharing is active. */
class ShareService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Location sharing", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 4, Intent(this, ShareService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_nav)
            .setContentTitle("Sharing your live location")
            .setContentText("Anyone with your link can see where you are")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop sharing", stop)
            .build()
        ServiceCompat.startForeground(this, NOTIF_ID, n,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        val c = (application as RoadwiseApp).c
        c.location.acquire("share", 5000)
        scope.launch { c.share.state.collect { if (!it.active) stopSelf() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            (application as RoadwiseApp).c.share.stop()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        (application as RoadwiseApp).c.location.release("share")
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "share"
        const val NOTIF_ID = 9
        const val ACTION_STOP = "com.roadwise.app.STOP_SHARE"
    }
}
