package com.star.launcher.mods.shaders

import com.star.launcher.data.db.model.ModLoaderType
import com.star.launcher.mods.compat.EnvironmentDetectionSource
import com.star.launcher.mods.compat.InstanceEnvironment
import com.star.launcher.mods.ui.ModsSubTab
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ShaderPackManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createEnv(gameDir: File, mcVersion: String = "1.21.4"): InstanceEnvironment =
        InstanceEnvironment(
            instanceId = "inst-shader-1",
            instanceName = "Test Shaders",
            gameDirPath = gameDir.absolutePath,
            rawVersionId = "fabric-loader-0.16.9-$mcVersion",
            baseMinecraftVersion = mcVersion,
            loaderType = ModLoaderType.FABRIC,
            loaderVersion = "0.16.9",
            detectionSource = EnvironmentDetectionSource.MANIFEST_VERIFIED
        )

    private fun createZip(target: File, entries: Map<String, String>): File {
        ZipOutputStream(FileOutputStream(target)).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return target
    }

    @Test
    fun `dedicated Shaders tab exists alongside Discover and My Mods`() {
        assertEquals(
            listOf(ModsSubTab.DISCOVER, ModsSubTab.MY_MODS, ModsSubTab.SHADERS),
            ModsSubTab.entries
        )
    }

    @Test
    fun `imports valid shader zip into shaderpacks without touching mods directory`() = runTest {
        val gameDir = tempFolder.newFolder("instance_1")
        val modsDir = File(gameDir, "mods").apply { mkdirs() }
        val existingMod = File(modsDir, "iris-mc1.21.4.jar").apply { writeText("fake-iris-jar") }
        val sourceShader = createZip(
            File(tempFolder.root, "ComplementaryUnbound.zip"),
            mapOf(
                "shaders/gbuffers_terrain.vsh" to "#version 120\nvoid main() {}",
                "shaders/gbuffers_terrain.fsh" to "#version 120\nvoid main() {}",
                "shaders/shaders.properties" to "profile.LOW=shadowMapResolution=512\n"
            )
        )

        val manager = ShaderPackManager()
        val importRes = manager.importShaderPackFromFile(gameDir.absolutePath, sourceShader)

        assertEquals(listOf("ComplementaryUnbound.zip"), importRes.imported)
        assertTrue(importRes.failures.isEmpty())
        assertTrue(importRes.skippedDuplicates.isEmpty())

        val installedShader = File(gameDir, "shaderpacks/ComplementaryUnbound.zip")
        assertTrue("Shader must be in shaderpacks/", installedShader.isFile)
        assertFalse("Shader must NEVER be in mods/", File(modsDir, "ComplementaryUnbound.zip").exists())
        assertTrue("Existing mod must remain untouched", existingMod.isFile)

        val report = manager.inspectShaderPacks(
            environment = createEnv(gameDir),
            activeRendererId = "opengles_mobileglues",
            activeRendererLabel = "MobileGlues"
        )
        assertEquals(1, report.packs.size)
        assertTrue(report.hasShaderModInstalled)
        assertEquals("Iris Shaders", report.detectedShaderModName)
        assertEquals(ShaderCompatibilityLevel.COMPATIBLE, report.rendererCompatibilityLevel)
        assertEquals(ShaderInstallStatus.ENABLED, report.packs.first().status)
        assertEquals("ComplementaryUnbound", report.packs.first().displayName)
    }

    @Test
    fun `rejects ordinary fabric mod jars and corrupted or non-shader zips with clear errors`() = runTest {
        val gameDir = tempFolder.newFolder("instance_reject")
        val manager = ShaderPackManager()

        // 1. Ordinary .jar mod
        val jarMod = createZip(
            File(tempFolder.root, "sodium-fabric.jar"),
            mapOf("fabric.mod.json" to """{"id":"sodium"}""")
        )
        val jarRes = manager.importShaderPackFromFile(gameDir.absolutePath, jarMod)
        assertTrue(jarRes.imported.isEmpty())
        assertEquals(1, jarRes.failures.size)
        assertTrue(jarRes.failures.first().reason.contains("mods", ignoreCase = true))

        // 2. Mod packaged as .zip (no shaders/)
        val zipMod = createZip(
            File(tempFolder.root, "not-a-shader-mod.zip"),
            mapOf("fabric.mod.json" to """{"id":"example"}""", "com/example/Mod.class" to "bytecode")
        )
        val zipModRes = manager.importShaderPackFromFile(gameDir.absolutePath, zipMod)
        assertTrue(zipModRes.imported.isEmpty())
        assertEquals(1, zipModRes.failures.size)
        assertTrue(zipModRes.failures.first().reason.contains("mod", ignoreCase = true))

        // 3. Corrupted non-ZIP file
        val corrupt = File(tempFolder.root, "broken.zip").apply { writeText("NOT A ZIP FILE") }
        val corruptRes = manager.importShaderPackFromFile(gameDir.absolutePath, corrupt)
        assertTrue(corruptRes.imported.isEmpty())
        assertEquals(1, corruptRes.failures.size)
        assertNotNull(corruptRes.failures.first().reason)

        // 4. ZIP Slip traversal archive
        val slip = createZip(
            File(tempFolder.root, "slip.zip"),
            mapOf("../../evil.fsh" to "malicious")
        )
        val slipRes = manager.importShaderPackFromFile(gameDir.absolutePath, slip)
        assertTrue(slipRes.imported.isEmpty())
        assertEquals(1, slipRes.failures.size)
        assertTrue(slipRes.failures.first().reason.contains("unsafe", ignoreCase = true))
    }

    @Test
    fun `supports duplicate detection enable disable bulk selection and removal preserving sidecar configs`() = runTest {
        val gameDir = tempFolder.newFolder("instance_manage")
        val shaderDir = File(gameDir, "shaderpacks").apply { mkdirs() }
        val sidecarConfig = File(shaderDir, "BSL.zip.txt").apply { writeText("WATER_QUALITY=2\n") }

        val bslZip = createZip(
            File(tempFolder.root, "BSL.zip"),
            mapOf("shaders/final.fsh" to "void main(){}")
        )
        val sildursZip = createZip(
            File(tempFolder.root, "Sildurs.zip"),
            mapOf("shaders/final.fsh" to "void main(){}")
        )

        val manager = ShaderPackManager()
        assertEquals(listOf("BSL.zip"), manager.importShaderPackFromFile(gameDir.absolutePath, bslZip).imported)
        assertEquals(listOf("BSL.zip"), manager.importShaderPackFromFile(gameDir.absolutePath, bslZip).skippedDuplicates)
        assertEquals(listOf("Sildurs.zip"), manager.importShaderPackFromFile(gameDir.absolutePath, sildursZip).imported)

        // Toggle single shader off and back on
        val disabled = manager.toggleShaderEnabled(gameDir.absolutePath, "BSL.zip")
        assertTrue(disabled.isSuccess)
        assertFalse(disabled.getOrThrow())
        assertTrue(File(shaderDir, "BSL.zip.disabled").isFile)
        assertTrue("Sidecar config must be preserved", sidecarConfig.isFile)

        // Bulk disable remaining enabled pack
        val bulkOff = manager.setShadersEnabled(gameDir.absolutePath, listOf("Sildurs.zip", "BSL.zip.disabled"), false)
        assertEquals(1, bulkOff.getOrThrow())
        assertTrue(File(shaderDir, "Sildurs.zip.disabled").isFile)

        // Bulk enable both
        val bulkOn = manager.setShadersEnabled(gameDir.absolutePath, listOf("Sildurs.zip.disabled", "BSL.zip.disabled"), true)
        assertEquals(2, bulkOn.getOrThrow())
        assertTrue(File(shaderDir, "BSL.zip").isFile)
        assertTrue(File(shaderDir, "Sildurs.zip").isFile)

        // Remove multiple packs
        val removed = manager.removeShaders(gameDir.absolutePath, listOf("BSL.zip", "Sildurs.zip"))
        assertEquals(2, removed.getOrThrow())
        assertFalse(File(shaderDir, "BSL.zip").exists())
        assertFalse(File(shaderDir, "Sildurs.zip").exists())
        assertTrue("Sidecar settings file stays preserved", sidecarConfig.isFile)
    }

    @Test
    fun `detects incompatible GL4ES renderer for shaders`() = runTest {
        val gameDir = tempFolder.newFolder("instance_gl4es")
        val manager = ShaderPackManager()
        val report = manager.inspectShaderPacks(
            environment = createEnv(gameDir, "1.20.4"),
            activeRendererId = "opengles2",
            activeRendererLabel = "NG-GL4ES"
        )
        assertEquals(ShaderCompatibilityLevel.INCOMPATIBLE, report.rendererCompatibilityLevel)
        assertTrue(report.rendererGuidance.contains("MobileGlues", ignoreCase = true))
    }
}
