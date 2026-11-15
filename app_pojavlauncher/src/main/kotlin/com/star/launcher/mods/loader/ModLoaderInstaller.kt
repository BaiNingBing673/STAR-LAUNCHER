package com.star.launcher.mods.loader

import com.star.launcher.core.AetherStorageManager
import com.star.launcher.data.db.dao.GameInstanceDao
import com.star.launcher.data.db.dao.GameVersionDao
import com.star.launcher.data.db.entity.GameInstanceEntity
import com.star.launcher.data.db.entity.GameVersionEntity
import com.star.launcher.data.db.model.ModLoaderType
import com.star.launcher.mods.compat.InstanceEnvironment
import com.star.launcher.mods.compat.InstanceEnvironmentResolver
import com.star.launcher.version.VersionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.kdt.pojavlaunch.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

data class ModLoaderCandidate(
    val loaderType: ModLoaderType,
    val loaderVersion: String,
    val minecraftVersion: String,
    val isRecommended: Boolean = false,
    val isStable: Boolean = true,
    val installerUrl: String? = null
) {
    val displayLabel: String
        get() = buildString {
            append(loaderVersion)
            if (isRecommended) append(" (Recommended)")
            else if (!isStable) append(" (Beta)")
        }
}

enum class ModLoaderInstallStage(val displayTitle: String) {
    VALIDATING_INSTANCE("Validating Instance & Minecraft Version"),
    FETCHING_LOADER_METADATA("Fetching Mod Loader Metadata"),
    EXTRACTING_INSTALLER("Processing Installer Package"),
    DOWNLOADING_LIBRARIES("Downloading Required Libraries"),
    WRITING_MANIFEST("Installing Version Manifest"),
    UPDATING_INSTANCE("Updating Instance Configuration"),
    VERIFYING("Verifying Mod Loader Installation"),
    COMPLETED("Installation Complete")
}

data class ModLoaderInstallProgress(
    val instanceId: String,
    val loaderType: ModLoaderType,
    val loaderVersion: String,
    val minecraftVersion: String,
    val stage: ModLoaderInstallStage,
    val completedSteps: Int,
    val totalSteps: Int,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val currentItem: String? = null
) {
    val fraction: Float
        get() = if (totalSteps > 0) {
            (completedSteps.toFloat() / totalSteps.toFloat()).coerceIn(0f, 1f)
        } else 0f
}

sealed interface ModLoaderInstallOutcome {
    data class Success(
        val instanceId: String,
        val installedVersionId: String,
        val loaderType: ModLoaderType,
        val loaderVersion: String,
        val minecraftVersion: String,
        val downloadedLibrariesCount: Int,
        val environment: InstanceEnvironment
    ) : ModLoaderInstallOutcome

    data class RequiresGuiInstaller(
        val instanceId: String,
        val loaderType: ModLoaderType,
        val loaderVersion: String,
        val minecraftVersion: String,
        val expectedVersionId: String,
        val installerJarPath: String
    ) : ModLoaderInstallOutcome

    data object Cancelled : ModLoaderInstallOutcome

    data class Failure(
        val message: String,
        val cause: Throwable? = null
    ) : ModLoaderInstallOutcome
}

interface ModLoaderNetworkSource {
    suspend fun fetchText(url: String): String
    suspend fun downloadToFile(
        url: String,
        targetFile: File,
        onBytesProgress: suspend (downloaded: Long, total: Long) -> Unit = { _, _ -> }
    )
}

class HttpModLoaderNetworkSource(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    private val maxRetries: Int = 3
) : ModLoaderNetworkSource {

    override suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
        var attempt = 0
        var backoffMs = 350L
        var lastError: Throwable? = null
        while (attempt < maxRetries) {
            coroutineContext.ensureActive()
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = connectTimeoutMs
                    readTimeout = readTimeoutMs
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "STARLauncher/${BuildConfig.VERSION_NAME}")
                    setRequestProperty("Accept", "application/json, application/xml, text/xml, */*")
                }
                try {
                    val code = conn.responseCode
                    if (code in 200..299) {
                        return@withContext conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    }
                    if (code !in setOf(408, 429, 500, 502, 503, 504)) {
                        throw IOException("HTTP $code when fetching $url")
                    }
                    lastError = IOException("HTTP $code from $url")
                } finally {
                    conn.disconnect()
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                lastError = t
                if (attempt + 1 >= maxRetries) break
            }
            attempt++
            delay(backoffMs)
            backoffMs = (backoffMs * 2L).coerceAtMost(2_500L)
        }
        throw IOException("Failed to fetch metadata from $url: ${lastError?.message ?: "Network error"}", lastError)
    }

    override suspend fun downloadToFile(
        url: String,
        targetFile: File,
        onBytesProgress: suspend (downloaded: Long, total: Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        var attempt = 0
        var backoffMs = 400L
        var lastError: Throwable? = null
        while (attempt < maxRetries) {
            coroutineContext.ensureActive()
            try {
                targetFile.parentFile?.mkdirs()
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = connectTimeoutMs
                    readTimeout = readTimeoutMs
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "STARLauncher/${BuildConfig.VERSION_NAME}")
                }
                try {
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        if (code !in setOf(408, 429, 500, 502, 503, 504)) {
                            throw IOException("Download failed with HTTP $code for $url")
                        }
                        throw IOException("Transient HTTP $code for $url")
                    }
                    val totalBytes = conn.contentLengthLong.coerceAtLeast(0L)
                    var downloaded = 0L
                    conn.inputStream.use { input ->
                        FileOutputStream(targetFile).use { output ->
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                downloaded += read
                                onBytesProgress(downloaded, totalBytes)
                            }
                            output.flush()
                             runCatching { output.fd.sync() }
                        }
                    }
                    return@withContext
                } finally {
                    conn.disconnect()
                }
            } catch (ce: CancellationException) {
                if (targetFile.exists()) targetFile.delete()
                throw ce
            } catch (t: Throwable) {
                if (targetFile.exists()) targetFile.delete()
                lastError = t
                if (attempt + 1 >= maxRetries) break
            }
            attempt++
            delay(backoffMs)
            backoffMs = (backoffMs * 2L).coerceAtMost(3_000L)
        }
        throw IOException("Failed to download $url after $maxRetries attempts: ${lastError?.message ?: "Network error"}", lastError)
    }
}

