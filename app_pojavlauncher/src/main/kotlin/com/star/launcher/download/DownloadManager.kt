package com.star.launcher.download

import com.star.launcher.core.AetherStorageManager
import com.star.launcher.data.db.dao.DownloadTaskDao
import com.star.launcher.data.db.entity.DownloadTaskEntity
import com.star.launcher.data.db.model.DownloadStatus
import com.star.launcher.data.db.model.DownloadTaskType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
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

class UrlConnectionHttpTransport : HttpTransport {
    override suspend fun downloadToFile(
        url: String,
        destinationFile: File,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        destinationFile.parentFile?.mkdirs()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "STARLauncher/1.0")
        }
        try {
            val code = conn.responseCode
            check(code in 200..299) { "HTTP $code downloading $url" }
            val total = conn.contentLengthLong.coerceAtLeast(0L)
            var downloaded = 0L
            conn.inputStream.use { input ->
                FileOutputStream(destinationFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }
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
