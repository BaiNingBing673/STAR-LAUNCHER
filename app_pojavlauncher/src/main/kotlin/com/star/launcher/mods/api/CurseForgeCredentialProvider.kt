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
    private val settingsRepository: AetherSettingsRepository,
    private val bundledApiKeyProvider: () -> String? = { null },
    private val bundledProxyUrlProvider: () -> String? = { null }
) {
    constructor(
        tokenVault: TokenVault,
        settingsRepository: AetherSettingsRepository,
        bundledApiKeyProvider: () -> String?
    ) : this(tokenVault, settingsRepository, bundledApiKeyProvider, { null })
    fun getBundledAuthConfig(): CurseForgeAuthConfig.DirectVaultKey? {
        val raw = runCatching { bundledApiKeyProvider() }.getOrNull() ?: return null
        val normalized = normalizeDeveloperKey(raw)
        return if (isPlausibleDeveloperKey(normalized)) {
            CurseForgeAuthConfig.DirectVaultKey(apiKey = normalized)
        } else {
            null
        }
    }

    fun getBundledProxyConfig(): CurseForgeAuthConfig.BackendProxy? {
        val raw = runCatching { bundledProxyUrlProvider() }.getOrNull()?.trim()?.trimEnd('/') ?: return null
        return if (isValidProxyUrl(raw)) {
            CurseForgeAuthConfig.BackendProxy(proxyBaseUrl = raw, clientToken = null)
        } else {
            null
        }
    }

    suspend fun getSavedProxyUrl(): String =
        settingsRepository.getString(KEY_PROXY_BASE_URL, "").trim()

    suspend fun getAuthConfig(): CurseForgeAuthConfig {
        val mode = settingsRepository.getString(KEY_AUTH_MODE, MODE_UNCONFIGURED)
        return when (mode) {
            MODE_PROXY -> {
                val url = settingsRepository.getString(KEY_PROXY_BASE_URL, "").trim().trimEnd('/')
                if (isValidProxyUrl(url)) {
                    val token = tokenVault.getSecret(VAULT_ALIAS_PROXY_TOKEN)?.trim()?.takeIf { it.isNotEmpty() }
                    CurseForgeAuthConfig.BackendProxy(proxyBaseUrl = url, clientToken = token)
                } else {
                    getBundledProxyConfig() ?: getBundledAuthConfig() ?: CurseForgeAuthConfig.Unconfigured
                }
            }
            MODE_DIRECT_VAULT -> {
                val key = tokenVault.getSecret(VAULT_ALIAS_DEV_KEY)?.let { normalizeDeveloperKey(it) }
                    ?.takeIf { isPlausibleDeveloperKey(it) }
                if (key != null) {
                    CurseForgeAuthConfig.DirectVaultKey(apiKey = key)
                } else {
                    getBundledProxyConfig() ?: getBundledAuthConfig() ?: CurseForgeAuthConfig.Unconfigured
                }
            }
            else -> {
                val fallbackKey = tokenVault.getSecret(VAULT_ALIAS_DEV_KEY)?.let { normalizeDeveloperKey(it) }
                    ?.takeIf { isPlausibleDeveloperKey(it) }
                if (fallbackKey != null) {
                    CurseForgeAuthConfig.DirectVaultKey(apiKey = fallbackKey)
                } else {
                    val savedProxy = settingsRepository.getString(KEY_PROXY_BASE_URL, "").trim().trimEnd('/')
                    if (isValidProxyUrl(savedProxy)) {
                        val token = tokenVault.getSecret(VAULT_ALIAS_PROXY_TOKEN)?.trim()?.takeIf { it.isNotEmpty() }
                        CurseForgeAuthConfig.BackendProxy(proxyBaseUrl = savedProxy, clientToken = token)
                    } else {
                        getBundledProxyConfig() ?: getBundledAuthConfig() ?: CurseForgeAuthConfig.Unconfigured
                    }
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
        val rawHasNewline = rawKey.trim().any { it == '\n' || it == '\r' }
        require(!rawHasNewline) { "Invalid API key format" }
        val normalized = normalizeDeveloperKey(rawKey)
        require(isPlausibleDeveloperKey(normalized)) {
            if (normalized.equals("DUMMY", ignoreCase = true) || normalized.equals("YOUR_API_KEY", ignoreCase = true)) {
                "Placeholder text is not a valid CurseForge API key"
            } else {
                "CurseForge API key appears too short or malformed"
            }
        }
        tokenVault.putSecret(VAULT_ALIAS_DEV_KEY, normalized)
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

        fun normalizeDeveloperKey(raw: String): String {
            var cleaned = raw
                .replace("\u200B", "")
                .replace("\u200C", "")
                .replace("\u200D", "")
                .replace("\uFEFF", "")
                .replace('\u00A0', ' ')
                .trim()
            if (cleaned.startsWith("x-api-key", ignoreCase = true)) {
                val sepIdx = cleaned.indexOfFirst { it == ':' || it == '=' }
                if (sepIdx >= 0) {
                    cleaned = cleaned.substring(sepIdx + 1).trim()
                }
            }
            if ((cleaned.startsWith('"') && cleaned.endsWith('"')) ||
                (cleaned.startsWith('\'') && cleaned.endsWith('\''))
            ) {
                if (cleaned.length >= 2) {
                    cleaned = cleaned.substring(1, cleaned.length - 1).trim()
                }
            }
            return cleaned
        }

        fun isPlausibleDeveloperKey(key: String): Boolean {
            if (key.length < 8) return false
            if (key.any { it.isWhitespace() || it.isISOControl() }) return false
            if (key.equals("DUMMY", ignoreCase = true) || key.equals("YOUR_API_KEY", ignoreCase = true)) return false
            return true
        }

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
