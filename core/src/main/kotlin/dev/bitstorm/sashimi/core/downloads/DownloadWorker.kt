package dev.bitstorm.sashimi.core.downloads

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * WorkManager entry point for a single download. All the real work lives in
 * [DownloadManager.performDownload] (so the streaming/resume logic sits next to
 * its dependencies); this worker just bridges WorkManager's lifecycle to it.
 *
 * The work is constrained to a connected network and survives process death —
 * WorkManager re-runs it, and the OkHttp Range resume picks up the partial file.
 */
class DownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val itemId = inputData.getString(KEY_ITEM_ID) ?: return Result.failure()
        val manager = DownloadManager.current ?: return Result.retry()
        return manager.performDownload(
            itemId = itemId,
            isStopped = { isStopped },
        )
    }

    companion object {
        const val KEY_ITEM_ID = "itemId"
        const val TAG = "sashimi-download"

        fun itemTag(itemId: String): String = "item:$itemId"

        fun uniqueName(itemId: String): String = "download-$itemId"
    }
}
