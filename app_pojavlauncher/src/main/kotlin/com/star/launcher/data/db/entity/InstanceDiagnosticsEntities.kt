package com.star.launcher.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.star.launcher.data.db.model.CompatibilityStatus
import com.star.launcher.data.db.model.CompatibilityTier
import com.star.launcher.data.db.model.DiagnosticLogLevel
import com.star.launcher.data.db.model.DownloadStatus
import com.star.launcher.data.db.model.DownloadTaskType
import com.star.launcher.data.db.model.ModLoaderType
import com.star.launcher.data.db.model.SessionStatus

@Entity(
    tableName = "game_instances",
    foreignKeys = [
        ForeignKey(
            entity = GameVersionEntity::class,
            parentColumns = ["versionId"],
            childColumns = ["versionId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = RuntimeRegistryEntity::class,
            parentColumns = ["runtimeId"],
            childColumns = ["runtimeId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = RendererProfileEntity::class,
            parentColumns = ["profileId"],
            childColumns = ["rendererProfileId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["gameDirPath"], unique = true),
        Index(value = ["versionId"]),
        Index(value = ["runtimeId"]),
        Index(value = ["rendererProfileId"]),
        Index(value = ["lastPlayedAt"])
    ]
)
data class GameInstanceEntity(
    @PrimaryKey val instanceId: String,
    val name: String,
    val gameDirPath: String,
    val versionId: String?,
    val runtimeId: String?,
    val rendererProfileId: String?,
    val allocatedRamMb: Int,
    val customJvmArgs: String?,
    val controlMapPath: String?,
    val lastPlayedAt: Long?,
    val createdAt: Long
)

@Entity(
    tableName = "download_tasks",
    foreignKeys = [
        ForeignKey(
            entity = GameInstanceEntity::class,
            parentColumns = ["instanceId"],
            childColumns = ["instanceId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = GameVersionEntity::class,
            parentColumns = ["versionId"],
            childColumns = ["versionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["status", "updatedAt"]),
        Index(value = ["instanceId"]),
        Index(value = ["versionId"])
    ]
)
data class DownloadTaskEntity(
    @PrimaryKey val taskId: String,
    val instanceId: String?,
    val versionId: String?,
    val taskType: DownloadTaskType,
    val sourceUrl: String,
    val targetPath: String,
    val expectedSha1: String?,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val status: DownloadStatus,
    val retryCount: Int,
    val errorMessage: String?,
    val updatedAt: Long
)

@Entity(
    tableName = "compatibility_results",
    foreignKeys = [
        ForeignKey(
            entity = DeviceInfoEntity::class,
            parentColumns = ["deviceFingerprint"],
            childColumns = ["deviceFingerprint"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = GameInstanceEntity::class,
            parentColumns = ["instanceId"],
            childColumns = ["instanceId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = GameVersionEntity::class,
            parentColumns = ["versionId"],
            childColumns = ["versionId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = RuntimeRegistryEntity::class,
            parentColumns = ["runtimeId"],
            childColumns = ["runtimeId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["deviceFingerprint", "tier"]),
        Index(value = ["instanceId", "evaluatedAt"]),
        Index(value = ["versionId"]),
        Index(value = ["runtimeId"])
    ]
)
data class CompatibilityResultEntity(
    @PrimaryKey val resultId: String,
    val deviceFingerprint: String,
    val instanceId: String?,
    val versionId: String?,
    val rendererId: String,
    val runtimeId: String?,
    val tier: CompatibilityTier,
    val status: CompatibilityStatus,
    val summaryReason: String,
    val detailsJson: String,
    val evaluatedAt: Long
)

@Entity(
    tableName = "game_sessions",
    foreignKeys = [
        ForeignKey(
            entity = GameInstanceEntity::class,
            parentColumns = ["instanceId"],
            childColumns = ["instanceId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = GameVersionEntity::class,
            parentColumns = ["versionId"],
            childColumns = ["versionId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = RuntimeRegistryEntity::class,
            parentColumns = ["runtimeId"],
            childColumns = ["runtimeId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = RendererProfileEntity::class,
            parentColumns = ["profileId"],
            childColumns = ["rendererProfileId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = DeviceInfoEntity::class,
            parentColumns = ["deviceFingerprint"],
            childColumns = ["deviceFingerprint"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["instanceId", "startedAt"]),
        Index(value = ["status"]),
        Index(value = ["versionId"]),
        Index(value = ["runtimeId"]),
        Index(value = ["rendererProfileId"]),
        Index(value = ["deviceFingerprint"])
    ]
)
data class GameSessionEntity(
    @PrimaryKey val sessionId: String,
    val instanceId: String,
    val versionId: String?,
    val runtimeId: String?,
    val rendererProfileId: String?,
    val deviceFingerprint: String?,
    val status: SessionStatus,
    val exitCode: Int?,
    val startedAt: Long,
    val endedAt: Long?,
    val crashReportPath: String?
)

@Entity(
    tableName = "diagnostic_logs",
    foreignKeys = [
        ForeignKey(
            entity = GameSessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["sessionId", "timestampMs"]),
        Index(value = ["sessionId", "level"])
    ]
)
data class DiagnosticLogEntity(
    @PrimaryKey(autoGenerate = true) val logId: Long = 0,
    val sessionId: String,
    val timestampMs: Long,
    val level: DiagnosticLogLevel,
    val sourceTag: String,
    val messageRedacted: String
)

@Entity(
    tableName = "installed_mods",
    foreignKeys = [
        ForeignKey(
            entity = GameInstanceEntity::class,
            parentColumns = ["instanceId"],
            childColumns = ["instanceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["instanceId", "fileName"], unique = true),
        Index(value = ["instanceId", "curseForgeModId"]),
        Index(value = ["installedAt"])
    ]
)
data class InstalledModEntity(
    @PrimaryKey val recordId: String,
    val instanceId: String,
    val fileName: String,
    val curseForgeModId: Long?,
    val curseForgeFileId: Long?,
    val displayName: String,
    val installedVersionLabel: String?,
    val mcVersionHint: String?,
    val loaderType: ModLoaderType,
    val sha1: String?,
    val fileSizeBytes: Long,
    val allowModDistribution: Boolean?,
    val websiteUrl: String?,
    val iconUrl: String?,
    val installedAt: Long
)
