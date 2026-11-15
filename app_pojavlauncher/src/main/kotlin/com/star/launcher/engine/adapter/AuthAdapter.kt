package com.star.launcher.engine.adapter

import android.net.Uri
import com.star.launcher.account.AccountRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.kdt.pojavlaunch.authenticator.microsoft.MicrosoftBackgroundLogin
import net.kdt.pojavlaunch.value.MinecraftAccount

/**
 * Adapter bridging STAR Launcher's Compose Microsoft login flow directly to the inherited engine's
 * [MicrosoftBackgroundLogin] backend without routing through LauncherActivity or mcAccountSpinner.
 */
object AuthAdapter {
    const val MICROSOFT_OAUTH_URL =
        "https://login.live.com/oauth20_authorize.srf" +
            "?client_id=00000000402b5328" +
            "&response_type=code" +
            "&scope=service%3A%3Auser.auth.xboxlive.com%3A%3AMBI_SSL" +
            "&redirect_url=https%3A%2F%2Flogin.live.com%2Foauth20_desktop.srf"

    const val MICROSOFT_REDIRECT_PREFIX = "ms-xal-00000000402b5328"

    fun isRedirectSuccessUrl(url: String): Boolean = url.startsWith(MICROSOFT_REDIRECT_PREFIX)

    fun isCancelUrl(url: String): Boolean = url.contains("res=cancel")

    fun extractAuthorizationCode(url: String): String? =
        Uri.parse(url).getQueryParameter("code")?.takeIf { it.isNotBlank() }

    fun describeLoginStep(step: Int): String = when (step) {
        1 -> "Acquiring Microsoft Live access token..."
        2 -> "Authenticating with Xbox Live..."
        3 -> "Obtaining XSTS security token..."
        4 -> "Exchanging for Minecraft token..."
        5 -> "Verifying Minecraft profile & entitlement..."
        else -> "Signing in..."
    }

    fun performMicrosoftLogin(
        authCode: String,
        accountRepository: AccountRepository?,
        scope: CoroutineScope,
        onStep: (Int, String) -> Unit,
        onSuccess: (MinecraftAccount) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        MicrosoftBackgroundLogin(false, authCode).performLogin(
            { step -> onStep(step, describeLoginStep(step)) },
            { account ->
                scope.launch(Dispatchers.IO) {
                    runCatching {
                        accountRepository?.selectAccount(account.username)
                        accountRepository?.mirrorSecretsToVault(account.username)
                    }
                    scope.launch(Dispatchers.Main) {
                        onSuccess(account)
                    }
                }
            },
            { exception ->
                onError(exception)
            }
        )
    }
}
