package com.star.launcher.mods.modpack

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModpackImportManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createZip(target: File, entries: Map<String, ByteArray>): File {
        ZipOutputStream(FileOutputStream(target)).use { zos ->
            for ((name, bytes) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return target
    }

    @Test
    fun `imports small CurseForge overrides-only modpack without network and preserves existing instances`() = runTest {
        val instancesRoot = tempFolder.newFolder("custom_instances")
        val existingWorld = File(instancesRoot, "existing_survival/saves/MyWorld/level.dat").apply {
            parentFile?.mkdirs()
            writeText("precious-world-data")
        }

        val manifestJson = """
            {
              "minecraft": {
                "version": "1.21.4",
                "modLoaders": [{"id": "fabric-0.16.9", "primary": true}]
              },
              "manifestType": "minecraftModpack",
              "manifestVersion": 1,
              "name": "STAR Small Pack",
              "version": "1.0.0",
              "overrides": "overrides",
              "files": []
            }
        """.trimIndent()

        val packZip = createZip(
            File(tempFolder.root, "small-cf-pack.zip"),
            mapOf(
                "manifest.json" to manifestJson.toByteArray(),
                "overrides/config/example.cfg" to "setting=true".toByteArray(),
                "overrides/mods/local-mod.jar" to "fake-jar-bytes".toByteArray()
            )
        )

        val manager = ModpackImportManager(availableBytesProvider = { 4L * 1024L * 1024L * 1024L })
        val result = manager.importLocalFile(packZip, instancesRoot)

        assertEquals(ModpackFormat.CURSEFORGE, result.format)
        assertEquals("STAR Small Pack", result.modpackName)
        assertEquals("1.21.4", result.minecraftVersion)
        assertTrue(File(result.instanceDir, "config/example.cfg").isFile)
        assertTrue(File(result.instanceDir, "mods/local-mod.jar").isFile)
        assertEquals("precious-world-data", existingWorld.readText())
        assertEquals(ModpackImportStage.COMPLETED, manager.progress.value.stage)
    }

    @Test
    fun `imports large streaming Modrinth mrpack without loading archive into memory`() = runTest {
        val instancesRoot = tempFolder.newFolder("large_instances")
        val mrpackFile = File(tempFolder.root, "large-pack.mrpack")

        val indexJson = """
            {
              "formatVersion": 1,
              "game": "minecraft",
              "versionId": "2.5.0",
              "name": "Large Streaming Pack",
              "dependencies": {
                "minecraft": "1.20.4",
                "fabric-loader": "0.15.11"
              },
              "files": []
            }
        """.trimIndent()

        // Create a ~32 MB uncompressed multi-entry archive (compressible so test runs fast while streaming 32 MB through 64 KiB buffers)
        val chunk1Mb = ByteArray(1024 * 1024) { idx -> ((idx % 26) + 'a'.code).toByte() }
        ZipOutputStream(FileOutputStream(mrpackFile)).use { zos ->
            zos.putNextEntry(ZipEntry("modrinth.index.json"))
            zos.write(indexJson.toByteArray())
            zos.closeEntry()

            for (i in 1..32) {
                zos.putNextEntry(ZipEntry("overrides/assets/chunk_$i.dat"))
                zos.write(chunk1Mb)
                zos.closeEntry()
            }
            zos.putNextEntry(ZipEntry("client-overrides/options.txt"))
            zos.write("renderDistance:10\n".toByteArray())
            zos.closeEntry()
        }

        val manager = ModpackImportManager(availableBytesProvider = { 8L * 1024L * 1024L * 1024L })
        val result = manager.importLocalFile(mrpackFile, instancesRoot)

        assertEquals(ModpackFormat.MODRINTH, result.format)
        assertEquals("Large Streaming Pack", result.modpackName)
        assertEquals("1.20.4", result.minecraftVersion)
        assertEquals(1024L * 1024L, File(result.instanceDir, "assets/chunk_1.dat").length())
        assertEquals(1024L * 1024L, File(result.instanceDir, "assets/chunk_32.dat").length())
        assertTrue(File(result.instanceDir, "options.txt").isFile)
        // No staging leftovers
        val stagingDirs = instancesRoot.listFiles()?.filter { it.name.startsWith(".staging_") }.orEmpty()
        assertTrue("Staging directories must be cleaned up", stagingDirs.isEmpty())
    }

    @Test
    fun `rejects corrupted invalid and ZIP Slip archives before writing any files`() = runTest {
        val instancesRoot = tempFolder.newFolder("invalid_instances")
        val manager = ModpackImportManager(availableBytesProvider = { 4L * 1024L * 1024L * 1024L })

        // 1. Corrupted archive
        val corrupt = File(tempFolder.root, "corrupt.zip").apply { writeText("corrupted-bytes") }
        try {
            manager.importLocalFile(corrupt, instancesRoot)
            fail("Expected InvalidModpackArchiveException for corrupted file")
        } catch (expected: InvalidModpackArchiveException) {
            assertTrue(expected.message!!.contains("Corrupted", ignoreCase = true))
        }

        // 2. Valid ZIP missing manifest
        val missingManifest = createZip(
            File(tempFolder.root, "no-manifest.zip"),
            mapOf("readme.txt" to "hello".toByteArray())
        )
        try {
            manager.importLocalFile(missingManifest, instancesRoot)
            fail("Expected InvalidModpackArchiveException for missing manifest")
        } catch (expected: InvalidModpackArchiveException) {
            assertTrue(expected.message!!.contains("manifest", ignoreCase = true))
        }

        // 3. ZIP Slip in entry name
        val slipZip = createZip(
            File(tempFolder.root, "zipslip.zip"),
            mapOf(
                "manifest.json" to """{"name":"Bad","version":"1","minecraft":{"version":"1.20.4"},"files":[]}""".toByteArray(),
                "overrides/../../escape.txt" to "pwned".toByteArray()
            )
        )
        try {
            manager.importLocalFile(slipZip, instancesRoot)
            fail("Expected InvalidModpackArchiveException for ZIP Slip entry")
        } catch (expected: InvalidModpackArchiveException) {
            assertTrue(expected.message!!.contains("unsafe", ignoreCase = true))
        }

        // 4. ZIP Slip in modrinth.index.json file path
        val mrSlip = createZip(
            File(tempFolder.root, "mr-slip.mrpack"),
            mapOf(
                "modrinth.index.json" to """
                    {
                      "name": "BadMR",
                      "versionId": "1",
                      "dependencies": {"minecraft": "1.20.4"},
                      "files": [{"path": "../../outside.jar", "fileSize": 10, "downloads": ["https://example.com/a.jar"]}]
                    }
                """.trimIndent().toByteArray()
            )
        )
        try {
            manager.importLocalFile(mrSlip, instancesRoot)
            fail("Expected InvalidModpackArchiveException for unsafe Modrinth path")
        } catch (expected: InvalidModpackArchiveException) {
            assertTrue(expected.message!!.contains("unsafe", ignoreCase = true))
        }

        assertEquals(0, instancesRoot.listFiles()?.size ?: 0)
    }

    @Test
    fun `fails cleanly on insufficient storage without leaving partial directories`() = runTest {
        val instancesRoot = tempFolder.newFolder("low_space_instances")
        val packZip = createZip(
            File(tempFolder.root, "space-test.zip"),
            mapOf(
                "manifest.json" to """{"name":"SpaceTest","version":"1","minecraft":{"version":"1.21.4"},"files":[]}""".toByteArray(),
                "overrides/big.bin" to ByteArray(512 * 1024)
            )
        )

        // Simulate only 5 MB available (below uncompressed size + 32 MB safety margin)
        val manager = ModpackImportManager(availableBytesProvider = { 5L * 1024L * 1024L })
        try {
            manager.importLocalFile(packZip, instancesRoot)
            fail("Expected InsufficientStorageException")
        } catch (expected: InsufficientStorageException) {
            assertTrue(expected.message!!.contains("Insufficient storage", ignoreCase = true))
            assertTrue(expected.requiredBytes > expected.availableBytes)
        }

        assertFalse(manager.progress.value.isActive)
        assertEquals(ModpackImportStage.FAILED, manager.progress.value.stage)
        assertEquals(0, instancesRoot.listFiles()?.size ?: 0)
    }
}
