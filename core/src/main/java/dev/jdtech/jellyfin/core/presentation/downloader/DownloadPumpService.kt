package dev.jdtech.jellyfin.core.presentation.downloader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import dagger.hilt.android.AndroidEntryPoint
import dev.jdtech.jellyfin.core.R as CoreR
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Foreground service that keeps the app process alive while the DownloadQueue has
 * work to do. Android's DownloadManager runs downloads independently, but the
 * in-app pump (which moves items from Pending to DownloadManager as slots free up)
 * lives in the app process. Without this service, closing the app would stall any
 * Pending items until the user reopens the app.
 */
@AndroidEntryPoint
class DownloadPumpService : Service() {

    @Inject lateinit var downloadQueue: DownloadQueue

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observeJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        startForegroundWithCount(0)
        observeQueue()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Already posted foreground notification in onCreate; nothing to do.
        return START_STICKY
    }

    private fun observeQueue() {
        observeJob?.cancel()
        observeJob =
            scope.launch {
                downloadQueue.entries
                    .map { entries ->
                        entries.count {
                            it.state is DownloadQueue.EntryState.Downloading ||
                                it.state is DownloadQueue.EntryState.Pending ||
                                it.state is DownloadQueue.EntryState.Paused
                        }
                    }
                    .distinctUntilChanged()
                    .collect { count ->
                        if (count == 0) {
                            stopForegroundCompat()
                            stopSelf()
                        } else {
                            updateNotification(count)
                        }
                    }
            }
    }

    private fun startForegroundWithCount(count: Int) {
        val notification = buildNotification(count)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(count: Int) {
        val nm = getSystemService<NotificationManager>() ?: return
        nm.notify(NOTIFICATION_ID, buildNotification(count))
    }

    private fun buildNotification(count: Int): android.app.Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(CoreR.drawable.ic_download)
            .setContentTitle(
                resources.getQuantityString(
                    CoreR.plurals.downloads_in_progress_title,
                    count.coerceAtLeast(1),
                    count.coerceAtLeast(1),
                )
            )
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService<NotificationManager>() ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(CoreR.string.downloads_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
        channel.description = getString(CoreR.string.downloads_channel_description)
        channel.setShowBadge(false)
        nm.createNotificationChannel(channel)
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object {
        private const val CHANNEL_ID = "download_pump"
        private const val NOTIFICATION_ID = 42

        fun start(context: Context) {
            val intent = Intent(context, DownloadPumpService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to start DownloadPumpService")
            }
        }
    }
}
