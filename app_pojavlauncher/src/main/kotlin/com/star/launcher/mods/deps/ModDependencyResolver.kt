package com.star.launcher.mods.deps

import com.star.launcher.mods.api.CurseForgeFile
import com.star.launcher.mods.api.CurseForgeMod
import com.star.launcher.mods.api.CurseForgeModLoader
import com.star.launcher.mods.api.CurseForgeRelationType
import com.star.launcher.mods.api.CurseForgeRepository
import com.star.launcher.mods.compat.InstanceEnvironment
import com.star.launcher.mods.compat.ModCompatibilityAssessment
import com.star.launcher.mods.compat.ModCompatibilityEngine
import com.star.launcher.mods.compat.ModCompatibilityLevel

data class PlannedModInstallItem(
    val mod: CurseForgeMod?,
    val file: CurseForgeFile,
    val isRootMod: Boolean,
    val compatibility: ModCompatibilityAssessment
)

data class OptionalDependencyNotice(
    val modId: Long,
    val modName: String?,
    val websiteUrl: String?
)

data class UnresolvedDependencyNotice(
    val modId: Long,
    val modName: String?,
    val reason: String
)

data class ModInstallPlan(
    val rootItem: PlannedModInstallItem,
    val requiredDependencies: List<PlannedModInstallItem>,
    val optionalDependencies: List<OptionalDependencyNotice>,
    val alreadyInstalledDependencies: List<Long>,
    val unresolvedDependencies: List<UnresolvedDependencyNotice>,
    val conflicts: List<String>,
    val detectedCycles: List<String>,
    val targetInstanceId: String,
    val targetInstanceName: String,
    val targetModsDirPath: String
) {
    val filesToDownload: List<PlannedModInstallItem>
        get() = buildList {
            addAll(requiredDependencies)
            add(rootItem)
        }.distinctBy { it.file.id }

    val totalDownloadBytes: Long
        get() = filesToDownload.sumOf { it.file.fileLengthBytes.coerceAtLeast(0L) }

    val canProceed: Boolean
        get() = rootItem.compatibility.canInstall &&
            unresolvedDependencies.isEmpty() &&
            conflicts.isEmpty() &&
            requiredDependencies.all { it.compatibility.canInstall }
}

/**
 * Resolves CurseForge file dependencies using breadth-first traversal:
 * - Separates required vs optional dependencies (never auto-installs optional dependencies).
 * - Detects and stops dependency cycles cleanly.
 * - Detects already-installed dependencies and prevents duplicate downloads.
 * - Flags incompatible dependencies against both installed mods and the planned graph.
 */
