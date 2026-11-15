package com.star.launcher.version

import com.star.launcher.core.AetherStorageManager
import com.star.launcher.data.db.dao.GameVersionDao
import com.star.launcher.data.db.entity.GameVersionEntity
import com.star.launcher.data.db.model.DownloadStatus
import com.star.launcher.data.db.model.DownloadTaskType
import com.star.launcher.data.db.model.ModLoaderType
import com.star.launcher.download.DownloadManager
import net.kdt.pojavlaunch.modloaders.FabriclikeUtils
import net.kdt.pojavlaunch.prefs.LauncherPreferences
import org.json.JSONObject
import java.io.File
import java.time.Instant

data class RemoteVersionEntry(
    val versionId: String,
    val versionType: String,
    val url: String,
    val sha1: String?,
    val releaseTimeEpochMs: Long
)

data class FabricLoaderOption(
    val version: String,
    val stable: Boolean
)

data class CatalogSyncResult(
    val entries: List<GameVersionEntity>,
    val remoteSha1ByVersionId: Map<String, String>
)

/**
 * Fetches official Mojang version manifests and Fabric/Quilt loader profiles through
 * [DownloadManager] without mutating upstream `launcher_profiles.json`.
 */
class VersionCatalogService(
    private val gameVersionDao: GameVersionDao,
    private val storageManager: AetherStorageManager,
    private val downloadManager: DownloadManager,
    private val versionManager: VersionManager
) {
    /**
     * Downloads Mojang `version_manifest_v2.json` via [DownloadManager] and indexes remote releases
     * (preserving existing `isInstalled = true` rows untouched).
     */
    suspend fun syncMojangManifestCatalog(
        manifestUrl: String = LauncherPreferences.PREF_VERSION_REPOS,
        includeSnapshots: Boolean = false,
        maxEntries: Int = 40
    ): List<GameVersionEntity> {
        return syncFullMojangCatalog(
            manifestUrl = manifestUrl,
            includeSnapshots = includeSnapshots,
            includeHistorical = includeSnapshots,
            maxEntries = maxEntries
        ).entries
    }

    /**
     * Fetches the Mojang `version_manifest_v2.json` catalog (releases, snapshots, old_beta, old_alpha),
     * batch-upserts non-installed entries while preserving `isInstalled = true`, and returns remote SHA-1
     * hashes when provided in the manifest for verifiable update detection.
     */
    suspend fun syncFullMojangCatalog(
        manifestUrl: String = LauncherPreferences.PREF_VERSION_REPOS,
        includeSnapshots: Boolean = true,
        includeHistorical: Boolean = true,
        maxEntries: Int = Int.MAX_VALUE
    ): CatalogSyncResult {
        val cacheFile = File(storageManager.downloadStagingDir(), "version_manifest_v2.json")
        val task = downloadManager.enqueue(
            taskType = DownloadTaskType.VERSION_MANIFEST,
            sourceUrl = manifestUrl,
            targetPath = cacheFile.absolutePath
        )
        val completed = downloadManager.executeTask(task.taskId)
        check(completed.status == DownloadStatus.COMPLETED && cacheFile.isFile) {
            "Failed to fetch version manifest: ${completed.errorMessage ?: completed.status}"
        }

        val remote = parseMojangManifestV2(
            jsonText = cacheFile.readText(),
            includeSnapshots = includeSnapshots,
            limit = maxEntries,
            includeHistorical = includeHistorical
        )
        val indexed = mutableListOf<GameVersionEntity>()
        val toUpsert = mutableListOf<GameVersionEntity>()
        val shaMap = LinkedHashMap<String, String>()
        for (entry in remote) {
            entry.sha1?.let { shaMap[entry.versionId] = it }
            val existing = gameVersionDao.getById(entry.versionId)
            if (existing?.isInstalled == true) {
                val updated = if (existing.manifestPath.isBlank()) {
                    existing.copy(manifestPath = entry.url)
                } else existing
                if (updated != existing) {
                    toUpsert += updated
                }
                indexed += updated
                continue
            }
            val inferredJava = inferJavaForRelease(entry.versionId)
            val entity = GameVersionEntity(
                versionId = entry.versionId,
                inheritsFrom = null,
                versionType = entry.versionType,
                modLoaderType = ModLoaderType.VANILLA,
                modLoaderVersion = null,
                requiredJavaMajor = inferredJava,
                lwjglMajor = 3,
                manifestPath = entry.url,
                isInstalled = false,
                releaseTimeEpochMs = entry.releaseTimeEpochMs
            )
            toUpsert += entity
            indexed += entity
        }
        if (toUpsert.isNotEmpty()) {
            gameVersionDao.upsertAll(toUpsert)
        }
        return CatalogSyncResult(
            entries = indexed,
            remoteSha1ByVersionId = shaMap
        )
    }

    /**
     * Installs a Fabric or Quilt version profile JSON into `<versions>/<id>/<id>.json`
     * without writing to `launcher_profiles.json`.
     */
    suspend fun installFabricOrQuiltProfile(
        modLoaderType: ModLoaderType,
        gameVersion: String,
        loaderVersion: String
    ): GameVersionEntity {
        val utils = when (modLoaderType) {
            ModLoaderType.FABRIC -> FabriclikeUtils.FABRIC_UTILS
            ModLoaderType.QUILT -> FabriclikeUtils.QUILT_UTILS
            else -> error("Unsupported Fabriclike loader type: $modLoaderType")
        }
        val jsonUrl = utils.createJsonDownloadUrl(gameVersion, loaderVersion)
        val stagedJson = File(storageManager.downloadStagingDir(), "${modLoaderType.name.lowercase()}_${gameVersion}_$loaderVersion.json")
        val task = downloadManager.enqueue(
            taskType = DownloadTaskType.MODLOADER,
            sourceUrl = jsonUrl,
            targetPath = stagedJson.absolutePath,
            versionId = null
        )
        val result = downloadManager.executeTask(task.taskId)
        check(result.status == DownloadStatus.COMPLETED && stagedJson.isFile) {
            "Mod loader download failed: ${result.errorMessage ?: result.status}"
        }

        val rawJson = stagedJson.readText()
        val rootObj = runCatching { org.json.JSONObject(rawJson) }.getOrElse { err ->
            stagedJson.delete()
            error("Invalid $modLoaderType profile JSON: ${err.message}")
        }
        val libs = rootObj.optJSONArray("libraries")
        val mainClass = rootObj.optString("mainClass", "").trim()
        check(mainClass.isNotBlank() && libs != null && libs.length() > 0) {
            "Incomplete $modLoaderType profile JSON: missing mainClass or libraries"
        }
        val parsed = VersionManager.parseManifestJson(rawJson, "$modLoaderType-$loaderVersion-$gameVersion")
        val targetJsonFile = File(storageManager.versionsRoot(), "${parsed.versionId}/${parsed.versionId}.json")
        targetJsonFile.parentFile?.mkdirs()
        val partFile = File(targetJsonFile.parentFile, "${targetJsonFile.name}.part")
        partFile.writeText(rawJson)
        if (!partFile.renameTo(targetJsonFile)) {
            targetJsonFile.writeText(rawJson)
            partFile.delete()
        }
        stagedJson.delete()

        val scanned = versionManager.scanInstalledVersions()
        return scanned.firstOrNull { it.versionId == parsed.versionId }
            ?: GameVersionEntity(
                versionId = parsed.versionId,
                inheritsFrom = parsed.inheritsFrom,
                versionType = parsed.versionType,
                modLoaderType = parsed.modLoaderType,
                modLoaderVersion = parsed.modLoaderVersion ?: loaderVersion,
                requiredJavaMajor = parsed.requiredJavaMajor,
                lwjglMajor = parsed.lwjglMajor,
                manifestPath = targetJsonFile.absolutePath,
                isInstalled = true,
                releaseTimeEpochMs = parsed.releaseTimeEpochMs
            ).also { gameVersionDao.upsert(it) }
    }

    fun fetchFabricOrQuiltLoaders(
        modLoaderType: ModLoaderType,
        gameVersion: String
    ): List<FabricLoaderOption> {
        val utils = when (modLoaderType) {
            ModLoaderType.FABRIC -> FabriclikeUtils.FABRIC_UTILS
            ModLoaderType.QUILT -> FabriclikeUtils.QUILT_UTILS
            else -> return emptyList()
        }
        val arr = utils.downloadLoaderVersions(gameVersion) ?: return emptyList()
        return arr.map { FabricLoaderOption(version = it.version, stable = it.stable) }
    }

    companion object {
        fun parseMojangManifestV2(
            jsonText: String,
            includeSnapshots: Boolean = false,
            limit: Int = 50,
            includeHistorical: Boolean = includeSnapshots
        ): List<RemoteVersionEntry> {
            val root = JSONObject(jsonText)
            val versions = root.optJSONArray("versions") ?: return emptyList()
            val out = mutableListOf<RemoteVersionEntry>()
            for (i in 0 until versions.length()) {
                val obj = versions.optJSONObject(i) ?: continue
                val id = obj.optString("id")
                val type = obj.optString("type", "release")
                val url = obj.optString("url")
                if (id.isBlank() || url.isBlank()) continue
                val includeType = when (type.lowercase()) {
                    "release" -> true
                    "snapshot" -> includeSnapshots
                    "old_beta", "old_alpha" -> includeHistorical
                    else -> includeSnapshots
                }
                if (!includeType) continue
                val releaseMs = runCatching {
                    Instant.parse(obj.optString("releaseTime")).toEpochMilli()
                }.getOrDefault(0L)
                out += RemoteVersionEntry(
                    versionId = id,
                    versionType = type,
                    url = url,
                    sha1 = obj.optString("sha1").ifBlank { null },
                    releaseTimeEpochMs = releaseMs
                )
                if (out.size >= limit) break
            }
            return out
        }

        internal fun inferJavaForRelease(versionId: String): Int {
            if (versionId == "latest-release" || versionId == "latest-snapshot") return 21
            val match = Regex("""1\.(\d+)(?:\.(\d+))?""").find(versionId) ?: return 8
            val minor = match.groupValues[1].toIntOrNull() ?: return 8
            val patch = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
            return when {
                minor <= 16 -> 8
                minor in 17..19 -> 17
                minor == 20 && patch <= 4 -> 17
                else -> 21
            }
        }
    }
}
