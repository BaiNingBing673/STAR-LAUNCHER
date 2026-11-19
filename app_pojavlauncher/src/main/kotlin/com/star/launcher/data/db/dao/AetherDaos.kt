package com.star.launcher.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.star.launcher.data.db.entity.AppSettingEntity
import com.star.launcher.data.db.entity.CompatibilityResultEntity
import com.star.launcher.data.db.entity.DeviceInfoEntity
import com.star.launcher.data.db.entity.DiagnosticLogEntity
import com.star.launcher.data.db.entity.DownloadTaskEntity
import com.star.launcher.data.db.entity.GameInstanceEntity
import com.star.launcher.data.db.entity.GameSessionEntity
import com.star.launcher.data.db.entity.GameVersionEntity
import com.star.launcher.data.db.entity.InstalledModEntity
import com.star.launcher.data.db.entity.RendererProfileEntity
import com.star.launcher.data.db.entity.RuntimeRegistryEntity
import com.star.launcher.data.db.model.CompatibilityTier
import com.star.launcher.data.db.model.DownloadStatus
import com.star.launcher.data.db.model.SessionStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface SettingsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(setting: AppSettingEntity)

    @Query("SELECT * FROM app_settings WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): AppSettingEntity?

    @Query("SELECT * FROM app_settings ORDER BY `key` ASC")
    fun observeAll(): Flow<List<AppSettingEntity>>

    @Query("DELETE FROM app_settings WHERE `key` = :key")
    suspend fun delete(key: String)
}

@Dao
interface DeviceInfoDao {
    @Upsert
    suspend fun upsert(deviceInfo: DeviceInfoEntity)

    @Query("SELECT * FROM device_info WHERE deviceFingerprint = :fingerprint LIMIT 1")
    suspend fun getByFingerprint(fingerprint: String): DeviceInfoEntity?

    @Query("SELECT * FROM device_info ORDER BY probedAt DESC LIMIT 1")
    suspend fun getLatest(): DeviceInfoEntity?
}

@Dao
interface RuntimeRegistryDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertStrict(runtime: RuntimeRegistryEntity)

    @Upsert
    suspend fun upsert(runtime: RuntimeRegistryEntity)

    @Query("SELECT * FROM runtime_registry ORDER BY majorVersion DESC, displayName ASC")
    fun observeAll(): Flow<List<RuntimeRegistryEntity>>

    @Query("SELECT * FROM runtime_registry ORDER BY majorVersion ASC, displayName ASC")
    suspend fun getAll(): List<RuntimeRegistryEntity>

    @Query("SELECT * FROM runtime_registry WHERE runtimeId = :runtimeId LIMIT 1")
    suspend fun getById(runtimeId: String): RuntimeRegistryEntity?

    @Query("DELETE FROM runtime_registry WHERE runtimeId = :runtimeId")
    suspend fun deleteById(runtimeId: String)
}

@Dao
interface RendererProfileDao {
    @Upsert
    suspend fun upsert(profile: RendererProfileEntity)

    @Query("SELECT * FROM renderer_profiles ORDER BY isDefault DESC, displayName ASC")
    fun observeAll(): Flow<List<RendererProfileEntity>>

    @Query("SELECT * FROM renderer_profiles WHERE profileId = :profileId LIMIT 1")
    suspend fun getById(profileId: String): RendererProfileEntity?

    @Query("UPDATE renderer_profiles SET isDefault = 0 WHERE profileId != :profileId AND isDefault = 1")
    suspend fun clearOtherDefaults(profileId: String)

    @Query("UPDATE renderer_profiles SET isDefault = 1 WHERE profileId = :profileId")
    suspend fun markDefault(profileId: String)

    @Transaction
    suspend fun setDefaultProfile(profileId: String) {
        clearOtherDefaults(profileId)
        markDefault(profileId)
    }
}

@Dao
interface GameVersionDao {
    @Upsert
    suspend fun upsert(version: GameVersionEntity)

    @Upsert
    suspend fun upsertAll(versions: List<GameVersionEntity>)

    @Query("SELECT * FROM game_versions WHERE versionId = :versionId LIMIT 1")
    suspend fun getById(versionId: String): GameVersionEntity?

    @Query("SELECT * FROM game_versions ORDER BY releaseTimeEpochMs DESC")
    fun observeAll(): Flow<List<GameVersionEntity>>
}

@Dao
interface GameInstanceDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertStrict(instance: GameInstanceEntity)

    @Update
    suspend fun update(instance: GameInstanceEntity)

    @Upsert
    suspend fun upsert(instance: GameInstanceEntity)

    @Upsert
    suspend fun insertCompatibility(result: CompatibilityResultEntity)

    @Transaction
    suspend fun upsertInstanceWithCompatibility(
        instance: GameInstanceEntity,
        initialCompatibility: CompatibilityResultEntity
    ) {
        upsert(instance)
        insertCompatibility(initialCompatibility)
    }

    @Query("SELECT * FROM game_instances WHERE instanceId = :instanceId LIMIT 1")
    suspend fun getById(instanceId: String): GameInstanceEntity?

    @Query("SELECT * FROM game_instances ORDER BY COALESCE(lastPlayedAt, createdAt) DESC")
    fun observeAll(): Flow<List<GameInstanceEntity>>

    @Query("DELETE FROM game_instances WHERE instanceId = :instanceId")
    suspend fun deleteById(instanceId: String)
}

