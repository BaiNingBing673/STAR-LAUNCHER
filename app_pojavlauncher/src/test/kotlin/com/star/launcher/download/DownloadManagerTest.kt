package com.star.launcher.download

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.star.launcher.core.AetherStorageManager
import com.star.launcher.data.db.AetherDatabase
import com.star.launcher.data.db.model.DownloadStatus
import com.star.launcher.data.db.model.DownloadTaskType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: AetherDatabase
    private lateinit var storage: AetherStorageManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AetherDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val gameDir = tempFolder.newFolder("game")
        val dataDir = tempFolder.newFolder("data")
        storage = AetherStorageManager(
            { gameDir },
            { dataDir }
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `download completes when sha1 matches and fails on mismatch`() = runTest {
        val payload = "hello-minecraft-client".toByteArray()
        val sha1 = MessageDigest.getInstance("SHA-1").digest(payload).joinToString("") { "%02x".format(it) }

        val fakeTransport = object : HttpTransport {
            override suspend fun downloadToFile(
                url: String,
                destinationFile: File,
                onProgress: suspend (Long, Long) -> Unit
            ) {
                destinationFile.parentFile?.mkdirs()
                destinationFile.writeBytes(payload)
                onProgress(payload.size.toLong(), payload.size.toLong())
            }
        }
        val manager = DownloadManager(db.downloadTaskDao(), storage, fakeTransport, { 100L })

        val targetOk = File(tempFolder.root, "ok/client.jar")
        val taskOk = manager.enqueue(
            taskType = DownloadTaskType.CLIENT_JAR,
            sourceUrl = "https://example.invalid/client.jar",
            targetPath = targetOk.absolutePath,
            expectedSha1 = sha1
        )
        val doneOk = manager.executeTask(taskOk.taskId)
        assertEquals(DownloadStatus.COMPLETED, doneOk.status)
        assertTrue(targetOk.isFile)
        assertEquals(payload.size.toLong(), targetOk.length())

        val targetBad = File(tempFolder.root, "bad/client.jar")
        val taskBad = manager.enqueue(
            taskType = DownloadTaskType.CLIENT_JAR,
            sourceUrl = "https://example.invalid/client.jar",
            targetPath = targetBad.absolutePath,
            expectedSha1 = "0000000000000000000000000000000000000000"
        )
        val doneBad = manager.executeTask(taskBad.taskId)
        assertEquals(DownloadStatus.FAILED, doneBad.status)
        assertEquals(1, doneBad.retryCount)
        assertFalse(targetBad.exists())
        assertTrue(doneBad.errorMessage!!.contains("SHA-1 mismatch"))
    }
}
