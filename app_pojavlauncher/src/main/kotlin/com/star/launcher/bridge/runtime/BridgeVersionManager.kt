package com.star.launcher.bridge.runtime

import com.star.launcher.bridge.model.GeyserReleaseInfo
import com.star.launcher.bridge.model.PaperBuildInfo
import com.star.launcher.bridge.model.VanillaServerInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

interface JsonHttpFetcher {
    suspend fun fetchText(url: String): String
}

class UrlConnectionJsonHttpFetcher(
    private val connectTimeoutMs: Int = 12_000,
    private val readTimeoutMs: Int = 15_000
) : JsonHttpFetcher {
    override suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "STARLauncher-BedrockBridge/1.0")
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IOException("HTTP $code from $url: ${body.take(240)}")
            }
            body
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * Queries official upstream release metadata from GeyserMC Downloads API v2, PaperMC API v2,
 * and Mojang Piston Meta without relying on hardcoded version tables or third-party mirrors.
 */
class BridgeVersionManager(
    private val fetcher: JsonHttpFetcher = UrlConnectionJsonHttpFetcher()
) {
    suspend fun fetchLatestGeyserStandalone(): GeyserReleaseInfo {
        val raw = fetcher.fetchText(GEYSER_LATEST_BUILD_URL)
        return parseGeyserBuildJson(raw, projectId = "geyser", downloadKey = "standalone")
    }

    suspend fun fetchLatestFloodgateSpigot(): GeyserReleaseInfo {
        val raw = fetcher.fetchText(FLOODGATE_LATEST_BUILD_URL)
        return parseGeyserBuildJson(raw, projectId = "floodgate", downloadKey = "spigot")
    }

    suspend fun fetchAvailableGeyserVersions(): List<String> {
        val raw = fetcher.fetchText(GEYSER_PROJECT_URL)
        val obj = JSONObject(raw)
        val arr = obj.optJSONArray("versions") ?: return emptyList()
        return List(arr.length()) { idx -> arr.getString(idx) }
    }

    suspend fun fetchLatestPaperBuild(mcVersion: String): PaperBuildInfo {
        val version = mcVersion.trim()
        val latestUrl = "$PAPER_FILL_V3_PROJECT_URL/versions/$version/builds/latest"
        val raw = fetcher.fetchText(latestUrl)
        return parsePaperFillV3BuildJson(version, raw)
    }

    suspend fun fetchSupportedPaperVersions(): List<String> {
        val raw = fetcher.fetchText(PAPER_FILL_V3_PROJECT_URL)
        val obj = JSONObject(raw)
        val versionsObj = obj.optJSONObject("versions") ?: return emptyList()
        val result = mutableListOf<String>()
        val keys = versionsObj.keys()
        while (keys.hasNext()) {
            val group = keys.next()
            val arr = versionsObj.optJSONArray(group) ?: continue
            for (i in 0 until arr.length()) {
                result += arr.getString(i)
            }
        }
        return result
    }

    suspend fun fetchVanillaServerDownload(mcVersion: String): VanillaServerInfo {
        val manifestRaw = fetcher.fetchText(MOJANG_VERSION_MANIFEST_V2_URL)
        val manifest = JSONObject(manifestRaw)
        val versions = manifest.getJSONArray("versions")
        var versionMetaUrl: String? = null
        for (i in 0 until versions.length()) {
            val item = versions.getJSONObject(i)
            if (item.getString("id") == mcVersion.trim()) {
                versionMetaUrl = item.getString("url")
                break
            }
        }
        val resolvedMetaUrl = versionMetaUrl
            ?: throw IOException("Minecraft version '$mcVersion' was not found in Mojang version_manifest_v2.json")
        val versionJson = JSONObject(fetcher.fetchText(resolvedMetaUrl))
        val downloads = versionJson.getJSONObject("downloads")
        val server = downloads.optJSONObject("server")
            ?: throw IOException("Minecraft '$mcVersion' does not publish an official dedicated server JAR")
        return VanillaServerInfo(
            minecraftVersion = mcVersion.trim(),
            downloadUrl = server.getString("url"),
            sha1 = server.getString("sha1"),
            sizeBytes = server.optLong("size", 0L)
        )
    }

    fun parseGeyserBuildJson(
        rawJson: String,
        projectId: String,
        downloadKey: String
    ): GeyserReleaseInfo {
        val root = JSONObject(rawJson)
        val version = root.getString("version")
        val build = root.getInt("build")
        val channel = root.optString("channel", "default")
        val time = root.optString("time").takeIf { it.isNotBlank() }
        val downloads = root.getJSONObject("downloads")
        val artifact = downloads.optJSONObject(downloadKey)
            ?: throw IOException("Artifact '$downloadKey' missing in $projectId build $build")
        val name = artifact.getString("name")
        val sha256 = artifact.getString("sha256")
        val url = "https://download.geysermc.org/v2/projects/$projectId/versions/$version/builds/$build/downloads/$downloadKey"
        return GeyserReleaseInfo(
            projectId = projectId,
            version = version,
            buildNumber = build,
            channel = channel,
            artifactName = name,
            downloadUrl = url,
            sha256 = sha256,
            publishedAt = time
        )
    }

    fun parsePaperFillV3BuildJson(mcVersion: String, rawJson: String): PaperBuildInfo {
        val trimmed = rawJson.trim()
        val buildObj = if (trimmed.startsWith("[")) {
            val arr = JSONArray(trimmed)
            if (arr.length() == 0) {
                throw IOException("No PaperMC builds published for Minecraft $mcVersion")
            }
            // Fill v3 /builds returns newest build first (index 0)
            arr.getJSONObject(0)
        } else {
            JSONObject(trimmed)
        }
        val buildId = buildObj.getInt("id")
        val downloads = buildObj.getJSONObject("downloads")
        val serverDefault = downloads.optJSONObject("server:default")
            ?: downloads.keys().asSequence().firstOrNull()?.let { downloads.optJSONObject(it) }
            ?: throw IOException("PaperMC Fill v3 build #$buildId has no server download entry")
        val fileName = serverDefault.getString("name")
        val sha256 = serverDefault.getJSONObject("checksums").getString("sha256")
        val downloadUrl = serverDefault.getString("url")
        return PaperBuildInfo(
            minecraftVersion = mcVersion,
            buildNumber = buildId,
            fileName = fileName,
            downloadUrl = downloadUrl,
            sha256 = sha256
        )
    }

    companion object {
        const val GEYSER_PROJECT_URL = "https://download.geysermc.org/v2/projects/geyser"
        const val GEYSER_LATEST_BUILD_URL = "https://download.geysermc.org/v2/projects/geyser/versions/latest/builds/latest"
        const val FLOODGATE_LATEST_BUILD_URL = "https://download.geysermc.org/v2/projects/floodgate/versions/latest/builds/latest"
        const val PAPER_FILL_V3_PROJECT_URL = "https://fill.papermc.io/v3/projects/paper"
        const val MOJANG_VERSION_MANIFEST_V2_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
    }
}
