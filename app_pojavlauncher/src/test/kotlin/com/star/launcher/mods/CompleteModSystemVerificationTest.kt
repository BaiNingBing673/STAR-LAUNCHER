package com.star.launcher.mods

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.star.launcher.core.AetherSettingsRepository
import com.star.launcher.core.AetherStorageManager
import com.star.launcher.data.db.AetherDatabase
import com.star.launcher.data.db.entity.GameInstanceEntity
import com.star.launcher.data.db.model.ModLoaderType
import com.star.launcher.data.security.InMemoryEncryptedTokenVault
import com.star.launcher.mods.api.CurseForgeApiException
import com.star.launcher.mods.api.CurseForgeConnectionState
import com.star.launcher.mods.api.CurseForgeCredentialProvider
import com.star.launcher.mods.api.CurseForgeCredentialStatus
import com.star.launcher.mods.api.CurseForgeDownloadUrlResult
import com.star.launcher.mods.api.CurseForgeErrorCategory
import com.star.launcher.mods.api.CurseForgeHttpResponse
import com.star.launcher.mods.api.CurseForgeModLoader
import com.star.launcher.mods.api.CurseForgeRepository
import com.star.launcher.mods.api.CurseForgeSearchQuery
import com.star.launcher.mods.api.CurseForgeTransport
import com.star.launcher.mods.compat.EnvironmentDetectionSource
import com.star.launcher.mods.compat.InstanceEnvironmentResolver
import com.star.launcher.mods.compat.ModCompatibilityEngine
import com.star.launcher.mods.compat.ModCompatibilityLevel
import com.star.launcher.download.HttpTransport
import com.star.launcher.mods.deps.ModDependencyResolver
import com.star.launcher.mods.download.ModDownloadInstaller
import com.star.launcher.mods.download.ModInstallOutcome
import com.star.launcher.mods.inventory.InstalledModManager
import com.star.launcher.mods.loader.ModLoaderInstallOutcome
import com.star.launcher.mods.loader.ModLoaderInstaller
import com.star.launcher.mods.loader.ModLoaderNetworkSource
import com.star.launcher.mods.ui.ModsController
import com.star.launcher.version.VersionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompleteModSystemVerificationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: AetherDatabase
    private lateinit var storage: AetherStorageManager
    private lateinit var settings: AetherSettingsRepository
    private lateinit var vault: InMemoryEncryptedTokenVault
    private lateinit var versionManager: VersionManager
    private lateinit var envResolver: InstanceEnvironmentResolver
    private val compatibilityEngine = ModCompatibilityEngine()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AetherDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val gameDir = tempFolder.newFolder("game")
        val dataDir = tempFolder.newFolder("data")
        storage = AetherStorageManager({ gameDir }, { dataDir })
        settings = AetherSettingsRepository(db.settingsDao())
        vault = InMemoryEncryptedTokenVault()
        versionManager = VersionManager(db.gameVersionDao(), storage)
        envResolver = InstanceEnvironmentResolver(db.gameVersionDao(), storage)
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * Verification 1, 2, 3:
     * - CurseForge API connection testing & states (Configuration Required, Connected, Connection Failed)
     * - Clear error messages for Missing Config, Invalid Key, Rate Limits, Network Failures, Service Errors
     * - Mod search, mod details, file retrieval, and download URL resolution
     */
    @Test
    fun `verify CurseForge API connection states, error categories, search, details, and file retrieval`() = runTest {
        val provider = CurseForgeCredentialProvider(vault, settings)
        var simulatedMode = "ok"

        val transport = object : CurseForgeTransport {
            override suspend fun get(url: String, headers: Map<String, String>): CurseForgeHttpResponse {
                return when (simulatedMode) {
                    "401" -> CurseForgeHttpResponse(401, """{"error":"Unauthorized"}""")
                    "429" -> CurseForgeHttpResponse(429, """{"error":"Too Many Requests"}""", mapOf("retry-after" to "20"))
                    "503" -> CurseForgeHttpResponse(503, """{"message":"Backend service maintenance"}""")
                    "offline" -> throw CurseForgeApiException.NetworkOffline()
                    else -> when {
                        url.contains("/v1/categories") -> CurseForgeHttpResponse(
                            200,
                            """{"data":[{"id":423,"name":"Performance","slug":"performance"}]}"""
                        )
                        url.contains("/v1/mods/search") -> CurseForgeHttpResponse(
                            200,
                            """
                            {
                              "data": [{
                                "id": 394468,
                                "name": "Sodium",
                                "slug": "sodium",
                                "summary": "Modern rendering engine for Minecraft",
                                "downloadCount": 45000000,
                                "allowModDistribution": true,
                                "authors": [{"id": 1, "name": "JellySquid"}],
                                "categories": [{"id": 423, "name": "Performance"}],
                                "latestFiles": [{
                                  "id": 5001,
                                  "modId": 394468,
                                  "displayName": "Sodium 0.5.8 for MC 1.20.1",
                                  "fileName": "sodium-fabric-0.5.8+mc1.20.1.jar",
                                  "releaseType": 1,
                                  "isAvailable": true,
                                  "isServerPack": false,
                                  "fileLength": 2048,
                                  "downloadUrl": "https://edge.forgecdn.net/files/5001/sodium-fabric-0.5.8+mc1.20.1.jar",
                                  "gameVersions": ["1.20.1", "Fabric"],
                                  "hashes": [],
                                  "dependencies": []
                                }]
                              }],
                              "pagination": {"index": 0, "pageSize": 20, "resultCount": 1, "totalCount": 1}
                            }
                            """.trimIndent()
                        )
                        url.contains("/v1/mods/394468/description") -> CurseForgeHttpResponse(
                            200,
                            """{"data":"<p>Sodium improves frame rates and reduces micro-stutter.</p>"}"""
                        )
                        url.contains("/v1/mods/394468/files/5001/download-url") -> CurseForgeHttpResponse(
                            200,
                            """{"data":"https://edge.forgecdn.net/files/5001/sodium-fabric-0.5.8+mc1.20.1.jar"}"""
                        )
                        url.contains("/v1/mods/394468/files") -> CurseForgeHttpResponse(
                            200,
                            """
                            {
                              "data": [{
                                "id": 5001,
                                "modId": 394468,
                                "displayName": "Sodium 0.5.8 for MC 1.20.1",
                                "fileName": "sodium-fabric-0.5.8+mc1.20.1.jar",
                                "releaseType": 1,
                                "isAvailable": true,
                                "isServerPack": false,
                                "fileLength": 2048,
                                "downloadUrl": "https://edge.forgecdn.net/files/5001/sodium-fabric-0.5.8+mc1.20.1.jar",
                                "gameVersions": ["1.20.1", "Fabric"],
                                "hashes": [],
                                "dependencies": []
                              }],
                              "pagination": {"index": 0, "pageSize": 50, "resultCount": 1, "totalCount": 1}
                            }
                            """.trimIndent()
                        )
                        url.contains("/v1/mods/394468") -> CurseForgeHttpResponse(
                            200,
                            """
                            {
                              "data": {
                                "id": 394468,
                                "name": "Sodium",
                                "slug": "sodium",
                                "summary": "Modern rendering engine for Minecraft",
                                "downloadCount": 45000000,
                                "allowModDistribution": true,
                                "authors": [{"id": 1, "name": "JellySquid"}],
                                "categories": [{"id": 423, "name": "Performance"}],
                                "latestFiles": []
                              }
                            }
                            """.trimIndent()
                        )
                        else -> CurseForgeHttpResponse(404, "{}")
                    }
                }
            }
        }

        val repo = CurseForgeRepository(provider, transport)

        // 1. Unconfigured -> Configuration Required
        val unconfTest = repo.testConnection()
        assertEquals(CurseForgeConnectionState.CONFIGURATION_REQUIRED, unconfTest.state)
        assertEquals(CurseForgeErrorCategory.MISSING_CONFIGURATION, unconfTest.errorCategory)
        assertEquals(CurseForgeCredentialStatus.UNCONFIGURED, unconfTest.credentialStatus)

        // 2. Configure valid developer key -> Connected
        provider.saveDeveloperApiKey("valid-cf-api-key-987654321")
        simulatedMode = "ok"
        val connectedTest = repo.testConnection()
        assertEquals(CurseForgeConnectionState.CONNECTED, connectedTest.state)
        assertEquals(CurseForgeErrorCategory.NONE, connectedTest.errorCategory)

        // 3. Invalid API key (401) -> Connection Failed + INVALID_API_KEY
        simulatedMode = "401"
        val invalidKeyTest = repo.testConnection()
        assertEquals(CurseForgeConnectionState.CONNECTION_FAILED, invalidKeyTest.state)
        assertEquals(CurseForgeErrorCategory.INVALID_API_KEY, invalidKeyTest.errorCategory)
        assertTrue(invalidKeyTest.message.contains("Invalid API key", ignoreCase = true))

        // 4. Rate Limited (429) -> Connection Failed + RATE_LIMITED
        simulatedMode = "429"
        val rateLimitTest = repo.testConnection()
        assertEquals(CurseForgeConnectionState.CONNECTION_FAILED, rateLimitTest.state)
        assertEquals(CurseForgeErrorCategory.RATE_LIMITED, rateLimitTest.errorCategory)
        assertTrue(rateLimitTest.message.contains("429"))

        // 5. Network Failure -> Connection Failed + NETWORK_FAILURE
        simulatedMode = "offline"
        val offlineTest = repo.testConnection()
        assertEquals(CurseForgeConnectionState.CONNECTION_FAILED, offlineTest.state)
        assertEquals(CurseForgeErrorCategory.NETWORK_FAILURE, offlineTest.errorCategory)

        // 6. Service Error (503) -> Connection Failed + SERVICE_ERROR
        simulatedMode = "503"
        val serviceErrTest = repo.testConnection()
        assertEquals(CurseForgeConnectionState.CONNECTION_FAILED, serviceErrTest.state)
        assertEquals(CurseForgeErrorCategory.SERVICE_ERROR, serviceErrTest.errorCategory)

        // 7. Restore healthy connection and test Search, Details, Files, and Download URL
        simulatedMode = "ok"
        val searchRes = repo.searchMods(
            CurseForgeSearchQuery(
                searchFilter = "Sodium",
                gameVersion = "1.20.1",
                modLoader = CurseForgeModLoader.FABRIC
            )
        ).getOrThrow()
        assertEquals(1, searchRes.mods.size)
        assertEquals("Sodium", searchRes.mods.first().name)
        assertEquals(CurseForgeConnectionState.CONNECTED, repo.lastConnectionResult?.state)

        val modDetail = repo.getMod(394468L).getOrThrow()
        assertEquals("Sodium", modDetail.name)
        val desc = repo.getModDescription(394468L).getOrThrow()
        assertTrue(desc.contains("Sodium improves frame rates"))

        val filesRes = repo.getModFiles(394468L, gameVersion = "1.20.1", modLoader = CurseForgeModLoader.FABRIC).getOrThrow()
        assertEquals(1, filesRes.files.size)
        val file = filesRes.files.first()
        val dlUrl = repo.resolveDownloadUrl(modDetail, file).getOrThrow()
        assertTrue(dlUrl is CurseForgeDownloadUrlResult.Available)
    }

    /**
     * Verification 4, 5, 6, 7, 8, 9, 10:
     * - Vanilla instance detection
     * - Fabric, Forge, NeoForge, and Quilt installation and detection (including custom profile IDs & parent manifest chains)
     * - Preserving user worlds, resource packs, saves, and configurations across all 4 loader installations
     * - Mod installation into compatible instance after loader installation
     */
    @Test
    fun `verify Vanilla detection, Fabric Forge NeoForge Quilt installation and detection, world preservation, and mod install`() = runTest {
        val instanceDir = tempFolder.newFolder("instance_survival")
        // Create user worlds, saves, resourcepacks, and config files that MUST be preserved
        val levelDat = File(instanceDir, "saves/MySurvivalWorld/level.dat").apply {
            parentFile?.mkdirs()
            writeText("WORLD_SAVE_DATA_IMPORTANT_12345")
        }
        val resourcePack = File(instanceDir, "resourcepacks/Faithful64x.zip").apply {
            parentFile?.mkdirs()
            writeText("RESOURCE_PACK_CONTENT_67890")
        }
        val modConfig = File(instanceDir, "config/sodium-mixins.properties").apply {
            parentFile?.mkdirs()
            writeText("mixin.features.chunk_rendering=true")
        }
        val optionsTxt = File(instanceDir, "options.txt").apply {
            writeText("fov:0.75\nrenderDistance:12")
        }

        // Write Vanilla 1.20.1 manifest on disk and register in catalog
        val vanillaDir = File(storage.versionsRoot(), "1.20.1").apply { mkdirs() }
        File(vanillaDir, "1.20.1.jar").writeText("MINECRAFT_1_20_1_CLIENT_JAR")
        File(vanillaDir, "1.20.1.json").writeText(
            """
            {
              "id": "1.20.1",
              "type": "release",
              "mainClass": "net.minecraft.client.main.Main",
              "javaVersion": {"majorVersion": 17},
              "libraries": [
                {"name": "com.mojang:logging:1.1.1"}
              ]
            }
            """.trimIndent()
        )
        versionManager.scanInstalledVersions()

        val initialInstance = GameInstanceEntity(
            instanceId = "inst-survival",
            name = "Survival 1.20.1",
            gameDirPath = instanceDir.absolutePath,
            versionId = "1.20.1",
            runtimeId = null,
            rendererProfileId = null,
            allocatedRamMb = 4096,
            customJvmArgs = null,
            controlMapPath = null,
            lastPlayedAt = null,
            createdAt = 1000L
        )
        db.gameInstanceDao().upsert(initialInstance)

        // Verification 4: Vanilla instance detection
        val vanillaEnv = envResolver.resolve(initialInstance)
        assertEquals(ModLoaderType.VANILLA, vanillaEnv.loaderType)
        assertEquals("1.20.1", vanillaEnv.baseMinecraftVersion)
        assertEquals(EnvironmentDetectionSource.MANIFEST_VERIFIED, vanillaEnv.detectionSource)
        assertFalse(vanillaEnv.isModdedLoader)

        // Build mock installer JARs for Forge (1.20.1-47.3.0) and NeoForge (20.1.80 for 1.20.1)
        val forgeInstallerBytes = createMockInstallerJar(
            versionJson = """
            {
              "id": "1.20.1-forge-47.3.0",
              "inheritsFrom": "1.20.1",
              "type": "release",
              "mainClass": "cpw.mods.bootstraplauncher.BootstrapLauncher",
              "arguments": {
                "game": [
                  "--launchTarget", "forgeclient",
                  "--fml.forgeVersion", "47.3.0",
                  "--fml.mcVersion", "1.20.1",
                  "--fml.forgeGroup", "net.minecraftforge"
                ]
              },
              "libraries": [
                {
                  "name": "net.minecraftforge:fmlloader:1.20.1-47.3.0",
                  "downloads": {
                    "artifact": {
                      "path": "net/minecraftforge/fmlloader/1.20.1-47.3.0/fmlloader-1.20.1-47.3.0.jar",
                      "url": ""
                    }
                  }
                }
              ]
            }
            """.trimIndent(),
            embeddedMavenFiles = mapOf(
                "maven/net/minecraftforge/fmlloader/1.20.1-47.3.0/fmlloader-1.20.1-47.3.0.jar" to "FORGE_FMLLOADER_BYTES".toByteArray()
            )
        )

        val neoForgeInstallerBytes = createMockInstallerJar(
            versionJson = """
            {
              "id": "neoforge-20.1.80",
              "inheritsFrom": "1.20.1",
              "type": "release",
              "mainClass": "cpw.mods.bootstraplauncher.BootstrapLauncher",
              "arguments": {
                "game": [
                  "--launchTarget", "forgeclient",
                  "--fml.neoForgeVersion", "20.1.80",
                  "--fml.fmlVersion", "2.0.17",
                  "--fml.mcVersion", "1.20.1",
                  "--fml.forgeGroup", "net.neoforged"
                ]
              },
              "libraries": [
                {
                  "name": "net.neoforged.fancymodloader:loader:2.0.17",
                  "downloads": {
                    "artifact": {
                      "path": "net/neoforged/fancymodloader/loader/2.0.17/loader-2.0.17.jar",
                      "url": "https://maven.neoforged.net/releases/net/neoforged/fancymodloader/loader/2.0.17/loader-2.0.17.jar"
                    }
                  }
                },
                {
                  "name": "net.neoforged:neoforge:20.1.80",
                  "downloads": {
                    "artifact": {
                      "path": "net/neoforged/neoforge/20.1.80/neoforge-20.1.80.jar",
                      "url": ""
                    }
                  }
                }
              ]
            }
            """.trimIndent(),
            embeddedMavenFiles = mapOf(
                "maven/net/neoforged/neoforge/20.1.80/neoforge-20.1.80.jar" to "NEOFORGE_EMBEDDED_BYTES".toByteArray()
            )
        )

        val networkSource = object : ModLoaderNetworkSource {
            override suspend fun fetchText(url: String): String = when {
                url.contains("meta.fabricmc.net/v2/versions/loader/1.20.1/0.16.9/profile/json") -> """
                    {
                      "id": "fabric-loader-0.16.9-1.20.1",
                      "inheritsFrom": "1.20.1",
                      "type": "release",
                      "mainClass": "net.fabricmc.loader.impl.launch.knot.KnotClient",
                      "libraries": [
                        {"name": "net.fabricmc:fabric-loader:0.16.9", "url": "https://maven.fabricmc.net/"},
                        {"name": "net.fabricmc:intermediary:1.20.1", "url": "https://maven.fabricmc.net/"}
                      ]
                    }
                """.trimIndent()
                url.contains("meta.fabricmc.net/v2/versions/loader/1.20.1") -> """
                    [{"loader": {"version": "0.16.9", "stable": true}}]
                """.trimIndent()
                url.contains("meta.quiltmc.org/v3/versions/loader/1.20.1/0.27.0/profile/json") -> """
                    {
                      "id": "quilt-loader-0.27.0-1.20.1",
                      "inheritsFrom": "1.20.1",
                      "type": "release",
                      "mainClass": "org.quiltmc.loader.impl.launch.knot.KnotClient",
                      "libraries": [
                        {"name": "org.quiltmc:quilt-loader:0.27.0", "url": "https://maven.quiltmc.org/repository/release/"},
                        {"name": "net.fabricmc:intermediary:1.20.1", "url": "https://maven.fabricmc.net/"}
                      ]
                    }
                """.trimIndent()
                url.contains("meta.quiltmc.org/v3/versions/loader/1.20.1") -> """
                    [{"loader": {"version": "0.27.0"}}]
                """.trimIndent()
                url.contains("maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml") -> """
                    <metadata><versioning><versions>
                      <version>1.19.4-45.2.0</version>
                      <version>1.20.1-47.2.0</version>
                      <version>1.20.1-47.3.0</version>
                    </versions></versioning></metadata>
                """.trimIndent()
                url.contains("maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml") -> """
                    <metadata><versioning><versions>
                      <version>20.1.80</version>
                      <version>20.4.237</version>
                      <version>21.1.72</version>
                    </versions></versioning></metadata>
                """.trimIndent()
                else -> throw IllegalArgumentException("Unexpected URL: $url")
            }

            override suspend fun downloadToFile(
                url: String,
                targetFile: File,
                onBytesProgress: suspend (downloaded: Long, total: Long) -> Unit
            ) {
                targetFile.parentFile?.mkdirs()
                val bytes = when {
                    url.endsWith("forge-1.20.1-47.3.0-installer.jar") -> forgeInstallerBytes
                    url.endsWith("neoforge-20.1.80-installer.jar") -> neoForgeInstallerBytes
                    else -> "LIBRARY_JAR_BINARY_FOR_$url".toByteArray()
                }
                targetFile.writeBytes(bytes)
                onBytesProgress(bytes.size.toLong(), bytes.size.toLong())
            }
        }

        val loaderInstaller = ModLoaderInstaller(
            storageManager = storage,
            gameVersionDao = db.gameVersionDao(),
            gameInstanceDao = db.gameInstanceDao(),
            versionManager = versionManager,
            environmentResolver = envResolver,
            networkSource = networkSource
        )

        // Verification 6: Forge installation & detection
        val forgeCandidates = loaderInstaller.fetchCompatibleVersions("1.20.1", ModLoaderType.FORGE).getOrThrow()
        assertEquals(listOf("47.3.0", "47.2.0"), forgeCandidates.map { it.loaderVersion })
        val forgeOutcome = loaderInstaller.installModLoader(
            db.gameInstanceDao().getById("inst-survival")!!,
            forgeCandidates.first()
        )
        assertTrue(forgeOutcome is ModLoaderInstallOutcome.Success)
        val forgeSuccess = forgeOutcome as ModLoaderInstallOutcome.Success
        assertEquals(ModLoaderType.FORGE, forgeSuccess.environment.loaderType)
        assertEquals("47.3.0", forgeSuccess.environment.loaderVersion)
        assertEquals("1.20.1", forgeSuccess.environment.baseMinecraftVersion)
        assertTrue(
            File(storage.versionsRoot().parentFile, "libraries/net/minecraftforge/fmlloader/1.20.1-47.3.0/fmlloader-1.20.1-47.3.0.jar").isFile
        )

        // Verification 7: NeoForge installation & detection
        val neoForgeCandidates = loaderInstaller.fetchCompatibleVersions("1.20.1", ModLoaderType.NEOFORGE).getOrThrow()
        assertEquals(listOf("20.1.80"), neoForgeCandidates.map { it.loaderVersion })
        val neoOutcome = loaderInstaller.installModLoader(
            db.gameInstanceDao().getById("inst-survival")!!,
            neoForgeCandidates.first()
        )
        assertTrue(neoOutcome is ModLoaderInstallOutcome.Success)
        val neoSuccess = neoOutcome as ModLoaderInstallOutcome.Success
        assertEquals(ModLoaderType.NEOFORGE, neoSuccess.environment.loaderType)
        assertEquals("20.1.80", neoSuccess.environment.loaderVersion)
        assertEquals("1.20.1", neoSuccess.environment.baseMinecraftVersion)
        assertTrue(
            File(storage.versionsRoot().parentFile, "libraries/net/neoforged/neoforge/20.1.80/neoforge-20.1.80.jar").isFile
        )

        // Verification 8: Quilt installation & detection
        val quiltCandidates = loaderInstaller.fetchCompatibleVersions("1.20.1", ModLoaderType.QUILT).getOrThrow()
        assertEquals(listOf("0.27.0"), quiltCandidates.map { it.loaderVersion })
        val quiltOutcome = loaderInstaller.installModLoader(
            db.gameInstanceDao().getById("inst-survival")!!,
            quiltCandidates.first()
        )
        assertTrue(quiltOutcome is ModLoaderInstallOutcome.Success)
        val quiltSuccess = quiltOutcome as ModLoaderInstallOutcome.Success
        assertEquals(ModLoaderType.QUILT, quiltSuccess.environment.loaderType)
        assertEquals("0.27.0", quiltSuccess.environment.loaderVersion)
        assertEquals("1.20.1", quiltSuccess.environment.baseMinecraftVersion)

        // Verification 5: Fabric installation & detection
        val fabricCandidates = loaderInstaller.fetchCompatibleVersions("1.20.1", ModLoaderType.FABRIC).getOrThrow()
        assertEquals(listOf("0.16.9"), fabricCandidates.map { it.loaderVersion })
        val fabricOutcome = loaderInstaller.installModLoader(
            db.gameInstanceDao().getById("inst-survival")!!,
            fabricCandidates.first()
        )
        assertTrue(fabricOutcome is ModLoaderInstallOutcome.Success)
        val fabricSuccess = fabricOutcome as ModLoaderInstallOutcome.Success
        assertEquals(ModLoaderType.FABRIC, fabricSuccess.environment.loaderType)
        assertEquals("0.16.9", fabricSuccess.environment.loaderVersion)
        assertEquals("1.20.1", fabricSuccess.environment.baseMinecraftVersion)

        // Verification 10: Existing worlds, resource packs, and configurations remain intact!
        assertEquals("WORLD_SAVE_DATA_IMPORTANT_12345", levelDat.readText())
        assertEquals("RESOURCE_PACK_CONTENT_67890", resourcePack.readText())
        assertEquals("mixin.features.chunk_rendering=true", modConfig.readText())
        assertEquals("fov:0.75\nrenderDistance:12", optionsTxt.readText())

        // Verification 9: Mod installation into the now-compatible Fabric 1.20.1 instance
        val provider = CurseForgeCredentialProvider(vault, settings).apply {
            saveDeveloperApiKey("valid-cf-api-key-987654321")
        }
        val repo = CurseForgeRepository(provider, object : CurseForgeTransport {
            override suspend fun get(url: String, headers: Map<String, String>): CurseForgeHttpResponse =
                CurseForgeHttpResponse(200, """{"data":"https://edge.forgecdn.net/files/5001/sodium-fabric-0.5.8+mc1.20.1.jar"}""")
        })
        val depResolver = ModDependencyResolver(repo, compatibilityEngine)
        val modBytes = createValidFabricModJar("sodium", "0.5.8")
        val downloadInstaller = ModDownloadInstaller(
            repository = repo,
            downloadTaskDao = db.downloadTaskDao(),
            installedModDao = db.installedModDao(),
            storageManager = storage,
            httpTransport = object : HttpTransport {
                override suspend fun downloadToFile(
                    url: String,
                    destinationFile: File,
                    onProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit
                ) {
                    destinationFile.parentFile?.mkdirs()
                    destinationFile.writeBytes(modBytes)
                    onProgress(modBytes.size.toLong(), modBytes.size.toLong())
                }
            }
        )

        val sodiumMod = com.star.launcher.mods.api.CurseForgeMod(
            id = 394468L,
            name = "Sodium",
            slug = "sodium",
            summary = "Modern rendering engine",
            downloadCount = 1000L,
            authors = emptyList(),
            categories = emptyList(),
            logo = null,
            screenshots = emptyList(),
            links = com.star.launcher.mods.api.CurseForgeLinks(null, null, null, null),
            allowModDistribution = true,
            dateModifiedIso = null,
            latestFiles = emptyList(),
            supportedMinecraftVersionsFromLatest = listOf("1.20.1"),
            supportedLoadersFromLatest = setOf(CurseForgeModLoader.FABRIC)
        )
        val sodiumFile = com.star.launcher.mods.api.CurseForgeFile(
            id = 5001L,
            modId = 394468L,
            displayName = "Sodium 0.5.8",
            fileName = "sodium-fabric-0.5.8+mc1.20.1.jar",
            releaseType = com.star.launcher.mods.api.CurseForgeReleaseType.RELEASE,
            isAvailable = true,
            isServerPack = false,
            fileDateIso = null,
            fileLengthBytes = modBytes.size.toLong(),
            downloadUrl = "https://edge.forgecdn.net/files/5001/sodium-fabric-0.5.8+mc1.20.1.jar",
            hashes = emptyList(),
            gameVersions = listOf("1.20.1", "Fabric"),
            dependencies = emptyList()
        )

        // Verify Vanilla instance is blocked with requiresModLoaderInstall = true
        val vanillaAssessment = compatibilityEngine.evaluateFile(vanillaEnv, sodiumFile, sodiumMod)
        assertEquals(ModCompatibilityLevel.INCOMPATIBLE, vanillaAssessment.level)
        assertFalse(vanillaAssessment.canInstall)
        assertTrue(vanillaAssessment.requiresModLoaderInstall)
        assertEquals(ModLoaderType.FABRIC, vanillaAssessment.suggestedModLoader)

        // Verify Fabric instance is COMPATIBLE and installs cleanly
        val plan = depResolver.buildInstallPlan(
            rootMod = sodiumMod,
            rootFile = sodiumFile,
            environment = fabricSuccess.environment,
            installedCurseForgeModIds = emptySet(),
            installedFileNames = emptySet()
        )
        assertTrue(plan.canProceed)
        val installOutcome = downloadInstaller.installPlan(plan, fabricSuccess.environment, allowOverwrite = false)
        assertTrue(installOutcome is ModInstallOutcome.Success)
        assertTrue(File(instanceDir, "mods/sodium-fabric-0.5.8+mc1.20.1.jar").isFile)

        // Worlds & configs still 100% intact after mod installation
        assertEquals("WORLD_SAVE_DATA_IMPORTANT_12345", levelDat.readText())
        assertEquals("RESOURCE_PACK_CONTENT_67890", resourcePack.readText())
        assertEquals("mixin.features.chunk_rendering=true", modConfig.readText())
        assertEquals("fov:0.75\nrenderDistance:12", optionsTxt.readText())
    }

    /**
     * Verifies ModsController end-to-end UI state transitions:
     * - Replaces API Unconfigured with Configuration Required / Connected / Connection Failed
     * - Opens Install Mod Loader dialog from a Vanilla instance, loads compatible loader candidates,
     *   installs Fabric, and automatically re-detects the instance environment as FABRIC.
     */
    @Test
    fun `verify ModsController UI state transitions and Install Mod Loader flow`() = runBlocking {
        val instanceDir = tempFolder.newFolder("instance_ui_test")
        val vanillaDir = File(storage.versionsRoot(), "1.20.4").apply { mkdirs() }
        File(vanillaDir, "1.20.4.jar").writeText("MINECRAFT_1_20_4_CLIENT_JAR")
        File(vanillaDir, "1.20.4.json").writeText(
            """
            {
              "id": "1.20.4",
              "type": "release",
              "mainClass": "net.minecraft.client.main.Main",
              "libraries": [{"name": "com.mojang:logging:1.1.1"}]
            }
            """.trimIndent()
        )
        versionManager.scanInstalledVersions()
        val instance = GameInstanceEntity(
            instanceId = "inst-ui-1204",
            name = "UI Vanilla 1.20.4",
            gameDirPath = instanceDir.absolutePath,
            versionId = "1.20.4",
            runtimeId = null,
            rendererProfileId = null,
            allocatedRamMb = 4096,
            customJvmArgs = null,
            controlMapPath = null,
            lastPlayedAt = null,
            createdAt = 2000L
        )
        db.gameInstanceDao().upsert(instance)

        val provider = CurseForgeCredentialProvider(vault, settings)
        val repo = CurseForgeRepository(provider, object : CurseForgeTransport {
            override suspend fun get(url: String, headers: Map<String, String>): CurseForgeHttpResponse {
                return when {
                    url.contains("/v1/categories") -> CurseForgeHttpResponse(200, """{"data":[{"id":423,"name":"Performance","slug":"performance"}]}""")
                    url.contains("/v1/mods/search") -> CurseForgeHttpResponse(200, """{"data":[],"pagination":{"index":0,"pageSize":20,"resultCount":0,"totalCount":0}}""")
                    else -> CurseForgeHttpResponse(200, """{"data":[]}""")
                }
            }
        })
        val loaderInstaller = ModLoaderInstaller(
            storageManager = storage,
            gameVersionDao = db.gameVersionDao(),
            gameInstanceDao = db.gameInstanceDao(),
            versionManager = versionManager,
            environmentResolver = envResolver,
            networkSource = object : ModLoaderNetworkSource {
                override suspend fun fetchText(url: String): String = when {
                    url.contains("meta.fabricmc.net/v2/versions/loader/1.20.4/0.16.9/profile/json") -> """
                        {
                          "id": "fabric-loader-0.16.9-1.20.4",
                          "inheritsFrom": "1.20.4",
                          "type": "release",
                          "mainClass": "net.fabricmc.loader.impl.launch.knot.KnotClient",
                          "libraries": [
                            {"name": "net.fabricmc:fabric-loader:0.16.9", "url": "https://maven.fabricmc.net/"},
                            {"name": "net.fabricmc:intermediary:1.20.4", "url": "https://maven.fabricmc.net/"}
                          ]
                        }
                    """.trimIndent()
                    url.contains("meta.fabricmc.net/v2/versions/loader/1.20.4") -> """
                        [{"loader": {"version": "0.16.9", "stable": true}}]
                    """.trimIndent()
                    else -> "[]"
                }

                override suspend fun downloadToFile(
                    url: String,
                    targetFile: File,
                    onBytesProgress: suspend (downloaded: Long, total: Long) -> Unit
                ) {
                    targetFile.parentFile?.mkdirs()
                    val bytes = "FABRIC_LIB".toByteArray()
                    targetFile.writeBytes(bytes)
                    onBytesProgress(bytes.size.toLong(), bytes.size.toLong())
                }
            },
            ioDispatcher = Dispatchers.Unconfined
        )

        val controller = ModsController(
            credentialProvider = provider,
            repository = repo,
            environmentResolver = envResolver,
            compatibilityEngine = compatibilityEngine,
            dependencyResolver = ModDependencyResolver(repo, compatibilityEngine),
            downloadInstaller = ModDownloadInstaller(repo, db.downloadTaskDao(), db.installedModDao(), storage),
            installedModManager = InstalledModManager(db.installedModDao()),
            ioDispatcher = Dispatchers.Unconfined,
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Unconfined),
            modLoaderInstaller = loaderInstaller
        )

        try {
            controller.startObserving()
            controller.syncSelectedInstance(instance, listOf(instance))
            val initialEnvState = withTimeout(3_000) {
                controller.uiState.first { it.instanceEnvironment != null }
            }
            assertEquals(CurseForgeConnectionState.CONFIGURATION_REQUIRED, initialEnvState.connectionState)
            assertEquals(ModLoaderType.VANILLA, initialEnvState.instanceEnvironment?.loaderType)

            controller.saveDeveloperKey("valid-key-abc123456")
            val connectedState = withTimeout(3_000) {
                controller.uiState.first { it.connectionState == CurseForgeConnectionState.CONNECTED }
            }
            assertEquals(CurseForgeConnectionState.CONNECTED, connectedState.connectionState)

            controller.openInstallLoaderDialog(ModLoaderType.FABRIC)
            val candidatesLoaded = withTimeout(3_000) {
                controller.uiState.first {
                    it.showInstallLoaderDialog && it.installLoaderSelectedCandidate != null
                }
            }
            assertEquals(1, candidatesLoaded.installLoaderCandidates.size)
            assertNotNull(candidatesLoaded.installLoaderSelectedCandidate)

            controller.startModLoaderInstall()
            val installedState = withTimeout(3_000) {
                controller.uiState.first {
                    !it.isInstallingModLoader && it.instanceEnvironment?.loaderType == ModLoaderType.FABRIC
                }
            }
            assertEquals(ModLoaderType.FABRIC, installedState.instanceEnvironment?.loaderType)
            assertEquals("0.16.9", installedState.instanceEnvironment?.loaderVersion)
            assertEquals("1.20.4", installedState.instanceEnvironment?.baseMinecraftVersion)
        } finally {
            controller.close()
        }
    }

    @Test
    fun `verify processor-based Forge and NeoForge installers require GUI installer and check base client`() = runTest {
        val instanceDir = tempFolder.newFolder("instance_forge_processors")
        val vanillaDir = File(storage.versionsRoot(), "1.20.1").apply { mkdirs() }
        val manifestFile = File(vanillaDir, "1.20.1.json").apply {
            writeText("""{"id":"1.20.1","type":"release","libraries":[]}""")
        }
        // Register 1.20.1 in Room without 1.20.1.jar to verify ModLoaderInstaller rejects missing base client JAR
        db.gameVersionDao().upsert(
            com.star.launcher.data.db.entity.GameVersionEntity(
                versionId = "1.20.1",
                inheritsFrom = null,
                versionType = "release",
                modLoaderType = ModLoaderType.VANILLA,
                modLoaderVersion = null,
                requiredJavaMajor = 17,
                lwjglMajor = 3,
                manifestPath = manifestFile.absolutePath,
                isInstalled = false,
                releaseTimeEpochMs = 1000L
            )
        )

        val instance = GameInstanceEntity(
            instanceId = "inst-proc-1201",
            name = "Forge Processors Test",
            gameDirPath = instanceDir.absolutePath,
            versionId = "1.20.1",
            runtimeId = null,
            rendererProfileId = null,
            allocatedRamMb = 4096,
            customJvmArgs = null,
            controlMapPath = null,
            lastPlayedAt = null,
            createdAt = 3000L
        )
        db.gameInstanceDao().upsert(instance)

        val tempJar = tempFolder.newFile("forge-proc-${System.nanoTime()}.jar")
        ZipOutputStream(FileOutputStream(tempJar)).use { zos ->
            zos.putNextEntry(ZipEntry("version.json"))
            zos.write("""{"id":"1.20.1-forge-47.3.0","inheritsFrom":"1.20.1","libraries":[]}""".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("install_profile.json"))
            zos.write("""{"version":"1.20.1-forge-47.3.0","processors":[{"sides":["client"],"jar":"net.minecraftforge:binarypatcher:1.0"}]}""".toByteArray())
            zos.closeEntry()
        }
        val procInstallerBytes = tempJar.readBytes()

        val installer = ModLoaderInstaller(
            storageManager = storage,
            gameVersionDao = db.gameVersionDao(),
            gameInstanceDao = db.gameInstanceDao(),
            versionManager = versionManager,
            environmentResolver = envResolver,
            networkSource = object : ModLoaderNetworkSource {
                override suspend fun fetchText(url: String): String = ""
                override suspend fun downloadToFile(
                    url: String,
                    targetFile: File,
                    onBytesProgress: suspend (downloaded: Long, total: Long) -> Unit
                ) {
                    targetFile.parentFile?.mkdirs()
                    targetFile.writeBytes(procInstallerBytes)
                    onBytesProgress(procInstallerBytes.size.toLong(), procInstallerBytes.size.toLong())
                }
            },
            ioDispatcher = Dispatchers.Unconfined
        )

        val candidate = com.star.launcher.mods.loader.ModLoaderCandidate(
            loaderType = ModLoaderType.FORGE,
            loaderVersion = "47.3.0",
            minecraftVersion = "1.20.1",
            isRecommended = true,
            isStable = true,
            installerUrl = "https://maven.minecraftforge.net/net/minecraftforge/forge/1.20.1-47.3.0/forge-1.20.1-47.3.0-installer.jar"
        )

        val missingJarOutcome = installer.installModLoader(instance, candidate)
        assertTrue(missingJarOutcome is ModLoaderInstallOutcome.Failure)

        File(vanillaDir, "1.20.1.jar").writeText("MINECRAFT_1_20_1_CLIENT_JAR")
        val guiOutcome = installer.installModLoader(instance, candidate)
        assertTrue(guiOutcome is ModLoaderInstallOutcome.RequiresGuiInstaller)
        val req = guiOutcome as ModLoaderInstallOutcome.RequiresGuiInstaller
        assertEquals("1.20.1-forge-47.3.0", req.expectedVersionId)
        assertTrue(File(req.installerJarPath).isFile)
    }

    private fun createMockInstallerJar(
        versionJson: String,
        embeddedMavenFiles: Map<String, ByteArray>
    ): ByteArray {
        val tempJar = tempFolder.newFile("installer-${System.nanoTime()}.jar")
        ZipOutputStream(FileOutputStream(tempJar)).use { zos ->
            zos.putNextEntry(ZipEntry("version.json"))
            zos.write(versionJson.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            for ((entryPath, bytes) in embeddedMavenFiles) {
                zos.putNextEntry(ZipEntry(entryPath))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return tempJar.readBytes()
    }

    private fun createValidFabricModJar(modId: String, version: String): ByteArray {
        val tempJar = tempFolder.newFile("mod-$modId-${System.nanoTime()}.jar")
        ZipOutputStream(FileOutputStream(tempJar)).use { zos ->
            zos.putNextEntry(ZipEntry("fabric.mod.json"))
            zos.write("""{"schemaVersion":1,"id":"$modId","version":"$version","name":"$modId"}""".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return tempJar.readBytes()
    }
}
