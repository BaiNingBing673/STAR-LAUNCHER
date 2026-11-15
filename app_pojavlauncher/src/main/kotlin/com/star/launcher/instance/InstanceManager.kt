package com.star.launcher.instance

import com.star.launcher.compat.CompatibilityEvaluator
import com.star.launcher.compat.CompatibilityTarget
import com.star.launcher.compat.DeviceCapabilitiesSnapshot
import com.star.launcher.compat.DeviceCapabilityProbe
import com.star.launcher.compat.TieredCompatibilityReport
import com.star.launcher.core.AetherStorageManager
import com.star.launcher.data.db.dao.DeviceInfoDao
import com.star.launcher.data.db.dao.GameInstanceDao
import com.star.launcher.data.db.dao.GameVersionDao
import com.star.launcher.data.db.dao.RendererProfileDao
import com.star.launcher.data.db.dao.RuntimeRegistryDao
import com.star.launcher.data.db.entity.GameInstanceEntity
import com.star.launcher.data.db.entity.GameVersionEntity
import com.star.launcher.data.db.entity.RendererProfileEntity
import com.star.launcher.data.db.entity.RuntimeRegistryEntity
import com.star.launcher.data.db.model.CompatibilityStatus
import com.star.launcher.data.db.model.ModLoaderType
import com.star.launcher.diagnostics.DiagnosticsManager
import com.star.launcher.engine.adapter.EngineLaunchSpec
import com.star.launcher.engine.adapter.InputProfileSpec
import com.star.launcher.engine.adapter.NativeEngineAdapter
import com.star.launcher.engine.adapter.RendererSpec
import com.star.launcher.engine.adapter.RuntimeDescriptor
import com.star.launcher.engine.adapter.mapRendererSpec
import com.star.launcher.version.VersionCatalogService
import kotlinx.coroutines.flow.Flow
import net.kdt.pojavlaunch.Architecture
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile
import java.io.File
import java.util.UUID

sealed interface LaunchPreparationResult {
    data class Ready(
        val sessionId: String,
        val instanceName: String,
        val useAngle: Boolean,
        val spec: EngineLaunchSpec,
        val compatibility: TieredCompatibilityReport
    ) : LaunchPreparationResult

    data class Blocked(
        val reasons: List<String>,
        val compatibility: TieredCompatibilityReport? = null
    ) : LaunchPreparationResult
}

/**
 * Coordinates isolated instance directories, non-destructive legacy profile discovery,
 * compatibility pre-flight checks, and session initialization.
 */
