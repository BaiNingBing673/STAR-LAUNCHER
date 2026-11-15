package com.star.launcher.mods.compat

import com.star.launcher.data.db.model.ModLoaderType
import com.star.launcher.mods.api.CurseForgeFile
import com.star.launcher.mods.api.CurseForgeMod
import com.star.launcher.mods.api.CurseForgeModLoader
import com.star.launcher.mods.api.CurseForgeRelationType

enum class ModCompatibilityLevel(val label: String) {
    COMPATIBLE("Compatible"),
    POTENTIALLY_COMPATIBLE("Potentially Compatible"),
    INCOMPATIBLE("Incompatible"),
    UNKNOWN("Unknown")
}

data class ModCompatibilityAssessment(
    val level: ModCompatibilityLevel,
    val summary: String,
    val reasons: List<String>,
    val apiFacts: List<String>,
    val starInferences: List<String>,
    val canInstall: Boolean
)

/**
 * Evaluates mod file compatibility against a target [InstanceEnvironment].
 *
 * Guarantees:
 * - Never claims [ModCompatibilityLevel.COMPATIBLE] solely from a matching Minecraft version.
 * - Blocks installation (`canInstall = false`) when the instance loader is incompatible or Vanilla.
 * - Clearly separates CurseForge API metadata ([apiFacts]) from STAR Launcher deductions ([starInferences]).
 */
class ModCompatibilityEngine {

