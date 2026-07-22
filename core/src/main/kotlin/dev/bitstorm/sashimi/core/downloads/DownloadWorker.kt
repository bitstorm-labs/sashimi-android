package dev.bitstorm.sashimi.core.downloads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters

/**
 * WorkManager entry point for a single download. All the real work lives in
 * [DownloadManager.performDownload] (so the streaming/resume logic sits next to
 * its dependencies); this worker bridges WorkManager's lifecycle to it and
 * publishes a foreground-service progress notification.
 *
 * Running as a foreground service (via [setForeground]) keeps long downloads
 * alive through Doze/background limits and shows the user live progress. The work
 * is constrained to a connected network and survives process death — WorkManager
 * re-runs it, and the OkHttp Range resume picks up the partial file.
 */
class DownloadWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val itemId = inputData.getString(KEY_ITEM_ID) ?: return Result.failure()
        val manager = DownloadManager.current ?: return Result.retry()

        ensureChannel()
        val notificationId = notificationId(itemId)
        runCatching { setForeground(foregroundInfo(notificationId, "Preparing download…", -1)) }

        return manager.performDownload(
            itemId = itemId,
            isStopped = { isStopped },
            onProgress = { title, percent ->
                runCatching { setForeground(foregroundInfo(notificationId, title, percent)) }
            },
        )
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    /** Builds the progress notification; [percent] < 0 renders an indeterminate bar. */
    private fun foregroundInfo(
        notificationId: Int,
        title: String,
        percent: Int,
    ): ForegroundInfo {
        val builder =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
        if (percent < 0) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setContentText("$percent%").setProgress(100, percent.coerceIn(0, 100), false)
        }
        val notification: Notification = builder.build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    companion object {
        const val KEY_ITEM_ID = "itemId"
        const val TAG = "sashimi-download"
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID_BASE = 4201

        /**
         * Stable, per-item foreground-notification id. With MAX_CONCURRENT=2 the
         * two live workers must not share one id (4201) or their foreground
         * notifications collide; deriving it from the item id keeps each worker's
         * notification distinct while staying stable across the worker's retries.
         */
        fun notificationId(itemId: String): Int = NOTIFICATION_ID_BASE + (itemId.hashCode() and 0xFFFF)

        fun itemTag(itemId: String): String = "item:$itemId"

        fun uniqueName(itemId: String): String = "download-$itemId"
    }
}
