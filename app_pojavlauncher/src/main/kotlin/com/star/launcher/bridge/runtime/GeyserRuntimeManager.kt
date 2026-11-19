package com.star.launcher.bridge.runtime

import com.star.launcher.bridge.model.GeyserReleaseInfo
import com.star.launcher.bridge.model.InstalledArtifactMetadata
import com.star.launcher.bridge.storage.BridgeStoragePaths

/**
 * Manages installation, verification, update checks, and rollback for `Geyser-Standalone.jar`.
 */
class GeyserRuntimeManager(
    private val paths: BridgeStoragePaths,
    private val versionManager: BridgeVersionManager,
    private val updateManager: BridgeUpdateManager
) {
    fun getInstalledMetadata(): InstalledArtifactMetadata? {
        val jar = paths.geyserJarFile()
        if (!jar.isFile) return null
        return updateManager.readMetadata(paths.geyserMetadataFile()) ?: InstalledArtifactMetadata(
            projectId = "geyser",
            version = "installed",
            buildNumber = 0,
            fileName = jar.name,
            sha256 = "",
            installedAtMs = jar.lastModified(),
            jarPath = jar.absolutePath
        )
    }

    fun isInstalled(): Boolean = paths.geyserJarFile().isFile

    suspend fun checkLatestRelease(): GeyserReleaseInfo =
        versionManager.fetchLatestGeyserStandalone()

    suspend fun installOrUpdateLatest(
        isGeyserRunning: () -> Boolean,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): ArtifactInstallResult {
        val release = versionManager.fetchLatestGeyserStandalone()
        return updateManager.installVerifiedArtifact(
            request = VerifiedArtifactRequest(
                projectId = "geyser",
                version = release.version,
                buildNumber = release.buildNumber,
                fileName = release.artifactName,
                downloadUrl = release.downloadUrl,
                expectedHash = release.sha256,
                hashAlgorithm = HashAlgorithm.SHA_256,
                targetJarFile = paths.geyserJarFile(),
                previousDir = paths.geyserPreviousDir(),
                metadataFile = paths.geyserMetadataFile()
            ),
            isRuntimeActive = isGeyserRunning,
            onProgress = onProgress
        )
    }

    fun rollbackToPrevious(isGeyserRunning: () -> Boolean): InstalledArtifactMetadata =
        updateManager.rollback(
            metadataFile = paths.geyserMetadataFile(),
            targetJarFile = paths.geyserJarFile(),
            isRuntimeActive = isGeyserRunning
        )
}
