package com.star.launcher.bridge.runtime

import com.star.launcher.bridge.logging.BridgeLogManager
import com.star.launcher.bridge.model.InstalledArtifactMetadata
import com.star.launcher.bridge.storage.BridgeStoragePaths
import com.star.launcher.download.HttpTransport
import com.star.launcher.download.UrlConnectionHttpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

enum class HashAlgorithm(val digestName: String) {
    SHA_256("SHA-256"),
    SHA_1("SHA-1")
}

data class VerifiedArtifactRequest(
    val projectId: String,
    val version: String,
    val buildNumber: Int,
    val fileName: String,
    val downloadUrl: String,
    val expectedHash: String,
    val hashAlgorithm: HashAlgorithm,
    val targetJarFile: File,
    val previousDir: File,
    val metadataFile: File
)

data class ArtifactInstallResult(
    val metadata: InstalledArtifactMetadata,
    val skippedDuplicateDownload: Boolean
)

/**
 * Handles verified downloads, checksum verification, duplicate-download avoidance,
 * resumable partial staging, previous-version preservation, and rollback.
 * Never replaces an active runtime while its process is running.
 */
class BridgeUpdateManager(
    private val paths: BridgeStoragePaths,
    private val logManager: BridgeLogManager,
    private val transport: HttpTransport = UrlConnectionHttpTransport(),
    private val clockMillis: () -> Long = { System.currentTimeMillis() }
) {
    suspend fun installVerifiedArtifact(
        request: VerifiedArtifactRequest,
        isRuntimeActive: () -> Boolean,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): ArtifactInstallResult = withContext(Dispatchers.IO) {
        if (isRuntimeActive()) {
            throw IllegalStateException(
                "Cannot modify ${request.projectId} while the runtime is active. Stop the running process first."
            )
        }

        val expectedNormalized = request.expectedHash.trim().lowercase()
        require(expectedNormalized.isNotBlank()) {
            "Expected ${request.hashAlgorithm.digestName} checksum is required for ${request.fileName}"
        }

        // Avoid duplicate downloads if the installed JAR already matches the verified checksum.
        if (request.targetJarFile.isFile) {
            val currentHash = computeFileHash(request.targetJarFile, request.hashAlgorithm)
            if (currentHash == expectedNormalized) {
                val existingMeta = readMetadata(request.metadataFile)
                val updatedMeta = existingMeta?.copy(
                    version = request.version,
                    buildNumber = request.buildNumber,
                    sha256 = currentHash
                ) ?: InstalledArtifactMetadata(
                    projectId = request.projectId,
                    version = request.version,
                    buildNumber = request.buildNumber,
                    fileName = request.fileName,
                    sha256 = currentHash,
                    installedAtMs = clockMillis(),
                    jarPath = request.targetJarFile.absolutePath
                )
                writeMetadata(request.metadataFile, updatedMeta)
                logManager.logInfo(
                    "UpdateManager",
                    "${request.projectId} ${request.version} (#${request.buildNumber}) already verified locally; skipped duplicate download"
                )
                return@withContext ArtifactInstallResult(updatedMeta, skippedDuplicateDownload = true)
            }
        }

        val stageFile = File(paths.stagingDir(), "${request.projectId}-${request.buildNumber}.part")
        logManager.logInfo("UpdateManager", "Downloading ${request.projectId} ${request.version} (#${request.buildNumber}) from ${request.downloadUrl}")

        try {
            transport.downloadToFile(request.downloadUrl, stageFile) { downloaded, total ->
                onProgress(downloaded, total)
            }

            if (isRuntimeActive()) {
                throw IllegalStateException("Runtime started during download; aborting swap for ${request.projectId}")
            }

            val actualHash = computeFileHash(stageFile, request.hashAlgorithm)
            if (actualHash != expectedNormalized) {
                stageFile.delete()
                throw IOException(
                    "Checksum mismatch for ${request.fileName} (${request.hashAlgorithm.digestName}): " +
                        "expected $expectedNormalized, got $actualHash"
                )
            }

            request.targetJarFile.parentFile?.mkdirs()
            request.previousDir.mkdirs()

            var previousVersion: String? = null
            var previousJarPath: String? = null
            if (request.targetJarFile.isFile) {
                val prevMeta = readMetadata(request.metadataFile)
                previousVersion = prevMeta?.version ?: "previous"
                val backupFile = File(request.previousDir, "${request.targetJarFile.name}.bak")
                request.targetJarFile.copyTo(backupFile, overwrite = true)
                previousJarPath = backupFile.absolutePath
            }

            if (!stageFile.renameTo(request.targetJarFile)) {
                stageFile.copyTo(request.targetJarFile, overwrite = true)
                stageFile.delete()
            }

            val newMeta = InstalledArtifactMetadata(
                projectId = request.projectId,
                version = request.version,
                buildNumber = request.buildNumber,
                fileName = request.fileName,
                sha256 = actualHash,
                installedAtMs = clockMillis(),
                jarPath = request.targetJarFile.absolutePath,
                previousVersion = previousVersion,
                previousJarPath = previousJarPath
            )
            writeMetadata(request.metadataFile, newMeta)
            ensureUpstreamLicensesFile()
            logManager.logInfo("UpdateManager", "Verified & installed ${request.projectId} ${request.version} (#${request.buildNumber})")
            ArtifactInstallResult(newMeta, skippedDuplicateDownload = false)
        } catch (t: Throwable) {
            logManager.logError("UpdateManager", "Failed installing ${request.projectId}", t)
            throw t
        }
    }

    fun rollback(
        metadataFile: File,
        targetJarFile: File,
        isRuntimeActive: () -> Boolean
    ): InstalledArtifactMetadata {
        if (isRuntimeActive()) {
            throw IllegalStateException("Cannot rollback while runtime is active. Stop the process first.")
        }
        val currentMeta = readMetadata(metadataFile)
            ?: throw IOException("No installation metadata found at ${metadataFile.absolutePath}")
        val prevPath = currentMeta.previousJarPath
            ?: throw IOException("No previous working version preserved for ${currentMeta.projectId}")
        val prevFile = File(prevPath)
        if (!prevFile.isFile) {
            throw IOException("Backup JAR missing at $prevPath")
        }

        val tempCurrent = File(paths.stagingDir(), "${currentMeta.projectId}.rollback_swap")
        if (targetJarFile.isFile) {
            targetJarFile.copyTo(tempCurrent, overwrite = true)
        }
        prevFile.copyTo(targetJarFile, overwrite = true)
        if (tempCurrent.isFile) {
            tempCurrent.copyTo(prevFile, overwrite = true)
            tempCurrent.delete()
        }

        val restoredSha = computeFileHash(targetJarFile, HashAlgorithm.SHA_256)
        val restored = currentMeta.copy(
            version = currentMeta.previousVersion ?: "restored",
            sha256 = restoredSha,
            installedAtMs = clockMillis(),
            previousVersion = currentMeta.version,
            previousJarPath = prevFile.absolutePath
        )
        writeMetadata(metadataFile, restored)
        logManager.logInfo("UpdateManager", "Rolled back ${currentMeta.projectId} to ${restored.version}")
        return restored
    }

    fun readMetadata(metadataFile: File): InstalledArtifactMetadata? {
        if (!metadataFile.isFile) return null
        return runCatching {
            val obj = JSONObject(metadataFile.readText(Charsets.UTF_8))
            InstalledArtifactMetadata(
                projectId = obj.getString("projectId"),
                version = obj.getString("version"),
                buildNumber = obj.optInt("buildNumber", 0),
                fileName = obj.getString("fileName"),
                sha256 = obj.getString("sha256"),
                installedAtMs = obj.optLong("installedAtMs", 0L),
                jarPath = obj.getString("jarPath"),
                previousVersion = obj.optString("previousVersion").takeIf { it.isNotBlank() },
                previousJarPath = obj.optString("previousJarPath").takeIf { it.isNotBlank() }
            )
        }.getOrNull()
    }

    fun writeMetadata(metadataFile: File, meta: InstalledArtifactMetadata) {
        metadataFile.parentFile?.mkdirs()
        val obj = JSONObject().apply {
            put("projectId", meta.projectId)
            put("version", meta.version)
            put("buildNumber", meta.buildNumber)
            put("fileName", meta.fileName)
            put("sha256", meta.sha256)
            put("installedAtMs", meta.installedAtMs)
            put("jarPath", meta.jarPath)
            put("previousVersion", meta.previousVersion ?: "")
            put("previousJarPath", meta.previousJarPath ?: "")
        }
        metadataFile.writeText(obj.toString(2), Charsets.UTF_8)
    }

    fun computeFileHash(file: File, algorithm: HashAlgorithm): String {
        val digest = MessageDigest.getInstance(algorithm.digestName)
        file.inputStream().use { input ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun ensureUpstreamLicensesFile() {
        val file = paths.licenseNoticeFile()
        if (file.isFile) return
        file.parentFile?.mkdirs()
        file.writeText(UPSTREAM_ATTRIBUTION_TEXT, Charsets.UTF_8)
    }

    companion object {
        val UPSTREAM_ATTRIBUTION_TEXT = """
            STAR Launcher Bedrock Bridge — Third-Party Upstream Components & Licenses

            1. GeyserMC (Geyser-Standalone)
               Source: https://github.com/GeyserMC/Geyser (Downloads: https://download.geysermc.org/)
               License: MIT License — Copyright (c) 2019-2026 GeyserMC Contributors

            2. Floodgate (floodgate-spigot)
               Source: https://github.com/GeyserMC/Floodgate (Downloads: https://download.geysermc.org/)
               License: MIT License — Copyright (c) 2019-2026 GeyserMC Contributors

            3. PaperMC (optional local Paper server)
               Source: https://papermc.io/ (Downloads: https://api.papermc.io/)
               License: GPLv3 / MIT components per PaperMC upstream terms

            4. Mojang Minecraft Server (optional local Vanilla server)
               Requires explicit acceptance of the Minecraft EULA (https://aka.ms/MinecraftEULA).
        """.trimIndent()
    }
}
