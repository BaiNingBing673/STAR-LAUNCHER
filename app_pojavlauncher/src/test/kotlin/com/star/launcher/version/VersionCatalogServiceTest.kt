package com.star.launcher.version

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.star.launcher.core.AetherStorageManager
import com.star.launcher.data.db.AetherDatabase
import com.star.launcher.data.db.entity.GameVersionEntity
import com.star.launcher.data.db.model.ModLoaderType
import com.star.launcher.download.DownloadManager
import com.star.launcher.download.HttpTransport
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VersionCatalogServiceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: AetherDatabase
    private lateinit var gameHome: File
    private lateinit var dataDir: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AetherDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        gameHome = tempFolder.newFolder("game_home")
        dataDir = tempFolder.newFolder("data_dir")
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `syncs Mojang manifest catalog while preserving installed rows`() = runTest {
        db.gameVersionDao().upsert(
            GameVersionEntity(
                versionId = "1.21.4",
                inheritsFrom = null,
                versionType = "release",
                modLoaderType = ModLoaderType.VANILLA,
                modLoaderVersion = null,
                requiredJavaMajor = 21,
                lwjglMajor = 3,
                manifestPath = "/local/1.21.4.json",
                isInstalled = true,
                releaseTimeEpochMs = 1000L
            )
        )

        val fakeManifest = """
            {
              "versions": [
                {"id": "25w02a", "type": "snapshot", "url": "https://example.invalid/25w02a.json", "releaseTime": "2025-01-10T00:00:00Z"},
                {"id": "1.21.4", "type": "release", "url": "https://example.invalid/1.21.4.json", "releaseTime": "2024-12-03T10:12:57+00:00"},
                {"id": "1.20.4", "type": "release", "url": "https://example.invalid/1.20.4.json", "releaseTime": "2023-12-07T12:00:00Z"}
              ]
            }
        """.trimIndent()

        val fakeTransport = object : HttpTransport {
            override suspend fun downloadToFile(
                url: String,
                destinationFile: File,
                onProgress: suspend (Long, Long) -> Unit
            ) {
                val bytes = fakeManifest.toByteArray()
                destinationFile.writeBytes(bytes)
                onProgress(bytes.size.toLong(), bytes.size.toLong())
            }
        }

        val storage = AetherStorageManager({ gameHome }, { dataDir })
        val dl = DownloadManager(db.downloadTaskDao(), storage, fakeTransport, { 100L })
        val vm = VersionManager(db.gameVersionDao(), storage)
        val svc = VersionCatalogService(db.gameVersionDao(), storage, dl, vm)

        val synced = svc.syncMojangManifestCatalog(manifestUrl = "https://example.invalid/manifest.json")
        assertEquals(2, synced.size)

        val localKept = db.gameVersionDao().getById("1.21.4")!!
        assertTrue(localKept.isInstalled)
        assertEquals("/local/1.21.4.json", localKept.manifestPath)

        val remote = db.gameVersionDao().getById("1.20.4")!!
        assertFalse(remote.isInstalled)
        assertEquals(17, remote.requiredJavaMajor)
    }

    @Test
    fun `installs Fabric profile JSON cleanly into isolated versions directory`() = runTest {
        val vanillaDir = File(gameHome, ".minecraft/versions/1.21.4").apply { mkdirs() }
        File(vanillaDir, "1.21.4.jar").writeText("jar")
        File(vanillaDir, "1.21.4.json").writeText("""{"id":"1.21.4","type":"release","javaVersion":{"majorVersion":21}}""")

        val fabricProfileJson = """
            {
              "id": "fabric-loader-0.16.10-1.21.4",
              "inheritsFrom": "1.21.4",
              "type": "release",
              "mainClass": "net.fabricmc.loader.impl.launch.knot.KnotClient",
              "libraries": [
                {"name": "net.fabricmc:fabric-loader:0.16.10"}
              ]
            }
        """.trimIndent()

        val fakeTransport = object : HttpTransport {
            override suspend fun downloadToFile(
                url: String,
                destinationFile: File,
                onProgress: suspend (Long, Long) -> Unit
            ) {
                destinationFile.writeText(fabricProfileJson)
                onProgress(fabricProfileJson.length.toLong(), fabricProfileJson.length.toLong())
            }
        }

        val storage = AetherStorageManager({ gameHome }, { dataDir })
        val dl = DownloadManager(db.downloadTaskDao(), storage, fakeTransport, { 200L })
        val vm = VersionManager(db.gameVersionDao(), storage)
        val svc = VersionCatalogService(db.gameVersionDao(), storage, dl, vm)

        val installed = svc.installFabricOrQuiltProfile(ModLoaderType.FABRIC, "1.21.4", "0.16.10")
        assertEquals("fabric-loader-0.16.10-1.21.4", installed.versionId)
        assertEquals(ModLoaderType.FABRIC, installed.modLoaderType)
        assertEquals(21, installed.requiredJavaMajor)
        assertTrue(installed.isInstalled)
        assertTrue(File(gameHome, ".minecraft/versions/fabric-loader-0.16.10-1.21.4/fabric-loader-0.16.10-1.21.4.json").isFile)
    }
}
