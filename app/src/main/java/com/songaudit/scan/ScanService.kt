package com.songaudit.scan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.songaudit.MainActivity
import com.songaudit.R
import com.songaudit.fix.Fixer
import com.songaudit.fix.Jobs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Runs a scan with the screen off. A full listen takes hours, so it is a
 * foreground service with a progress notification and a partial wake lock;
 * the work itself is [Scanner], which can be stopped at any moment and picks
 * up where it left off.
 */
class ScanService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Asked to stop when nothing runs: this instance exists only because of the request.
            job?.cancel() ?: stopSelf()
            return START_NOT_STICKY
        }
        // One thing at a time: a fix asked for during a scan waits for the next start.
        if (job?.isActive == true) return START_NOT_STICKY
        val fix = if (intent?.action == ACTION_FIX) Jobs.take() else null
        if (intent?.action == ACTION_FIX && fix == null) {
            if (job == null) stopSelf()
            return START_NOT_STICKY
        }

        channel()
        val first = notification(ScanState.progress.value)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION, first)
        }
        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "songaudit:scan")
            .apply { acquire(12 * 60 * 60 * 1000L) }

        val scan = scope.launch {
            if (fix != null) Fixer(applicationContext).run(fix) else Scanner(applicationContext, ::charging).run()
        }
        val updates = scope.launch {
            ScanState.progress.collect {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(it))
                delay(1000)
            }
        }
        job = scan
        scan.invokeOnCompletion {
            updates.cancel()
            wake?.takeIf { it.isHeld }?.release()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Android 15 caps data-sync services at six hours a day; what is done is saved, so just stop. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        job?.cancel()
    }

    override fun onDestroy() {
        scope.cancel()
        wake?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun charging(): Boolean {
        val status = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return status != 0
    }

    private fun channel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Scan", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress of a library scan"
                    setShowBadge(false)
                },
            )
        }
    }

    private fun notification(p: Progress): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ScanService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = when {
            p.waitingForCharger -> "Waiting for the charger"
            p.phase == Phase.LISTENING -> "${p.done} of ${p.total}" + (p.etaSeconds?.let { " · " + Format.duration(it) + " left" } ?: "")
            p.total > 0 -> "${p.done} of ${p.total}"
            p.phase == Phase.FINDING -> "${p.found} files"
            else -> ""
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_scan)
            .setContentTitle(p.phase.label)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(p.total.coerceAtLeast(0), p.done, p.total == 0)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "scan"
        private const val NOTIFICATION = 1
        private const val ACTION_STOP = "stop"
        private const val ACTION_FIX = "fix"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ScanService::class.java))
        }

        /** Runs the job posted to [Jobs] with the screen off, like a scan. */
        fun fix(context: Context) {
            context.startForegroundService(Intent(context, ScanService::class.java).setAction(ACTION_FIX))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ScanService::class.java).setAction(ACTION_STOP))
        }
    }
}

object Format {
    fun duration(seconds: Long): String = when {
        seconds < 60 -> "under a minute"
        seconds < 3600 -> "${seconds / 60} min"
        else -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
    }
}
