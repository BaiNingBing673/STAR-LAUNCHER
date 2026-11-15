package com.star.launcher.mods.download

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.star.launcher.core.AetherSettingsRepository
import com.star.launcher.core.AetherStorageManager
import com.star.launcher.data.db.AetherDatabase
import com.star.launcher.data.db.entity.GameInstanceEntity
import com.star.launcher.data.db.model.DownloadStatus
import com.star.launcher.data.db.model.ModLoaderType
import com.star.launcher.data.security.InMemoryEncryptedTokenVault
import com.star.launcher.download.HttpTransport
import com.star.launcher.mods.api.CurseForgeCredentialProvider
import com.star.launcher.mods.api.CurseForgeFile
import com.star.launcher.mods.api.CurseForgeFileHash
import com.star.launcher.mods.api.CurseForgeHashAlgorithm
import com.star.launcher.mods.api.CurseForgeHttpResponse
import com.star.launcher.mods.api.CurseForgeLinks
import com.star.launcher.mods.api.CurseForgeMod
import com.star.launcher.mods.api.CurseForgeModLoader
import com.star.launcher.mods.api.CurseForgeReleaseType
import com.star.launcher.mods.api.CurseForgeRepository
import com.star.launcher.mods.api.CurseForgeTransport
import com.star.launcher.mods.compat.EnvironmentDetectionSource
import com.star.launcher.mods.compat.InstanceEnvironment
import com.star.launcher.mods.compat.ModCompatibilityLevel
import com.star.launcher.mods.inventory.InstalledModManager
import com.star.launcher.mods.inventory.JarModMetadataParser
import com.star.launcher.mods.inventory.ModMetadataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModDownloadAndInventoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: AetherDatabase
    private lateinit var storage: AetherStorageManager
    private lateinit var cred: CurseForgeCredentialProvider
    private lateinit var instDir: File
    private lateinit var otherInstDir: File
    private lateinit var env: InstanceEnvironment

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AetherDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val gameDir = tempFolder.newFolder("game")
        val dataDir = tempFolder.newFolder("data")
        storage = AetherStorageManager({ gameDir }, { dataDir })
        val settings = AetherSettingsRepository(db.settingsDao())
        cred = CurseForgeCredentialProvider(InMemoryEncryptedTokenVault(), settings)
        instDir = tempFolder.newFolder("inst_alpha")
        otherInstDir = tempFolder.newFolder("inst_beta")
        runTest {
            cred.saveDeveloperApiKey("test-key-dl-123456")
            db.gameInstanceDao().upsert(
                GameInstanceEntity(
                    instanceId = "inst-alpha",
                    name = "Alpha Pack",
                    gameDirPath = instDir.absolutePath,
                    versionId = null,
                    runtimeId = null,
                    rendererProfileId = null,
                    allocatedRamMb = 2048,
                    customJvmArgs = null,
                    controlMapPath = null,
                    lastPlayedAt = null,
                    createdAt = 1L
                )
            )
            db.gameInstanceDao().upsert(
                GameInstanceEntity(
                    instanceId = "inst-beta",
                    name = "Beta Pack",
                    gameDirPath = otherInstDir.absolutePath,
                    versionId = null,
                    runtimeId = null,
                    rendererProfileId = null,
                    allocatedRamMb = 2048,
                    customJvmArgs = null,
                    controlMapPath = null,
                    lastPlayedAt = null,
                    createdAt = 2L
                )
            )
        }
        env = InstanceEnvironment(
            instanceId = "inst-alpha",
            instanceName = "Alpha Pack",
            gameDirPath = instDir.absolutePath,
            rawVersionId = "1.20.1-fabric",
            baseMinecraftVersion = "1.20.1",
            loaderType = ModLoaderType.FABRIC,
            loaderVersion = "0.15.0",
            detectionSource = EnvironmentDetectionSource.MANIFEST_VERIFIED
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `hash mismatch rejects corrupted download and cleans staging part file`() = runTest {
        val expectedBytes = "valid-mod-jar-bytes".toByteArray()
        val sha1 = sha1Hex(expectedBytes)
        val corruptedBytes = "corrupted-bytes".toByteArray()

        val repo = stubDownloadUrlRepo("https://dl.example/mod.jar")
        val installer = ModDownloadInstaller(
            repository = repo,
            downloadTaskDao = db.downloadTaskDao(),
            installedModDao = db.installedModDao(),
            storageManager = storage,
            httpTransport = object : HttpTransport {
                override suspend fun downloadToFile(url: String, destinationFile: File, onProgress: suspend (Long, Long) -> Unit) {
                    destinationFile.writeBytes(corruptedBytes)
                    onProgress(corruptedBytes.size.toLong(), corruptedBytes.size.toLong())
                }
            }
        )

        val file = sampleModFile(fileId = 10L, modId = 200L, fileName = "good-mod.jar", bytes = expectedBytes, sha1 = sha1)
        val mod = sampleMod(modId = 200L, name = "Good Mod")

        val outcome = installer.installSingleFile(mod, file, env)
        assertTrue(outcome is ModInstallOutcome.Failed)
        assertFalse(File(instDir, "mods/good-mod.jar").exists())
        assertTrue(storage.downloadStagingDir().listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    @Test
    fun `interrupted download fails cleanly without installing partial file`() = runTest {
        val payload = "complete-mod-jar".toByteArray()
        val repo = stubDownloadUrlRepo("https://dl.example/interrupted.jar")
        val installer = ModDownloadInstaller(
            repository = repo,
            downloadTaskDao = db.downloadTaskDao(),
            installedModDao = db.installedModDao(),
            storageManager = storage,
            httpTransport = object : HttpTransport {
                override suspend fun downloadToFile(url: String, destinationFile: File, onProgress: suspend (Long, Long) -> Unit) {
                    destinationFile.writeBytes("partial".toByteArray())
                    throw IOException("Connection reset by peer")
                }
            }
        )

        val outcome = installer.installSingleFile(
            sampleMod(201L, "Net Mod"),
            sampleModFile(11L, 201L, "net-mod.jar", payload, sha1Hex(payload)),
            env
        )
        assertTrue(outcome is ModInstallOutcome.Failed)
        assertFalse(File(instDir, "mods/net-mod.jar").exists())
    }

    @Test
    fun `download cancellation reports Cancelled and cleans up temp file`() = runTest {
        val payload = "cancel-mod-jar".toByteArray()
        val repo = stubDownloadUrlRepo("https://dl.example/cancel.jar")
        lateinit var installer: ModDownloadInstaller
        installer = ModDownloadInstaller(
            repository = repo,
            downloadTaskDao = db.downloadTaskDao(),
            installedModDao = db.installedModDao(),
            storageManager = storage,
            httpTransport = object : HttpTransport {
                override suspend fun downloadToFile(url: String, destinationFile: File, onProgress: suspend (Long, Long) -> Unit) {
                    destinationFile.writeBytes(byteArrayOf(1, 2))
                    installer.requestCancel(12L)
                    throw CancellationException("User cancelled")
                }
            }
        )

        val outcome = installer.installSingleFile(
            sampleMod(202L, "Cancel Mod"),
            sampleModFile(12L, 202L, "cancel-mod.jar", payload, sha1Hex(payload)),
            env
        )
        assertTrue(outcome is ModInstallOutcome.Cancelled)
        assertFalse(File(instDir, "mods/cancel-mod.jar").exists())
        assertEquals(DownloadStatus.CANCELLED, installer.activeProgress.value[12L]?.phase?.let {
            if (it == ModDownloadPhase.CANCELLED) DownloadStatus.CANCELLED else DownloadStatus.FAILED
        })
    }

    @Test
    fun `insufficient storage blocks download before transferring bytes`() = runTest {
        var downloadCalled = false
        val payload = ByteArray(4096) { 0x5A }
        val repo = stubDownloadUrlRepo("https://dl.example/huge.jar")
        val installer = ModDownloadInstaller(
            repository = repo,
            downloadTaskDao = db.downloadTaskDao(),
            installedModDao = db.installedModDao(),
            storageManager = storage,
            httpTransport = object : HttpTransport {
                override suspend fun downloadToFile(url: String, destinationFile: File, onProgress: suspend (Long, Long) -> Unit) {
                    downloadCalled = true
                }
            },
            storageSpaceChecker = object : StorageSpaceChecker {
                override fun usableBytes(directory: File): Long = 1024L // < required bytes + headroom
            }
        )

        val outcome = installer.installSingleFile(
            sampleMod(203L, "Huge Mod"),
            sampleModFile(13L, 203L, "huge-mod.jar", payload, sha1Hex(payload)),
            env
        )
        assertTrue(outcome is ModInstallOutcome.Failed)
        assertTrue((outcome as ModInstallOutcome.Failed).message.contains("Insufficient storage"))
        assertFalse(downloadCalled)
    }

    @Test
    fun `duplicate mod overwrite prevention, wrong-instance isolation, and verified install succeed`() = runTest {
        val payload = createFabricModJarBytes("sodium", "Sodium", "0.5.8")
        val sha1 = sha1Hex(payload)
        val repo = stubDownloadUrlRepo("https://dl.example/sodium.jar")
        val installer = ModDownloadInstaller(
            repository = repo,
            downloadTaskDao = db.downloadTaskDao(),
            installedModDao = db.installedModDao(),
            storageManager = storage,
            httpTransport = object : HttpTransport {
                override suspend fun downloadToFile(url: String, destinationFile: File, onProgress: suspend (Long, Long) -> Unit) {
                    destinationFile.writeBytes(payload)
                    onProgress(payload.size.toLong(), payload.size.toLong())
                }
            }
        )

        val file = sampleModFile(14L, 204L, "sodium.jar", payload, sha1)
        val mod = sampleMod(204L, "Sodium")

        // First install succeeds into inst-alpha/mods ONLY (never inst-beta or global .minecraft)
        val ok = installer.installSingleFile(mod, file, env)
        assertTrue(ok is ModInstallOutcome.Success)
        val installedJar = File(instDir, "mods/sodium.jar")
        assertTrue(installedJar.isFile)
        assertFalse(File(otherInstDir, "mods/sodium.jar").exists())

        // Duplicate install with allowOverwrite = false is refused without silent overwrite
        val dup = installer.installSingleFile(mod, file, env, allowOverwrite = false)
        assertTrue(dup is ModInstallOutcome.Failed)
        assertTrue((dup as ModInstallOutcome.Failed).message.contains("already exists"))

        // Incompatible loader blocked even if targeting another instance (inst-beta runs NeoForge)
        val betaEnv = env.copy(instanceId = "inst-beta", instanceName = "Beta Pack", gameDirPath = otherInstDir.absolutePath, loaderType = ModLoaderType.NEOFORGE)
        val blockedWrongInstance = installer.installSingleFile(mod, file, betaEnv)
        assertTrue(blockedWrongInstance is ModInstallOutcome.Failed)
        assertFalse(File(otherInstDir, "mods/sodium.jar").exists())
    }

    @Test
    fun `installed mod manager parses jar manifests, toggles disabled safely, detects duplicates, removes on confirm, and checks updates`() = runTest {
        val modsDir = File(instDir, "mods").apply { mkdirs() }
        val modJar = File(modsDir, "iris-1.7.0.jar")
        modJar.writeBytes(createFabricModJarBytes("iris", "Iris Shaders", "1.7.0"))

        val dupJar = File(modsDir, "iris-1.7.0.jar.disabled")
        dupJar.writeBytes(createFabricModJarBytes("iris", "Iris Shaders", "1.7.0"))

        val weirdTxt = File(modsDir, "not-a-mod.txt").apply { writeText("notes") }
        assertTrue(weirdTxt.exists())

        val parsed = JarModMetadataParser.parse(modJar)
        assertEquals(ModMetadataSource.JAR_MANIFEST, parsed.source)
        assertEquals("iris", parsed.modId)
        assertEquals("Iris Shaders", parsed.displayName)
        assertEquals("1.7.0", parsed.version)
        assertEquals(ModLoaderType.FABRIC, parsed.loaderType)

        // Track mod record for update check
        db.installedModDao().upsert(
            com.star.launcher.data.db.entity.InstalledModEntity(
                recordId = "rec-1",
                instanceId = env.instanceId,
                fileName = "iris-1.7.0.jar",
                curseForgeModId = 455508L,
                curseForgeFileId = 500L,
                displayName = "Iris Shaders",
                installedVersionLabel = "1.7.0",
                mcVersionHint = "1.20.1",
                loaderType = ModLoaderType.FABRIC,
                sha1 = null,
                fileSizeBytes = modJar.length(),
                allowModDistribution = true,
                websiteUrl = "https://cf.example/iris",
                iconUrl = null,
                installedAt = 10L
            )
        )

        val updateRepo = CurseForgeRepository(cred, object : CurseForgeTransport {
            override suspend fun get(url: String, headers: Map<String, String>): CurseForgeHttpResponse {
                return CurseForgeHttpResponse(
                    200,
                    """{"data":[{"id":550,"modId":455508,"displayName":"Iris 1.7.2","fileName":"iris-1.7.2.jar","releaseType":1,"isAvailable":true,"isServerPack":false,"fileLength":2048,"downloadUrl":"https://dl.example/iris-1.7.2.jar","hashes":[],"gameVersions":["1.20.1","Fabric"],"dependencies":[]}],"pagination":{"index":0,"pageSize":25,"resultCount":1,"totalCount":1}}"""
                )
            }
        })

        val manager = InstalledModManager(db.installedModDao(), updateRepo)
        val report = manager.inspectInventory(env)
        assertEquals(listOf("not-a-mod.txt"), report.suspiciousNonJarFiles)
        assertEquals(2, report.mods.size)
        assertTrue(report.mods.all { it.isDuplicateOrSuspicious })

        // Remove duplicate disabled copy explicitly
        manager.removeMod(env, "iris-1.7.0.jar.disabled").getOrThrow()
        assertFalse(dupJar.exists())

        // Toggle enabled -> .jar.disabled preserves mod file
        val afterToggle = manager.toggleModEnabled(env, "iris-1.7.0.jar").getOrThrow()
        assertNotNull(afterToggle)
        assertEquals("iris-1.7.0.jar.disabled", afterToggle!!.fileName)
        assertFalse(afterToggle.isEnabled)
        assertTrue(File(modsDir, "iris-1.7.0.jar.disabled").isFile)

        // Check updates detects newer compatible file WITHOUT installing or modifying local file
        val refreshed = manager.inspectInventory(env)
        val checked = manager.checkUpdates(env, refreshed.mods)
        assertEquals(550L, checked.first().availableUpdate?.latestCompatibleFile?.id)
        assertTrue(File(modsDir, "iris-1.7.0.jar.disabled").isFile)
        assertFalse(File(modsDir, "iris-1.7.2.jar").exists())
    }

    @Test
    fun `rejects non-zip corrupted jar payload and rolls back failed multi-mod batch atomically`() = runTest {
        val htmlErrorPayload = "<html><body>502 Bad Gateway</body></html>".toByteArray()
        val htmlSha1 = sha1Hex(htmlErrorPayload)
        val corruptedFile = sampleModFile(901L, 301L, "corrupt-html.jar", htmlErrorPayload, htmlSha1)
        val corruptMod = sampleMod(301L, "CorruptMod")

        val installer = ModDownloadInstaller(
            repository = stubDownloadUrlRepo("https://dl.example/corrupt-html.jar"),
            downloadTaskDao = db.downloadTaskDao(),
            installedModDao = db.installedModDao(),
            storageManager = storage,
            httpTransport = object : HttpTransport {
                override suspend fun downloadToFile(url: String, destinationFile: File, onProgress: suspend (Long, Long) -> Unit) {
                    destinationFile.writeBytes(htmlErrorPayload)
                    onProgress(htmlErrorPayload.size.toLong(), htmlErrorPayload.size.toLong())
                }
            }
        )

        val corruptOutcome = installer.installSingleFile(corruptMod, corruptedFile, env)
        assertTrue(corruptOutcome is ModInstallOutcome.Failed)
        assertFalse(File(instDir, "mods/corrupt-html.jar").exists())
        assertTrue((File(instDir, "mods").listFiles() ?: emptyArray()).none { it.name.endsWith(".part") })

        // Corrupt existing jar in mods/ is flagged by JarModMetadataParser
        val badExisting = File(instDir, "mods/broken.jar").apply {
            parentFile?.mkdirs()
            writeBytes(htmlErrorPayload)
        }
        val parsedBad = JarModMetadataParser.parse(badExisting)
        assertFalse(parsedBad.isValidArchive)
        badExisting.delete()

        // Atomic batch install: first file downloads validly, second fails -> neither is committed to mods/
        val validBytes = createFabricModJarBytes("liba", "LibA", "1.0.0")
        val validSha1 = sha1Hex(validBytes)
        val libMod = sampleMod(401L, "LibA")
        val libFile = sampleModFile(1001L, 401L, "liba-1.0.0.jar", validBytes, validSha1)
        val failMod = sampleMod(402L, "BrokenDep")
        val failFile = sampleModFile(1002L, 402L, "brokendep-1.0.0.jar", validBytes, validSha1)

        val batchInstaller2 = ModDownloadInstaller(
            repository = CurseForgeRepository(
                cred,
                object : CurseForgeTransport {
                    override suspend fun get(url: String, headers: Map<String, String>): CurseForgeHttpResponse =
                        CurseForgeHttpResponse(200, """{"data":"https://dl.example/valid.jar"}""")
                }
            ),
            downloadTaskDao = db.downloadTaskDao(),
            installedModDao = db.installedModDao(),
            storageManager = storage,
            httpTransport = object : HttpTransport {
                override suspend fun downloadToFile(url: String, destinationFile: File, onProgress: suspend (Long, Long) -> Unit) {
                    if (url.contains("liba")) {
                        destinationFile.writeBytes(validBytes)
                        onProgress(validBytes.size.toLong(), validBytes.size.toLong())
                    } else {
                        throw java.io.IOException("Simulated CDN failure")
                    }
                }
            }
        )

        val compat = com.star.launcher.mods.compat.ModCompatibilityAssessment(
            level = ModCompatibilityLevel.COMPATIBLE,
            summary = "Compatible",
            reasons = listOf("Matches 1.20.1 & Fabric"),
            apiFacts = emptyList(),
            starInferences = emptyList(),
            canInstall = true
        )
        val plan = com.star.launcher.mods.deps.ModInstallPlan(
            rootItem = com.star.launcher.mods.deps.PlannedModInstallItem(
                mod = failMod,
                file = failFile.copy(downloadUrl = "https://dl.example/brokendep-1.0.0.jar"),
                isRootMod = true,
                compatibility = compat
            ),
            requiredDependencies = listOf(
                com.star.launcher.mods.deps.PlannedModInstallItem(
                    mod = libMod,
                    file = libFile.copy(downloadUrl = "https://dl.example/liba-1.0.0.jar"),
                    isRootMod = false,
                    compatibility = compat
                )
            ),
            optionalDependencies = emptyList(),
            alreadyInstalledDependencies = emptyList(),
            unresolvedDependencies = emptyList(),
            conflicts = emptyList(),
            detectedCycles = emptyList(),
            targetInstanceId = env.instanceId,
            targetInstanceName = env.instanceName,
            targetModsDirPath = File(instDir, "mods").absolutePath
        )
        val batchOutcome = batchInstaller2.installPlan(plan, env, allowOverwrite = false)
        assertTrue(batchOutcome is ModInstallOutcome.Failed)
        assertFalse(File(instDir, "mods/liba-1.0.0.jar").exists())
        assertFalse(File(instDir, "mods/brokendep-1.0.0.jar").exists())
    }

    private fun stubDownloadUrlRepo(downloadUrl: String) = CurseForgeRepository(
        cred,
        object : CurseForgeTransport {
            override suspend fun get(url: String, headers: Map<String, String>): CurseForgeHttpResponse {
                return if (url.contains("/download-url")) {
                    CurseForgeHttpResponse(200, """{"data":"$downloadUrl"}""")
                } else {
                    CurseForgeHttpResponse(404, "{}")
                }
            }
        }
    )

    private fun sampleMod(modId: Long, name: String) = CurseForgeMod(
        id = modId,
        name = name,
        slug = name.lowercase().replace(' ', '-'),
        summary = "Summary",
        downloadCount = 100L,
        authors = emptyList(),
        categories = emptyList(),
        logo = null,
        screenshots = emptyList(),
        links = CurseForgeLinks(null, null, null, null),
        allowModDistribution = true,
        dateModifiedIso = null,
        latestFiles = emptyList(),
        supportedMinecraftVersionsFromLatest = listOf("1.20.1"),
        supportedLoadersFromLatest = setOf(CurseForgeModLoader.FABRIC)
    )

    private fun sampleModFile(
        fileId: Long,
        modId: Long,
        fileName: String,
        bytes: ByteArray,
        sha1: String
    ) = CurseForgeFile(
        id = fileId,
        modId = modId,
        displayName = fileName,
        fileName = fileName,
        releaseType = CurseForgeReleaseType.RELEASE,
        isAvailable = true,
        isServerPack = false,
        fileDateIso = null,
        fileLengthBytes = bytes.size.toLong(),
        downloadUrl = "https://dl.example/$fileName",
        hashes = listOf(CurseForgeFileHash(sha1, CurseForgeHashAlgorithm.SHA1)),
        gameVersions = listOf("1.20.1", "Fabric"),
        dependencies = emptyList()
    )

    private fun createFabricModJarBytes(id: String, name: String, version: String): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("fabric.mod.json"))
            val json = """{"schemaVersion":1,"id":"$id","version":"$version","name":"$name","depends":{"minecraft":"1.20.1"}}"""
            zos.write(json.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return baos.toByteArray()
    }

    private fun sha1Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
}