    fun evaluateFile(
        environment: InstanceEnvironment?,
        file: CurseForgeFile,
        mod: CurseForgeMod? = null,
        installedCurseForgeModIds: Set<Long> = emptySet()
    ): ModCompatibilityAssessment {
        val apiFacts = buildList {
            if (file.declaredMinecraftVersions.isNotEmpty()) {
                add("API game versions: ${file.declaredMinecraftVersions.joinToString(", ")}")
            } else {
                add("API game versions: none listed")
            }
            if (file.declaredLoaders.isNotEmpty()) {
                add("API loaders: ${file.declaredLoaders.joinToString(", ") { it.displayName }}")
            } else {
                add("API loaders: none listed on this file")
            }
            val reqCount = file.dependencies.count { it.relationType == CurseForgeRelationType.REQUIRED_DEPENDENCY }
            val optCount = file.dependencies.count { it.relationType == CurseForgeRelationType.OPTIONAL_DEPENDENCY }
            val incCount = file.dependencies.count { it.relationType == CurseForgeRelationType.INCOMPATIBLE }
            if (reqCount > 0 || optCount > 0 || incCount > 0) {
                add("API relations: $reqCount required, $optCount optional, $incCount incompatible")
            }
        }

        val starInferences = mutableListOf<String>()
        val reasons = mutableListOf<String>()

        if (environment == null) {
            return ModCompatibilityAssessment(
                level = ModCompatibilityLevel.UNKNOWN,
                summary = "No Minecraft instance is selected.",
                reasons = listOf("Select or create an instance to evaluate compatibility."),
                apiFacts = apiFacts,
                starInferences = listOf("No instance context available."),
                canInstall = false
            )
        }

        val targetMc = environment.baseMinecraftVersion
        if (targetMc.isNullOrBlank()) {
            return ModCompatibilityAssessment(
                level = ModCompatibilityLevel.UNKNOWN,
                summary = "Selected instance '${environment.instanceName}' has no Minecraft version.",
                reasons = listOf("Configure a Minecraft version for this instance before installing mods."),
                apiFacts = apiFacts,
                starInferences = listOf("Instance version could not be determined."),
                canInstall = false
            )
        }

        starInferences += when (environment.detectionSource) {
            EnvironmentDetectionSource.MANIFEST_VERIFIED ->
                "Instance manifest verified: MC $targetMc on ${environment.loaderType.name}${environment.loaderVersion?.let { " $it" }.orEmpty()}"
            EnvironmentDetectionSource.VERSION_ID_INFERRED ->
                "Inferred from instance versionId '${environment.rawVersionId}': MC $targetMc on ${environment.loaderType.name}"
            EnvironmentDetectionSource.UNKNOWN ->
                "Instance loader/version could not be verified."
        }

        // Check incompatible dependencies declared by the file
        val conflictingInstalled = file.dependencies
            .filter { it.relationType == CurseForgeRelationType.INCOMPATIBLE }
            .map { it.modId }
            .filter { it in installedCurseForgeModIds }
        if (conflictingInstalled.isNotEmpty()) {
            reasons += "Conflicts with installed CurseForge mod(s): ${conflictingInstalled.joinToString(", ")}"
            return ModCompatibilityAssessment(
                level = ModCompatibilityLevel.INCOMPATIBLE,
                summary = "Incompatible with an already installed mod in '${environment.instanceName}'.",
                reasons = reasons,
                apiFacts = apiFacts,
                starInferences = starInferences,
                canInstall = false
            )
        }

        // Loader check: Vanilla instances cannot run Forge/NeoForge/Fabric/Quilt mods
        if (!environment.isModdedLoader) {
            reasons += "Instance '${environment.instanceName}' uses ${environment.loaderType.name} (no mod loader detected)."
            return ModCompatibilityAssessment(
                level = ModCompatibilityLevel.INCOMPATIBLE,
                summary = "Incompatible loader: instance has no Forge, NeoForge, Fabric, or Quilt loader.",
                reasons = reasons,
                apiFacts = apiFacts,
                starInferences = starInferences,
                canInstall = false
            )
        }

        val instanceCfLoader = environment.curseForgeLoader
        val fileLoaders = file.declaredLoaders
        val loaderState = evaluateLoaderMatch(instanceCfLoader, fileLoaders, mod?.supportedLoadersFromLatest.orEmpty())
        val mcState = evaluateMinecraftVersionMatch(targetMc, file.declaredMinecraftVersions)

        if (loaderState == LoaderMatch.MISMATCH) {
            val expected = fileLoaders.joinToString(", ") { it.displayName }.ifBlank { "another loader" }
            reasons += "File targets $expected, but '${environment.instanceName}' runs ${instanceCfLoader.displayName}."
            return ModCompatibilityAssessment(
                level = ModCompatibilityLevel.INCOMPATIBLE,
                summary = "Incompatible mod loader ($expected vs ${instanceCfLoader.displayName}).",
                reasons = reasons,
                apiFacts = apiFacts,
                starInferences = starInferences,
                canInstall = false
            )
        }

        if (mcState == McMatch.MISMATCH) {
            reasons += "File supports ${file.declaredMinecraftVersions.joinToString(", ")}, which does not include $targetMc."
            return ModCompatibilityAssessment(
                level = ModCompatibilityLevel.INCOMPATIBLE,
                summary = "Incompatible Minecraft version (requires ${file.declaredMinecraftVersions.firstOrNull() ?: "other"}, instance is $targetMc).",
                reasons = reasons,
                apiFacts = apiFacts,
                starInferences = starInferences,
                canInstall = false
            )
        }

        val requiredDeps = file.dependencies.filter { it.relationType == CurseForgeRelationType.REQUIRED_DEPENDENCY }
        val missingRequired = requiredDeps.filter { it.modId !in installedCurseForgeModIds }
        if (missingRequired.isNotEmpty()) {
            reasons += "Requires ${missingRequired.size} dependency mod(s) that will need to be installed."
        }

        return when {
            loaderState == LoaderMatch.EXACT &&
                mcState == McMatch.EXACT &&
                environment.detectionSource == EnvironmentDetectionSource.MANIFEST_VERIFIED -> {
                reasons += "Matches MC $targetMc and ${instanceCfLoader.displayName}."
                ModCompatibilityAssessment(
                    level = ModCompatibilityLevel.COMPATIBLE,
                    summary = "Compatible with $targetMc (${instanceCfLoader.displayName}).",
                    reasons = reasons,
                    apiFacts = apiFacts,
                    starInferences = starInferences,
                    canInstall = true
                )
            }
            loaderState == LoaderMatch.UNKNOWN && mcState == McMatch.UNKNOWN -> {
                reasons += "File metadata does not declare Minecraft version or mod loader tags."
                ModCompatibilityAssessment(
                    level = ModCompatibilityLevel.UNKNOWN,
                    summary = "Compatibility unknown — file metadata is incomplete.",
                    reasons = reasons,
                    apiFacts = apiFacts,
                    starInferences = starInferences,
                    canInstall = true
                )
            }
            else -> {
                if (loaderState == LoaderMatch.QUILT_FABRIC_BRIDGE) {
                    reasons += "Fabric mod on Quilt instance — may work via Quilted Fabric API, not guaranteed."
                    starInferences += "Quilt compatibility with Fabric-tagged files is inferred as partial."
                }
                if (loaderState == LoaderMatch.UNKNOWN) {
                    reasons += "File omits explicit loader tag; cannot confirm ${instanceCfLoader.displayName} support from file metadata alone."
                }
                if (loaderState == LoaderMatch.PROJECT_INFERRED_ONLY) {
                    reasons += "Loader inferred from project summary rather than this specific file's tags."
                }
                if (mcState == McMatch.MINOR_FAMILY) {
                    reasons += "Matches Minecraft major.minor family for $targetMc, but exact patch version is not listed."
                    starInferences += "Patch-level compatibility ($targetMc) is inferred, not explicitly listed."
                }
                if (mcState == McMatch.UNKNOWN) {
                    reasons += "File omits explicit Minecraft version tags."
                }
                if (environment.detectionSource == EnvironmentDetectionSource.VERSION_ID_INFERRED) {
                    reasons += "Instance loader/version was inferred from version ID '${environment.rawVersionId}' (manifest not verified on disk)."
                }
                ModCompatibilityAssessment(
                    level = ModCompatibilityLevel.POTENTIALLY_COMPATIBLE,
                    summary = "Potentially compatible — review loader, version, and dependency details before launching.",
                    reasons = reasons,
                    apiFacts = apiFacts,
                    starInferences = starInferences,
                    canInstall = true
                )
            }
        }
    }

