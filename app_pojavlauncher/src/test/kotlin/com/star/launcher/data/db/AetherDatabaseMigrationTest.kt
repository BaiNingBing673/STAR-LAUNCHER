package com.star.launcher.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.star.launcher.data.db.entity.InstalledModEntity
import com.star.launcher.data.db.model.ModLoaderType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AetherDatabaseMigrationTest {

    @Test
    fun `migration 1 to 2 preserves existing v1 instances and adds installed_mods table cleanly`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration_test_aether.db"
        context.deleteDatabase(dbName)

        val v1Helper = object : SQLiteOpenHelper(context, dbName, null, 1) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `app_settings` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, `valueType` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_app_settings_updatedAt` ON `app_settings` (`updatedAt`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `device_info` (`deviceFingerprint` TEXT NOT NULL, `cpuAbiPrimary` TEXT NOT NULL, `supportedAbisJson` TEXT NOT NULL, `is64Bit` INTEGER NOT NULL, `androidApiLevel` INTEGER NOT NULL, `gpuVendor` TEXT NOT NULL, `gpuRenderer` TEXT NOT NULL, `glVersion` TEXT NOT NULL, `glesMajorVersion` INTEGER NOT NULL, `hasVulkan` INTEGER NOT NULL, `vulkanVersion` TEXT, `totalRamMb` INTEGER NOT NULL, `probedAt` INTEGER NOT NULL, PRIMARY KEY(`deviceFingerprint`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_device_info_probedAt` ON `device_info` (`probedAt`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `runtime_registry` (`runtimeId` TEXT NOT NULL, `displayName` TEXT NOT NULL, `majorVersion` INTEGER NOT NULL, `arch` TEXT NOT NULL, `installPath` TEXT NOT NULL, `binaryVersion` TEXT, `isInternal` INTEGER NOT NULL, `isVerified` INTEGER NOT NULL, `installedAt` INTEGER NOT NULL, PRIMARY KEY(`runtimeId`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_runtime_registry_majorVersion_arch` ON `runtime_registry` (`majorVersion`, `arch`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_runtime_registry_installPath` ON `runtime_registry` (`installPath`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `renderer_profiles` (`profileId` TEXT NOT NULL, `rendererId` TEXT NOT NULL, `displayName` TEXT NOT NULL, `backendConfigJson` TEXT NOT NULL, `useAngle` INTEGER NOT NULL, `preferSystemDriver` INTEGER NOT NULL, `isDefault` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`profileId`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_renderer_profiles_rendererId` ON `renderer_profiles` (`rendererId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_renderer_profiles_isDefault` ON `renderer_profiles` (`isDefault`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `game_versions` (`versionId` TEXT NOT NULL, `inheritsFrom` TEXT, `versionType` TEXT NOT NULL, `modLoaderType` TEXT NOT NULL, `modLoaderVersion` TEXT, `requiredJavaMajor` INTEGER NOT NULL, `lwjglMajor` INTEGER NOT NULL, `manifestPath` TEXT NOT NULL, `isInstalled` INTEGER NOT NULL, `releaseTimeEpochMs` INTEGER NOT NULL, PRIMARY KEY(`versionId`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_game_versions_inheritsFrom` ON `game_versions` (`inheritsFrom`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_game_versions_modLoaderType_isInstalled` ON `game_versions` (`modLoaderType`, `isInstalled`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `game_instances` (`instanceId` TEXT NOT NULL, `name` TEXT NOT NULL, `gameDirPath` TEXT NOT NULL, `versionId` TEXT, `runtimeId` TEXT, `rendererProfileId` TEXT, `allocatedRamMb` INTEGER NOT NULL, `customJvmArgs` TEXT, `controlMapPath` TEXT, `lastPlayedAt` INTEGER, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`instanceId`), FOREIGN KEY(`versionId`) REFERENCES `game_versions`(`versionId`) ON UPDATE NO ACTION ON DELETE SET NULL , FOREIGN KEY(`runtimeId`) REFERENCES `runtime_registry`(`runtimeId`) ON UPDATE NO ACTION ON DELETE SET NULL , FOREIGN KEY(`rendererProfileId`) REFERENCES `renderer_profiles`(`profileId`) ON UPDATE NO ACTION ON DELETE SET NULL )")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_game_instances_gameDirPath` ON `game_instances` (`gameDirPath`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_game_instances_versionId` ON `game_instances` (`versionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_game_instances_runtimeId` ON `game_instances` (`runtimeId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_game_instances_rendererProfileId` ON `game_instances` (`rendererProfileId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_game_instances_lastPlayedAt` ON `game_instances` (`lastPlayedAt`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `download_tasks` (`taskId` TEXT NOT NULL, `instanceId` TEXT, `versionId` TEXT, `taskType` TEXT NOT NULL, `sourceUrl` TEXT NOT NULL, `targetPath` TEXT NOT NULL, `expectedSha1` TEXT, `totalBytes` INTEGER NOT NULL, `downloadedBytes` INTEGER NOT NULL, `status` TEXT NOT NULL, `retryCount` INTEGER NOT NULL, `errorMessage` TEXT, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`taskId`), FOREIGN KEY(`instanceId`) REFERENCES `game_instances`(`instanceId`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`versionId`) REFERENCES `game_versions`(`versionId`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_download_tasks_status_updatedAt` ON `download_tasks` (`status`, `updatedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_download_tasks_instanceId` ON `download_tasks` (`instanceId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_download_tasks_versionId` ON `download_tasks` (`versionId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `compatibility_results` (`resultId` TEXT NOT NULL, `deviceFingerprint` TEXT NOT NULL, `instanceId` TEXT, `versionId` TEXT, `rendererId` TEXT NOT NULL, `runtimeId` TEXT, `tier` TEXT NOT NULL, `status` TEXT NOT NULL, `summaryReason` TEXT NOT NULL, `detailsJson` TEXT NOT NULL, `evaluatedAt` INTEGER NOT NULL, PRIMARY KEY(`resultId`), FOREIGN KEY(`deviceFingerprint`) REFERENCES `device_info`(`deviceFingerprint`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`instanceId`) REFERENCES `game_instances`(`instanceId`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`versionId`) REFERENCES `game_versions`(`versionId`) ON UPDATE NO ACTION ON DELETE SET NULL , FOREIGN KEY(`runtimeId`) REFERENCES `runtime_registry`(`runtimeId`) ON UPDATE NO ACTION ON DELETE SET NULL )")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_compatibility_results_deviceFingerprint_tier` ON `compatibility_results` (`deviceFingerprint`, `tier`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_compatibility_results_instanceId_evaluatedAt` ON `compatibility_results` (`instanceId`, `evaluatedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_compatibility_results_versionId` ON `compatibility_results` (`versionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_compatibility_results_runtimeId` ON `compatibility_results` (`runtimeId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `game_sessions` (`sessionId` TEXT NOT NULL, `instanceId` TEXT NOT NULL, `versionId` TEXT, `runtimeId` TEXT, `rendererProfileId` TEXT, `deviceFingerprint` TEXT, `status` TEXT NOT NULL, `exitCode` INTEGER, `startedAt` INTEGER NOT NULL, `endedAt` INTEGER, `crashReportPath` TEXT, PRIMARY KEY(`sessionId`), FOREIGN KEY(`instanceId`) REFERENCES `game_instances`(`instanceId`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`versionId`) REFERENCES `game_versions`(`versionId`) ON UPDATE NO ACTION ON DELETE SET NULL , FOREIGN KEY(`runtimeId`) REFERENCES `runtime_registry`(`runtimeId`) ON UPDATE NO ACTION ON DELETE SET NULL , FOREIGN KEY(`rendererProfileId`) REFERENCES `renderer_profiles`(`profileId`) ON UPDATE NO ACTION ON DELETE SET NULL , FOREIGN KEY(`deviceFingerprint`) REFERENCES `device_info`(`deviceFingerprint`) ON UPDATE NO ACTION ON DELETE SET NULL )")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_game_sessions_instanceId_startedAt` ON `game_sessions` (`instanceId`, `startedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_game_sessions_status` ON `game_sessions` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_game_sessions_versionId` ON `game_sessions` (`versionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_game_sessions_runtimeId` ON `game_sessions` (`runtimeId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_game_sessions_rendererProfileId` ON `game_sessions` (`rendererProfileId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_game_sessions_deviceFingerprint` ON `game_sessions` (`deviceFingerprint`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `diagnostic_logs` (`logId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` TEXT NOT NULL, `timestampMs` INTEGER NOT NULL, `level` TEXT NOT NULL, `sourceTag` TEXT NOT NULL, `messageRedacted` TEXT NOT NULL, FOREIGN KEY(`sessionId`) REFERENCES `game_sessions`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_diagnostic_logs_sessionId_timestampMs` ON `diagnostic_logs` (`sessionId`, `timestampMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_diagnostic_logs_sessionId_level` ON `diagnostic_logs` (`sessionId`, `level`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
                db.execSQL("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES(42, 'bcb3ca70e2e9d97821db73c679de5599')")

                db.execSQL(
                    "INSERT INTO `game_instances` (`instanceId`, `name`, `gameDirPath`, `versionId`, `runtimeId`, `rendererProfileId`, `allocatedRamMb`, `customJvmArgs`, `controlMapPath`, `lastPlayedAt`, `createdAt`) " +
                        "VALUES ('legacy-inst', 'Existing Survival', '/data/inst/legacy-inst', NULL, NULL, NULL, 2048, NULL, NULL, NULL, 1000)"
                )
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        v1Helper.writableDatabase.close()
        v1Helper.close()

        val migratedDb = Room.databaseBuilder(context, AetherDatabase::class.java, dbName)
            .allowMainThreadQueries()
            .addMigrations(*AetherDatabase.MIGRATIONS)
            .build()

        try {
            val preserved = migratedDb.gameInstanceDao().getById("legacy-inst")
            assertEquals("Existing Survival", preserved?.name)

            migratedDb.installedModDao().upsert(
                InstalledModEntity(
                    recordId = "mod-rec-1",
                    instanceId = "legacy-inst",
                    fileName = "sodium.jar",
                    curseForgeModId = 394468L,
                    curseForgeFileId = 9001L,
                    displayName = "Sodium",
                    installedVersionLabel = "0.5.8",
                    mcVersionHint = "1.20.1",
                    loaderType = ModLoaderType.FABRIC,
                    sha1 = "abc",
                    fileSizeBytes = 1024L,
                    allowModDistribution = true,
                    websiteUrl = null,
                    iconUrl = null,
                    installedAt = 2000L
                )
            )
            val rows = migratedDb.installedModDao().getForInstance("legacy-inst")
            assertEquals(1, rows.size)
            assertEquals("sodium.jar", rows.first().fileName)
            assertTrue(migratedDb.openHelper.writableDatabase.isOpen)
        } finally {
            migratedDb.close()
            context.deleteDatabase(dbName)
        }
    }
}
