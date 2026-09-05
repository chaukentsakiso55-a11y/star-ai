package com.cyberpulse.starai

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class LibraryDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val pageId = inputData.getInt(KEY_PAGE_ID, -1)
        val title = inputData.getString(KEY_TITLE).orEmpty()
        if (pageId <= 0 || title.isBlank()) return@withContext Result.failure()

        try {
            if (downloadLibraryToDisk(applicationContext, pageId, title)) {
                Result.success()
            } else if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        } catch (_: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private fun downloadLibraryToDisk(context: Context, pageId: Int, title: String): Boolean {
        val encodedTitle = URLEncoder.encode(title.replace(' ', '_'), Charsets.UTF_8.name())
        val url = URL("https://en.wikipedia.org/api/rest_v1/page/summary/$encodedTitle")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "StarAI/1.1 Android")
        }

        return try {
            val code = connection.responseCode
            if (code !in 200..299) return false

            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(body)
            val extract = json.optString("extract").trim()
            if (extract.isBlank()) return false

            val canonicalTitle = json.optString("title").ifBlank { title }
            val pageUrl = json.optJSONObject("content_urls")
                ?.optJSONObject("desktop")
                ?.optString("page")
                .orEmpty()

            val libraryDir = File(context.filesDir, "star_libraries").apply { mkdirs() }
            val safeName = canonicalTitle
                .replace(Regex("[^A-Za-z0-9._-]+"), "_")
                .trim('_')
                .take(80)
                .ifBlank { "library_$pageId" }

            val payload = JSONObject().apply {
                put("pageId", pageId)
                put("title", canonicalTitle)
                put("source", "Wikipedia")
                put("sourceUrl", pageUrl)
                put("downloadedAt", System.currentTimeMillis())
                put("text", extract)
            }

            File(libraryDir, "$safeName.json").writeText(payload.toString(2), Charsets.UTF_8)
            true
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val KEY_PAGE_ID = "page_id"
        const val KEY_TITLE = "title"
    }
}