    /**
     * Evaluates project-level compatibility in Discover/Details using `latestFiles` (never claims
     * full compatibility without checking both MC version and loader).
     */
    fun evaluateModOverview(
        environment: InstanceEnvironment?,
        mod: CurseForgeMod,
        installedCurseForgeModIds: Set<Long> = emptySet()
    ): ModCompatibilityAssessment {
        if (environment == null || environment.baseMinecraftVersion.isNullOrBlank()) {
            return ModCompatibilityAssessment(
                level = ModCompatibilityLevel.UNKNOWN,
                summary = "Select an instance with a Minecraft version to evaluate compatibility.",
                reasons = emptyList(),
                apiFacts = listOf(
                    "API latest MC versions: ${mod.supportedMinecraftVersionsFromLatest.joinToString(", ").ifBlank { "none listed" }}",
                    "API latest loaders: ${mod.supportedLoadersFromLatest.joinToString(", ") { it.displayName }.ifBlank { "none listed" }}"
                ),
                starInferences = emptyList(),
                canInstall = false
            )
        }
        val candidates = mod.latestFiles.filter { !it.isServerPack }
        if (candidates.isEmpty()) {
            val loaderMatch = if (environment.isModdedLoader && environment.curseForgeLoader in mod.supportedLoadersFromLatest) {
                LoaderMatch.PROJECT_INFERRED_ONLY
            } else if (mod.supportedLoadersFromLatest.isEmpty()) {
                LoaderMatch.UNKNOWN
            } else {
                LoaderMatch.MISMATCH
            }
            val mcMatch = evaluateMinecraftVersionMatch(
                environment.baseMinecraftVersion,
                mod.supportedMinecraftVersionsFromLatest
            )
            return when {
                !environment.isModdedLoader -> ModCompatibilityAssessment(
                    level = ModCompatibilityLevel.INCOMPATIBLE,
                    summary = "Instance runs ${environment.loaderType.name}; a mod loader is required.",
                    reasons = listOf("Vanilla instances cannot load mods."),
                    apiFacts = emptyList(),
                    starInferences = listOf("No latestFiles returned in search payload."),
                    canInstall = false
                )
                loaderMatch == LoaderMatch.MISMATCH || mcMatch == McMatch.MISMATCH -> ModCompatibilityAssessment(
                    level = ModCompatibilityLevel.INCOMPATIBLE,
                    summary = "Latest indexed files do not match ${environment.baseMinecraftVersion} (${environment.curseForgeLoader.displayName}).",
                    reasons = listOf("Open Mod Details to check older file versions."),
                    apiFacts = emptyList(),
                    starInferences = emptyList(),
                    canInstall = false
                )
                else -> ModCompatibilityAssessment(
                    level = ModCompatibilityLevel.UNKNOWN,
                    summary = "Inspect Mod Details to verify compatible files.",
                    reasons = listOf("Search summary has limited file metadata."),
                    apiFacts = emptyList(),
                    starInferences = emptyList(),
                    canInstall = true
                )
            }
        }

        val evaluations = candidates.map { evaluateFile(environment, it, mod, installedCurseForgeModIds) }
        return evaluations.firstOrNull { it.level == ModCompatibilityLevel.COMPATIBLE }
            ?: evaluations.firstOrNull { it.level == ModCompatibilityLevel.POTENTIALLY_COMPATIBLE }
            ?: evaluations.firstOrNull { it.level == ModCompatibilityLevel.UNKNOWN }
            ?: evaluations.first()
    }

