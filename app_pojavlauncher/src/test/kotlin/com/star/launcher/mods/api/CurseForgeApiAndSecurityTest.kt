package com.star.launcher.mods.api

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.star.launcher.core.AetherSettingsRepository
import com.star.launcher.data.db.AetherDatabase
import com.star.launcher.data.security.InMemoryEncryptedTokenVault
import com.star.launcher.diagnostics.SensitiveLogRedactor
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CurseForgeApiAndSecurityTest {

    private lateinit var db: AetherDatabase
    private lateinit var settings: AetherSettingsRepository
    private lateinit var vault: InMemoryEncryptedTokenVault
    private lateinit var credentialProvider: CurseForgeCredentialProvider

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AetherDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settings = AetherSettingsRepository(db.settingsDao())
        vault = InMemoryEncryptedTokenVault()
        credentialProvider = CurseForgeCredentialProvider(vault, settings)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `search without configured credentials fails cleanly without network calls`() = runTest {
        var callsMade = 0
        val repo = CurseForgeRepository(credentialProvider, object : CurseForgeTransport {
            override suspend fun get(url: String, headers: Map<String, String>): CurseForgeHttpResponse {
                callsMade++
                return CurseForgeHttpResponse(200, "{}")
            }
        })

        val res = repo.searchMods(CurseForgeSearchQuery(searchFilter = "sodium"))
        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull() is CurseForgeApiException.NotConfigured)
        assertEquals(0, callsMade)
        assertEquals(CurseForgeCredentialStatus.UNCONFIGURED, repo.getCredentialStatus())
    }

    @Test
    fun `search with valid Vault key sends x-api-key header and parses pagination and filters`() = runTest {
        val secretKey = "\$2a\$10\$SuperSecretCurseForgeDevKey123456"
        credentialProvider.saveDeveloperApiKey(secretKey)
        assertFalse(settings.getString(CurseForgeCredentialProvider.KEY_AUTH_MODE).contains(secretKey))

        var capturedUrl = ""
        var capturedHeaders = emptyMap<String, String>()
        val repo = CurseForgeRepository(credentialProvider, object : CurseForgeTransport {
            override suspend fun get(url: String, headers: Map<String, String>): CurseForgeHttpResponse {
                capturedUrl = url
                capturedHeaders = headers
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
                              "downloadCount": 42000000,
                              "allowModDistribution": true,
                              "authors": [{"id": 1, "name": "jellysquid3", "url": "https://cf.example/a"}],
                              "categories": [{"id": 406, "name": "World Gen", "slug": "world-gen"}],
                              "logo": {"id": 10, "thumbnailUrl": "https://media.example/thumb.png", "url": "https://media.example/logo.png"},
                              "screenshots": [{"id": 11, "title": "Bench", "url": "https://media.example/shot.png"}],
                              "links": {"websiteUrl": "https://www.curseforge.com/minecraft/mc-mods/sodium"},
                              "latestFiles": [
                                {
                                  "id": 999001,
                                  "modId": 394468,
                                  "displayName": "sodium-fabric-mc1.20.1-0.5.8.jar",
                                  "fileName": "sodium-fabric-mc1.20.1-0.5.8.jar",
                                  "releaseType": 1,
                                  "isAvailable": true,
                                  "isServerPack": false,
                                  "fileLength": 920000,
                                  "downloadUrl": "https://mediafilez.example/sodium.jar",
                                  "hashes": [{"value": "abc123", "algo": 1}],
                                  "gameVersions": ["1.20.1", "Fabric"],
                                  "dependencies": [{"modId": 306612, "relationType": 2}]
                                }
                              ]
                            }
                          ],
                          "pagination": {"index": 0, "pageSize": 20, "resultCount": 1, "totalCount": 45}
                        }
                    """.trimIndent()
                )
            }
        })

        val res = repo.searchMods(
            CurseForgeSearchQuery(
                searchFilter = "sodium",
                gameVersion = "1.20.1",
                modLoader = CurseForgeModLoader.FABRIC,
                categoryId = 406,
                sortField = CurseForgeSortField.POPULARITY,
                index = 0,
                pageSize = 20
            )
        ).getOrThrow()

        assertTrue(capturedUrl.startsWith("https://api.curseforge.com/v1/mods/search?"))
        assertTrue(capturedUrl.contains("gameId=432"))
        assertTrue(capturedUrl.contains("classId=6"))
        assertTrue(capturedUrl.contains("gameVersion=1.20.1"))
        assertTrue(capturedUrl.contains("modLoaderType=4"))
        assertTrue(capturedUrl.contains("categoryId=406"))
        assertEquals(secretKey, capturedHeaders["x-api-key"])

        assertEquals(1, res.mods.size)
        val sodium = res.mods.first()
        assertEquals(394468L, sodium.id)
        assertEquals("Sodium", sodium.name)
        assertEquals("jellysquid3", sodium.primaryAuthor)
        assertTrue(sodium.supportedLoadersFromLatest.contains(CurseForgeModLoader.FABRIC))
        assertTrue(sodium.supportedMinecraftVersionsFromLatest.contains("1.20.1"))
        assertEquals(45L, res.pagination.totalCount)
        assertTrue(res.pagination.hasMore)
    }

    @Test
    fun `proxy config routes requests through cf prefix and never leaks secret in logs`() = runTest {
        val clientSecret = "star-client-token-secret-xyz"
        credentialProvider.saveProxyConfig("https://mods-proxy.starlauncher.dev", clientSecret)
        assertEquals(CurseForgeCredentialStatus.BACKEND_PROXY_CONFIGURED, repoStatus())

        var requestedUrl = ""
        var sentHeaders = emptyMap<String, String>()
        val repo = CurseForgeRepository(credentialProvider, object : CurseForgeTransport {
            override suspend fun get(url: String, headers: Map<String, String>): CurseForgeHttpResponse {
                requestedUrl = url
                sentHeaders = headers
                return CurseForgeHttpResponse(200, """{"data":[],"pagination":{"index":20,"pageSize":20,"resultCount":0,"totalCount":20}}""")
            }
        })

        val emptyPage = repo.searchMods(CurseForgeSearchQuery(index = 20, pageSize = 20)).getOrThrow()
        assertTrue(requestedUrl.startsWith("https://mods-proxy.starlauncher.dev/cf/v1/mods/search?"))
        assertEquals("Bearer $clientSecret", sentHeaders["Authorization"])
        assertNull(sentHeaders["x-api-key"])
        assertTrue(emptyPage.mods.isEmpty())
        assertFalse(emptyPage.pagination.hasMore)

        val redacted = SensitiveLogRedactor.redact(
            "Request failed headers={x-api-key: rawSecret999, Authorization: Bearer $clientSecret}"
        )
        assertFalse(redacted.contains("rawSecret999"))
        assertFalse(redacted.contains(clientSecret))
    }

    @Test
    fun `http 429 rate limit, 401 unauthorized, 500 error, offline, and secret redaction behave safely`() = runTest {
        val rawKey = "super-secret-cf-api-key-98765"
        credentialProvider.saveDeveloperApiKey(rawKey)

        // 429 with Retry-After
        val rateLimitedRepo = CurseForgeRepository(credentialProvider, object : CurseForgeTransport {
            override suspend fun get(url: String, headers: Map<String, String>): CurseForgeHttpResponse {
                return CurseForgeHttpResponse(429, "{}", mapOf("retry-after" to "45"))
            }
        })
        val rlErr = rateLimitedRepo.searchMods(CurseForgeSearchQuery()).exceptionOrNull()
        assertTrue(rlErr is CurseForgeApiException.RateLimited)
        assertEquals(45, (rlErr as CurseForgeApiException.RateLimited).retryAfterSeconds)

        // 401 never exposes raw key
        val unauthRepo = CurseForgeRepository(credentialProvider, object : CurseForgeTransport {
            override suspend fun get(url: String, headers: Map<String, String>): CurseForgeHttpResponse {
                return CurseForgeHttpResponse(401, "Invalid $rawKey")
            }
        })
        val authErr = unauthRepo.searchMods(CurseForgeSearchQuery()).exceptionOrNull()
        assertTrue(authErr is CurseForgeApiException.Unauthorized)
        assertFalse(authErr?.message.orEmpty().contains(rawKey))

        // 500 ServerError
        val serverErrRepo = CurseForgeRepository(credentialProvider, object : CurseForgeTransport {
            override suspend fun get(url: String, headers: Map<String, String>): CurseForgeHttpResponse {
                return CurseForgeHttpResponse(503, "Down")
            }
        })
        val srvErr = serverErrRepo.searchMods(CurseForgeSearchQuery()).exceptionOrNull()
        assertTrue(srvErr is CurseForgeApiException.ServerError)

        // Exception with secret leaked by lower layer gets redacted
        val leakingRepo = CurseForgeRepository(credentialProvider, object : CurseForgeTransport {
            override suspend fun get(url: String, headers: Map<String, String>): CurseForgeHttpResponse {
                throw IllegalStateException("Transport failed with x-api-key=$rawKey")
            }
        })
        val genericErr = leakingRepo.searchMods(CurseForgeSearchQuery()).exceptionOrNull()
        assertFalse(genericErr?.message.orEmpty().contains(rawKey))
    }

    @Test
    fun `mod details, categories, versions, loaders, and download restrictions respect API rules`() = runTest {
        credentialProvider.saveDeveloperApiKey("valid-test-key-abcdef")
        val requestedUrls = mutableListOf<String>()

        val repo = CurseForgeRepository(credentialProvider, object : CurseForgeTransport {
            override suspend fun get(url: String, headers: Map<String, String>): CurseForgeHttpResponse {
                requestedUrls.add(url)
                return when {
                    url.endsWith("/description") -> CurseForgeHttpResponse(200, """{"data":"<p>Fast&nbsp;mod<br>Line 2</p>"}""")
                    url.contains("/download-url") -> CurseForgeHttpResponse(403, "{}")
                    url.contains("/files") -> CurseForgeHttpResponse(
                        200,
                        """{"data":[{"id":555,"modId":101,"displayName":"TestFile","fileName":"test-1.0.jar","releaseType":2,"isAvailable":true,"isServerPack":false,"fileLength":1024,"downloadUrl":null,"hashes":[],"gameVersions":["1.21.1","NeoForge"],"dependencies":[]}],"pagination":{"index":0,"pageSize":50,"resultCount":1,"totalCount":1}}"""
                    )
                    url.contains("/v1/mods/101") -> CurseForgeHttpResponse(
                        200,
                        """{"data":{"id":101,"name":"RestrictedMod","summary":"Blocked API dist","downloadCount":100,"allowModDistribution":false,"links":{"websiteUrl":"https://cf.example/m/101"}}}"""
                    )
                    url.contains("/v1/categories") -> CurseForgeHttpResponse(
                        200,
                        """{"data":[{"id":406,"name":"World Gen","slug":"world-gen","classId":6}]}"""
                    )
                    url.contains("/v1/minecraft/version") -> CurseForgeHttpResponse(
                        200,
                        """{"data":[{"id":1,"versionString":"1.21.1","approved":true}]}"""
                    )
                    url.contains("/v1/minecraft/modloader") -> CurseForgeHttpResponse(
                        200,
                        """{"data":[{"name":"neoforge-21.1","gameVersion":"1.21.1","latest":true,"recommended":true,"type":6}]}"""
                    )
                    else -> CurseForgeHttpResponse(404, "{}")
                }
            }
        })

        val mod = repo.getMod(101L).getOrThrow()
        assertEquals(false, mod.allowModDistribution)
        val desc = repo.getModDescription(101L).getOrThrow()
        assertTrue(desc.contains("Fast mod"))
        assertFalse(desc.contains("<p>"))

        val files = repo.getModFiles(101L).getOrThrow().files
        assertEquals(1, files.size)
        assertEquals(CurseForgeReleaseType.BETA, files.first().releaseType)
        assertNull(files.first().downloadUrl)

        // Restricted mod: must return Restricted and NEVER fabricate a CDN URL or call /download-url unnecessarily
        val dlResultForDisallowed = repo.resolveDownloadUrl(mod, files.first()).getOrThrow()
        assertTrue(dlResultForDisallowed is CurseForgeDownloadUrlResult.Restricted)
        assertFalse(requestedUrls.any { it.contains("/download-url") })

        // Even when mod flag is null, HTTP 403 on /download-url must return Restricted cleanly
        val dlResult403 = repo.resolveDownloadUrl(mod.copy(allowModDistribution = null), files.first()).getOrThrow()
        assertTrue(dlResult403 is CurseForgeDownloadUrlResult.Restricted)

        assertEquals(1, repo.getCategories().getOrThrow().size)
        assertEquals("1.21.1", repo.getMinecraftVersions().getOrThrow().first().versionString)
        assertEquals(CurseForgeModLoader.NEOFORGE, repo.getModLoaders("1.21.1").getOrThrow().first().type)
    }

    private suspend fun repoStatus(): CurseForgeCredentialStatus =
        credentialProvider.getCredentialStatus()
}
