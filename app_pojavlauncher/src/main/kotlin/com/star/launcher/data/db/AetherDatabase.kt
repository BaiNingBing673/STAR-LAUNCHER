package com.star.launcher.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.star.launcher.data.db.dao.CompatibilityResultDao
import com.star.launcher.data.db.dao.DeviceInfoDao
import com.star.launcher.data.db.dao.DiagnosticSessionDao
import com.star.launcher.data.db.dao.DownloadTaskDao
import com.star.launcher.data.db.dao.GameInstanceDao
import com.star.launcher.data.db.dao.GameVersionDao
import com.star.launcher.data.db.dao.InstalledModDao
import com.star.launcher.data.db.dao.RendererProfileDao
import com.star.launcher.data.db.dao.RuntimeRegistryDao
import com.star.launcher.data.db.dao.SettingsDao
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

/**
 * Primary STAR Launcher persistence database (WAL mode, schema exported, zero destructive migrations).
 * Owned by the `:launcher` UI process; the `:game` process never writes directly to this SQLite file.
 */
@Database(
    entities = [
        AppSettingEntity::class,
        DeviceInfoEntity::class,
        RuntimeRegistryEntity::class,
        RendererProfileEntity::class,
        GameVersionEntity::class,
        GameInstanceEntity::class,
        DownloadTaskEntity::class,
        CompatibilityResultEntity::class,
        GameSessionEntity::class,
        DiagnosticLogEntity::class,
        InstalledModEntity::class
    ],
    version = 2,
    exportSchema = true
)
@TypeConverters(AetherConverters::class)
abstract class AetherDatabase : RoomDatabase() {
    abstract fun settingsDao(): SettingsDao
    abstract fun deviceInfoDao(): DeviceInfoDao
    abstract fun runtimeRegistryDao(): RuntimeRegistryDao
    abstract fun rendererProfileDao(): RendererProfileDao
    abstract fun gameVersionDao(): GameVersionDao
    abstract fun gameInstanceDao(): GameInstanceDao
    abstract fun downloadTaskDao(): DownloadTaskDao
    abstract fun compatibilityResultDao(): CompatibilityResultDao
    abstract fun diagnosticSessionDao(): DiagnosticSessionDao
    abstract fun installedModDao(): InstalledModDao

    companion object {
        const val DATABASE_NAME = "aether_launcher.db"

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `installed_mods` (
                        `recordId` TEXT NOT NULL,
                        `instanceId` TEXT NOT NULL,
                        `fileName` TEXT NOT NULL,
                        `curseForgeModId` INTEGER,
                        `curseForgeFileId` INTEGER,
                        `displayName` TEXT NOT NULL,
                        `installedVersionLabel` TEXT,
                        `mcVersionHint` TEXT,
                        `loaderType` TEXT NOT NULL,
                        `sha1` TEXT,
                        `fileSizeBytes` INTEGER NOT NULL,
                        `allowModDistribution` INTEGER,
                        `websiteUrl` TEXT,
                        `iconUrl` TEXT,
                        `installedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`recordId`),
                        FOREIGN KEY(`instanceId`) REFERENCES `game_instances`(`instanceId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_installed_mods_instanceId_fileName` ON `installed_mods` (`instanceId`, `fileName`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_installed_mods_instanceId_curseForgeModId` ON `installed_mods` (`instanceId`, `curseForgeModId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_installed_mods_installedAt` ON `installed_mods` (`installedAt`)"
                )
            }
        }

        /**
         * Ordered schema migrations for production upgrades.
         * fallbackToDestructiveMigration() is strictly forbidden.
         */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

        @Volatile
        private var instance: AetherDatabase? = null

        @Volatile
        private var instanceContext: Context? = null

        fun getInstance(context: Context): AetherDatabase {
            val appContext = context.applicationContext ?: context
            val existing = instance
            if (existing != null && instanceContext === appContext && existing.isOpen) {
                return existing
            }
            return synchronized(this) {
                val synced = instance
                if (synced != null && instanceContext === appContext && synced.isOpen) {
                    synced
                } else {
                    buildDatabase(appContext).also {
                        instance = it
                        instanceContext = appContext
                    }
                }
            }
        }

        private fun buildDatabase(appContext: Context): AetherDatabase =
            Room.databaseBuilder(
                appContext,
                AetherDatabase::class.java,
                DATABASE_NAME
            )
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