/**
 * Installs Forge, NeoForge, Fabric, and Quilt mod loaders directly into a selected STAR Launcher
 * instance while preserving all user worlds, saves, resource packs, and configurations.
 */
class ModLoaderInstaller(
    private val storageManager: AetherStorageManager,
    private val gameVersionDao: GameVersionDao,
    private val gameInstanceDao: GameInstanceDao,
    private val versionManager: VersionManager,
    private val environmentResolver: InstanceEnvironmentResolver,
    private val networkSource: ModLoaderNetworkSource = HttpModLoaderNetworkSource(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private data class CachedCandidates(
        val candidates: List<ModLoaderCandidate>,
        val timestampMs: Long = System.currentTimeMillis()
    )

    private val candidateCache = ConcurrentHashMap<String, CachedCandidates>()
    private val activeInstalls = ConcurrentHashMap<String, Boolean>()
    private val cacheTtlMs = 5 * 60 * 1000L

    fun isInstalling(instanceId: String): Boolean = activeInstalls.containsKey(instanceId)

    fun clearCache() {
        candidateCache.clear()
    }

    /**
     * Queries compatible loader versions for the given [minecraftVersion] and [loaderType].
     * Only versions compatible with [minecraftVersion] are returned.
     */
    suspend fun fetchCompatibleVersions(
        minecraftVersion: String,
        loaderType: ModLoaderType,
        forceRefresh: Boolean = false
    ): Result<List<ModLoaderCandidate>> = withContext(ioDispatcher) {
        val cleanMc = minecraftVersion.trim()
        if (cleanMc.isEmpty() || !MC_VERSION_REGEX.matches(cleanMc)) {
            return@withContext Result.failure(
                IllegalArgumentException("Select a valid Minecraft version before choosing a mod loader (detected: '$minecraftVersion').")
            )
        }
        if (loaderType !in SUPPORTED_INSTALL_LOADERS) {
            return@withContext Result.failure(
                IllegalArgumentException("Unsupported mod loader type: ${loaderType.name}")
            )
        }

        val cacheKey = "${loaderType.name}|$cleanMc"
        if (!forceRefresh) {
            candidateCache[cacheKey]?.let { cached ->
                if (System.currentTimeMillis() - cached.timestampMs <= cacheTtlMs) {
                    return@withContext Result.success(cached.candidates)
                }
            }
        }

        runCatching {
            val list = when (loaderType) {
                ModLoaderType.FABRIC -> fetchFabricCandidates(cleanMc)
                ModLoaderType.QUILT -> fetchQuiltCandidates(cleanMc)
                ModLoaderType.FORGE -> fetchForgeCandidates(cleanMc)
                ModLoaderType.NEOFORGE -> fetchNeoForgeCandidates(cleanMc)
                else -> emptyList()
            }
            if (list.isNotEmpty()) {
                candidateCache[cacheKey] = CachedCandidates(list)
            }
            list
        }
    }

    private suspend fun fetchFabricCandidates(mcVersion: String): List<ModLoaderCandidate> {
        val encodedMc = URLEncoder.encode(mcVersion, "UTF-8")
        val url = "$FABRIC_META_BASE_URL/v2/versions/loader/$encodedMc"
        val raw = networkSource.fetchText(url)
        val arr = JSONArray(raw)
        var recommendedAssigned = false
        return buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val loaderObj = item.optJSONObject("loader") ?: item
                val ver = loaderObj.optString("version").trim()
                if (ver.isEmpty()) continue
                val stable = loaderObj.optBoolean("stable", !ver.contains('-'))
                val isRec = !recommendedAssigned && stable
                if (isRec) recommendedAssigned = true
                add(
                    ModLoaderCandidate(
                        loaderType = ModLoaderType.FABRIC,
                        loaderVersion = ver,
                        minecraftVersion = mcVersion,
                        isRecommended = isRec,
                        isStable = stable
                    )
                )
            }
        }
    }

    private suspend fun fetchQuiltCandidates(mcVersion: String): List<ModLoaderCandidate> {
        val encodedMc = URLEncoder.encode(mcVersion, "UTF-8")
        val url = "$QUILT_META_BASE_URL/v3/versions/loader/$encodedMc"
        val raw = networkSource.fetchText(url)
        val arr = JSONArray(raw)
        var recommendedAssigned = false
        return buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val loaderObj = item.optJSONObject("loader") ?: item
                val ver = loaderObj.optString("version").trim()
                if (ver.isEmpty()) continue
                val stable = !ver.contains("beta", ignoreCase = true) && !ver.contains("alpha", ignoreCase = true)
                val isRec = !recommendedAssigned && stable
                if (isRec) recommendedAssigned = true
                add(
                    ModLoaderCandidate(
                        loaderType = ModLoaderType.QUILT,
                        loaderVersion = ver,
                        minecraftVersion = mcVersion,
                        isRecommended = isRec,
                        isStable = stable
                    )
                )
            }
        }
    }

    private suspend fun fetchForgeCandidates(mcVersion: String): List<ModLoaderCandidate> {
        val xml = networkSource.fetchText(FORGE_MAVEN_METADATA_URL)
        val allVersions = parseMavenMetadataVersions(xml)
        val prefix = "$mcVersion-"
        val matching = allVersions
            .filter { it.startsWith(prefix) }
            .mapNotNull { full ->
                val forgeVer = full.removePrefix(prefix).substringBefore('-').trim()
                if (forgeVer.isEmpty()) null else full to forgeVer
            }
            .reversed()

        var recommendedAssigned = false
        return matching.map { (fullVer, forgeVer) ->
            val isRec = !recommendedAssigned
            if (isRec) recommendedAssigned = true
            ModLoaderCandidate(
                loaderType = ModLoaderType.FORGE,
                loaderVersion = forgeVer,
                minecraftVersion = mcVersion,
                isRecommended = isRec,
                isStable = true,
                installerUrl = "$FORGE_MAVEN_BASE_URL/net/minecraftforge/forge/$fullVer/forge-$fullVer-installer.jar"
            )
        }
    }

    private suspend fun fetchNeoForgeCandidates(mcVersion: String): List<ModLoaderCandidate> {
        val xml = networkSource.fetchText(NEOFORGE_MAVEN_METADATA_URL)
        val allVersions = parseMavenMetadataVersions(xml)
        val matching = allVersions
            .filter { ver ->
                val inferredMc = InstanceEnvironmentResolver.inferMcFromNeoForgeVersion(ver.substringBefore('-'))
                inferredMc == mcVersion || ver.startsWith("$mcVersion-")
            }
            .reversed()

        var recommendedAssigned = false
        return matching.map { ver ->
            val stable = !ver.contains("beta", ignoreCase = true) && !ver.contains("alpha", ignoreCase = true)
            val isRec = !recommendedAssigned && stable
            if (isRec) recommendedAssigned = true
            ModLoaderCandidate(
                loaderType = ModLoaderType.NEOFORGE,
                loaderVersion = ver,
                minecraftVersion = mcVersion,
                isRecommended = isRec,
                isStable = stable,
                installerUrl = "$NEOFORGE_MAVEN_BASE_URL/net/neoforged/neoforge/$ver/neoforge-$ver-installer.jar"
            )
        }
    }

    /**
     * Installs the specified [candidate] into [instance], downloading required libraries,
     * writing the version manifest, updating the instance's active `versionId`, preserving
     * all user saves/worlds/configs, and re-detecting the instance environment.
     */
    suspend fun installModLoader(
        instance: GameInstanceEntity,
        candidate: ModLoaderCandidate,
        onProgress: (ModLoaderInstallProgress) -> Unit = {}
    ): ModLoaderInstallOutcome = withContext(ioDispatcher) {
        if (activeInstalls.putIfAbsent(instance.instanceId, true) != null) {
            return@withContext ModLoaderInstallOutcome.Failure(
                "A mod loader installation is already in progress for '${instance.name}'."
            )
        }

        val createdStagingFiles = mutableListOf<File>()
        try {
            coroutineContext.ensureActive()
            val mcVersion = candidate.minecraftVersion.trim()
            val loaderVersion = candidate.loaderVersion.trim()
            val loaderType = candidate.loaderType

            if (mcVersion.isEmpty() || !MC_VERSION_REGEX.matches(mcVersion)) {
                return@withContext ModLoaderInstallOutcome.Failure(
                    "Invalid Minecraft version '$mcVersion' for instance '${instance.name}'."
                )
            }
            if (loaderVersion.isEmpty()) {
                return@withContext ModLoaderInstallOutcome.Failure(
                    "Invalid ${loaderType.name} loader version."
                )
            }
            if (loaderType !in SUPPORTED_INSTALL_LOADERS) {
                return@withContext ModLoaderInstallOutcome.Failure(
                    "Unsupported mod loader type: ${loaderType.name}"
                )
            }

            // Verify compatibility between candidate and target Minecraft version
            if (loaderType == ModLoaderType.NEOFORGE) {
                val inferredMc = InstanceEnvironmentResolver.inferMcFromNeoForgeVersion(loaderVersion.substringBefore('-'))
                if (inferredMc != null && inferredMc != mcVersion) {
                    return@withContext ModLoaderInstallOutcome.Failure(
                        "NeoForge $loaderVersion is built for Minecraft $inferredMc and is not compatible with Minecraft $mcVersion."
                    )
                }
            }

            onProgress(
                ModLoaderInstallProgress(
                    instanceId = instance.instanceId,
                    loaderType = loaderType,
                    loaderVersion = loaderVersion,
                    minecraftVersion = mcVersion,
                    stage = ModLoaderInstallStage.VALIDATING_INSTANCE,
                    completedSteps = 0,
                    totalSteps = 4,
                    currentItem = instance.name
                )
            )

            // Snapshot existing instance directory structure to ensure nothing is lost
            val instanceDir = File(instance.gameDirPath).apply { mkdirs() }

            val extractOutcome: ForgeExtractResult = when (loaderType) {
                ModLoaderType.FABRIC -> {
                    val (root, id, libs) = fetchAndPrepareFabricOrQuiltProfile(
                        instanceId = instance.instanceId,
                        loaderType = ModLoaderType.FABRIC,
                        mcVersion = mcVersion,
                        loaderVersion = loaderVersion,
                        onProgress = onProgress
                    )
                    ForgeExtractResult.HeadlessReady(root, id, libs)
                }
                ModLoaderType.QUILT -> {
                    val (root, id, libs) = fetchAndPrepareFabricOrQuiltProfile(
                        instanceId = instance.instanceId,
                        loaderType = ModLoaderType.QUILT,
                        mcVersion = mcVersion,
                        loaderVersion = loaderVersion,
                        onProgress = onProgress
                    )
                    ForgeExtractResult.HeadlessReady(root, id, libs)
                }
                ModLoaderType.FORGE, ModLoaderType.NEOFORGE -> fetchAndExtractForgeLikeInstaller(
                    instanceId = instance.instanceId,
                    loaderType = loaderType,
                    mcVersion = mcVersion,
                    loaderVersion = loaderVersion,
                    explicitInstallerUrl = candidate.installerUrl,
                    createdStagingFiles = createdStagingFiles,
                    onProgress = onProgress
                )
                else -> throw IllegalArgumentException("Unsupported loader: $loaderType")
            }

            if (extractOutcome is ForgeExtractResult.NeedsGuiInstaller) {
                onProgress(
                    ModLoaderInstallProgress(
                        instanceId = instance.instanceId,
                        loaderType = loaderType,
                        loaderVersion = loaderVersion,
                        minecraftVersion = mcVersion,
                        stage = ModLoaderInstallStage.COMPLETED,
                        completedSteps = 5,
                        totalSteps = 5,
                        currentItem = File(extractOutcome.installerJarPath).name
                    )
                )
                return@withContext ModLoaderInstallOutcome.RequiresGuiInstaller(
                    instanceId = instance.instanceId,
                    loaderType = loaderType,
                    loaderVersion = loaderVersion,
                    minecraftVersion = mcVersion,
                    expectedVersionId = extractOutcome.expectedVersionId,
                    installerJarPath = extractOutcome.installerJarPath
                )
            }

            val readyResult = extractOutcome as ForgeExtractResult.HeadlessReady
            val manifestRoot = readyResult.manifestRoot
            val installedVersionId = readyResult.installedVersionId
            val extraLibraries = readyResult.extraLibraries

            coroutineContext.ensureActive()

            // Embed STAR loader metadata block inside manifest JSON for unambiguous verification
            val starMeta = JSONObject().apply {
                put("loaderType", loaderType.name)
                put("loaderVersion", loaderVersion)
                put("minecraftVersion", mcVersion)
                put("installedAtEpochMs", System.currentTimeMillis())
            }
            manifestRoot.put("starLoaderMetadata", starMeta)
            if (manifestRoot.optString("inheritsFrom").isBlank()) {
                manifestRoot.put("inheritsFrom", mcVersion)
            }

            // Collect and download all required libraries
            val librariesRoot = File(storageManager.versionsRoot().parentFile, "libraries").apply { mkdirs() }
            val requiredLibs = collectRequiredLibraries(manifestRoot, extraLibraries, loaderType)
            val totalSteps = (requiredLibs.size + 3).coerceAtLeast(4)
            var downloadedCount = 0

            for ((index, libSpec) in requiredLibs.withIndex()) {
                coroutineContext.ensureActive()
                val targetLibFile = File(librariesRoot, libSpec.relativePath)
                onProgress(
                    ModLoaderInstallProgress(
                        instanceId = instance.instanceId,
                        loaderType = loaderType,
                        loaderVersion = loaderVersion,
                        minecraftVersion = mcVersion,
                        stage = ModLoaderInstallStage.DOWNLOADING_LIBRARIES,
                        completedSteps = index + 1,
                        totalSteps = totalSteps,
                        currentItem = targetLibFile.name
                    )
                )

                val alreadyValid = targetLibFile.isFile && targetLibFile.length() > 0L &&
                    (libSpec.sha1 == null || computeSha1(targetLibFile).equals(libSpec.sha1, ignoreCase = true))

                if (!alreadyValid && libSpec.downloadUrl != null) {
                    val stagingLib = File(targetLibFile.parentFile, "${targetLibFile.name}.part")
                    createdStagingFiles.add(stagingLib)
                    networkSource.downloadToFile(libSpec.downloadUrl, stagingLib) { dl, total ->
                        onProgress(
                            ModLoaderInstallProgress(
                                instanceId = instance.instanceId,
                                loaderType = loaderType,
                                loaderVersion = loaderVersion,
                                minecraftVersion = mcVersion,
                                stage = ModLoaderInstallStage.DOWNLOADING_LIBRARIES,
                                completedSteps = index + 1,
                                totalSteps = totalSteps,
                                downloadedBytes = dl,
                                totalBytes = total,
                                currentItem = targetLibFile.name
                            )
                        )
                    }
                    coroutineContext.ensureActive()
                    if (libSpec.sha1 != null) {
                        val actualSha1 = computeSha1(stagingLib)
                        if (!actualSha1.equals(libSpec.sha1, ignoreCase = true)) {
                            stagingLib.delete()
                            throw IOException("SHA-1 mismatch for library ${targetLibFile.name} (expected ${libSpec.sha1}, got $actualSha1)")
                        }
                    }
                    promoteStagingFile(stagingLib, targetLibFile)
                    downloadedCount++
                } else if (alreadyValid) {
                    downloadedCount++
                }
            }

            coroutineContext.ensureActive()

            // Stage: Writing Version Manifest
            onProgress(
                ModLoaderInstallProgress(
                    instanceId = instance.instanceId,
                    loaderType = loaderType,
                    loaderVersion = loaderVersion,
                    minecraftVersion = mcVersion,
                    stage = ModLoaderInstallStage.WRITING_MANIFEST,
                    completedSteps = totalSteps - 2,
                    totalSteps = totalSteps,
                    currentItem = "$installedVersionId.json"
                )
            )
            val versionDir = File(storageManager.versionsRoot(), installedVersionId).apply { mkdirs() }
            val manifestFile = File(versionDir, "$installedVersionId.json")
            val manifestStaging = File(versionDir, "$installedVersionId.json.part")
            createdStagingFiles.add(manifestStaging)
            manifestStaging.writeText(manifestRoot.toString(2))
            promoteStagingFile(manifestStaging, manifestFile)

            // Write instance-local loader metadata without touching any user worlds/saves/configs
            val instanceMetaFile = File(instanceDir, InstanceEnvironmentResolver.STAR_LOADER_METADATA_FILE)
            val instanceMetaStaging = File(instanceDir, "${InstanceEnvironmentResolver.STAR_LOADER_METADATA_FILE}.part")
            createdStagingFiles.add(instanceMetaStaging)
            val instanceMetaJson = JSONObject().apply {
                put("installedVersionId", installedVersionId)
                put("minecraftVersion", mcVersion)
                put("loaderType", loaderType.name)
                put("loaderVersion", loaderVersion)
                put("installedAtEpochMs", System.currentTimeMillis())
            }
            instanceMetaStaging.writeText(instanceMetaJson.toString(2))
            promoteStagingFile(instanceMetaStaging, instanceMetaFile)

            // Stage: Updating Instance & Version Catalog
            onProgress(
                ModLoaderInstallProgress(
                    instanceId = instance.instanceId,
                    loaderType = loaderType,
                    loaderVersion = loaderVersion,
                    minecraftVersion = mcVersion,
                    stage = ModLoaderInstallStage.UPDATING_INSTANCE,
                    completedSteps = totalSteps - 1,
                    totalSteps = totalSteps,
                    currentItem = instance.name
                )
            )
            val parsedManifest = VersionManager.parseManifestJson(manifestFile.readText(), installedVersionId)
            val versionEntity = GameVersionEntity(
                versionId = installedVersionId,
                inheritsFrom = parsedManifest.inheritsFrom ?: mcVersion,
                versionType = "modded",
                modLoaderType = loaderType,
                modLoaderVersion = loaderVersion,
                requiredJavaMajor = parsedManifest.requiredJavaMajor,
                lwjglMajor = parsedManifest.lwjglMajor,
                manifestPath = manifestFile.absolutePath,
                isInstalled = true,
                releaseTimeEpochMs = parsedManifest.releaseTimeEpochMs.takeIf { it > 0L } ?: System.currentTimeMillis()
            )
            gameVersionDao.upsert(versionEntity)
            runCatching { versionManager.scanInstalledVersions() }

            val latestInstance = gameInstanceDao.getById(instance.instanceId) ?: instance
            val updatedInstance = latestInstance.copy(versionId = installedVersionId)
            gameInstanceDao.upsert(updatedInstance)

            // Stage: Verifying & Re-detecting Instance Environment
            onProgress(
                ModLoaderInstallProgress(
                    instanceId = instance.instanceId,
                    loaderType = loaderType,
                    loaderVersion = loaderVersion,
                    minecraftVersion = mcVersion,
                    stage = ModLoaderInstallStage.VERIFYING,
                    completedSteps = totalSteps,
                    totalSteps = totalSteps,
                    currentItem = installedVersionId
                )
            )
            val resolvedEnv = environmentResolver.resolve(updatedInstance)
            if (resolvedEnv.loaderType != loaderType) {
                return@withContext ModLoaderInstallOutcome.Failure(
                    "Post-install verification failed: expected ${loaderType.name}, but resolved ${resolvedEnv.loaderType.name}."
                )
            }

            onProgress(
                ModLoaderInstallProgress(
                    instanceId = instance.instanceId,
                    loaderType = loaderType,
                    loaderVersion = loaderVersion,
                    minecraftVersion = mcVersion,
                    stage = ModLoaderInstallStage.COMPLETED,
                    completedSteps = totalSteps,
                    totalSteps = totalSteps,
                    currentItem = installedVersionId
                )
            )

            ModLoaderInstallOutcome.Success(
                instanceId = instance.instanceId,
                installedVersionId = installedVersionId,
                loaderType = loaderType,
                loaderVersion = loaderVersion,
                minecraftVersion = mcVersion,
                downloadedLibrariesCount = downloadedCount,
                environment = resolvedEnv
            )
        } catch (_: CancellationException) {
            createdStagingFiles.forEach { if (it.exists()) it.delete() }
            ModLoaderInstallOutcome.Cancelled
        } catch (t: Throwable) {
            createdStagingFiles.forEach { if (it.exists()) it.delete() }
            ModLoaderInstallOutcome.Failure(
                message = t.message ?: "Failed to install ${candidate.loaderType.name} ${candidate.loaderVersion}.",
                cause = t
            )
        } finally {
            activeInstalls.remove(instance.instanceId)
        }
    }

    private suspend fun fetchAndPrepareFabricOrQuiltProfile(
        instanceId: String,
        loaderType: ModLoaderType,
        mcVersion: String,
        loaderVersion: String,
        onProgress: (ModLoaderInstallProgress) -> Unit
    ): Triple<JSONObject, String, List<JSONObject>> {
        val defaultId = if (loaderType == ModLoaderType.QUILT) {
            "quilt-loader-$loaderVersion-$mcVersion"
        } else {
            "fabric-loader-$loaderVersion-$mcVersion"
        }
        onProgress(
            ModLoaderInstallProgress(
                instanceId = instanceId,
                loaderType = loaderType,
                loaderVersion = loaderVersion,
                minecraftVersion = mcVersion,
                stage = ModLoaderInstallStage.FETCHING_LOADER_METADATA,
                completedSteps = 1,
                totalSteps = 4,
                currentItem = "$defaultId.json"
            )
        )
        val encodedMc = URLEncoder.encode(mcVersion, "UTF-8")
        val encodedLoader = URLEncoder.encode(loaderVersion, "UTF-8")
        val url = if (loaderType == ModLoaderType.QUILT) {
            "$QUILT_META_BASE_URL/v3/versions/loader/$encodedMc/$encodedLoader/profile/json"
        } else {
            "$FABRIC_META_BASE_URL/v2/versions/loader/$encodedMc/$encodedLoader/profile/json"
        }
        val rawJson = networkSource.fetchText(url)
        val root = JSONObject(rawJson)
        val versionId = root.optString("id").trim().ifEmpty { defaultId }
        root.put("id", versionId)
        if (root.optString("inheritsFrom").isBlank()) {
            root.put("inheritsFrom", mcVersion)
        }
        if (root.optString("mainClass").isBlank()) {
            root.put(
                "mainClass",
                if (loaderType == ModLoaderType.QUILT) {
                    "org.quiltmc.loader.impl.launch.knot.KnotClient"
                } else {
                    "net.fabricmc.loader.impl.launch.knot.KnotClient"
                }
            )
        }
        return Triple(root, versionId, emptyList())
    }

    private sealed interface ForgeExtractResult {
        data class HeadlessReady(
            val manifestRoot: JSONObject,
            val installedVersionId: String,
            val extraLibraries: List<JSONObject>
        ) : ForgeExtractResult

        data class NeedsGuiInstaller(
            val installerJarPath: String,
            val expectedVersionId: String
        ) : ForgeExtractResult
    }

    private suspend fun fetchAndExtractForgeLikeInstaller(
        instanceId: String,
        loaderType: ModLoaderType,
        mcVersion: String,
        loaderVersion: String,
        explicitInstallerUrl: String?,
        createdStagingFiles: MutableList<File>,
        onProgress: (ModLoaderInstallProgress) -> Unit
    ): ForgeExtractResult {
        val defaultVersionId = if (loaderType == ModLoaderType.NEOFORGE) {
            "neoforge-$loaderVersion"
        } else {
            "$mcVersion-forge-$loaderVersion"
        }
        val installerUrl = explicitInstallerUrl ?: if (loaderType == ModLoaderType.NEOFORGE) {
            "$NEOFORGE_MAVEN_BASE_URL/net/neoforged/neoforge/$loaderVersion/neoforge-$loaderVersion-installer.jar"
        } else {
            val fullVer = if (loaderVersion.startsWith("$mcVersion-")) loaderVersion else "$mcVersion-$loaderVersion"
            "$FORGE_MAVEN_BASE_URL/net/minecraftforge/forge/$fullVer/forge-$fullVer-installer.jar"
        }

        val cacheDir = File(storageManager.versionsRoot().parentFile, "cache/loader_installers").apply { mkdirs() }
        val installerJar = File(cacheDir, "$defaultVersionId-installer.jar.part")
        createdStagingFiles.add(installerJar)

        onProgress(
            ModLoaderInstallProgress(
                instanceId = instanceId,
                loaderType = loaderType,
                loaderVersion = loaderVersion,
                minecraftVersion = mcVersion,
                stage = ModLoaderInstallStage.FETCHING_LOADER_METADATA,
                completedSteps = 1,
                totalSteps = 5,
                currentItem = installerJar.name.removeSuffix(".part")
            )
        )

        networkSource.downloadToFile(installerUrl, installerJar) { dl, total ->
            onProgress(
                ModLoaderInstallProgress(
                    instanceId = instanceId,
                    loaderType = loaderType,
                    loaderVersion = loaderVersion,
                    minecraftVersion = mcVersion,
                    stage = ModLoaderInstallStage.FETCHING_LOADER_METADATA,
                    completedSteps = 1,
                    totalSteps = 5,
                    downloadedBytes = dl,
                    totalBytes = total,
                    currentItem = installerJar.name.removeSuffix(".part")
                )
            )
        }

        coroutineContext.ensureActive()
        onProgress(
            ModLoaderInstallProgress(
                instanceId = instanceId,
                loaderType = loaderType,
                loaderVersion = loaderVersion,
                minecraftVersion = mcVersion,
                stage = ModLoaderInstallStage.EXTRACTING_INSTALLER,
                completedSteps = 2,
                totalSteps = 5,
                currentItem = "Extracting $defaultVersionId"
            )
        )

        val librariesRoot = File(storageManager.versionsRoot().parentFile, "libraries").apply { mkdirs() }
        var versionJsonRoot: JSONObject? = null
        val extraInstallLibs = mutableListOf<JSONObject>()
        var hasProcessors = false

        ZipFile(installerJar).use { zip ->
            // 1. Extract embedded maven/ artifacts from the installer JAR
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                coroutineContext.ensureActive()
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val entryName = entry.name.replace('\\', '/')
                if (entryName.startsWith("maven/") && entryName.length > "maven/".length) {
                    val relPath = entryName.removePrefix("maven/")
                    if (!relPath.contains("..")) {
                        val destFile = File(librariesRoot, relPath)
                        val stagingFile = File(destFile.parentFile, "${destFile.name}.part")
                        createdStagingFiles.add(stagingFile)
                        stagingFile.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(stagingFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        promoteStagingFile(stagingFile, destFile)
                    }
                }
            }

            // 2. Read version.json and/or install_profile.json
            val versionEntry = zip.getEntry("version.json")
            val installProfileEntry = zip.getEntry("install_profile.json")

            if (versionEntry != null) {
                val raw = zip.getInputStream(versionEntry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                versionJsonRoot = JSONObject(raw)
            }

            if (installProfileEntry != null) {
                val rawProfile = zip.getInputStream(installProfileEntry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                val profileJson = JSONObject(rawProfile)
                if (versionJsonRoot == null && profileJson.has("versionInfo")) {
                    versionJsonRoot = profileJson.optJSONObject("versionInfo")
                }
                // Extract legacy embedded universal jar if specified in install_profile.json
                val installSection = profileJson.optJSONObject("install")
                if (installSection != null) {
                    val filePath = installSection.optString("filePath").trim()
                    val coord = installSection.optString("path").trim()
                    val relPath = mavenCoordToRelativePath(coord)
                    val embeddedEntry = if (filePath.isNotEmpty()) zip.getEntry(filePath) else null
                    if (embeddedEntry != null && relPath != null) {
                        val destFile = File(librariesRoot, relPath)
                        val stagingFile = File(destFile.parentFile, "${destFile.name}.part")
                        createdStagingFiles.add(stagingFile)
                        stagingFile.parentFile?.mkdirs()
                        zip.getInputStream(embeddedEntry).use { input ->
                            FileOutputStream(stagingFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        promoteStagingFile(stagingFile, destFile)
                    }
                }
                val profileLibs = profileJson.optJSONArray("libraries")
                if (profileLibs != null) {
                    for (i in 0 until profileLibs.length()) {
                        profileLibs.optJSONObject(i)?.let { extraInstallLibs.add(it) }
                    }
                }
                val processors = profileJson.optJSONArray("processors")
                if (processors != null && processors.length() > 0) {
                    hasProcessors = true
                }
            }
        }

        val root = versionJsonRoot
        val resolvedId = root?.optString("id")?.trim()?.ifEmpty { defaultVersionId } ?: defaultVersionId

        if (hasProcessors) {
            val baseDir = File(storageManager.versionsRoot(), mcVersion)
            val baseJson = File(baseDir, "$mcVersion.json")
            val baseJar = File(baseDir, "$mcVersion.jar")
            if (!baseJson.isFile || baseJson.length() <= 0L || !baseJar.isFile || baseJar.length() <= 0L) {
                installerJar.delete()
                throw IOException(
                    "Minecraft $mcVersion client files must be downloaded or launched once before installing ${loaderType.name} $loaderVersion."
                )
            }
            val persistentInstaller = File(cacheDir, "$defaultVersionId-installer.jar")
            promoteStagingFile(installerJar, persistentInstaller)
            createdStagingFiles.remove(installerJar)
            return ForgeExtractResult.NeedsGuiInstaller(
                installerJarPath = persistentInstaller.absolutePath,
                expectedVersionId = resolvedId
            )
        }

        installerJar.delete()

        val validRoot = root
            ?: throw IOException("Installer JAR for $defaultVersionId did not contain a valid version.json or install_profile.json.")

        validRoot.put("id", resolvedId)
        if (validRoot.optString("inheritsFrom").isBlank()) {
            validRoot.put("inheritsFrom", mcVersion)
        }
        return ForgeExtractResult.HeadlessReady(validRoot, resolvedId, extraInstallLibs)
    }

    private data class RequiredLibrarySpec(
        val coordinate: String,
        val relativePath: String,
        val downloadUrl: String?,
        val sha1: String?
    )

    private fun collectRequiredLibraries(
        manifestRoot: JSONObject,
        extraLibraries: List<JSONObject>,
        loaderType: ModLoaderType
    ): List<RequiredLibrarySpec> {
        val defaultRepoBase = when (loaderType) {
            ModLoaderType.FABRIC -> "https://maven.fabricmc.net/"
            ModLoaderType.QUILT -> "https://maven.quiltmc.org/repository/release/"
            ModLoaderType.FORGE -> "$FORGE_MAVEN_BASE_URL/"
            ModLoaderType.NEOFORGE -> "$NEOFORGE_MAVEN_BASE_URL/"
            else -> "https://libraries.minecraft.net/"
        }

        val combined = mutableListOf<JSONObject>()
        val mainArr = manifestRoot.optJSONArray("libraries")
        if (mainArr != null) {
            for (i in 0 until mainArr.length()) {
                mainArr.optJSONObject(i)?.let { combined.add(it) }
            }
        }
        combined.addAll(extraLibraries)

        val seenPaths = HashSet<String>()
        return buildList {
            for (libObj in combined) {
                val coord = libObj.optString("name").trim()
                if (coord.isEmpty() || coord.startsWith("org.lwjgl")) continue
                val artifactObj = libObj.optJSONObject("downloads")?.optJSONObject("artifact")
                val relPath = artifactObj?.optString("path")?.trim()?.ifEmpty { null }
                    ?: mavenCoordToRelativePath(coord)
                    ?: continue
                if (!seenPaths.add(relPath)) continue

                val explicitUrl = artifactObj?.optString("url")?.trim()?.ifEmpty { null }
                val baseRepo = libObj.optString("url").trim().ifEmpty { null }
                val resolvedUrl = when {
                    explicitUrl != null -> explicitUrl
                    baseRepo != null -> "${baseRepo.trimEnd('/')}/$relPath"
                    !libObj.has("downloads") -> "${defaultRepoBase.trimEnd('/')}/$relPath"
                    else -> null
                }
                val sha1 = artifactObj?.optString("sha1")?.trim()?.ifEmpty { null }
                    ?: libObj.optString("sha1").trim().ifEmpty { null }

                add(
                    RequiredLibrarySpec(
                        coordinate = coord,
                        relativePath = relPath,
                        downloadUrl = resolvedUrl,
                        sha1 = sha1
                    )
                )
            }
        }
    }

    private fun promoteStagingFile(stagingFile: File, targetFile: File) {
        targetFile.parentFile?.mkdirs()
        if (targetFile.exists() && !targetFile.delete()) {
            throw IOException("Unable to replace ${targetFile.absolutePath}")
        }
        if (!stagingFile.renameTo(targetFile)) {
            stagingFile.copyTo(targetFile, overwrite = true)
            stagingFile.delete()
        }
    }

    companion object {
        const val FABRIC_META_BASE_URL = "https://meta.fabricmc.net"
        const val QUILT_META_BASE_URL = "https://meta.quiltmc.org"
        const val FORGE_MAVEN_BASE_URL = "https://maven.minecraftforge.net"
        const val FORGE_MAVEN_METADATA_URL = "$FORGE_MAVEN_BASE_URL/net/minecraftforge/forge/maven-metadata.xml"
        const val NEOFORGE_MAVEN_BASE_URL = "https://maven.neoforged.net/releases"
        const val NEOFORGE_MAVEN_METADATA_URL = "$NEOFORGE_MAVEN_BASE_URL/net/neoforged/neoforge/maven-metadata.xml"

        val SUPPORTED_INSTALL_LOADERS: List<ModLoaderType> = listOf(
            ModLoaderType.FABRIC,
            ModLoaderType.FORGE,
            ModLoaderType.NEOFORGE,
            ModLoaderType.QUILT
        )

        private val MC_VERSION_REGEX = Regex("""^\d+\.\d+(?:\.\d+)?$""")

        internal fun parseMavenMetadataVersions(xmlText: String): List<String> {
            if (xmlText.isBlank()) return emptyList()
            return runCatching {
                val factory = XmlPullParserFactory.newInstance()
                val parser = factory.newPullParser()
                parser.setInput(StringReader(xmlText))
                val versions = mutableListOf<String>()
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG && parser.name == "version") {
                        val text = parser.nextText()?.trim().orEmpty()
                        if (text.isNotEmpty()) versions.add(text)
                    }
                    event = parser.next()
                }
                versions
            }.getOrElse {
                // Fallback regex parser if XmlPullParser is unavailable in plain JVM context
                Regex("""<version>\s*([^<\s]+)\s*</version>""")
                    .findAll(xmlText)
                    .map { it.groupValues[1].trim() }
                    .filter { it.isNotEmpty() }
                    .toList()
            }
        }

        internal fun mavenCoordToRelativePath(coord: String): String? {
            val clean = coord.trim()
            if (clean.isEmpty()) return null
            val parts = clean.split(":")
            if (parts.size < 3) return null
            val groupPath = parts[0].replace('.', '/')
            val artifact = parts[1]
            val versionAndExt = parts[2].split("@")
            val version = versionAndExt[0]
            val defaultExt = versionAndExt.getOrNull(1) ?: "jar"
            val classifierAndExt = parts.getOrNull(3)?.split("@")
            val classifier = classifierAndExt?.getOrNull(0)?.takeIf { it.isNotBlank() }
            val ext = classifierAndExt?.getOrNull(1) ?: defaultExt
            val fileName = if (classifier != null) {
                "$artifact-$version-$classifier.$ext"
            } else {
                "$artifact-$version.$ext"
            }
            return "$groupPath/$artifact/$version/$fileName"
        }

        internal fun computeSha1(file: File): String {
            val digest = MessageDigest.getInstance("SHA-1")
            file.inputStream().use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val r = input.read(buf)
                    if (r <= 0) break
                    digest.update(buf, 0, r)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