class InstanceManager(
    private val gameInstanceDao: GameInstanceDao,
    private val gameVersionDao: GameVersionDao,
    private val runtimeRegistryDao: RuntimeRegistryDao,
    private val rendererProfileDao: RendererProfileDao,
    private val deviceInfoDao: DeviceInfoDao,
    private val storageManager: AetherStorageManager,
    private val capabilityProbe: DeviceCapabilityProbe,
    private val compatibilityEvaluator: CompatibilityEvaluator,
    private val nativeEngineAdapter: NativeEngineAdapter,
    private val diagnosticsManager: DiagnosticsManager,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: () -> String = { UUID.randomUUID().toString() }
) {
    fun observeInstances(): Flow<List<GameInstanceEntity>> = gameInstanceDao.observeAll()

    fun observeRuntimes(): Flow<List<RuntimeRegistryEntity>> = runtimeRegistryDao.observeAll()

    fun observeRendererProfiles(): Flow<List<RendererProfileEntity>> = rendererProfileDao.observeAll()

    suspend fun getInstance(instanceId: String): GameInstanceEntity? = gameInstanceDao.getById(instanceId)

    /**
     * Synchronizes discovered upstream JREs and compatible GPU renderers into Room
     * without overwriting user-customized renderer flags.
     */
    suspend fun syncRuntimesAndRenderers(
        runtimes: List<RuntimeDescriptor>,
        renderers: List<RendererSpec>,
        runtimeInstallRoot: String = "${Tools.DIR_DATA}/runtimes"
    ) {
        val now = clockMillis()
        val validIds = runtimes.filter { it.majorJavaVersion > 0 }.map { it.runtimeId }.toSet()
        for (existing in runtimeRegistryDao.getAll()) {
            if (existing.runtimeId !in validIds) {
                runtimeRegistryDao.deleteById(existing.runtimeId)
            }
        }
        for (rt in runtimes) {
            if (rt.majorJavaVersion <= 0) continue
            runtimeRegistryDao.upsert(
                RuntimeRegistryEntity(
                    runtimeId = rt.runtimeId,
                    displayName = rt.displayName,
                    majorVersion = rt.majorJavaVersion,
                    arch = rt.arch,
                    installPath = "$runtimeInstallRoot/${AetherStorageManager.sanitizeSegment(rt.runtimeId)}",
                    binaryVersion = rt.versionString,
                    isInternal = rt.isInternal,
                    isVerified = true,
                    installedAt = now
                )
            )
        }
        renderers.forEachIndexed { idx, spec ->
            val profileId = rendererProfileIdFor(spec.rendererId)
            val existing = rendererProfileDao.getById(profileId)
            if (existing == null) {
                rendererProfileDao.upsert(
                    RendererProfileEntity(
                        profileId = profileId,
                        rendererId = spec.rendererId,
                        displayName = spec.displayName,
                        backendConfigJson = "{}",
                        useAngle = false,
                        preferSystemDriver = false,
                        isDefault = idx == 0,
                        updatedAt = now
                    )
                )
            }
        }
    }

    suspend fun updateInstanceConfig(
        instanceId: String,
        name: String,
        versionId: String?,
        runtimeId: String?,
        rendererProfileId: String?,
        allocatedRamMb: Int,
        customJvmArgs: String?,
        controlMapPath: String?
    ): GameInstanceEntity {
        val existing = requireNotNull(gameInstanceDao.getById(instanceId)) { "Unknown instance: $instanceId" }
        val cleanName = name.trim().ifBlank { existing.name }
        val capabilities = probeAndPersistDevice()
        val version = resolveOrRegisterVersion(versionId)
        val runtime = runtimeId?.let { runtimeRegistryDao.getById(it) }
        val profile = rendererProfileId?.let { rendererProfileDao.getById(it) }

        val compatReport = compatibilityEvaluator.evaluate(
            capabilities = capabilities,
            target = CompatibilityTarget(
                instanceId = instanceId,
                versionId = version?.versionId ?: "",
                requiredJavaMajor = version?.requiredJavaMajor ?: 8,
                rendererId = profile?.rendererId ?: "opengles_mobileglues",
                modLoaderType = version?.modLoaderType ?: ModLoaderType.VANILLA,
                selectedRuntimeId = runtime?.runtimeId,
                selectedRuntimeJavaMajor = runtime?.majorVersion,
                persistedVersionId = version?.versionId
            )
        )

        val updated = existing.copy(
            name = cleanName,
            versionId = version?.versionId,
            runtimeId = runtime?.runtimeId,
            rendererProfileId = profile?.profileId,
            allocatedRamMb = allocatedRamMb.coerceAtLeast(MIN_RAM_MB),
            customJvmArgs = customJvmArgs?.takeIf { it.isNotBlank() },
            controlMapPath = controlMapPath?.takeIf { it.isNotBlank() }
        )
        gameInstanceDao.upsertInstanceWithCompatibility(updated, compatReport.theoretical)
        return updated
    }

    suspend fun createInstance(
        name: String,
        versionId: String?,
        runtimeId: String?,
        rendererProfileId: String?,
        allocatedRamMb: Int = DEFAULT_RAM_MB,
        customJvmArgs: String? = null,
        controlMapPath: String? = null
    ): GameInstanceEntity {
        val cleanName = name.trim().ifBlank { "New Instance" }
        val instanceId = idGenerator()
        val gameDir = storageManager.ensureInstanceDirectories(instanceId)
        val now = clockMillis()

        val capabilities = probeAndPersistDevice()
        val version = resolveOrRegisterVersion(versionId)
        val runtime = runtimeId?.let { runtimeRegistryDao.getById(it) }
        val profile = rendererProfileId?.let { rendererProfileDao.getById(it) }

        val compatReport = compatibilityEvaluator.evaluate(
            capabilities = capabilities,
            target = CompatibilityTarget(
                instanceId = instanceId,
                versionId = version?.versionId ?: "",
                requiredJavaMajor = version?.requiredJavaMajor ?: 8,
                rendererId = profile?.rendererId ?: "opengles_mobileglues",
                modLoaderType = version?.modLoaderType ?: ModLoaderType.VANILLA,
                selectedRuntimeId = runtimeId,
                selectedRuntimeJavaMajor = runtime?.majorVersion,
                persistedVersionId = version?.versionId
            )
        )

        val instance = GameInstanceEntity(
            instanceId = instanceId,
            name = cleanName,
            gameDirPath = gameDir.absolutePath,
            versionId = version?.versionId,
            runtimeId = runtime?.runtimeId,
            rendererProfileId = profile?.profileId,
            allocatedRamMb = allocatedRamMb.coerceAtLeast(MIN_RAM_MB),
            customJvmArgs = customJvmArgs?.takeIf { it.isNotBlank() },
            controlMapPath = controlMapPath?.takeIf { it.isNotBlank() },
            lastPlayedAt = null,
            createdAt = now
        )
        gameInstanceDao.upsertInstanceWithCompatibility(instance, compatReport.theoretical)
        return instance
    }

    /**
     * Read-only registration of legacy `launcher_profiles.json` entries into Room.
     * Never modifies or deletes `launcher_profiles.json` or existing directories.
     */
    suspend fun importLegacyProfilesReadOnly(
        legacyProfiles: Map<String, MinecraftProfile>,
        defaultLegacyGameDir: String = "${Tools.DIR_GAME_HOME}/.minecraft"
    ): Int {
        var importedCount = 0
        val now = clockMillis()
        for ((key, prof) in legacyProfiles) {
            val instanceId = "legacy_${AetherStorageManager.sanitizeSegment(key)}"
            if (gameInstanceDao.getById(instanceId) != null) continue
            val resolvedDir = prof.gameDir?.takeIf { it.isNotBlank() } ?: defaultLegacyGameDir
            // Avoid violating unique index on gameDirPath when multiple legacy profiles share `.minecraft`.
            val uniqueDir = if (importedCount == 0 && resolvedDir == defaultLegacyGameDir) {
                resolvedDir
            } else {
                "$resolvedDir#legacy_$key"
            }
            val version = resolveOrRegisterVersion(prof.lastVersionId)
            val entity = GameInstanceEntity(
                instanceId = instanceId,
                name = prof.name?.takeIf { it.isNotBlank() } ?: "Legacy ($key)",
                gameDirPath = uniqueDir,
                versionId = version?.versionId,
                runtimeId = null,
                rendererProfileId = null,
                allocatedRamMb = DEFAULT_RAM_MB,
                customJvmArgs = prof.javaArgs?.takeIf { it.isNotBlank() },
                controlMapPath = prof.controlFile?.takeIf { it.isNotBlank() },
                lastPlayedAt = null,
                createdAt = now
            )
            val result = runCatching { gameInstanceDao.insertStrict(entity) }
            if (result.isSuccess) importedCount++
        }
        return importedCount
    }

    suspend fun prepareLaunch(
        instanceId: String,
        allowWarningsOrExperimental: Boolean = true
    ): LaunchPreparationResult {
        val instance = gameInstanceDao.getById(instanceId)
            ?: return LaunchPreparationResult.Blocked(listOf("Instance '$instanceId' not found"))

        val resolvedVersionId = instance.versionId?.trim()?.takeIf { isValidVersionId(it) }
        val version = resolvedVersionId?.let { gameVersionDao.getById(it) }
        val requiredJava = version?.requiredJavaMajor
            ?: resolvedVersionId?.let { VersionCatalogService.inferJavaForRelease(it) }
            ?: 8
        val capabilities = probeAndPersistDevice()
        val deviceArchId = expectedDeviceArchId(capabilities)

        val requestedRuntime = instance.runtimeId?.let { runtimeRegistryDao.getById(it) }
        val isRequestedUsable = requestedRuntime != null &&
            requestedRuntime.isVerified &&
            requestedRuntime.majorVersion >= requiredJava &&
            isRuntimeArchCompatibleForDevice(requestedRuntime.arch, deviceArchId)

        val selectedRuntimeEntity = if (isRequestedUsable) {
            requestedRuntime
        } else {
            runtimeRegistryDao.getAll()
                .filter {
                    it.isVerified &&
                        it.majorVersion >= requiredJava &&
                        isRuntimeArchCompatibleForDevice(it.arch, deviceArchId)
                }
                .minByOrNull { it.majorVersion }
        }

        val profile = instance.rendererProfileId?.let { rendererProfileDao.getById(it) }
        val rendererId = profile?.rendererId ?: "opengles_mobileglues"
        val rendererSpec: RendererSpec = mapRendererSpec(rendererId, profile?.displayName ?: rendererId)

        val runtimeDesc = if (selectedRuntimeEntity != null) {
            RuntimeDescriptor(
                runtimeId = selectedRuntimeEntity.runtimeId,
                displayName = selectedRuntimeEntity.displayName,
                majorJavaVersion = selectedRuntimeEntity.majorVersion,
                arch = selectedRuntimeEntity.arch,
                versionString = selectedRuntimeEntity.binaryVersion,
                isInternal = selectedRuntimeEntity.isInternal
            )
        } else {
            RuntimeDescriptor(
                runtimeId = "",
                displayName = "Missing Java $requiredJava+",
                majorJavaVersion = requestedRuntime?.majorVersion ?: 0,
                arch = requestedRuntime?.arch ?: capabilities.cpuAbiPrimary,
                versionString = requestedRuntime?.binaryVersion,
                isInternal = false
            )
        }

        val compat = compatibilityEvaluator.evaluate(
            capabilities = capabilities,
            target = CompatibilityTarget(
                instanceId = instance.instanceId,
                versionId = resolvedVersionId ?: "",
                requiredJavaMajor = requiredJava,
                rendererId = rendererId,
                modLoaderType = version?.modLoaderType ?: ModLoaderType.VANILLA,
                selectedRuntimeId = selectedRuntimeEntity?.runtimeId,
                selectedRuntimeJavaMajor = selectedRuntimeEntity?.majorVersion ?: requestedRuntime?.majorVersion,
                persistedVersionId = version?.versionId
            )
        )

        val spec = EngineLaunchSpec(
            instanceId = instance.instanceId,
            versionId = resolvedVersionId ?: "",
            gameDirPath = instance.gameDirPath,
            runtime = runtimeDesc,
            renderer = rendererSpec,
            inputProfile = InputProfileSpec(controlMapPath = instance.controlMapPath),
            allocatedRamMb = instance.allocatedRamMb,
            customJvmArgs = instance.customJvmArgs
        )

        val adapterIssues = nativeEngineAdapter.validateLaunchSpec(spec)
        val hardCompatBlock = compat.theoretical.status == CompatibilityStatus.FAIL ||
            compat.theoretical.status == CompatibilityStatus.UNSUPPORTED
        val softCompatBlock = !allowWarningsOrExperimental &&
            (compat.theoretical.status == CompatibilityStatus.WARN ||
                compat.theoretical.status == CompatibilityStatus.EXPERIMENTAL)

        val blockReasons = buildList {
            if (resolvedVersionId == null) {
                add(
                    "Instance '${instance.name}' has no Minecraft version selected. " +
                        "Open Library -> Instance Settings -> Target Version ID to choose a version (e.g. 1.21.4)."
                )
            }
            if (selectedRuntimeEntity == null) {
                if (requestedRuntime != null && !isRuntimeArchCompatibleForDevice(requestedRuntime.arch, deviceArchId)) {
                    add(
                        "Runtime '${requestedRuntime.displayName}' (${requestedRuntime.arch}) is incompatible " +
                            "with device ABI ${capabilities.cpuAbiPrimary}. Install a Java $requiredJava+ runtime " +
                            "for ${capabilities.cpuAbiPrimary} in Settings -> Java Runtimes."
                    )
                } else {
                    add(
                        "No compatible Java $requiredJava+ runtime is installed for ${capabilities.cpuAbiPrimary}. " +
                            "Open Settings -> Java Runtimes to download External-$requiredJava or import a compatible runtime."
                    )
                }
            }
            addAll(adapterIssues)
            if (hardCompatBlock || softCompatBlock) {
                add(compat.theoretical.summaryReason)
            }
        }.distinct()
        if (blockReasons.isNotEmpty()) {
            return LaunchPreparationResult.Blocked(blockReasons, compat)
        }

        val sessionId = diagnosticsManager.startSession(
            instanceId = instance.instanceId,
            versionId = instance.versionId,
            runtimeId = selectedRuntimeEntity?.runtimeId,
            rendererProfileId = instance.rendererProfileId,
            deviceFingerprint = capabilities.deviceFingerprint,
            startupSummary = "Preparing launch instance=${instance.name} version=${spec.versionId} renderer=$rendererId runtime=${selectedRuntimeEntity?.runtimeId ?: "none"}"
        )
        gameInstanceDao.update(instance.copy(lastPlayedAt = clockMillis()))
        return LaunchPreparationResult.Ready(
            sessionId = sessionId,
            instanceName = instance.name,
            useAngle = profile?.useAngle ?: false,
            spec = spec,
            compatibility = compat
        )
    }

    /**
     * Deletes the Room instance record; deletes disk files ONLY inside STAR-owned `aether_instances/`.
     */
    suspend fun deleteInstance(instanceId: String, deleteAetherOwnedFiles: Boolean = false) {
        val existing = gameInstanceDao.getById(instanceId)
        gameInstanceDao.deleteById(instanceId)
        if (deleteAetherOwnedFiles && existing != null) {
            val aetherRoot = storageManager.instancesRoot().canonicalPath
            val target = File(existing.gameDirPath).parentFile
            if (target != null && target.canonicalPath.startsWith(aetherRoot) && target.canonicalPath != aetherRoot) {
                target.deleteRecursively()
            }
        }
    }

    private suspend fun probeAndPersistDevice(): DeviceCapabilitiesSnapshot {
        val snapshot = capabilityProbe.probe()
        deviceInfoDao.upsert(snapshot.toDeviceInfoEntity())
        return snapshot
    }

    private suspend fun resolveOrRegisterVersion(rawVersionId: String?): GameVersionEntity? {
        val cleanId = rawVersionId?.trim()?.takeIf { isValidVersionId(it) } ?: return null
        gameVersionDao.getById(cleanId)?.let { return it }

        val versionType = when {
            cleanId == MinecraftProfile.LATEST_SNAPSHOT -> "snapshot"
            else -> "release"
        }
        val stub = GameVersionEntity(
            versionId = cleanId,
            inheritsFrom = null,
            versionType = versionType,
            modLoaderType = ModLoaderType.VANILLA,
            modLoaderVersion = null,
            requiredJavaMajor = VersionCatalogService.inferJavaForRelease(cleanId),
            lwjglMajor = 3,
            manifestPath = "",
            isInstalled = false,
            releaseTimeEpochMs = 0L
        )
        gameVersionDao.upsert(stub)
        return stub
    }

    private fun expectedDeviceArchId(capabilities: DeviceCapabilitiesSnapshot): Int {
        val primary = Architecture.archAsInt(capabilities.cpuAbiPrimary)
        if (primary != Architecture.UNSUPPORTED_ARCH) return primary
        for (abi in capabilities.supportedAbis) {
            val mapped = Architecture.archAsInt(abi)
            if (mapped != Architecture.UNSUPPORTED_ARCH) return mapped
        }
        return if (capabilities.is64Bit) Architecture.ARCH_ARM64 else Architecture.ARCH_ARM
    }

    private fun isRuntimeArchCompatibleForDevice(runtimeArch: String, deviceArchId: Int): Boolean {
        val rtArch = Architecture.archAsInt(runtimeArch)
        return rtArch != Architecture.UNSUPPORTED_ARCH && rtArch == deviceArchId
    }

    companion object {
        const val MIN_RAM_MB = 256
        const val DEFAULT_RAM_MB = 2048

        fun isValidVersionId(versionId: String?): Boolean {
            val trimmed = versionId?.trim().orEmpty()
            return trimmed.isNotBlank() && !trimmed.equals("unknown", ignoreCase = true)
        }

        fun rendererProfileIdFor(rendererId: String): String =
            "profile_${AetherStorageManager.sanitizeSegment(rendererId)}"
    }
}