    private enum class LoaderMatch { EXACT, QUILT_FABRIC_BRIDGE, PROJECT_INFERRED_ONLY, UNKNOWN, MISMATCH }
    private enum class McMatch { EXACT, MINOR_FAMILY, UNKNOWN, MISMATCH }

    private fun evaluateLoaderMatch(
        instanceLoader: CurseForgeModLoader,
        fileLoaders: Set<CurseForgeModLoader>,
        projectLoaders: Set<CurseForgeModLoader>
    ): LoaderMatch {
        if (instanceLoader == CurseForgeModLoader.ANY) return LoaderMatch.MISMATCH
        if (fileLoaders.isEmpty()) {
            return if (instanceLoader in projectLoaders) {
                LoaderMatch.PROJECT_INFERRED_ONLY
            } else {
                LoaderMatch.UNKNOWN
            }
        }
        if (instanceLoader in fileLoaders) return LoaderMatch.EXACT
        if (instanceLoader == CurseForgeModLoader.QUILT && CurseForgeModLoader.FABRIC in fileLoaders) {
            return LoaderMatch.QUILT_FABRIC_BRIDGE
        }
        return LoaderMatch.MISMATCH
    }

    private fun evaluateMinecraftVersionMatch(
        targetMc: String,
        declaredMcVersions: List<String>
    ): McMatch {
        if (declaredMcVersions.isEmpty()) return McMatch.UNKNOWN
        if (declaredMcVersions.any { it.equals(targetMc, ignoreCase = true) }) return McMatch.EXACT
        val targetFamily = majorMinorOf(targetMc)
        if (targetFamily != null && declaredMcVersions.any { majorMinorOf(it) == targetFamily }) {
            return McMatch.MINOR_FAMILY
        }
        return McMatch.MISMATCH
    }

    private fun majorMinorOf(version: String): String? {
        val m = Regex("""^(\d+\.\d+)""").find(version.trim()) ?: return null
        return m.groupValues[1]
    }
}
