package com.roadwise.app.offline

import android.app.Notification
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Keeps offline downloads running while the app is in the background. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        ServiceCompat.startForeground(
            this, NOTIF_ID, build("Preparing offline download", 0.0),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
        if (job?.isActive != true) {
            job = scope.launch {
                val areas = (application as RoadwiseApp).c.offlineAreas
                while (true) {
                    val next = areas.nextQueued() ?: break
                    val job = launch {
                        areas.download(next) { step, p ->
                            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build("${next.name}: $step", p))
                        }
                    }
                    areas.activeId = next.id
                    areas.activeJob = job
                    job.join()
                    areas.activeJob = null
                    areas.activeId = -1
                }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Offline downloads", NotificationManager.IMPORTANCE_LOW))
    }

    private fun build(text: String, p: Double): Notification {
        val pi = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java).putExtra("open", "offline"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_nav)
            .setContentTitle("Downloading offline maps")
            .setContentText(text)
            .setProgress(100, (p * 100).toInt(), p <= 0.0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "downloads"
        const val NOTIF_ID = 42
    }
}
