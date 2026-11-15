package com.star.launcher.download

import com.star.launcher.core.AetherStorageManager
import com.star.launcher.data.db.dao.DownloadTaskDao
import com.star.launcher.data.db.entity.DownloadTaskEntity
import com.star.launcher.data.db.model.DownloadStatus
import com.star.launcher.data.db.model.DownloadTaskType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

interface HttpTransport {
    suspend fun downloadToFile(
        url: String,
        destinationFile: File,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit
    )
}

class UrlConnectionHttpTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    private val maxTransientRetries: Int = 2
) : HttpTransport {
    override suspend fun downloadToFile(
        url: String,
        destinationFile: File,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        destinationFile.parentFile?.mkdirs()
        var attempt = 0
        var backoffMs = 400L
        while (true) {
            try {
                downloadWithResume(url, destinationFile, onProgress)
                return@withContext
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: IOException) {
                if (attempt >= maxTransientRetries || e is NonRetryableHttpException) {
                    throw e
                }
                attempt++
                delay(backoffMs)
                backoffMs = (backoffMs * 2L).coerceAtMost(2_500L)
            }
        }
    }

    private suspend fun downloadWithResume(
        initialUrl: String,
        destinationFile: File,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit
    ) {
        var existingBytes = if (destinationFile.isFile) destinationFile.length().coerceAtLeast(0L) else 0L
        var currentUrl = initialUrl
        var redirects = 0
        while (true) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                requestMethod = "GET"
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "STARLauncher/1.0")
                if (existingBytes > 0L) {
                    setRequestProperty("Range", "bytes=$existingBytes-")
                }
            }
            try {
                val code = conn.responseCode
                if (code in 300..399 && redirects < 5) {
                    val location = conn.getHeaderField("Location")?.trim()
                    if (!location.isNullOrEmpty()) {
                        currentUrl = URL(URL(currentUrl), location).toString()
                        redirects++
                        continue
                    }
                }
                if (code == 416 && existingBytes > 0L) {
                    // Range not satisfiable (stale or already-complete partial file); restart cleanly.
                    destinationFile.delete()
                    existingBytes = 0L
                    continue
                }
                if (code !in 200..299) {
                    val retryable = code in setOf(408, 429, 502, 503, 504)
                    val msg = "HTTP $code downloading $currentUrl"
                    throw if (retryable) IOException(msg) else NonRetryableHttpException(msg)
                }

                val isResumeAccepted = code == HttpURLConnection.HTTP_PARTIAL && existingBytes > 0L
                val appendOffset = if (isResumeAccepted) existingBytes else 0L
                val remainingLength = conn.contentLengthLong.coerceAtLeast(0L)
                val total = if (remainingLength > 0L) appendOffset + remainingLength else 0L

                var downloaded = appendOffset
                var lastReportedBytes = downloaded
                var lastReportMs = 0L

                conn.inputStream.use { input ->
                    FileOutputStream(destinationFile, isResumeAccepted).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            if (downloaded - lastReportedBytes >= 64 * 1024L || now - lastReportMs >= 50L) {
                                lastReportedBytes = downloaded
                                lastReportMs = now
                                onProgress(downloaded, total)
                            }
                        }
                        output.flush()
                    }
                }
                onProgress(downloaded, if (total > 0L) total else downloaded)
                return
            } catch (e: SocketTimeoutException) {
                throw IOException("Timed out downloading $currentUrl", e)
            } finally {
                conn.disconnect()
            }
        }
    }

    private class NonRetryableHttpException(message: String) : IOException(message)
}

/**
 * Room-backed download queue with atomic `.part` staging and SHA-1 verification.
 */
