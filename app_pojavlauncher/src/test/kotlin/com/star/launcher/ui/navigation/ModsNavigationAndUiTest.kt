package com.star.launcher.ui.navigation

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.star.launcher.core.AetherSettingsRepository
import com.star.launcher.core.AetherStorageManager
import com.star.launcher.data.db.AetherDatabase
import com.star.launcher.data.db.entity.GameInstanceEntity
import com.star.launcher.data.security.InMemoryEncryptedTokenVault
import com.star.launcher.mods.api.CurseForgeCredentialProvider
import com.star.launcher.mods.api.CurseForgeCredentialStatus
import com.star.launcher.mods.api.CurseForgeHttpResponse
import com.star.launcher.mods.api.CurseForgeModLoader
import com.star.launcher.mods.api.CurseForgeRepository
import com.star.launcher.mods.api.CurseForgeTransport
import com.star.launcher.mods.compat.InstanceEnvironmentResolver
import com.star.launcher.mods.compat.ModCompatibilityEngine
import com.star.launcher.mods.deps.ModDependencyResolver
import com.star.launcher.mods.download.ModDownloadInstaller
import com.star.launcher.mods.inventory.InstalledModManager
import com.star.launcher.mods.ui.ModsController
import com.star.launcher.mods.ui.ModsErrorKind
import com.star.launcher.ui.dashboard.AetherDashboardActivity
import com.star.launcher.ui.dashboard.DashboardTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModsNavigationAndUiTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `mods tab and mod detail route navigate and restore across recreation`() {
        val routeEncoded = AetherRoute.ModDetail(394468L).encode()
        val decoded = AetherRoute.decode(routeEncoded)
        assertEquals(AetherRoute.ModDetail(394468L), decoded)
        assertEquals(DashboardTab.MODS, decoded?.ownerTab)
        assertEquals(AetherRoute.Tab(DashboardTab.MODS), AetherRoute.decode("MODS"))

        val controller = Robolectric.buildActivity(AetherDashboardActivity::class.java)
            .create()
            .start()
            .resume()
        val activity = controller.get()

        activity.stateViewModel.onTabSelected(DashboardTab.MODS)
        assertEquals(AetherRoute.Tab(DashboardTab.MODS), activity.stateViewModel.navigation.currentRoute)
        assertEquals(DashboardTab.MODS, activity.stateViewModel.controller.uiState.value.selectedTab)

        activity.stateViewModel.navigation.push(AetherRoute.ModDetail(394468L))
        activity.stateViewModel.syncControllerWithCurrentRoute()
        assertEquals(AetherRoute.ModDetail(394468L), activity.stateViewModel.navigation.currentRoute)
        assertEquals(DashboardTab.MODS, activity.stateViewModel.controller.uiState.value.selectedTab)

        controller.recreate()
        val recreated = controller.get()
        assertEquals(AetherRoute.ModDetail(394468L), recreated.stateViewModel.navigation.currentRoute)

        recreated.onBackPressedDispatcher.onBackPressed()
        assertEquals(AetherRoute.Tab(DashboardTab.MODS), recreated.stateViewModel.navigation.currentRoute)

        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertNull(Shadows.shadowOf(recreated).nextStartedActivity)
        controller.pause().stop().destroy()
    }

    @Test
    fun `mods controller handles unconfigured state and instance sync cleanly`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AetherDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val gameDir = tempFolder.newFolder("game")
        val dataDir = tempFolder.newFolder("data")
        val storage = AetherStorageManager({ gameDir }, { dataDir })
        val settings = AetherSettingsRepository(db.settingsDao())
        val cred = CurseForgeCredentialProvider(InMemoryEncryptedTokenVault(), settings)
        val repo = CurseForgeRepository(cred, object : CurseForgeTransport {
            override suspend fun get(url: String, headers: Map<String, String>): CurseForgeHttpResponse =
                CurseForgeHttpResponse(200, """{"data":[],"pagination":{"index":0,"pageSize":20,"resultCount":0,"totalCount":0}}""")
        })
        val compat = ModCompatibilityEngine()
        val modsController = ModsController(
            credentialProvider = cred,
            repository = repo,
            environmentResolver = InstanceEnvironmentResolver(db.gameVersionDao(), storage),
            compatibilityEngine = compat,
            dependencyResolver = ModDependencyResolver(repo, compat),
            downloadInstaller = ModDownloadInstaller(repo, db.downloadTaskDao(), db.installedModDao(), storage),
            installedModManager = InstalledModManager(db.installedModDao(), repo, compat),
            ioDispatcher = Dispatchers.Unconfined,
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Unconfined)
        )

        try {
            modsController.startObserving()
            val initial = withTimeout(3_000) {
                modsController.uiState.first { it.discoverError?.kind == ModsErrorKind.UNCONFIGURED }
            }
            assertEquals(CurseForgeCredentialStatus.UNCONFIGURED, initial.credentialStatus)
            assertEquals(ModsErrorKind.UNCONFIGURED, initial.discoverError?.kind)

            val fabricInstance = GameInstanceEntity(
                instanceId = "inst-f",
                name = "Fabric Test",
                gameDirPath = tempFolder.newFolder("inst_f").absolutePath,
                versionId = "fabric-loader-0.15.0-1.20.1",
                runtimeId = null,
                rendererProfileId = null,
                allocatedRamMb = 2048,
                customJvmArgs = null,
                controlMapPath = null,
                lastPlayedAt = null,
                createdAt = 1L
            )
            modsController.syncSelectedInstance(fabricInstance, listOf(fabricInstance))
            val synced = withTimeout(3_000) {
                modsController.uiState.first {
                    it.instanceEnvironment?.baseMinecraftVersion == "1.20.1" &&
                        it.filterLoader == CurseForgeModLoader.FABRIC
                }
            }
            assertEquals("1.20.1", synced.instanceEnvironment?.baseMinecraftVersion)
            assertEquals(CurseForgeModLoader.FABRIC, synced.filterLoader)
            assertTrue(synced.availableInstances.size == 1)
        } finally {
            modsController.close()
            db.close()
        }
    }
}
