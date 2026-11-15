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

    @Test
    fun `json null fields do not leak literal string null into mod or link metadata`() = runTest {
        credentialProvider.saveDeveloperApiKey("valid-test-key-abcdef")
        val repo = CurseForgeRepository(credentialProvider, object : CurseForgeTransport {
            override suspend fun get(url: String, headers: Map<String, String>): CurseForgeHttpResponse {
                return CurseForgeHttpResponse(
                    200,
                    """{"data":{"id":202,"name":"CleanMod","slug":null,"summary":null,"downloadCount":10,"logo":{"id":1,"thumbnailUrl":null,"url":null},"links":{"websiteUrl":null,"wikiUrl":null,"issuesUrl":null,"sourceUrl":null},"latestFiles":[]}}"""
                )
            }
        })
        val mod = repo.getMod(202L).getOrThrow()
        assertEquals("", mod.summary)
        assertNull(mod.logo?.thumbnailUrl)
        assertNull(mod.logo?.url)
        assertNull(mod.links.websiteUrl)
        assertNull(mod.links.sourceUrl)
    }

    @Test
    fun `developer api key normalization strips quotes prefix and invisibles while http 403 exposes safe auth reason`() = runTest {
        val rawExpected = "\$2a\$10\$CleanedKeyValue123456"
        credentialProvider.saveDeveloperApiKey("  \uFEFFx-api-key: \"$rawExpected\" \u200B ")
        val auth = credentialProvider.getAuthConfig() as CurseForgeAuthConfig.DirectVaultKey
        assertEquals(rawExpected, auth.apiKey)

        val dummyErr = runCatching { credentialProvider.saveDeveloperApiKey("DUMMY") }.exceptionOrNull()
        assertTrue(dummyErr is IllegalArgumentException)

        val repo403 = CurseForgeRepository(credentialProvider, object : CurseForgeTransport {
            override suspend fun get(url: String, headers: Map<String, String>): CurseForgeHttpResponse {
                return CurseForgeHttpResponse(403, "Forbidden: API Key missing or invalid ($rawExpected)")
            }
        })
        val err = repo403.searchMods(CurseForgeSearchQuery()).exceptionOrNull()
        assertTrue(err is CurseForgeApiException.Forbidden)
        val forbidden = err as CurseForgeApiException.Forbidden
        assertFalse(forbidden.isDistributionRestriction)
        assertTrue(forbidden.message.contains("API Key missing or invalid"))
        assertFalse(forbidden.message.contains(rawExpected))
    }

    @Test
    fun `release fallback uses bundled key or public Modrinth catalog when unconfigured or 403 rejected`() = runTest {
        val dummyCred = CurseForgeCredentialProvider(vault, settings) { "DUMMY\n" }
        assertEquals(CurseForgeCredentialStatus.UNCONFIGURED, dummyCred.getCredentialStatus())

        val bundledCred = CurseForgeCredentialProvider(vault, settings) { "  \$2a\$10\$BundledReleaseKey9999  \n" }
        assertEquals(CurseForgeCredentialStatus.DEVELOPER_VAULT_KEY_CONFIGURED, bundledCred.getCredentialStatus())
        assertEquals("\$2a\$10\$BundledReleaseKey9999", (bundledCred.getAuthConfig() as CurseForgeAuthConfig.DirectVaultKey).apiKey)

        // Even with unconfigured or 403-rejected CurseForge key, enablePublicFallback=true serves real mods
        credentialProvider.saveDeveloperApiKey("stale-invalid-vault-key-403")
        val fallbackRepo = CurseForgeRepository(
            credentialProvider = credentialProvider,
            enablePublicFallback = true,
            transport = object : CurseForgeTransport {
                override suspend fun get(url: String, headers: Map<String, String>): CurseForgeHttpResponse {
                    return when {
                        url.startsWith("https://api.curseforge.com") ->
                            CurseForgeHttpResponse(403, "Forbidden: API Key missing or invalid")
                        url.startsWith("https://api.modrinth.com/v2/search") ->
                            CurseForgeHttpResponse(
                                200,
                                """
                                {
                                  "hits": [
                                    {
                                      "project_id": "AANobbMI",
                                      "slug": "sodium",
                                      "title": "Sodium",
                                      "description": "The fastest rendering optimization mod",
                                      "author": "jellysquid3",
                                      "downloads": 55000000,
                                      "icon_url": "https://cdn.modrinth.com/sodium.png",
                                      "versions": ["1.20.1", "1.21.1"],
                                      "categories": ["fabric", "neoforge", "optimization"]
                                    }
                                  ],
                                  "offset": 0,
                                  "limit": 20,
                                  "total_hits": 1
                                }
                                """.trimIndent()
                            )
                        url.startsWith("https://api.modrinth.com/v2/project/AANobbMI/version") ->
                            CurseForgeHttpResponse(
                                200,
                                """
                                [
                                  {
                                    "id": "ver123",
                                    "project_id": "AANobbMI",
                                    "name": "Sodium 0.6.0",
                                    "version_type": "release",
                                    "game_versions": ["1.21.1"],
                                    "loaders": ["fabric"],
                                    "files": [
                                      {
                                        "url": "https://cdn.modrinth.com/data/AANobbMI/sodium-0.6.0.jar",
                                        "filename": "sodium-0.6.0.jar",
                                        "primary": true,
                                        "size": 1048576,
                                        "hashes": {"sha1": "11223344556677889900aabbccddeeff00112233"}
                                      }
                                    ],
                                    "dependencies": []
                                  }
                                ]
                                """.trimIndent()
                            )
                        url.startsWith("https://api.modrinth.com/v2/project/AANobbMI") ->
                            CurseForgeHttpResponse(
                                200,
                                """
                                {
                                  "id": "AANobbMI",
                                  "slug": "sodium",
                                  "title": "Sodium",
                                  "description": "The fastest rendering optimization mod",
                                  "body": "<p>Full description of Sodium</p>",
                                  "downloads": 55000000,
                                  "game_versions": ["1.20.1", "1.21.1"],
                                  "loaders": ["fabric", "neoforge"],
                                  "categories": ["optimization"]
                                }
                                """.trimIndent()
                            )
                        else -> CurseForgeHttpResponse(404, "{}")
                    }
                }
            }
        )

        val searchRes = fallbackRepo.searchMods(CurseForgeSearchQuery(searchFilter = "sodium")).getOrThrow()
        assertEquals(1, searchRes.mods.size)
        val mod = searchRes.mods.first()
        assertEquals("Sodium", mod.name)
        assertTrue(mod.supportedLoadersFromLatest.contains(CurseForgeModLoader.FABRIC))

        val detailMod = fallbackRepo.getMod(mod.id).getOrThrow()
        assertEquals("Sodium", detailMod.name)
        val desc = fallbackRepo.getModDescription(mod.id).getOrThrow()
        assertEquals("Full description of Sodium", desc)

        val files = fallbackRepo.getModFiles(mod.id, gameVersion = "1.21.1", modLoader = CurseForgeModLoader.FABRIC).getOrThrow().files
        assertEquals(1, files.size)
        val dl = fallbackRepo.resolveDownloadUrl(detailMod, files.first()).getOrThrow()
        assertTrue(dl is CurseForgeDownloadUrlResult.Available)
        assertEquals("https://cdn.modrinth.com/data/AANobbMI/sodium-0.6.0.jar", (dl as CurseForgeDownloadUrlResult.Available).url)
    }

    private suspend fun repoStatus(): CurseForgeCredentialStatus =
        credentialProvider.getCredentialStatus()
}
