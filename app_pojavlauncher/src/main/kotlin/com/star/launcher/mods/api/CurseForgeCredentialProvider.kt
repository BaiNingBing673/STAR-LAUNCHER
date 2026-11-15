package com.star.launcher.mods.api

import com.star.launcher.core.AetherSettingsRepository
import com.star.launcher.data.security.TokenVault
import java.net.URI

sealed interface CurseForgeAuthConfig {
    data object Unconfigured : CurseForgeAuthConfig

    data class BackendProxy(
        val proxyBaseUrl: String,
        val clientToken: String? = null
    ) : CurseForgeAuthConfig

    data class DirectVaultKey(
        val apiKey: String
    ) : CurseForgeAuthConfig
}

enum class CurseForgeCredentialStatus {
    UNCONFIGURED,
    BACKEND_PROXY_CONFIGURED,
    DEVELOPER_VAULT_KEY_CONFIGURED
}

/**
 * Manages CurseForge API credentials without ever embedding a production key in source,
 * resources, BuildConfig, or the APK.
 *
 * Keys are stored exclusively inside [TokenVault] (AES-256-GCM backed by Android Keystore),
 * while non-secret proxy URL and mode flags live in [AetherSettingsRepository].
 */
class CurseForgeCredentialProvider(
    private val tokenVault: TokenVault,
    private val settingsRepository: AetherSettingsRepository
) {
    suspend fun getAuthConfig(): CurseForgeAuthConfig {
        val mode = settingsRepository.getString(KEY_AUTH_MODE, MODE_UNCONFIGURED)
        return when (mode) {
            MODE_PROXY -> {
                val url = settingsRepository.getString(KEY_PROXY_BASE_URL, "").trim().trimEnd('/')
                if (isValidProxyUrl(url)) {
                    val token = tokenVault.getSecret(VAULT_ALIAS_PROXY_TOKEN)?.trim()?.takeIf { it.isNotEmpty() }
                    CurseForgeAuthConfig.BackendProxy(proxyBaseUrl = url, clientToken = token)
                } else {
                    CurseForgeAuthConfig.Unconfigured
                }
            }
            MODE_DIRECT_VAULT -> {
                val key = tokenVault.getSecret(VAULT_ALIAS_DEV_KEY)?.trim()?.takeIf { it.isNotEmpty() }
                if (key != null) {
                    CurseForgeAuthConfig.DirectVaultKey(apiKey = key)
                } else {
                    CurseForgeAuthConfig.Unconfigured
                }
            }
            else -> {
                val fallbackKey = tokenVault.getSecret(VAULT_ALIAS_DEV_KEY)?.trim()?.takeIf { it.isNotEmpty() }
                if (fallbackKey != null) {
                    CurseForgeAuthConfig.DirectVaultKey(apiKey = fallbackKey)
                } else {
                    CurseForgeAuthConfig.Unconfigured
                }
            }
        }
    }

    suspend fun getCredentialStatus(): CurseForgeCredentialStatus = when (getAuthConfig()) {
        is CurseForgeAuthConfig.BackendProxy -> CurseForgeCredentialStatus.BACKEND_PROXY_CONFIGURED
        is CurseForgeAuthConfig.DirectVaultKey -> CurseForgeCredentialStatus.DEVELOPER_VAULT_KEY_CONFIGURED
        CurseForgeAuthConfig.Unconfigured -> CurseForgeCredentialStatus.UNCONFIGURED
    }

    suspend fun saveDeveloperApiKey(rawKey: String) {
        val trimmed = rawKey.trim()
        require(trimmed.length >= 8) { "CurseForge API key appears too short" }
        require(!trimmed.contains('\n') && !trimmed.contains('\r')) { "Invalid API key format" }
        tokenVault.putSecret(VAULT_ALIAS_DEV_KEY, trimmed)
        settingsRepository.setString(KEY_AUTH_MODE, MODE_DIRECT_VAULT)
    }

    suspend fun saveProxyConfig(rawBaseUrl: String, rawClientToken: String?) {
        val normalized = rawBaseUrl.trim().trimEnd('/')
        require(isValidProxyUrl(normalized)) {
            "Proxy URL must use HTTPS (or http://localhost / 127.0.0.1 for local development)"
        }
        settingsRepository.setString(KEY_PROXY_BASE_URL, normalized)
        val cleanToken = rawClientToken?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanToken != null) {
            tokenVault.putSecret(VAULT_ALIAS_PROXY_TOKEN, cleanToken)
        } else {
            tokenVault.deleteSecret(VAULT_ALIAS_PROXY_TOKEN)
        }
        settingsRepository.setString(KEY_AUTH_MODE, MODE_PROXY)
    }

    suspend fun clearCredentials() {
        tokenVault.deleteSecret(VAULT_ALIAS_DEV_KEY)
        tokenVault.deleteSecret(VAULT_ALIAS_PROXY_TOKEN)
        settingsRepository.remove(KEY_PROXY_BASE_URL)
        settingsRepository.setString(KEY_AUTH_MODE, MODE_UNCONFIGURED)
    }

    companion object {
        const val OFFICIAL_CURSEFORGE_BASE_URL = "https://api.curseforge.com"
        const val MINECRAFT_GAME_ID = 432
        const val MODS_CLASS_ID = 6

        const val KEY_AUTH_MODE = "curseforge_auth_mode"
        const val KEY_PROXY_BASE_URL = "curseforge_proxy_base_url"
        const val MODE_UNCONFIGURED = "unconfigured"
        const val MODE_PROXY = "proxy"
        const val MODE_DIRECT_VAULT = "direct_vault"

        const val VAULT_ALIAS_DEV_KEY = "curseforge_dev_api_key"
        const val VAULT_ALIAS_PROXY_TOKEN = "curseforge_proxy_client_token"

        fun isValidProxyUrl(url: String): Boolean {
            if (url.isBlank()) return false
            val parsed = runCatching { URI(url) }.getOrNull() ?: return false
            val scheme = parsed.scheme?.lowercase() ?: return false
            val host = parsed.host?.lowercase() ?: return false
            return when (scheme) {
                "https" -> host.isNotBlank()
                "http" -> host == "localhost" || host == "127.0.0.1"
                else -> false
            }
        }
    }
}
