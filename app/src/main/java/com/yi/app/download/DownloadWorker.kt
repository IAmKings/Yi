package com.yi.app.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * First-run model downloader.
 *
 * Design: stream HF /resolve/<revision>/<file> to `<final>.part`, then verify
 * SHA-256 against the commit's LFS oid, then rename and record the install.
 * WorkManager retry re-runs the whole stream (size mismatch aborts early);
 * HF revisions are immutable, so a plain restart is the safest resume.
 */
class DownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_SPEC_ID = "spec_id"
        const val KEY_HOST = "host"
        const val KEY_PROGRESS = "progress"
        const val KEY_BYTES_DONE = "bytes_done"
        const val KEY_BYTES_TOTAL = "bytes_total"
        const val KEY_ERROR = "error"

        /**
         * Enqueue a download; requires an unmetered network by default.
         * Existing REPLACE: a re-request restarts and supersedes partial state.
         */
        fun enqueue(context: Context, spec: ModelSpec, host: String = "huggingface.co") {
            val req = OneTimeWorkRequestBuilder<DownloadWorker>()
                .addTag("model-download")
                .setInputData(workDataOf(KEY_SPEC_ID to spec.id, KEY_HOST to host))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5_000L, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("download-${spec.id}", ExistingWorkPolicy.REPLACE, req)
        }
    }

    override suspend fun doWork(): Result {
        val specId = inputData.getString(KEY_SPEC_ID) ?: return Result.failure()
        val spec = Models.byId(specId)
            ?: return Result.failure(workDataOf(KEY_ERROR to "unknown model id $specId"))
        val host = inputData.getString(KEY_HOST) ?: "huggingface.co"
        val repo = ModelRepository(applicationContext)
        val dir = File(applicationContext.filesDir, "models/${spec.id}").apply { mkdirs() }
        val final = File(dir, spec.fileName)
        val part = File(dir, spec.fileName + ".part")

        try {
            runDownload(spec, host, part, repo)
            if (part.length() != spec.sizeBytes) {
                throw IOException("size mismatch: got ${part.length()}, expected ${spec.sizeBytes}")
            }
            val sha = repo.sha256File(part)
            if (!sha.equals(spec.sha256, ignoreCase = true)) {
                throw IOException("sha256 mismatch: $sha (want ${spec.sha256})")
            }
            if (!part.renameTo(final)) throw IOException("rename .part -> final failed")
            repo.markInstalled(
                InstalledModel(
                    id = spec.id,
                    fileName = spec.fileName,
                    repo = spec.repo,
                    revision = spec.revision,
                    sizeBytes = final.length(),
                    sha256 = sha,
                ),
            )
            setProgress(
                workDataOf(
                    KEY_PROGRESS to 1f,
                    KEY_BYTES_DONE to final.length(),
                    KEY_BYTES_TOTAL to spec.sizeBytes,
                ),
            )
            return Result.success()
        } catch (e: Exception) {
            return if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure(workDataOf(KEY_ERROR to (e.message ?: e.javaClass.simpleName)))
            }
        }
    }

    private suspend fun runDownload(
        spec: ModelSpec,
        host: String,
        part: File,
        repo: ModelRepository,
    ) = withContext(kotlinx.coroutines.Dispatchers.IO) {
        setProgress(workDataOf(KEY_PROGRESS to 0f, KEY_BYTES_DONE to part.length(), KEY_BYTES_TOTAL to spec.sizeBytes))
        val resumableBytes = if (part.exists()) part.length().coerceAtMost(spec.sizeBytes) else 0L
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        val req = Request.Builder()
            .url(spec.url(host))
            .apply { if (resumableBytes > 0) addHeader("Range", "bytes=$resumableBytes-") }
            .build()
        client.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code} fetching ${spec.url(host)}" }
            val body = resp.body ?: throw IOException("empty body")
            // 206: appending a verified partial; 200: start over.
            val append = resp.code == 206 && resumableBytes > 0
            val from = if (append) resumableBytes else 0L
            val total = from + (body.contentLength().takeIf { it > 0 } ?: (spec.sizeBytes - from))
            body.byteStream().use { ins ->
                java.io.FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(256 shl 10)
                    var written = from
                    var read: Int
                    while (ins.read(buf).also { read = it } >= 0) {
                        out.write(buf, 0, read)
                        written += read
                        if (written % (8 shl 20) < buf.size) {
                            setProgress(
                                workDataOf(
                                    KEY_PROGRESS to written.toFloat() / total.coerceAtLeast(1),
                                    KEY_BYTES_DONE to written,
                                    KEY_BYTES_TOTAL to total,
                                ),
                            )
                        }
                    }
                }
            }
            if (!append && resumableBytes > 0) part.delete()
        }
    }
}