class ModDependencyResolver(
    private val repository: CurseForgeRepository,
    private val compatibilityEngine: ModCompatibilityEngine = ModCompatibilityEngine()
) {
    suspend fun buildInstallPlan(
        rootMod: CurseForgeMod,
        rootFile: CurseForgeFile,
        environment: InstanceEnvironment,
        installedCurseForgeModIds: Set<Long>,
        installedFileNames: Set<String>
    ): ModInstallPlan {
        val rootCompat = compatibilityEngine.evaluateFile(
            environment = environment,
            file = rootFile,
            mod = rootMod,
            installedCurseForgeModIds = installedCurseForgeModIds
        )
        val rootPlanItem = PlannedModInstallItem(
            mod = rootMod,
            file = rootFile,
            isRootMod = true,
            compatibility = rootCompat
        )

        val modsDirPath = java.io.File(environment.gameDirPath, "mods").absolutePath
        if (!rootCompat.canInstall) {
            return ModInstallPlan(
                rootItem = rootPlanItem,
                requiredDependencies = emptyList(),
                optionalDependencies = emptyList(),
                alreadyInstalledDependencies = emptyList(),
                unresolvedDependencies = emptyList(),
                conflicts = emptyList(),
                detectedCycles = emptyList(),
                targetInstanceId = environment.instanceId,
                targetInstanceName = environment.instanceName,
                targetModsDirPath = modsDirPath
            )
        }

        val requiredItems = mutableListOf<PlannedModInstallItem>()
        val optionalNotices = LinkedHashMap<Long, OptionalDependencyNotice>()
        val alreadyInstalled = linkedSetOf<Long>()
        val unresolved = mutableListOf<UnresolvedDependencyNotice>()
        val conflicts = mutableListOf<String>()
        val detectedCycles = mutableListOf<String>()

        // Track active ancestry for true cycle detection + visited set to avoid duplicate downloads
        val resolvedModIds = linkedSetOf(rootMod.id)
        val incompatibleModIds = linkedSetOf<Long>()
        val queue = ArrayDeque<Pair<CurseForgeFile, List<Long>>>()
        queue.addLast(rootFile to listOf(rootMod.id))

        val modMetadataCache = HashMap<Long, CurseForgeMod?>()
        modMetadataCache[rootMod.id] = rootMod

        while (queue.isNotEmpty()) {
            val (currentFile, path) = queue.removeFirst()

            for (dep in currentFile.dependencies) {
                when (dep.relationType) {
                    CurseForgeRelationType.INCOMPATIBLE -> {
                        incompatibleModIds.add(dep.modId)
                        if (dep.modId in installedCurseForgeModIds) {
                            conflicts += "Incompatible with already-installed mod ID ${dep.modId}."
                        }
                    }
                    CurseForgeRelationType.OPTIONAL_DEPENDENCY -> {
                        if (dep.modId !in optionalNotices && dep.modId !in installedCurseForgeModIds) {
                            val info = fetchModCached(dep.modId, modMetadataCache)
                            optionalNotices[dep.modId] = OptionalDependencyNotice(
                                modId = dep.modId,
                                modName = info?.name,
                                websiteUrl = info?.links?.websiteUrl
                            )
                        }
                    }
                    CurseForgeRelationType.REQUIRED_DEPENDENCY -> {
                        if (dep.modId in path) {
                            val cycleChain = (path + dep.modId).joinToString(" -> ")
                            detectedCycles += "Dependency cycle detected: $cycleChain"
                            continue
                        }
                        if (dep.modId in installedCurseForgeModIds) {
                            alreadyInstalled.add(dep.modId)
                            continue
                        }
                        if (dep.modId in resolvedModIds) {
                            // Already scheduled in this install plan — prevent duplicate downloads
                            continue
                        }

                        val depMod = fetchModCached(dep.modId, modMetadataCache)
                        if (depMod == null) {
                            unresolved += UnresolvedDependencyNotice(
                                modId = dep.modId,
                                modName = null,
                                reason = "Could not load metadata for required dependency mod ${dep.modId}."
                            )
                            continue
                        }

                        if (depMod.allowModDistribution == false) {
                            unresolved += UnresolvedDependencyNotice(
                                modId = dep.modId,
                                modName = depMod.name,
                                reason = "Required dependency '${depMod.name}' restricts third-party API distribution."
                            )
                            continue
                        }

                        val bestFile = selectBestCompatibleFile(
                            modId = dep.modId,
                            mod = depMod,
                            environment = environment
                        )
                        if (bestFile == null) {
                            unresolved += UnresolvedDependencyNotice(
                                modId = dep.modId,
                                modName = depMod.name,
                                reason = "No compatible file found for '${depMod.name}' on MC ${environment.baseMinecraftVersion ?: "?"} (${environment.curseForgeLoader.displayName})."
                            )
                            continue
                        }

                        val depCompat = compatibilityEngine.evaluateFile(
                            environment = environment,
                            file = bestFile,
                            mod = depMod,
                            installedCurseForgeModIds = installedCurseForgeModIds + resolvedModIds
                        )
                        if (!depCompat.canInstall) {
                            unresolved += UnresolvedDependencyNotice(
                                modId = dep.modId,
                                modName = depMod.name,
                                reason = depCompat.summary
                            )
                            continue
                        }

                        if (bestFile.fileName in installedFileNames || "${bestFile.fileName}.disabled" in installedFileNames) {
                            alreadyInstalled.add(dep.modId)
                            continue
                        }

                        resolvedModIds.add(dep.modId)
                        requiredItems += PlannedModInstallItem(
                            mod = depMod,
                            file = bestFile,
                            isRootMod = false,
                            compatibility = depCompat
                        )
                        queue.addLast(bestFile to (path + dep.modId))
                    }
                    CurseForgeRelationType.EMBEDDED_LIBRARY,
                    CurseForgeRelationType.INCLUDE,
                    CurseForgeRelationType.TOOL -> Unit
                }
            }
        }

        // Cross-check if any planned item is incompatible with another planned item
        for (planned in listOf(rootPlanItem) + requiredItems) {
            val pid = planned.mod?.id ?: planned.file.modId
            if (pid in incompatibleModIds) {
                conflicts += "Dependency '${planned.mod?.name ?: pid}' is marked incompatible in the dependency graph."
            }
        }

        return ModInstallPlan(
            rootItem = rootPlanItem,
            requiredDependencies = requiredItems,
            optionalDependencies = optionalNotices.values.toList(),
            alreadyInstalledDependencies = alreadyInstalled.toList(),
            unresolvedDependencies = unresolved,
            conflicts = conflicts.distinct(),
            detectedCycles = detectedCycles.distinct(),
            targetInstanceId = environment.instanceId,
            targetInstanceName = environment.instanceName,
            targetModsDirPath = modsDirPath
        )
    }

    private suspend fun fetchModCached(
        modId: Long,
        cache: MutableMap<Long, CurseForgeMod?>
    ): CurseForgeMod? {
        if (cache.containsKey(modId)) return cache[modId]
        val fetched = repository.getMod(modId).getOrNull()
        cache[modId] = fetched
        return fetched
    }

    private suspend fun selectBestCompatibleFile(
        modId: Long,
        mod: CurseForgeMod,
        environment: InstanceEnvironment
    ): CurseForgeFile? {
        val loader = if (environment.curseForgeLoader == CurseForgeModLoader.QUILT) {
            // Try Quilt first, then fallback to ALL to inspect Fabric-tagged files if needed
            CurseForgeModLoader.QUILT
        } else {
            environment.curseForgeLoader
        }

        val candidates = repository.getModFiles(
            modId = modId,
            gameVersion = environment.baseMinecraftVersion,
            modLoader = loader,
            index = 0,
            pageSize = 30
        ).getOrNull()?.files.orEmpty().ifEmpty {
            if (environment.curseForgeLoader == CurseForgeModLoader.QUILT) {
                repository.getModFiles(
                    modId = modId,
                    gameVersion = environment.baseMinecraftVersion,
                    modLoader = CurseForgeModLoader.FABRIC,
                    index = 0,
                    pageSize = 30
                ).getOrNull()?.files.orEmpty()
            } else {
                mod.latestFiles.filter { !it.isServerPack }
            }
        }

        val scored = candidates
            .filter { it.isAvailable && !it.isServerPack }
            .map { file -> file to compatibilityEngine.evaluateFile(environment, file, mod) }
            .filter { (_, compat) -> compat.canInstall }
            .sortedWith(
                compareBy<Pair<CurseForgeFile, ModCompatibilityAssessment>> {
                    when (it.second.level) {
                        ModCompatibilityLevel.COMPATIBLE -> 0
                        ModCompatibilityLevel.POTENTIALLY_COMPATIBLE -> 1
                        ModCompatibilityLevel.UNKNOWN -> 2
                        ModCompatibilityLevel.INCOMPATIBLE -> 3
                    }
                }.thenBy { it.first.releaseType.apiId }
                    .thenByDescending { it.first.id }
            )

        return scored.firstOrNull()?.first
    }
}
