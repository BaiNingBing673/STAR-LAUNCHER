package com.star.launcher.core

import com.star.launcher.data.db.dao.SettingsDao
import com.star.launcher.data.db.entity.AppSettingEntity
import com.star.launcher.data.db.model.SettingValueType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Reactive Room-backed launcher settings store.
 * Actively rejects credential-like keys so authentication secrets only live in TokenVault.
 */
class AetherSettingsRepository(
    private val settingsDao: SettingsDao,
    private val clockMillis: () -> Long = { System.currentTimeMillis() }
) {
    fun observeAll(): Flow<Map<String, AppSettingEntity>> =
        settingsDao.observeAll().map { list -> list.associateBy { it.key } }

    suspend fun getString(key: String, defaultValue: String = ""): String =
        settingsDao.get(key)?.value ?: defaultValue

    suspend fun setString(key: String, value: String) {
        assertNonSecretKey(key)
        settingsDao.upsert(
            AppSettingEntity(
                key = key,
                value = value,
                valueType = SettingValueType.STRING,
                updatedAt = clockMillis()
            )
        )
    }

    suspend fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        val raw = settingsDao.get(key)?.value ?: return defaultValue
        return raw.toBooleanStrictOrNull() ?: defaultValue
    }

    suspend fun setBoolean(key: String, value: Boolean) {
        assertNonSecretKey(key)
        settingsDao.upsert(
            AppSettingEntity(
                key = key,
                value = value.toString(),
                valueType = SettingValueType.BOOL,
                updatedAt = clockMillis()
            )
        )
    }

    suspend fun getInt(key: String, defaultValue: Int): Int {
        val raw = settingsDao.get(key)?.value ?: return defaultValue
        return raw.toIntOrNull() ?: defaultValue
    }

    suspend fun setInt(key: String, value: Int) {
        assertNonSecretKey(key)
        settingsDao.upsert(
            AppSettingEntity(
                key = key,
                value = value.toString(),
                valueType = SettingValueType.INT,
                updatedAt = clockMillis()
            )
        )
    }

    suspend fun remove(key: String) {
        settingsDao.delete(key)
    }

    private fun assertNonSecretKey(key: String) {
        require(key.isNotBlank()) { "Setting key cannot be blank" }
        val normalized = key.lowercase()
        require(FORBIDDEN_SECRET_SUBSTRINGS.none { normalized.contains(it) }) {
            "Secret key '$key' must be stored in TokenVault, not AppSettings"
        }
    }

    companion object {
        private val FORBIDDEN_SECRET_SUBSTRINGS = listOf(
            "accesstoken",
            "access_token",
            "refreshtoken",
            "refresh_token",
            "clienttoken",
            "client_token",
            "msarefresh",
            "password",
            "xststoken"
        )
    }
}