class DownloadManager(
    private val downloadTaskDao: DownloadTaskDao,
    private val storageManager: AetherStorageManager,
    private val httpTransport: HttpTransport = UrlConnectionHttpTransport(),
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: () -> String = { UUID.randomUUID().toString() }
) {
    fun observeActiveTasks(): Flow<List<DownloadTaskEntity>> =
        downloadTaskDao.observeByStatus(
            listOf(
                DownloadStatus.QUEUED,
                DownloadStatus.RUNNING,
                DownloadStatus.PAUSED,
                DownloadStatus.VERIFYING,
                DownloadStatus.FAILED
            )
        )

    suspend fun enqueue(
        taskType: DownloadTaskType,
        sourceUrl: String,
        targetPath: String,
        expectedSha1: String? = null,
        instanceId: String? = null,
        versionId: String? = null
    ): DownloadTaskEntity {
        val entity = DownloadTaskEntity(
            taskId = idGenerator(),
            instanceId = instanceId,
            versionId = versionId,
            taskType = taskType,
            sourceUrl = sourceUrl,
            targetPath = targetPath,
            expectedSha1 = expectedSha1?.lowercase()?.takeIf { it.isNotBlank() },
            totalBytes = 0L,
            downloadedBytes = 0L,
            status = DownloadStatus.QUEUED,
            retryCount = 0,
            errorMessage = null,
            updatedAt = clockMillis()
        )
        downloadTaskDao.upsert(entity)
        return entity
    }

    suspend fun cancelTask(taskId: String) {
        val current = downloadTaskDao.getById(taskId) ?: return
        downloadTaskDao.upsert(
            current.copy(
                status = DownloadStatus.CANCELLED,
                updatedAt = clockMillis()
            )
        )
    }

    suspend fun executeTask(taskId: String): DownloadTaskEntity {
        var task = requireNotNull(downloadTaskDao.getById(taskId)) { "Unknown download task: $taskId" }
        val targetFile = File(task.targetPath)
        val partFile = File(storageManager.downloadStagingDir(), "${task.taskId}.part")

        task = task.copy(status = DownloadStatus.RUNNING, errorMessage = null, updatedAt = clockMillis())
        downloadTaskDao.upsert(task)

        try {
            httpTransport.downloadToFile(task.sourceUrl, partFile) { downloaded, total ->
                val latest = downloadTaskDao.getById(taskId)
                if (latest?.status == DownloadStatus.CANCELLED) {
                    throw CancellationException("Download cancelled: $taskId")
                }
                downloadTaskDao.upsert(
                    task.copy(
                        downloadedBytes = downloaded,
                        totalBytes = total.coerceAtLeast(downloaded),
                        status = DownloadStatus.RUNNING,
                        updatedAt = clockMillis()
                    )
                )
            }

            task = task.copy(
                downloadedBytes = partFile.length(),
                totalBytes = partFile.length(),
                status = DownloadStatus.VERIFYING,
                updatedAt = clockMillis()
            )
            downloadTaskDao.upsert(task)

            val expectedSha1 = task.expectedSha1
            if (expectedSha1 != null) {
                val actualSha1 = computeSha1(partFile)
                check(actualSha1.equals(expectedSha1, ignoreCase = true)) {
                    "SHA-1 mismatch: expected=$expectedSha1 actual=$actualSha1"
                }
            }

            targetFile.parentFile?.mkdirs()
            if (targetFile.exists()) targetFile.delete()
            check(partFile.renameTo(targetFile) || partFile.copyTo(targetFile, overwrite = true).let {
                partFile.delete()
                true
            }) {
                "Failed to promote verified artifact to ${targetFile.absolutePath}"
            }

            val completed = task.copy(
                downloadedBytes = targetFile.length(),
                totalBytes = targetFile.length(),
                status = DownloadStatus.COMPLETED,
                errorMessage = null,
                updatedAt = clockMillis()
            )
            downloadTaskDao.upsert(completed)
            return completed
        } catch (ce: CancellationException) {
            partFile.delete()
            val cancelled = task.copy(status = DownloadStatus.CANCELLED, updatedAt = clockMillis())
            downloadTaskDao.upsert(cancelled)
            throw ce
        } catch (t: Throwable) {
            partFile.delete()
            val failed = task.copy(
                status = DownloadStatus.FAILED,
                retryCount = task.retryCount + 1,
                errorMessage = t.message ?: t::class.java.simpleName,
                updatedAt = clockMillis()
            )
            downloadTaskDao.upsert(failed)
            return failed
        }
    }

    companion object {
        fun computeSha1(file: File): String {
            val digest = MessageDigest.getInstance("SHA-1")
            file.inputStream().use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val read = input.read(buf)
                    if (read == -1) break
                    digest.update(buf, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
