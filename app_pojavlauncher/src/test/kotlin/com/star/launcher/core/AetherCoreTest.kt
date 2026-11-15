package com.star.launcher.core

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.star.launcher.data.db.AetherDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AetherCoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: AetherDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AetherDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `settings repository persists typed values and blocks credential keys`() = runTest {
        val repo = AetherSettingsRepository(db.settingsDao(), clockMillis = { 1234L })
        repo.setBoolean("prefer_dark_theme", true)
        repo.setInt("default_ram_mb", 3072)
        repo.setString("default_renderer", "opengles_mobileglues")

        assertTrue(repo.getBoolean("prefer_dark_theme", false))
        assertEquals(3072, repo.getInt("default_ram_mb", 1024))
        assertEquals("opengles_mobileglues", repo.getString("default_renderer"))

        val rejected = runCatching {
            repo.setString("msa_refresh_token", "secret")
        }
        assertTrue(rejected.isFailure)
    }

    @Test
    fun `storage manager isolates instance dirs and sanitizes traversal characters`() {
        val gameHome = tempFolder.newFolder("game_home")
        val dataDir = tempFolder.newFolder("app_data")
        val storage = AetherStorageManager({ gameHome }, { dataDir })

        val dir = storage.ensureInstanceDirectories("fabric/1.21.4:test")
        assertTrue(dir.isDirectory)
        assertTrue(dir.absolutePath.contains("aether_instances"))
        assertEquals(".minecraft", dir.name)
        assertEquals("fabric_1.21.4_test", dir.parentFile?.name)
    }
}
