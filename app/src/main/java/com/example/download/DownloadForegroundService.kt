package com.example.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.BrowserHunterApplication
import com.example.MainActivity
import com.example.model.DownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Keeps the shared downloader process important while a user-initiated transfer is active.
 * It does not run when the queue is idle and is not restarted automatically after process death.
 */
class DownloadForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observerJob: Job? = null
    private var lastNotificationUpdateMs = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val initialNotification = buildNotification(emptyList(), queuedCount = 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        observerJob?.cancel()
        val downloader = (application as BrowserHunterApplication).downloader
        observerJob = serviceScope.launch {
            downloader.downloads.collect { items ->
                val active = items.filter {
                    it.status == DownloadStatus.CONNECTING ||
                        it.status == DownloadStatus.DOWNLOADING
                }
                val queuedCount = items.count { it.status == DownloadStatus.PENDING }
                if (active.isEmpty() && queuedCount == 0) {
                    // Do not stop a newer service start if another download was enqueued
                    // while this observer was handling the previous queue becoming idle.
                    // stopSelfResult(startId) only succeeds for the latest delivered start ID.
                    if (stopSelfResult(startId)) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    }
                    return@collect
                }

                val now = SystemClock.elapsedRealtime()
                if (lastNotificationUpdateMs == 0L || now - lastNotificationUpdateMs >= 1000L) {
                    lastNotificationUpdateMs = now
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, buildNotification(active, queuedCount))
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Active downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown only while Browser Hunter is downloading files"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        active: List<com.example.model.DownloadItem>,
        queuedCount: Int
    ): Notification {
        val knownTotals = active.filter { it.totalBytes > 0L }
        val determinate = active.isNotEmpty() && knownTotals.size == active.size
        val totalBytes = knownTotals.sumOf { it.totalBytes }
        val downloadedBytes = active.sumOf { it.downloadedBytes.coerceAtLeast(0L) }
        val percent = if (determinate && totalBytes > 0L) {
            ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 99)
        } else {
            0
        }
        val text = when {
            active.isEmpty() && queuedCount > 0 -> "$queuedCount queued download(s)"
            active.isEmpty() -> "Preparing download…"
            queuedCount > 0 && determinate -> "${active.size} active • $queuedCount queued • $percent%"
            queuedCount > 0 -> "${active.size} active • $queuedCount queued"
            determinate -> "${active.size} active download(s) • $percent%"
            else -> "${active.size} active download(s)"
        }
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Browser Hunter")
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(if (determinate) 100 else 0, percent, !determinate)
            .build()
    }

    /**
     * Android 15+ enforces a rolling six-hour limit for dataSync foreground services.
     * Preserve partial files and pause transfers before stopping within the timeout grace period.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        (application as? BrowserHunterApplication)?.downloader?.pauseAllActiveDownloads()
        stopSelf()
    }

    override fun onDestroy() {
        observerJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "browser_hunter_active_downloads"
        private const val NOTIFICATION_ID = 47021

        @JvmStatic
        fun start(context: Context): Boolean {
            return try {
                ContextCompat.startForegroundService(
                    context.applicationContext,
                    Intent(context.applicationContext, DownloadForegroundService::class.java)
                )
                true
            } catch (_: Exception) {
                false
            }
        }
    }
}
