package com.star.launcher.version

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.star.launcher.compat.CompatibilityEvaluator
import com.star.launcher.compat.DeviceCapabilitiesSnapshot
import com.star.launcher.compat.DeviceCapabilityProbe
import com.star.launcher.compat.GpuFamily
import com.star.launcher.core.AetherSettingsRepository
import com.star.launcher.core.AetherStorageManager
import com.star.launcher.data.db.AetherDatabase
import com.star.launcher.data.db.entity.GameVersionEntity
import com.star.launcher.data.db.entity.RendererProfileEntity
import com.star.launcher.data.db.entity.RuntimeRegistryEntity
import com.star.launcher.data.db.model.ModLoaderType
import com.star.launcher.diagnostics.CrossProcessSpoolBridge
import com.star.launcher.diagnostics.DiagnosticsManager
import com.star.launcher.download.DownloadManager
import com.star.launcher.download.HttpTransport
import com.star.launcher.engine.adapter.EngineLaunchSpec
import com.star.launcher.engine.adapter.NativeEngineAdapter
import com.star.launcher.engine.adapter.RendererFacade
import com.star.launcher.engine.adapter.RendererSpec
import com.star.launcher.engine.adapter.RuntimeDescriptor
import com.star.launcher.engine.adapter.RuntimeFacade
import com.star.launcher.instance.InstanceManager
import com.star.launcher.ui.dashboard.AetherDashboardController
import com.star.launcher.ui.screens.filterAndSortVersionCatalog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.kdt.pojavlaunch.utils.DownloadUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.SocketTimeoutException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VersionManagerRedesignTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: AetherDatabase
    private lateinit var gameHome: File
    private lateinit var dataDir: File
    private lateinit var storage: AetherStorageManager
    private lateinit var settings: AetherSettingsRepository
    private lateinit var versionManager: VersionManager
    private val directExecutor = Executor { runnable -> runnable.run() }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AetherDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(directExecutor)
            .setTransactionExecutor(directExecutor)
            .build()
        gameHome = tempFolder.newFolder("game_home")
        dataDir = tempFolder.newFolder("data_dir")
        storage = AetherStorageManager({ gameHome }, { dataDir })
        settings = AetherSettingsRepository(db.settingsDao()) { 5000L }
        versionManager = VersionManager(db.gameVersionDao(), storage)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `installVersion prevents duplicates emits real stages and verifies files before marking installed`() = runTest(UnconfinedTestDispatcher()) {
        val startCount = AtomicInteger(0)
        var activeCallbacks: MinecraftInstallBackend.Callbacks? = null

        val fakeBackend = object : MinecraftInstallBackend {
            override fun isNetworkAvailable() = true
            override fun availableStorageBytes() = 10L * 1024 * 1024 * 1024
            override fun startInstall(
                versionId: String,
                manifestUrl: String?,
                manifestSha1: String?,
                callbacks: MinecraftInstallBackend.Callbacks
            ): MinecraftInstallBackend.BackendHandle {
                startCount.incrementAndGet()
                activeCallbacks = callbacks
                return object : MinecraftInstallBackend.BackendHandle {
                    override fun cancel() {}
                }
            }
        }

        db.gameVersionDao().upsert(
            sampleEntity(versionId = "1.21.4", isInstalled = false, releaseTime = 2000L)
        )

        val coordinator = VersionInstallCoordinator(
            versionManager = versionManager,
            settingsRepository = settings,
            backend = fakeBackend,
            clockMillis = { 9000L },
            scope = backgroundScope
        )

        assertTrue(coordinator.installVersion("1.21.4"))
        // Duplicate install request for same version MUST be ignored
        assertFalse(coordinator.installVersion("1.21.4"))
        assertEquals(1, startCount.get())

        val callbacks = activeCallbacks!!
        callbacks.onStageChanged(InstallationStage.DOWNLOADING_CLIENT_JAR, "1.21.4.jar")
        callbacks.onProgress(125L * 1024 * 1024, 200L * 1024 * 1024, 1, 10, "1.21.4.jar")

        val midProgress = coordinator.activeProgress.value["1.21.4"]!!
        assertEquals(InstallationStage.DOWNLOADING_CLIENT_JAR, midProgress.stage)
        assertEquals(62.5f, midProgress.progressPercent!!, 0.1f)
        assertEquals(125L * 1024 * 1024, midProgress.downloadedBytes)
        assertEquals(200L * 1024 * 1024, midProgress.totalBytes)

        // Write valid version files on disk so verification succeeds
        writeValidVersionFiles(versionId = "1.21.4")
        callbacks.onCompleted()

        val doneProgress = coordinator.activeProgress.value["1.21.4"]!!
        assertEquals(InstallationStage.COMPLETED, doneProgress.stage)
        assertTrue(doneProgress.isFinished)
        assertTrue(db.gameVersionDao().getById("1.21.4")!!.isInstalled)
        val meta = coordinator.getMetadata("1.21.4")!!
        assertTrue((meta.diskSizeBytes ?: 0L) > 0L)
        assertNotNull(meta.installedAt)
    }

    @Test
    fun `unknown totalBytes does not invent fake percentages and incomplete files fail verification`() = runTest(UnconfinedTestDispatcher()) {
        var capturedCallbacks: MinecraftInstallBackend.Callbacks? = null

        val fakeBackend = object : MinecraftInstallBackend {
            override fun isNetworkAvailable() = true
            override fun availableStorageBytes() = 10L * 1024 * 1024 * 1024
            override fun startInstall(
                versionId: String,
                manifestUrl: String?,
                manifestSha1: String?,
                callbacks: MinecraftInstallBackend.Callbacks
            ): MinecraftInstallBackend.BackendHandle {
                capturedCallbacks = callbacks
                return object : MinecraftInstallBackend.BackendHandle {
                    override fun cancel() {}
                }
            }
        }

        val coordinator = VersionInstallCoordinator(
            versionManager = versionManager,
            settingsRepository = settings,
            backend = fakeBackend,
            scope = backgroundScope
        )

        assertTrue(coordinator.installVersion("1.20.4"))
        val callbacks = capturedCallbacks!!
        callbacks.onStageChanged(InstallationStage.DOWNLOADING_LIBRARIES, "gson-2.10.jar")
        callbacks.onProgress(512_000L, null, 3, 24, "gson-2.10.jar")

        val progress = coordinator.activeProgress.value["1.20.4"]!!
        assertNull("Progress percentage must stay null when total bytes are unknown", progress.progressPercent)
        assertNull(progress.totalBytes)
        assertEquals(3, progress.processedFiles)
        assertEquals(24, progress.totalFiles)

        // Complete WITHOUT creating the client jar on disk -> verification MUST reject marking installed
        val verDir = File(storage.versionsRoot(), "1.20.4").apply { mkdirs() }
        File(verDir, "1.20.4.json").writeText("""{"id":"1.20.4","type":"release","downloads":{"client":{"url":"https://x","size":100}}}""")
        callbacks.onCompleted()

        val afterVerify = coordinator.activeProgress.value["1.20.4"]!!
        assertEquals(InstallErrorCategory.CORRUPTED_FILES, afterVerify.errorCategory)
        assertFalse(db.gameVersionDao().getById("1.20.4")?.isInstalled ?: false)
    }

    @Test
    fun `cancellation removes partial aetherpart files and never marks version installed`() = runTest(UnconfinedTestDispatcher()) {
        val backendCancelled = AtomicBoolean(false)

        val fakeBackend = object : MinecraftInstallBackend {
            override fun isNetworkAvailable() = true
            override fun availableStorageBytes() = 10L * 1024 * 1024 * 1024
            override fun startInstall(
                versionId: String,
                manifestUrl: String?,
                manifestSha1: String?,
                callbacks: MinecraftInstallBackend.Callbacks
            ): MinecraftInstallBackend.BackendHandle {
                return object : MinecraftInstallBackend.BackendHandle {
                    override fun cancel() {
                        backendCancelled.set(true)
                        callbacks.onCancelled()
                    }
                }
            }
        }

        val verDir = File(storage.versionsRoot(), "1.19.4").apply { mkdirs() }
        val partJar = File(verDir, "1.19.4.jar.aetherpart").apply { writeText("partial-bytes") }
        val libsPart = File(storage.versionsRoot().parentFile, "libraries/org/example/lib.jar.aetherpart").apply {
            parentFile?.mkdirs()
            writeText("partial-lib")
        }

        val coordinator = VersionInstallCoordinator(
            versionManager = versionManager,
            settingsRepository = settings,
            backend = fakeBackend,
            scope = backgroundScope
        )

        assertTrue(coordinator.installVersion("1.19.4"))
        coordinator.cancelInstall("1.19.4")

        assertTrue(backendCancelled.get())
        assertFalse(partJar.exists())
        assertFalse(libsPart.exists())
        assertNull(coordinator.activeProgress.value["1.19.4"])
        assertEquals("", settings.getString(VersionInstallCoordinator.KEY_ACTIVE_INSTALL_VERSION_ID, ""))
    }

    @Test
    fun `handles offline insufficient storage failure retry and process interruption recovery`() = runTest(UnconfinedTestDispatcher()) {
        var online = false
        var freeBytes = 1024L * 1024L * 1024L
        var simulatedError: Throwable? = null

        val fakeBackend = object : MinecraftInstallBackend {
            override fun isNetworkAvailable() = online
            override fun availableStorageBytes() = freeBytes
            override fun startInstall(
                versionId: String,
                manifestUrl: String?,
                manifestSha1: String?,
                callbacks: MinecraftInstallBackend.Callbacks
            ): MinecraftInstallBackend.BackendHandle {
                val err = simulatedError
                if (err != null) {
                    callbacks.onFailed(err)
                } else {
                    writeValidVersionFiles(versionId)
                    callbacks.onCompleted()
                }
                return object : MinecraftInstallBackend.BackendHandle { override fun cancel() {} }
            }
        }

        val coordinator = VersionInstallCoordinator(
            versionManager = versionManager,
            settingsRepository = settings,
            backend = fakeBackend,
            scope = backgroundScope
        )

        // 1. Offline
        assertFalse(coordinator.installVersion("1.21.1"))
        assertEquals(
            InstallErrorCategory.NETWORK_UNAVAILABLE,
            coordinator.activeProgress.value["1.21.1"]?.errorCategory
        )

        // 2. Insufficient storage
        online = true
        freeBytes = 10L * 1024 * 1024 // 10 MB < 128 MB minimum
        assertFalse(coordinator.retryInstall("1.21.1"))
        assertEquals(
            InstallErrorCategory.INSUFFICIENT_STORAGE,
            coordinator.activeProgress.value["1.21.1"]?.errorCategory
        )

        // 3. Interrupted network stream -> DOWNLOAD_INTERRUPTED
        freeBytes = 2L * 1024 * 1024 * 1024
        simulatedError = SocketTimeoutException("Read timed out")
        assertTrue(coordinator.retryInstall("1.21.1"))
        assertEquals(
            InstallErrorCategory.DOWNLOAD_INTERRUPTED,
            coordinator.activeProgress.value["1.21.1"]?.errorCategory
        )

        // 4. Corrupted checksum -> Retry succeeds
        simulatedError = DownloadUtils.SHA1VerificationException("mismatch")
        assertTrue(coordinator.retryInstall("1.21.1"))
        assertEquals(
            InstallErrorCategory.CORRUPTED_FILES,
            coordinator.activeProgress.value["1.21.1"]?.errorCategory
        )
        assertNotNull(coordinator.activeProgress.value["1.21.1"]?.diagnosticDetails)

        simulatedError = null
        assertTrue(coordinator.retryInstall("1.21.1"))
        assertEquals(InstallationStage.COMPLETED, coordinator.activeProgress.value["1.21.1"]?.stage)
        assertTrue(db.gameVersionDao().getById("1.21.1")!!.isInstalled)

        // 5. Process interruption recovery
        val partInterrupted = File(storage.versionsRoot(), "1.18.2/1.18.2.jar.aetherpart").apply {
            parentFile?.mkdirs()
            writeText("interrupted")
        }
        settings.setString(VersionInstallCoordinator.KEY_ACTIVE_INSTALL_VERSION_ID, "1.18.2")
        coordinator.recoverInterruptedInstalls()
        assertFalse(partInterrupted.exists())
        val recoveredMeta = coordinator.getMetadata("1.18.2")!!
        assertTrue(recoveredMeta.wasInterrupted)
        assertEquals(InstallErrorCategory.PROCESS_INTERRUPTED, recoveredMeta.lastErrorCategory)
    }

    @Test
    fun `full catalog sync includes all version types and verifies real manifest updates`() = runTest {
        val fakeManifest = """
            {
              "versions": [
                {"id": "1.21.4", "type": "release", "url": "https://example.invalid/1.21.4.json", "sha1": "newsha1hash", "releaseTime": "2024-12-03T10:00:00Z"},
                {"id": "25w04a", "type": "snapshot", "url": "https://example.invalid/25w04a.json", "sha1": "snapsha1", "releaseTime": "2025-01-22T10:00:00Z"},
                {"id": "b1.7.3", "type": "old_beta", "url": "https://example.invalid/b1.7.3.json", "releaseTime": "2011-07-08T10:00:00Z"},
                {"id": "a1.2.6", "type": "old_alpha", "url": "https://example.invalid/a1.2.6.json", "releaseTime": "2010-12-03T10:00:00Z"}
              ]
            }
        """.trimIndent()

        val transport = object : HttpTransport {
            override suspend fun downloadToFile(
                url: String,
                destinationFile: File,
                onProgress: suspend (Long, Long) -> Unit
            ) {
                destinationFile.writeText(fakeManifest)
            }
        }
        val dl = DownloadManager(db.downloadTaskDao(), storage, transport, clockMillis = { 100L })
        val svc = VersionCatalogService(db.gameVersionDao(), storage, dl, versionManager)

        val result = svc.syncFullMojangCatalog(
            manifestUrl = "https://example.invalid/manifest.json",
            includeSnapshots = true,
            includeHistorical = true
        )
        assertEquals(4, result.entries.size)
        assertEquals("newsha1hash", result.remoteSha1ByVersionId["1.21.4"])

        // Record local install with an older SHA-1 and check verifiable update flag
        val verDir = writeValidVersionFiles("1.21.4")
        val verification = versionManager.verifyInstallation("1.21.4")
        assertTrue(verification.isValid)

        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val coordinator = VersionInstallCoordinator(
            versionManager = versionManager,
            settingsRepository = settings,
            backend = object : MinecraftInstallBackend {
                override fun isNetworkAvailable() = true
                override fun availableStorageBytes() = 1024L * 1024 * 1024
                override fun startInstall(
                    versionId: String,
                    manifestUrl: String?,
                    manifestSha1: String?,
                    callbacks: MinecraftInstallBackend.Callbacks
                ) = object : MinecraftInstallBackend.BackendHandle { override fun cancel() {} }
            },
            scope = kotlinx.coroutines.CoroutineScope(testDispatcher)
        )
        coordinator.recordVerifiedMetadata(verification)
        coordinator.recordRemoteManifestHashes(result.remoteSha1ByVersionId)
        val metaUpdated = coordinator.getMetadata("1.21.4")!!
        assertTrue("Manifest SHA mismatch must detect real update", metaUpdated.updateAvailable)

        // Matching remote SHA clears updateAvailable cleanly
        val matchingSha = verification.installedManifestSha1!!
        coordinator.recordRemoteManifestHashes(mapOf("1.21.4" to matchingSha))
        assertFalse(coordinator.getMetadata("1.21.4")!!.updateAvailable)
        assertTrue(verDir.exists())
    }

    @Test
    fun `controller persists selected version blocks uninstalled launch and filters or sorts catalog`() = runTest(UnconfinedTestDispatcher()) {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        db.runtimeRegistryDao().upsert(
            RuntimeRegistryEntity(
                runtimeId = "jre-21",
                displayName = "Internal JRE 21",
                majorVersion = 21,
                arch = "arm64",
                installPath = "/rt/21",
                binaryVersion = "21",
                isInternal = true,
                isVerified = true,
                installedAt = 1L
            )
        )
        db.rendererProfileDao().upsert(
            RendererProfileEntity(
                profileId = "mg",
                rendererId = "opengles_mobileglues",
                displayName = "MobileGlues",
                backendConfigJson = "{}",
                useAngle = false,
                preferSystemDriver = false,
                isDefault = true,
                updatedAt = 1L
            )
        )

        writeValidVersionFiles("1.21.4")
        db.gameVersionDao().upsertAll(
            listOf(
                sampleEntity("1.21.4", isInstalled = true, releaseTime = 3000L),
                sampleEntity("1.20.1", isInstalled = false, releaseTime = 2000L),
                sampleEntity("25w04a", isInstalled = false, releaseTime = 4000L, versionType = "snapshot"),
                sampleEntity("fabric-loader-0.16-1.21.4", isInstalled = true, releaseTime = 3500L, versionType = "modded", modLoaderType = ModLoaderType.FABRIC, loaderVer = "0.16")
            )
        )

        val diagnostics = DiagnosticsManager(db.diagnosticSessionDao(), db.compatibilityResultDao())
        val probe = object : DeviceCapabilityProbe {
            override fun probe() = DeviceCapabilitiesSnapshot(
                deviceFingerprint = "fp",
                cpuAbiPrimary = "arm64-v8a",
                supportedAbis = listOf("arm64-v8a"),
                is64Bit = true,
                androidApiLevel = 34,
                gpuVendor = "Qualcomm",
                gpuRenderer = "Adreno 740",
                glVersion = "3.2",
                glesMajorVersion = 3,
                gpuFamily = GpuFamily.ADRENO,
                hasVulkan = true,
                vulkanVersion = "1.3",
                hasAdrenoKgslNode = false,
                totalRamMb = 8192L,
                probedAt = 1L
            )
        }
        val nativeAdapter = object : NativeEngineAdapter {
            override fun verifyEngineLibrariesPresent() = emptyList<String>()
            override fun validateLaunchSpec(spec: EngineLaunchSpec) = emptyList<String>()
        }
        val instanceManager = InstanceManager(
            gameInstanceDao = db.gameInstanceDao(),
            gameVersionDao = db.gameVersionDao(),
            runtimeRegistryDao = db.runtimeRegistryDao(),
            rendererProfileDao = db.rendererProfileDao(),
            deviceInfoDao = db.deviceInfoDao(),
            storageManager = storage,
            capabilityProbe = probe,
            compatibilityEvaluator = CompatibilityEvaluator(),
            nativeEngineAdapter = nativeAdapter,
            diagnosticsManager = diagnostics
        )
        val coordinator = VersionInstallCoordinator(
            versionManager = versionManager,
            settingsRepository = settings,
            backend = object : MinecraftInstallBackend {
                override fun isNetworkAvailable() = false
                override fun availableStorageBytes() = 0L
                override fun startInstall(
                    versionId: String,
                    manifestUrl: String?,
                    manifestSha1: String?,
                    callbacks: MinecraftInstallBackend.Callbacks
                ) = object : MinecraftInstallBackend.BackendHandle { override fun cancel() {} }
            },
            scope = kotlinx.coroutines.CoroutineScope(testDispatcher)
        )

        val runtimeFacade = object : RuntimeFacade {
            override fun listInstalledRuntimes() = listOf(
                RuntimeDescriptor("jre-21", "Internal JRE 21", 21, "arm64", "21", true)
            )
            override fun resolveRuntime(runtimeId: String) = listInstalledRuntimes().firstOrNull { it.runtimeId == runtimeId }
            override fun isCompatibleWithJavaVersion(runtime: RuntimeDescriptor, requiredJavaMajor: Int) =
                runtime.majorJavaVersion >= requiredJavaMajor
        }
        val rendererFacade = object : RendererFacade {
            override fun listSupportedRenderers() = listOf(
                RendererSpec("opengles_mobileglues", "MobileGlues", requiresVulkan = false, requiresGles3 = true)
            )
            override fun isRendererCompatible(rendererId: String) = true
        }

        val controller = AetherDashboardController(
            instanceManager = instanceManager,
            versionManager = versionManager,
            downloadManager = DownloadManager(db.downloadTaskDao(), storage),
            capabilityProbe = probe,
            compatibilityEvaluator = CompatibilityEvaluator(),
            nativeEngineAdapter = nativeAdapter,
            spoolBridge = CrossProcessSpoolBridge(storage, diagnostics),
            diagnosticsManager = diagnostics,
            runtimeFacade = runtimeFacade,
            rendererFacade = rendererFacade,
            settingsRepository = settings,
            versionInstallCoordinator = coordinator,
            ioDispatcher = testDispatcher,
            mainDispatcher = testDispatcher,
            scope = backgroundScope
        )

        controller.startObserving()
        testScheduler.advanceUntilIdle()
        controller.selectVersion("1.20.1")
        testScheduler.advanceUntilIdle()
        assertEquals("1.20.1", controller.uiState.value.selectedVersionId)
        assertEquals("1.20.1", settings.getString(VersionInstallCoordinator.KEY_SELECTED_VERSION_ID, ""))

        // Uninstalled version MUST NOT launch
        var launchedUninstalled = false
        controller.playVersion("1.20.1") { _, _ -> launchedUninstalled = true }
        assertFalse(launchedUninstalled)
        assertTrue(controller.uiState.value.statusBanner!!.contains("not installed", ignoreCase = true))

        // Installed version launches cleanly
        var launchedVersion: String? = null
        controller.playVersion("1.21.4") { _, payload -> launchedVersion = payload.versionId }
        testScheduler.advanceUntilIdle()
        assertEquals("Banner was: ${controller.uiState.value.statusBanner}", "1.21.4", launchedVersion)

        // Filter & sort verification
        val catalog = controller.uiState.value.versionCatalogItems
        assertEquals(2, filterAndSortVersionCatalog(catalog, "", VersionFilter.INSTALLED, VersionSort.NEWEST, true, false).size)
        assertEquals(1, filterAndSortVersionCatalog(catalog, "fabric", VersionFilter.MODDED, VersionSort.ALPHABETICAL, true, false).size)
        assertEquals("25w04a", filterAndSortVersionCatalog(catalog, "", VersionFilter.SNAPSHOT, VersionSort.NEWEST, true, false).single().versionId)
        val alpha = filterAndSortVersionCatalog(catalog, "", VersionFilter.ALL, VersionSort.ALPHABETICAL, true, false)
        assertEquals("1.20.1", alpha.first().versionId)
    }

    @Test
    fun `verifyInstallation accepts Pojav preProcessLibraries substitutions like asm-all 5_0_4 for Minecraft 1_1`() = runTest {
        val verDir = File(storage.versionsRoot(), "1.1").apply { mkdirs() }
        File(verDir, "1.1.jar").writeBytes(byteArrayOf(1, 2, 3, 4))
        File(verDir, "1.1.json").writeText(
            """
            {
              "id": "1.1",
              "type": "release",
              "javaVersion": {"majorVersion": 8},
              "downloads": {"client": {"url": "https://example.invalid/client.jar", "size": 12}},
              "libraries": [
                {
                  "name": "org.ow2.asm:asm-all:4.1",
                  "downloads": {"artifact": {"path": "org/ow2/asm/asm-all/4.1/asm-all-4.1.jar"}}
                },
                {
                  "name": "net.java.dev.jna:jna:4.4.0",
                  "downloads": {"artifact": {"path": "net/java/dev/jna/jna/4.4.0/jna-4.4.0.jar"}}
                },
                {
                  "name": "com.github.oshi:oshi-core:6.2.1",
                  "downloads": {"artifact": {"path": "com/github/oshi/oshi-core/6.2.1/oshi-core-6.2.1.jar"}}
                }
              ]
            }
            """.trimIndent()
        )

        val libsRoot = File(storage.versionsRoot().parentFile, "libraries")
        fun writeLib(rel: String) {
            File(libsRoot, rel).apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(9, 8, 7))
            }
        }
        writeLib("org/ow2/asm/asm-all/5.0.4/asm-all-5.0.4.jar")
        writeLib("net/java/dev/jna/jna/5.13.0/jna-5.13.0.jar")
        writeLib("com/github/oshi/oshi-core/6.3.0/oshi-core-6.3.0.jar")

        val validResult = versionManager.verifyInstallation("1.1", requireAssetIndex = false)
        assertTrue(validResult.failureMessage ?: "", validResult.isValid)
        assertEquals(0, validResult.missingLibrariesCount)

        // When substituted jar is genuinely absent, verification fails naming the substituted target path
        File(libsRoot, "org/ow2/asm/asm-all/5.0.4/asm-all-5.0.4.jar").delete()
        val missingResult = versionManager.verifyInstallation("1.1", requireAssetIndex = false)
        assertFalse(missingResult.isValid)
        assertEquals(InstallErrorCategory.MISSING_LIBRARIES, missingResult.failureCategory)
        assertTrue(missingResult.diagnosticDetails.orEmpty().contains("asm-all-5.0.4.jar"))
    }

    private fun sampleEntity(
        versionId: String,
        isInstalled: Boolean,
        releaseTime: Long,
        versionType: String = "release",
        modLoaderType: ModLoaderType = ModLoaderType.VANILLA,
        loaderVer: String? = null
    ) = GameVersionEntity(
        versionId = versionId,
        inheritsFrom = null,
        versionType = versionType,
        modLoaderType = modLoaderType,
        modLoaderVersion = loaderVer,
        requiredJavaMajor = 21,
        lwjglMajor = 3,
        manifestPath = "https://example.invalid/$versionId.json",
        isInstalled = isInstalled,
        releaseTimeEpochMs = releaseTime
    )

    private fun writeValidVersionFiles(versionId: String): File {
        val verDir = File(storage.versionsRoot(), versionId).apply { mkdirs() }
        File(verDir, "$versionId.json").writeText(
            """
            {
              "id": "$versionId",
              "type": "release",
              "javaVersion": {"majorVersion": 21},
              "downloads": {"client": {"url": "https://example.invalid/client.jar", "size": 12}},
              "libraries": []
            }
            """.trimIndent()
        )
        File(verDir, "$versionId.jar").writeBytes(byteArrayOf(1, 2, 3, 4))
        return verDir
    }
}
