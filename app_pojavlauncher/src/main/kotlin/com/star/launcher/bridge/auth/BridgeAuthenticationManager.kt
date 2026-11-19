package com.star.launcher.bridge.auth

import com.star.launcher.account.AccountRepository
import com.star.launcher.bridge.model.BridgeAuthMode
import com.star.launcher.bridge.model.GeyserConfig
import com.star.launcher.bridge.model.LocalServerConfig
import com.star.launcher.bridge.model.LocalServerSoftware

data class AuthValidationResult(
    val isValid: Boolean,
    val summary: String,
    val warnings: List<String> = emptyList(),
    val errors: List<String> = emptyList()
)

/**
 * Validates Java and Bedrock authentication workflows.
 * Respects Microsoft/Mojang online authentication and never disables authentication automatically.
 */
class BridgeAuthenticationManager(
    private val floodgateManager: FloodgateManager,
    private val accountRepository: AccountRepository? = null
) {
    suspend fun hasAuthenticatedJavaAccount(): Boolean {
        val accounts = runCatching { accountRepository?.listAccounts().orEmpty() }.getOrDefault(emptyList())
        return accounts.any { it.isMicrosoft }
    }

    suspend fun activeJavaAccountName(): String? {
        val accounts = runCatching { accountRepository?.listAccounts().orEmpty() }.getOrDefault(emptyList())
        return accounts.firstOrNull { it.isSelected }?.username ?: accounts.firstOrNull()?.username
    }

    fun validateLocalHostingAuth(
        serverConfig: LocalServerConfig,
        geyserConfig: GeyserConfig
    ): AuthValidationResult {
        val warnings = mutableListOf<String>()
        val errors = mutableListOf<String>()

        when (geyserConfig.authType) {
            BridgeAuthMode.ONLINE -> {
                if (!serverConfig.onlineMode) {
                    warnings += "Local Java server has online-mode=false while Geyser is set to online auth"
                }
            }
            BridgeAuthMode.FLOODGATE -> {
                if (!serverConfig.software.supportsFloodgatePlugin) {
                    errors += "${serverConfig.software.displayName} does not support plugins like Floodgate. Switch local server software to PaperMC or change Geyser auth-type to Online."
                }
                if (!serverConfig.onlineMode) {
                    warnings += "Floodgate is intended for online-mode=true servers; online-mode is currently disabled."
                }
                val fgReport = floodgateManager.inspectFloodgateState(geyserConfig.floodgateKeyFile)
                if (!fgReport.isValidForLocalFloodgate) {
                    errors += fgReport.issues
                }
            }
            BridgeAuthMode.OFFLINE -> {
                if (serverConfig.onlineMode) {
                    errors += "Geyser auth-type 'offline' cannot join a Java server with online-mode=true. Keep Online/Floodgate enabled."
                } else {
                    warnings += "Offline mode disables Microsoft/Mojang session verification. Use only on trusted private LANs."
                }
            }
        }

        val summary = when {
            errors.isNotEmpty() -> errors.first()
            warnings.isNotEmpty() -> warnings.first()
            geyserConfig.authType == BridgeAuthMode.FLOODGATE -> "Floodgate configured (Java online-mode + verified key.pem)"
            geyserConfig.authType == BridgeAuthMode.ONLINE -> "Microsoft/Mojang online authentication active"
            else -> "Offline local mode"
        }

        return AuthValidationResult(
            isValid = errors.isEmpty(),
            summary = summary,
            warnings = warnings,
            errors = errors
        )
    }

    fun validateExternalServerAuth(geyserConfig: GeyserConfig): AuthValidationResult {
        val warnings = mutableListOf<String>()
        val errors = mutableListOf<String>()

        if (geyserConfig.authType == BridgeAuthMode.FLOODGATE) {
            val fgReport = floodgateManager.inspectFloodgateState(geyserConfig.floodgateKeyFile)
            if (!fgReport.geyserKeyPresent) {
                errors += "Floodgate auth requires the external server's key.pem imported into Geyser"
            }
        } else if (geyserConfig.authType == BridgeAuthMode.OFFLINE) {
            warnings += "Offline mode skips Microsoft/Mojang verification and only works on offline-mode servers"
        }

        return AuthValidationResult(
            isValid = errors.isEmpty(),
            summary = errors.firstOrNull() ?: warnings.firstOrNull() ?: "External server authentication ready (${geyserConfig.authType.displayName})",
            warnings = warnings,
            errors = errors
        )
    }
}
