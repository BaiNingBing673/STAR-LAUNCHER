package com.star.launcher.mods.compat

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.star.launcher.core.AetherSettingsRepository
import com.star.launcher.core.AetherStorageManager
import com.star.launcher.data.db.AetherDatabase
import com.star.launcher.data.db.entity.GameInstanceEntity
import com.star.launcher.data.db.entity.GameVersionEntity
import com.star.launcher.data.db.model.ModLoaderType
import com.star.launcher.data.security.InMemoryEncryptedTokenVault
import com.star.launcher.mods.api.CurseForgeCredentialProvider
import com.star.launcher.mods.api.CurseForgeFile
import com.star.launcher.mods.api.CurseForgeFileDependency
import com.star.launcher.mods.api.CurseForgeHttpResponse
import com.star.launcher.mods.api.CurseForgeModLoader
import com.star.launcher.mods.api.CurseForgeRelationType
import com.star.launcher.mods.api.CurseForgeReleaseType
import com.star.launcher.mods.api.CurseForgeRepository
import com.star.launcher.mods.api.CurseForgeTransport
import com.star.launcher.mods.deps.ModDependencyResolver
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
class ModCompatibilityAndDepsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: AetherDatabase
    private lateinit var storage: AetherStorageManager
    private lateinit var envResolver: InstanceEnvironmentResolver
    private val engine = ModCompatibilityEngine()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AetherDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val gameDir = tempFolder.newFolder("game")
        val dataDir = tempFolder.newFolder("data")
        storage = AetherStorageManager({ gameDir }, { dataDir })
        envResolver = InstanceEnvironmentResolver(db.gameVersionDao(), storage)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `environment resolver prefers manifest over versionId string inference`() = runTest {
        val versionDir = File(storage.versionsRoot(), "custom-pack-1").apply { mkdirs() }
        File(versionDir, "custom-pack-1.json").writeText(
            """
            {
              "id": "custom-pack-1",
              "inheritsFrom": "1.20.1",
              "mainClass": "net.fabricmc.loader.impl.launch.knot.KnotClient",
              "libraries": [
                {"name": "net.fabricmc:fabric-loader:0.16.5"}
              ]
            }
            """.trimIndent()
        )
        val instDir = tempFolder.newFolder("inst_fabric")
        val entity = GameInstanceEntity(
            instanceId = "inst-1",
            name = "My Pack",
            gameDirPath = instDir.absolutePath,
            versionId = "custom-pack-1",
            runtimeId = null,
            rendererProfileId = null,
            allocatedRamMb = 2048,
            customJvmArgs = null,
            controlMapPath = null,
            lastPlayedAt = null,
            createdAt = 100L
        )
        val env = envResolver.resolve(entity)
        assertEquals(EnvironmentDetectionSource.MANIFEST_VERIFIED, env.detectionSource)
        assertEquals("1.20.1", env.baseMinecraftVersion)
        assertEquals(ModLoaderType.FABRIC, env.loaderType)
    }

    @Test
    fun `compatibility engine distinguishes compatible, potentially compatible, incompatible, and never trusts MC version alone`() = runTest {
        val fabricEnv = InstanceEnvironment(
            instanceId = "inst-fabric",
            instanceName = "Fabric 1.20.1",
            gameDirPath = "/tmp/inst-fabric",
            rawVersionId = "fabric-loader-0.15-1.20.1",
            baseMinecraftVersion = "1.20.1",
            loaderType = ModLoaderType.FABRIC,
            loaderVersion = "0.15.0",
            detectionSource = EnvironmentDetectionSource.MANIFEST_VERIFIED
        )

        // Exact Fabric + MC 1.20.1 match with manifest -> Compatible
        val goodFile = sampleFile(id = 1, mc = listOf("1.20.1", "Fabric"))
        val goodAssessment = engine.evaluateFile(fabricEnv, goodFile)
        assertEquals(ModCompatibilityLevel.COMPATIBLE, goodAssessment.level)
        assertTrue(goodAssessment.canInstall)
        assertTrue(goodAssessment.apiFacts.any { it.contains("1.20.1") })
        assertTrue(goodAssessment.starInferences.isNotEmpty())

        // MC 1.20.1 matches, but API omits loader tag -> Potentially Compatible (NOT full Compatible!)
        val noLoaderTagFile = sampleFile(id = 2, mc = listOf("1.20.1"))
        val partialAssessment = engine.evaluateFile(fabricEnv, noLoaderTagFile)
        assertEquals(ModCompatibilityLevel.POTENTIALLY_COMPATIBLE, partialAssessment.level)

        // Forge mod on Fabric instance -> Incompatible & blocked
        val forgeFile = sampleFile(id = 3, mc = listOf("1.20.1", "Forge"))
        val wrongLoader = engine.evaluateFile(fabricEnv, forgeFile)
        assertEquals(ModCompatibilityLevel.INCOMPATIBLE, wrongLoader.level)
        assertFalse(wrongLoader.canInstall)

        // Vanilla instance -> Incompatible & blocked
        val vanillaEnv = fabricEnv.copy(loaderType = ModLoaderType.VANILLA, loaderVersion = null)
        val vanillaBlocked = engine.evaluateFile(vanillaEnv, goodFile)
        assertEquals(ModCompatibilityLevel.INCOMPATIBLE, vanillaBlocked.level)
        assertFalse(vanillaBlocked.canInstall)

        // Null environment -> Unknown & blocked
        val unknownAssessment = engine.evaluateFile(null, goodFile)
        assertEquals(ModCompatibilityLevel.UNKNOWN, unknownAssessment.level)
        assertFalse(unknownAssessment.canInstall)
    }

    @Test
    fun `dependency resolver builds plan, separates optional deps, detects cycles, skips installed, and explains unresolved`() = runTest {
        val settings = AetherSettingsRepository(db.settingsDao())
        val vault = InMemoryEncryptedTokenVault()
        val cred = CurseForgeCredentialProvider(vault, settings).apply {
            saveDeveloperApiKey("test-key-deps-123456")
        }

        // Mod graph:
        // Root (1000) requires A (1001), optional Opt (1002), alreadyInstalled DepInstalled (1003), missing Unresolved (1004)
        // A (1001) requires B (1005)
        // B (1005) requires A (1001) -> cycle!
        val repo = CurseForgeRepository(cred, object : CurseForgeTransport {
            override suspend fun get(url: String, headers: Map<String, String>): CurseForgeHttpResponse {
                return when {
                    url.contains("/v1/mods/1001/files") -> jsonFileResp(1001, 2001, "dep-a-1.20.1.jar", deps = listOf(1005L to 3))
                    url.contains("/v1/mods/1005/files") -> jsonFileResp(1005, 2005, "dep-b-1.20.1.jar", deps = listOf(1001L to 3))
                    url.contains("/v1/mods/1004/files") -> CurseForgeHttpResponse(200, """{"data":[]}""")
                    url.contains("/v1/mods/1001") -> jsonModResp(1001, "Dep A")
                    url.contains("/v1/mods/1002") -> jsonModResp(1002, "Opt UI")
                    url.contains("/v1/mods/1005") -> jsonModResp(1005, "Dep B")
                    url.contains("/v1/mods/1004") -> jsonModResp(1004, "Broken Dep")
                    else -> CurseForgeHttpResponse(404, "{}")
                }
            }
        })

        val resolver = ModDependencyResolver(repo, engine)
        val env = InstanceEnvironment(
            instanceId = "inst-1",
            instanceName = "Fabric Pack",
            gameDirPath = tempFolder.newFolder("inst_mods").absolutePath,
            rawVersionId = "1.20.1-fabric",
            baseMinecraftVersion = "1.20.1",
            loaderType = ModLoaderType.FABRIC,
            loaderVersion = "0.15.0",
            detectionSource = EnvironmentDetectionSource.MANIFEST_VERIFIED
        )

        val rootFile = sampleFile(
            id = 2000,
            modId = 1000,
            mc = listOf("1.20.1", "Fabric"),
            dependencies = listOf(
                CurseForgeFileDependency(1001, CurseForgeRelationType.REQUIRED_DEPENDENCY),
                CurseForgeFileDependency(1002, CurseForgeRelationType.OPTIONAL_DEPENDENCY),
                CurseForgeFileDependency(1003, CurseForgeRelationType.REQUIRED_DEPENDENCY),
                CurseForgeFileDependency(1004, CurseForgeRelationType.REQUIRED_DEPENDENCY)
            )
        )
        val rootMod = com.star.launcher.mods.api.CurseForgeMod(
            id = 1000,
            name = "Root Mod",
            slug = "root-mod",
            summary = "Root",
            downloadCount = 10,
            authors = emptyList(),
            categories = emptyList(),
            logo = null,
            screenshots = emptyList(),
            links = com.star.launcher.mods.api.CurseForgeLinks(null, null, null, null),
            allowModDistribution = true,
            dateModifiedIso = null,
            latestFiles = listOf(rootFile),
            supportedMinecraftVersionsFromLatest = listOf("1.20.1"),
            supportedLoadersFromLatest = setOf(CurseForgeModLoader.FABRIC)
        )

        val plan = resolver.buildInstallPlan(
            rootMod = rootMod,
            rootFile = rootFile,
            environment = env,
            installedCurseForgeModIds = setOf(1003L),
            installedFileNames = emptySet()
        )

        assertTrue(plan.alreadyInstalledDependencies.contains(1003L))
        assertEquals(listOf(1002L), plan.optionalDependencies.map { it.modId })
        assertTrue(plan.requiredDependencies.any { it.file.modId == 1001L })
        assertTrue(plan.requiredDependencies.any { it.file.modId == 1005L })
        assertFalse(plan.filesToDownload.any { it.file.modId == 1002L }) // optional NEVER auto-included
        assertTrue(plan.detectedCycles.isNotEmpty())
        assertEquals(1, plan.unresolvedDependencies.size)
        assertEquals(1004L, plan.unresolvedDependencies.first().modId)
        assertFalse(plan.canProceed) // unresolved required dep blocks blind install
    }

    private fun sampleFile(
        id: Long,
        modId: Long = 100L,
        mc: List<String>,
        dependencies: List<CurseForgeFileDependency> = emptyList()
    ) = CurseForgeFile(
        id = id,
        modId = modId,
        displayName = "file-$id.jar",
        fileName = "file-$id.jar",
        releaseType = CurseForgeReleaseType.RELEASE,
        isAvailable = true,
        isServerPack = false,
        fileDateIso = null,
        fileLengthBytes = 1024L,
        downloadUrl = "https://example.com/file-$id.jar",
        hashes = emptyList(),
        gameVersions = mc,
        dependencies = dependencies
    )

    private fun jsonModResp(id: Long, name: String) = CurseForgeHttpResponse(
        200,
        """{"data":{"id":$id,"name":"$name","summary":"","downloadCount":1,"allowModDistribution":true,"links":{"websiteUrl":"https://cf.example/$id"}}}"""
    )

    private fun jsonFileResp(modId: Long, fileId: Long, name: String, deps: List<Pair<Long, Int>>): CurseForgeHttpResponse {
        val depsJson = deps.joinToString(",") { """{"modId":${it.first},"relationType":${it.second}}""" }
        return CurseForgeHttpResponse(
            200,
            """{"data":[{"id":$fileId,"modId":$modId,"displayName":"$name","fileName":"$name","releaseType":1,"isAvailable":true,"isServerPack":false,"fileLength":512,"downloadUrl":"https://dl.example/$name","hashes":[],"gameVersions":["1.20.1","Fabric"],"dependencies":[$depsJson]}],"pagination":{"index":0,"pageSize":30,"resultCount":1,"totalCount":1}}"""
        )
    }
}
