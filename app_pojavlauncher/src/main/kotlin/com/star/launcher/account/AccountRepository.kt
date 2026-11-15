package com.star.launcher.account

import android.content.Context
import com.star.launcher.core.AetherSettingsRepository
import com.star.launcher.data.security.TokenVault
import net.kdt.pojavlaunch.PojavProfile
import net.kdt.pojavlaunch.Tools
import org.json.JSONObject
import java.io.File

/**
 * Non-secret UI view of an upstream or STAR Launcher-managed Minecraft account.
 * Credentials are never copied into Room tables or log streams.
 */
data class AetherAccountSummary(
    val username: String,
    val profileId: String,
    val isMicrosoft: Boolean,
    val isLocal: Boolean,
    val isDemo: Boolean,
    val isSelected: Boolean,
    val hasVaultBackup: Boolean
)

/**
 * Coordinates read-only discovery of upstream account JSON files, offline account creation,
 * active selection, and encrypted [TokenVault] secret mirroring.
 */
class AccountRepository(
    private val context: Context,
    private val tokenVault: TokenVault,
    private val settingsRepository: AetherSettingsRepository,
    private val accountsDirProvider: () -> File = { File(Tools.DIR_ACCOUNT_NEW) }
) {
    private val appContext = context.applicationContext

    suspend fun listAccounts(): List<AetherAccountSummary> {
        val activeName = resolveSelectedAccountName()
        val accountsDir = accountsDirProvider()
        val files = if (accountsDir.isDirectory) {
            accountsDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
        } else {
            emptyArray()
        }

        val summaries = files.mapNotNull { file ->
            runCatching {
                val obj = JSONObject(file.readText())
                val uname = obj.optString("username").takeIf { it.isNotBlank() } ?: file.nameWithoutExtension
                val accessToken = obj.optString("accessToken", "0").ifBlank { "0" }
                val isDemo = uname.startsWith("Demo.")
                val isLocal = accessToken == "0" && !isDemo
                AetherAccountSummary(
                    username = uname,
                    profileId = obj.optString("profileId", "00000000-0000-0000-0000-000000000000")
                        .ifBlank { "00000000-0000-0000-0000-000000000000" },
                    isMicrosoft = obj.optBoolean("isMicrosoft", false),
                    isLocal = isLocal,
                    isDemo = isDemo,
                    isSelected = uname == activeName,
                    hasVaultBackup = tokenVault.containsSecret(vaultAccessKey(uname)) ||
                        tokenVault.containsSecret(vaultMsaKey(uname))
                )
            }.getOrNull()
        }
        return summaries.sortedWith(compareByDescending<AetherAccountSummary> { it.isSelected }.thenBy { it.username.lowercase() })
    }

    suspend fun resolveSelectedAccountName(): String? {
        val stored = settingsRepository.getString(SETTING_ACTIVE_ACCOUNT)
        if (!stored.isNullOrBlank()) return stored
        return runCatching {
            PojavProfile.getCurrentProfileName(appContext).takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    suspend fun selectAccount(username: String) {
        require(username.isNotBlank()) { "Account username cannot be blank" }
        settingsRepository.setString(SETTING_ACTIVE_ACCOUNT, username)
        runCatching {
            PojavProfile.setCurrentProfile(appContext, username)
        }
    }

    /**
     * Encrypts sensitive fields from an existing account JSON file into [TokenVault]
     * without modifying or deleting the upstream JSON file.
     */
    fun mirrorSecretsToVault(username: String): Boolean {
        val file = File(accountsDirProvider(), "$username.json")
        if (!file.isFile) return false
        val obj = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return false
        var storedAny = false
        obj.optString("accessToken").takeIf { it.isNotBlank() && it != "0" }?.let { token ->
            tokenVault.putSecret(vaultAccessKey(username), token)
            storedAny = true
        }
        obj.optString("msaRefreshToken").takeIf { it.isNotBlank() && it != "0" }?.let { refresh ->
            tokenVault.putSecret(vaultMsaKey(username), refresh)
            storedAny = true
        }
        return storedAny
    }

    suspend fun createOfflineAccount(rawUsername: String): AetherAccountSummary {
        val username = sanitizeUsername(rawUsername)
        val dir = accountsDirProvider().apply { mkdirs() }
        val profileUuid = "00000000-0000-0000-0000-000000000000"
        val json = JSONObject().apply {
            put("accessToken", "0")
            put("clientToken", "0")
            put("profileId", profileUuid)
            put("username", username)
            put("selectedVersion", "1.7.10")
            put("isMicrosoft", false)
            put("msaRefreshToken", "0")
        }
        val outFile = File(dir, "$username.json")
        outFile.writeText(json.toString())
        selectAccount(username)
        return AetherAccountSummary(
            username = username,
            profileId = profileUuid,
            isMicrosoft = false,
            isLocal = !username.startsWith("Demo."),
            isDemo = username.startsWith("Demo."),
            isSelected = true,
            hasVaultBackup = false
        )
    }

    companion object {
        const val SETTING_ACTIVE_ACCOUNT = "active_account_name"

        private val validUsernameChars = Regex("""[^a-zA-Z0-9._-]""")

        fun sanitizeUsername(raw: String): String {
            val cleaned = validUsernameChars.replace(raw.trim(), "_")
            require(cleaned.isNotBlank()) { "Username cannot be blank" }
            return cleaned.take(16)
        }

        fun vaultAccessKey(username: String): String = "acct_${username}_access"
        fun vaultMsaKey(username: String): String = "acct_${username}_msa_refresh"
    }
}
