package com.star.launcher.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.star.launcher.data.db.model.ModLoaderType
import com.star.launcher.data.db.model.SettingValueType

/**
 * Non-secret launcher preferences. Never store authentication tokens or credentials here.
 */
@Entity(
    tableName = "app_settings",
    indices = [Index(value = ["updatedAt"])]
)
data class AppSettingEntity(
    @PrimaryKey val key: String,
    val value: String,
    val valueType: SettingValueType,
    val updatedAt: Long
)

/**
 * Hardware and driver capability snapshot probed on the local device.
 */
@Entity(
    tableName = "device_info",
    indices = [Index(value = ["probedAt"])]
)
data class DeviceInfoEntity(
    @PrimaryKey val deviceFingerprint: String,
    val cpuAbiPrimary: String,
    val supportedAbisJson: String,
    val is64Bit: Boolean,
    val androidApiLevel: Int,
    val gpuVendor: String,
    val gpuRenderer: String,
    val glVersion: String,
    val glesMajorVersion: Int,
    val hasVulkan: Boolean,
    val vulkanVersion: String?,
    val totalRamMb: Long,
    val probedAt: Long
)

/**
 * Registered JRE installations (internal engine JRE-8 or user/downloaded JRE 8/17/21/25).
 */
@Entity(
    tableName = "runtime_registry",
    indices = [
        Index(value = ["majorVersion", "arch"]),
        Index(value = ["installPath"], unique = true)
    ]
)
data class RuntimeRegistryEntity(
    @PrimaryKey val runtimeId: String,
    val displayName: String,
    val majorVersion: Int,
    val arch: String,
    val installPath: String,
    val binaryVersion: String?,
    val isInternal: Boolean,
    val isVerified: Boolean,
    val installedAt: Long
)

/**
 * Renderer backend presets and driver configuration (MobileGlues, GL4ES, Zink, ANGLE).
 */
@Entity(
    tableName = "renderer_profiles",
    indices = [
        Index(value = ["rendererId"]),
        Index(value = ["isDefault"])
    ]
)
data class RendererProfileEntity(
    @PrimaryKey val profileId: String,
    val rendererId: String,
    val displayName: String,
    val backendConfigJson: String,
    val useAngle: Boolean,
    val preferSystemDriver: Boolean,
    val isDefault: Boolean,
    val updatedAt: Long
)

/**
 * Cataloged Minecraft versions and mod-loader metadata.
 */
@Entity(
    tableName = "game_versions",
    indices = [
        Index(value = ["inheritsFrom"]),
        Index(value = ["modLoaderType", "isInstalled"])
    ]
)
data class GameVersionEntity(
    @PrimaryKey val versionId: String,
    val inheritsFrom: String?,
    val versionType: String,
    val modLoaderType: ModLoaderType,
    val modLoaderVersion: String?,
    val requiredJavaMajor: Int,
    val lwjglMajor: Int,
    val manifestPath: String,
    val isInstalled: Boolean,
    val releaseTimeEpochMs: Long
)
