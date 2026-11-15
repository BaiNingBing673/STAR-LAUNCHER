package com.star.launcher.version

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.star.launcher.core.AetherStorageManager
import com.star.launcher.data.db.AetherDatabase
import com.star.launcher.data.db.model.ModLoaderType
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VersionManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: AetherDatabase
    private lateinit var gameHome: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AetherDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        gameHome = tempFolder.newFolder("game_home")
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `scans vanilla and inherited fabric manifests into room`() = runTest {
        val versionsDir = File(gameHome, ".minecraft/versions")
        val vanillaDir = File(versionsDir, "1.21.4").apply { mkdirs() }
        File(vanillaDir, "1.21.4.jar").writeText("jar")
        File(vanillaDir, "1.21.4.json").writeText(
            """
            {
              "id": "1.21.4",
              "type": "release",
              "releaseTime": "2024-12-03T10:00:00Z",
              "javaVersion": { "majorVersion": 21 },
              "arguments": { "game": [] },
              "libraries": [ { "name": "org.lwjgl:lwjgl:3.3.3" } ]
            }
            """.trimIndent()
        )

        val fabricDir = File(versionsDir, "1.21.4-fabric-0.16.9").apply { mkdirs() }
        File(fabricDir, "1.21.4-fabric-0.16.9.json").writeText(
            """
            {
              "id": "1.21.4-fabric-0.16.9",
              "inheritsFrom": "1.21.4",
              "mainClass": "net.fabricmc.loader.impl.launch.knot.KnotClient",
              "libraries": [ { "name": "net.fabricmc:fabric-loader:0.16.9" } ]
            }
            """.trimIndent()
        )

        val storage = AetherStorageManager({ gameHome }, { tempFolder.newFolder("data") })
        val manager = VersionManager(db.gameVersionDao(), storage)
        val scanned = manager.scanInstalledVersions()

        assertEquals(2, scanned.size)
        val fabric = db.gameVersionDao().getById("1.21.4-fabric-0.16.9")!!
        assertEquals(ModLoaderType.FABRIC, fabric.modLoaderType)
        assertEquals("0.16.9", fabric.modLoaderVersion)
        assertEquals(21, fabric.requiredJavaMajor)
        assertEquals(3, fabric.lwjglMajor)
        assertTrue(fabric.isInstalled)
    }

    @Test
    fun `parses legacy 1_8 forge manifest with java 8 and lwjgl 2`() {
        val json = """
            {
              "id": "1.8.9-forge-11.15.1.2318",
              "type": "release",
              "minecraftArguments": "--username ${'$'}{auth_player_name}",
              "libraries": [ { "name": "net.minecraftforge:forge:1.8.9-11.15.1.2318" } ]
            }
        """.trimIndent()

        val parsed = VersionManager.parseManifestJson(json, "1.8.9")
        assertEquals(ModLoaderType.FORGE, parsed.modLoaderType)
        assertEquals(8, parsed.requiredJavaMajor)
        assertEquals(2, parsed.lwjglMajor)
    }
}
