package com.star.launcher.version

import com.star.launcher.data.db.entity.GameVersionEntity
import com.star.launcher.data.db.model.CompatibilityTier
import com.star.launcher.data.db.model.ModLoaderType

/**
 * Release classification parsed from Mojang or local version manifests.
 */
enum class VersionReleaseType(val label: String) {
    RELEASE("Release"),
    SNAPSHOT("Snapshot"),
    OLD_BETA("Old Beta"),
    OLD_ALPHA("Old Alpha"),
    MODDED("Modded");

    companion object {
        fun fromManifestType(rawType: String?, modLoaderType: ModLoaderType): VersionReleaseType {
            if (modLoaderType != ModLoaderType.VANILLA && rawType.equals("modded", ignoreCase = true)) {
                return MODDED
            }
            return when (rawType?.lowercase()?.trim()) {
                "release" -> RELEASE
                "snapshot" -> SNAPSHOT
                "old_beta" -> OLD_BETA
                "old_alpha" -> OLD_ALPHA
                "modded" -> MODDED
                else -> if (modLoaderType != ModLoaderType.VANILLA) MODDED else RELEASE
            }
        }
    }
}

/**
 * Unified installation & availability states for a Minecraft version.
 */
enum class VersionState {
    NOT_INSTALLED,
    INSTALLING,
    INSTALLED,
    FAILED,
    UNSUPPORTED
}

/**
 * Real backend stages emitted sequentially during Minecraft version installation.
 */
enum class InstallationStage(
    val stepOrder: Int,
    val displayTitle: String
) {
    FETCHING_METADATA(1, "Fetching version metadata"),
    DOWNLOADING_CLIENT_JAR(2, "Downloading client JAR"),
    DOWNLOADING_LIBRARIES(3, "Downloading libraries"),
    DOWNLOADING_ASSETS(4, "Downloading assets"),
    PREPARING_NATIVES(5, "Preparing native libraries"),
    VALIDATING(6, "Validating installation"),
    COMPLETED(7, "Installation complete")
}

/**
 * Structured error categories covering all installation and launch validation failure modes.
 */
enum class InstallErrorCategory(
    val title: String
) {
    NETWORK_UNAVAILABLE("Network unavailable"),
    DOWNLOAD_INTERRUPTED("Download interrupted"),
    INSUFFICIENT_STORAGE("Insufficient storage"),
    MISSING_RUNTIME("Missing Java runtime"),
    INVALID_METADATA("Invalid version metadata"),
    CORRUPTED_FILES("Corrupted files detected"),
    MISSING_LIBRARIES("Missing required libraries"),
    UNSUPPORTED_VERSION("Unsupported version"),
    CANCELLED("Installation cancelled"),
    PROCESS_INTERRUPTED("Interrupted by app shutdown"),
    UNKNOWN("Installation failed")
}

/**
 * Live installation progress state observed by the Version Manager and Home screens.
 *
 * When [totalBytes] is null or non-positive, [progressPercent] MUST remain null so UI renders
 * an indeterminate progress indicator instead of inventing a percentage.
 */
data class InstallationProgress(
    val versionId: String,
    val stage: InstallationStage,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val progressPercent: Float? = null,
    val processedFiles: Int = 0,
    val totalFiles: Int = 0,
    val currentFile: String? = null,
    val errorMessage: String? = null,
    val errorCategory: InstallErrorCategory? = null,
    val diagnosticDetails: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val isFinished: Boolean = false,
    val isCancellable: Boolean = true
)

/**
 * Persisted installation & diagnostic metadata stored per version in AetherSettingsRepository.
 */
data class VersionInstallMetadata(
    val versionId: String,
    val installedAt: Long? = null,
    val diskSizeBytes: Long? = null,
    val downloadSizeBytes: Long? = null,
    val updateAvailable: Boolean = false,
    val remoteManifestSha1: String? = null,
    val installedManifestSha1: String? = null,
    val lastErrorMessage: String? = null,
    val lastErrorCategory: InstallErrorCategory? = null,
    val lastDiagnosticDetails: String? = null,
    val lastAttemptAt: Long? = null,
    val wasInterrupted: Boolean = false
)

enum class VersionFilter(val label: String) {
    ALL("All"),
    INSTALLED("Installed"),
    NOT_INSTALLED("Not Installed"),
    RELEASE("Release"),
    SNAPSHOT("Snapshot"),
    MODDED("Modded")
}

enum class VersionSort(val label: String) {
    NEWEST("Newest first"),
    OLDEST("Oldest first"),
    ALPHABETICAL("Alphabetical"),
    RECENTLY_PLAYED("Recently played")
}

/**
 * Rich UI/domain projection combining Room catalog state, live install progress,
 * verified installation status, loader metadata, runtime checks, and session history.
 */
data class VersionCatalogItem(
    val entity: GameVersionEntity,
    val state: VersionState,
    val progress: InstallationProgress? = null,
    val metadata: VersionInstallMetadata? = null,
    val loaderType: ModLoaderType? = null,
    val loaderLabel: String? = null,
    val unsupportedReason: String? = null,
    val updateAvailable: Boolean = false,
    val lastPlayedAt: Long? = null,
    val compatibilityTier: CompatibilityTier = CompatibilityTier.THEORETICAL
) {
    val versionId: String get() = entity.versionId
    val releaseType: VersionReleaseType
        get() = VersionReleaseType.fromManifestType(entity.versionType, entity.modLoaderType)
    val isInstalled: Boolean get() = state == VersionState.INSTALLED
    val isInstalling: Boolean get() = state == VersionState.INSTALLING
    val isFailed: Boolean get() = state == VersionState.FAILED
    val isUnsupported: Boolean get() = state == VersionState.UNSUPPORTED
    val isModded: Boolean get() = loaderType != null && loaderType != ModLoaderType.VANILLA
    val installedAt: Long? get() = metadata?.installedAt
    val diskSizeBytes: Long? get() = metadata?.diskSizeBytes
    val downloadSizeBytes: Long? get() = metadata?.downloadSizeBytes
    val canPlay: Boolean get() = state == VersionState.INSTALLED
    val canInstall: Boolean get() = state == VersionState.NOT_INSTALLED || state == VersionState.FAILED
    val errorMessage: String? get() = progress?.errorMessage ?: metadata?.lastErrorMessage
    val errorCategory: InstallErrorCategory? get() = progress?.errorCategory ?: metadata?.lastErrorCategory
    val diagnosticDetails: String? get() = progress?.diagnosticDetails ?: metadata?.lastDiagnosticDetails
}
