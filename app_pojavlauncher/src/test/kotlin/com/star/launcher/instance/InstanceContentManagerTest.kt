package com.star.launcher.instance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class InstanceContentManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `inspects instance mods resourcepacks shaders worlds and toggles mod non-destructively`() {
        val gameDir = tempFolder.newFolder("instance_mc")
        val modsDir = File(gameDir, "mods").apply { mkdirs() }
        File(modsDir, "iris-1.8.jar").writeText("iris")
        File(modsDir, "replaymod.jar.disabled").writeText("replay")
        File(gameDir, "resourcepacks/faithful.zip").apply { parentFile?.mkdirs(); writeText("rp") }
        File(gameDir, "shaderpacks/bsl.zip").apply { parentFile?.mkdirs(); writeText("sp") }
        File(gameDir, "saves/MyWorld/level.dat").apply { parentFile?.mkdirs(); writeText("nbt") }

        val mgr = InstanceContentManager()
        val initial = mgr.inspectInstanceContent(gameDir.absolutePath)
        assertEquals(2, initial.mods.size)
        assertEquals(1, initial.resourcePackCount)
        assertEquals(1, initial.shaderPackCount)
        assertEquals(1, initial.worldCount)

        val disabled = mgr.toggleModEnabled(gameDir.absolutePath, "iris-1.8.jar")!!
        assertFalse(disabled.isEnabled)
        assertEquals("iris-1.8.jar.disabled", disabled.fileName)
        assertTrue(File(modsDir, "iris-1.8.jar.disabled").isFile)

        val reEnabled = mgr.toggleModEnabled(gameDir.absolutePath, "replaymod.jar.disabled")!!
        assertTrue(reEnabled.isEnabled)
        assertEquals("replaymod.jar", reEnabled.fileName)
        assertTrue(File(modsDir, "replaymod.jar").isFile)
    }
}
