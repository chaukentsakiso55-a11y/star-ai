package com.cyberpulse.starai

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LibraryDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val pageId = inputData.getInt(KEY_PAGE_ID, -1)
        val title = inputData.getString(KEY_TITLE).orEmpty()
        if (pageId <= 0 || title.isBlank()) return@withContext Result.failure()
        try {
            if (downloadLibraryToDisk(applicationContext, pageId, title)) Result.success() else Result.retry()
        } catch (_: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val KEY_PAGE_ID = "page_id"
        const val KEY_TITLE = "title"
    }
}