@Dao
interface DownloadTaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: DownloadTaskEntity)

    @Query("SELECT * FROM download_tasks WHERE taskId = :taskId LIMIT 1")
    suspend fun getById(taskId: String): DownloadTaskEntity?

    @Query("SELECT * FROM download_tasks WHERE status IN (:statuses) ORDER BY updatedAt DESC")
    fun observeByStatus(statuses: List<DownloadStatus>): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE instanceId = :instanceId ORDER BY updatedAt DESC")
    suspend fun getForInstance(instanceId: String): List<DownloadTaskEntity>
}

@Dao
interface CompatibilityResultDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(result: CompatibilityResultEntity)

    @Query(
        "SELECT * FROM compatibility_results WHERE deviceFingerprint = :fingerprint AND tier = :tier ORDER BY evaluatedAt DESC"
    )
    suspend fun getByDeviceAndTier(
        fingerprint: String,
        tier: CompatibilityTier
    ): List<CompatibilityResultEntity>

    @Query("SELECT * FROM compatibility_results WHERE instanceId = :instanceId ORDER BY evaluatedAt DESC")
    fun observeForInstance(instanceId: String): Flow<List<CompatibilityResultEntity>>
}

@Dao
interface DiagnosticSessionDao {
    @Upsert
    suspend fun upsertSession(session: GameSessionEntity)

    @Insert
    suspend fun insertLog(log: DiagnosticLogEntity): Long

    @Insert
    suspend fun insertLogs(logs: List<DiagnosticLogEntity>)

    @Transaction
    suspend fun startSessionWithLog(session: GameSessionEntity, initialLog: DiagnosticLogEntity) {
        upsertSession(session)
        insertLog(initialLog)
    }

    @Query(
        "UPDATE game_sessions SET status = :status, exitCode = :exitCode, endedAt = :endedAt, crashReportPath = :crashReportPath WHERE sessionId = :sessionId"
    )
    suspend fun updateSessionExit(
        sessionId: String,
        status: SessionStatus,
        exitCode: Int?,
        endedAt: Long,
        crashReportPath: String?
    )

    @Transaction
    suspend fun finishSessionWithLog(
        sessionId: String,
        status: SessionStatus,
        exitCode: Int?,
        endedAt: Long,
        crashReportPath: String?,
        finalLog: DiagnosticLogEntity
    ) {
        updateSessionExit(sessionId, status, exitCode, endedAt, crashReportPath)
        insertLog(finalLog)
    }

    @Query("SELECT * FROM game_sessions WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getSession(sessionId: String): GameSessionEntity?

    @Query("SELECT * FROM game_sessions ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecentSessions(limit: Int = 20): Flow<List<GameSessionEntity>>

    @Query("SELECT * FROM diagnostic_logs WHERE sessionId = :sessionId ORDER BY timestampMs ASC, logId ASC")
    suspend fun getLogsForSession(sessionId: String): List<DiagnosticLogEntity>

    @Query("SELECT * FROM diagnostic_logs ORDER BY timestampMs DESC, logId DESC LIMIT :limit")
    fun observeRecentLogs(limit: Int = 50): Flow<List<DiagnosticLogEntity>>
}

@Dao
interface InstalledModDao {
    @Upsert
    suspend fun upsert(mod: InstalledModEntity)

    @Query("SELECT * FROM installed_mods WHERE instanceId = :instanceId ORDER BY displayName ASC")
    fun observeForInstance(instanceId: String): Flow<List<InstalledModEntity>>

    @Query("SELECT * FROM installed_mods WHERE instanceId = :instanceId ORDER BY displayName ASC")
    suspend fun getForInstance(instanceId: String): List<InstalledModEntity>

    @Query("SELECT * FROM installed_mods WHERE instanceId = :instanceId AND fileName = :fileName LIMIT 1")
    suspend fun getByFileName(instanceId: String, fileName: String): InstalledModEntity?

    @Query("SELECT * FROM installed_mods WHERE instanceId = :instanceId AND curseForgeModId = :modId")
    suspend fun getByCurseForgeModId(instanceId: String, modId: Long): List<InstalledModEntity>

    @Query("UPDATE installed_mods SET fileName = :newFileName WHERE instanceId = :instanceId AND fileName = :oldFileName")
    suspend fun updateFileName(instanceId: String, oldFileName: String, newFileName: String)

    @Query("DELETE FROM installed_mods WHERE instanceId = :instanceId AND fileName = :fileName")
    suspend fun deleteByFileName(instanceId: String, fileName: String)

    @Query("DELETE FROM installed_mods WHERE recordId = :recordId")
    suspend fun deleteByRecordId(recordId: String)
}

@Dao
interface ServerProfileDao {
    @Upsert
    suspend fun upsert(profile: com.star.launcher.data.db.entity.ServerProfileEntity)

    @Query("SELECT * FROM bridge_server_profiles ORDER BY isFavorite DESC, COALESCE(lastConnectedAtMs, createdAtMs) DESC")
    fun observeAll(): Flow<List<com.star.launcher.data.db.entity.ServerProfileEntity>>

    @Query("SELECT * FROM bridge_server_profiles ORDER BY isFavorite DESC, COALESCE(lastConnectedAtMs, createdAtMs) DESC")
    suspend fun getAll(): List<com.star.launcher.data.db.entity.ServerProfileEntity>

    @Query("SELECT * FROM bridge_server_profiles WHERE profileId = :profileId LIMIT 1")
    suspend fun getById(profileId: String): com.star.launcher.data.db.entity.ServerProfileEntity?

    @Query("DELETE FROM bridge_server_profiles WHERE profileId = :profileId")
    suspend fun deleteById(profileId: String)
}
