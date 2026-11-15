package com.star.launcher.data.db

import androidx.room.TypeConverter
import com.star.launcher.data.db.model.CompatibilityStatus
import com.star.launcher.data.db.model.CompatibilityTier
import com.star.launcher.data.db.model.DiagnosticLogLevel
import com.star.launcher.data.db.model.DownloadStatus
import com.star.launcher.data.db.model.DownloadTaskType
import com.star.launcher.data.db.model.ModLoaderType
import com.star.launcher.data.db.model.SessionStatus
import com.star.launcher.data.db.model.SettingValueType

/**
 * Enum type converters for [AetherDatabase].
 * Stored as explicit string names so enum reordering never corrupts persisted rows.
 */
class AetherConverters {
    @TypeConverter
    fun fromSettingValueType(v: SettingValueType): String = v.name
    @TypeConverter
    fun toSettingValueType(s: String): SettingValueType = SettingValueType.valueOf(s)

    @TypeConverter
    fun fromModLoaderType(v: ModLoaderType): String = v.name
    @TypeConverter
    fun toModLoaderType(s: String): ModLoaderType = ModLoaderType.valueOf(s)

    @TypeConverter
    fun fromDownloadTaskType(v: DownloadTaskType): String = v.name
    @TypeConverter
    fun toDownloadTaskType(s: String): DownloadTaskType = DownloadTaskType.valueOf(s)

    @TypeConverter
    fun fromDownloadStatus(v: DownloadStatus): String = v.name
    @TypeConverter
    fun toDownloadStatus(s: String): DownloadStatus = DownloadStatus.valueOf(s)

    @TypeConverter
    fun fromCompatibilityTier(v: CompatibilityTier): String = v.name
    @TypeConverter
    fun toCompatibilityTier(s: String): CompatibilityTier = CompatibilityTier.valueOf(s)

    @TypeConverter
    fun fromCompatibilityStatus(v: CompatibilityStatus): String = v.name
    @TypeConverter
    fun toCompatibilityStatus(s: String): CompatibilityStatus = CompatibilityStatus.valueOf(s)

    @TypeConverter
    fun fromSessionStatus(v: SessionStatus): String = v.name
    @TypeConverter
    fun toSessionStatus(s: String): SessionStatus = SessionStatus.valueOf(s)

    @TypeConverter
    fun fromDiagnosticLogLevel(v: DiagnosticLogLevel): String = v.name
    @TypeConverter
    fun toDiagnosticLogLevel(s: String): DiagnosticLogLevel = DiagnosticLogLevel.valueOf(s)
}
