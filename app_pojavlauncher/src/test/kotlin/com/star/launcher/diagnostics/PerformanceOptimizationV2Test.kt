package com.star.launcher.diagnostics

import com.star.launcher.compat.UiPerformanceProfile
import com.star.launcher.compat.UiPerformanceProfileManager
import com.star.launcher.compat.UiPerformanceProfileMode
import com.star.launcher.core.AetherSettingsRepository
import com.star.launcher.data.db.dao.SettingsDao
import com.star.launcher.data.db.entity.AppSettingEntity
import com.star.launcher.data.db.entity.GameVersionEntity
import com.star.launcher.data.db.model.ModLoaderType
import com.star.launcher.data.security.TokenVault
import com.star.launcher.mods.api.CurseForgeCredentialProvider
import com.star.launcher.mods.api.CurseForgeHttpResponse
import com.star.launcher.mods.api.CurseForgeRepository
import com.star.launcher.mods.api.CurseForgeSearchQuery
import com.star.launcher.mods.api.CurseForgeTransport
import com.star.launcher.ui.dashboard.AetherDashboardUiState
import com.star.launcher.ui.dashboard.DashboardTab
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PerformanceOptimizationV2Test {

    @Test
    fun `UiPerformanceProfileManager builds expected LOW BALANCED and HIGH specs`() {
        val lowSpec = UiPerformanceProfileManager.buildSpec(
            UiPerformanceProfileMode.LOW,
            UiPerformanceProfile.LOW
        )
        assertEquals(UiPerformanceProfile.LOW, lowSpec.profile)
        assertFalse(lowSpec.enableDecorativeLoops)
        assertTrue(lowSpec.preferRgb565Thumbnails)
        assertEquals(192, lowSpec.maxThumbnailDimensionPx)

        val balancedSpec = UiPerformanceProfileManager.buildSpec(
            UiPerformanceProfileMode.BALANCED,
            UiPerformanceProfile.BALANCED
        )
        assertEquals(UiPerformanceProfile.BALANCED, balancedSpec.profile)
        assertTrue(balancedSpec.enableDecorativeLoops)
        assertFalse(balancedSpec.preferRgb565Thumbnails)
        assertEquals(256, balancedSpec.maxThumbnailDimensionPx)

        val highSpec = UiPerformanceProfileManager.buildSpec(
            UiPerformanceProfileMode.HIGH,
            UiPerformanceProfile.HIGH
        )
        assertEquals(UiPerformanceProfile.HIGH, highSpec.profile)
        assertTrue(highSpec.enableDecorativeLoops)
        assertEquals(384, highSpec.maxThumbnailDimensionPx)
        assertEquals(50L, highSpec.lyricsWordSyncIntervalMs)
    }

    @Test
    fun `AetherDashboardUiState memoizes versionCatalogItems across state copies`() {
        val versions = (1..250).map { i ->
            GameVersionEntity(
                versionId = "1.21.$i",
                inheritsFrom = null,
                versionType = "release",
                modLoaderType = ModLoaderType.VANILLA,
                modLoaderVersion = null,
                requiredJavaMajor = 21,
                lwjglMajor = 3,
                manifestPath = "/tmp/1.21.$i.json",
                isInstalled = (i == 1),
                releaseTimeEpochMs = 1700000000000L + i
            )
        }
        val state1 = AetherDashboardUiState(
            selectedTab = DashboardTab.HOME,
            selectedVersionId = "1.21.1",
            versions = versions
        )
        val catalog1 = state1.versionCatalogItems
        assertEquals(250, catalog1.size)
        assertSame(catalog1, state1.versionCatalogItems)

        // Copying state with unrelated UI field changes must reuse the exact same catalog instance
        val state2 = state1.copy(
            selectedTab = DashboardTab.VERSIONS,
            statusBanner = "Ready",
            versionSearchQuery = "1.21"
        )
        assertSame(catalog1, state2.versionCatalogItems)
        assertNotNull(state2.selectedVersionItem)
        assertEquals("1.21.1", state2.selectedVersionItem?.versionId)
    }

    @Test
    fun `CurseForgeRepository caches search and mod detail responses within TTL`() = runBlocking {
        val fakeDao = object : SettingsDao {
            private val map = HashMap<String, AppSettingEntity>()
            override suspend fun get(key: String) = map[key]
            override fun observeAll(): Flow<List<AppSettingEntity>> = emptyFlow()
            override suspend fun upsert(setting: AppSettingEntity) {
                map[setting.key] = setting
            }
            override suspend fun delete(key: String) {
                map.remove(key)
            }
        }
        val vault = object : TokenVault {
            private val secrets = HashMap<String, String>()
            override fun putSecret(alias: String, plaintextSecret: String) {
                secrets[alias] = plaintextSecret
            }
            override fun getSecret(alias: String): String? = secrets[alias]
            override fun deleteSecret(alias: String) {
                secrets.remove(alias)
            }
            override fun containsSecret(alias: String): Boolean = secrets.containsKey(alias)
        }
        val settings = AetherSettingsRepository(fakeDao)
        val provider = CurseForgeCredentialProvider(
            tokenVault = vault,
            settingsRepository = settings,
            bundledApiKeyProvider = { "\$2a\$10\$validBundledDeveloperKeyForUnitTests123456" }
        )

        val requestCounter = AtomicInteger(0)
        val transport = object : CurseForgeTransport {
            override suspend fun get(url: String, headers: Map<String, String>): CurseForgeHttpResponse {
                requestCounter.incrementAndGet()
                return CurseForgeHttpResponse(
                    statusCode = 200,
                    body = """
                        {
                          "data": [
                            {
                              "id": 394468,
                              "name": "Sodium",
                              "slug": "sodium",
                              "summary": "Modern rendering engine",
                              "downloadCount": 50000000,
                              "allowModDistribution": true
                            }
                          ],
                          "pagination": {
                            "index": 0,
                            "pageSize": 20,
                            "resultCount": 1,
                            "totalCount": 1
                          }
                        }
                    """.trimIndent()
                )
            }
        }
        val repo = CurseForgeRepository(provider, transport, enablePublicFallback = true)
        val query = CurseForgeSearchQuery(searchFilter = "sodium")

        val res1 = repo.searchMods(query)
        val res2 = repo.searchMods(query)
        val modRes = repo.getMod(394468L)

        assertTrue(res1.isSuccess)
        assertTrue(res2.isSuccess)
        assertTrue(modRes.isSuccess)
        // Both the second searchMods(query) AND getMod(394468L) should be served from the populated cache!
        assertEquals(1, requestCounter.get())
    }
}
