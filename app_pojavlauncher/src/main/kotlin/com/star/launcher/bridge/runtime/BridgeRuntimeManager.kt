package com.star.launcher.bridge.runtime

import com.star.launcher.bridge.model.InstalledArtifactMetadata
import com.star.launcher.bridge.model.LocalServerSoftware
import com.star.launcher.bridge.model.RuntimeCheckDetails
import com.star.launcher.bridge.storage.BridgeStoragePaths

/**
 * Coordinates bridge Java runtime resolution and local server artifact installation
 * (PaperMC or Vanilla Mojang server JAR) independently of the client Minecraft runtime.
 */
class BridgeRuntimeManager(
    private val paths: BridgeStoragePaths,
    private val runtimeValidator: RuntimeValidator,
    private val versionManager: BridgeVersionManager,
    private val updateManager: BridgeUpdateManager
) {
    fun validateGeyserRuntime(
        preferredRuntimeName: String?,
        memoryLimitMb: Int
    ): RuntimeCheckDetails = runtimeValidator.validateForGeyser(
        preferredRuntimeName = preferredRuntimeName,
        requiredMemoryMb = memoryLimitMb
    )

    fun validateLocalServerRuntime(
        minecraftVersion: String,
        preferredRuntimeName: String?,
        memoryLimitMb: Int
    ): RuntimeCheckDetails = runtimeValidator.validateForLocalServer(
        minecraftVersion = minecraftVersion,
        preferredRuntimeName = preferredRuntimeName,
        requiredMemoryMb = memoryLimitMb
    )

    fun isLocalServerJarInstalled(): Boolean = paths.serverJarFile().isFile

    fun getInstalledServerMetadata(): InstalledArtifactMetadata? {
        val jar = paths.serverJarFile()
        if (!jar.isFile) return null
        return updateManager.readMetadata(paths.serverMetadataFile()) ?: InstalledArtifactMetadata(
            projectId = "local-server",
            version = "installed",
            buildNumber = 0,
            fileName = jar.name,
            sha256 = "",
            installedAtMs = jar.lastModified(),
            jarPath = jar.absolutePath
        )
    }

    suspend fun installOrUpdateLocalServer(
        software: LocalServerSoftware,
        minecraftVersion: String,
        isServerRunning: () -> Boolean,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): ArtifactInstallResult = when (software) {
        LocalServerSoftware.PAPER -> {
            val paper = versionManager.fetchLatestPaperBuild(minecraftVersion)
            updateManager.installVerifiedArtifact(
                request = VerifiedArtifactRequest(
                    projectId = "paper",
                    version = paper.minecraftVersion,
                    buildNumber = paper.buildNumber,
                    fileName = paper.fileName,
                    downloadUrl = paper.downloadUrl,
                    expectedHash = paper.sha256,
                    hashAlgorithm = HashAlgorithm.SHA_256,
                    targetJarFile = paths.serverJarFile(),
                    previousDir = paths.serverPreviousDir(),
                    metadataFile = paths.serverMetadataFile()
                ),
                isRuntimeActive = isServerRunning,
                onProgress = onProgress
            )
        }
        LocalServerSoftware.VANILLA -> {
            val vanilla = versionManager.fetchVanillaServerDownload(minecraftVersion)
            updateManager.installVerifiedArtifact(
                request = VerifiedArtifactRequest(
                    projectId = "vanilla-server",
                    version = vanilla.minecraftVersion,
                    buildNumber = 0,
                    fileName = "server-${vanilla.minecraftVersion}.jar",
                    downloadUrl = vanilla.downloadUrl,
                    expectedHash = vanilla.sha1,
                    hashAlgorithm = HashAlgorithm.SHA_1,
                    targetJarFile = paths.serverJarFile(),
                    previousDir = paths.serverPreviousDir(),
                    metadataFile = paths.serverMetadataFile()
                ),
                isRuntimeActive = isServerRunning,
                onProgress = onProgress
            )
        }
    }

    fun rollbackLocalServer(isServerRunning: () -> Boolean): InstalledArtifactMetadata =
        updateManager.rollback(
            metadataFile = paths.serverMetadataFile(),
            targetJarFile = paths.serverJarFile(),
            isRuntimeActive = isServerRunning
        )
}
