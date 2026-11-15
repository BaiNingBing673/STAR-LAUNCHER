package com.star.launcher.ui.screens

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.star.launcher.account.AccountRepository
import com.star.launcher.engine.adapter.AuthAdapter
import com.star.launcher.ui.components.AetherAccentCard
import com.star.launcher.ui.components.AetherOutlinedButton
import com.star.launcher.ui.theme.AetherColors
import com.star.launcher.ui.theme.AetherMonoStyle
import com.star.launcher.ui.theme.AetherSpacing

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MicrosoftAuthScreen(
    accountRepository: AccountRepository?,
    onBack: () -> Unit,
    onLoginSuccess: (String) -> Unit,
    onLoginError: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var isAuthenticating by remember { mutableStateOf(false) }
    var currentStep by remember { mutableIntStateOf(0) }
    var stepMessage by remember { mutableStateOf("Sign in with your Microsoft account to authorize Minecraft Java.") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(AetherSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
    ) {
        AetherSubscreenHeader(
            title = "Microsoft Sign-In",
            subtitle = "STAR OAuth2 & Xbox Live bridge",
            backLabel = "Settings",
            onBack = onBack
        )

        errorMessage?.let { err ->
            AetherAccentCard(accentColor = AetherColors.TierBlockedFg) {
                Text(
                    text = "Authentication Error",
                    style = MaterialTheme.typography.titleMedium,
                    color = AetherColors.TierBlockedFg
                )
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherColors.OnSurfaceDark
                )
                AetherOutlinedButton(
                    text = "Try Again",
                    onClick = {
                        errorMessage = null
                        isAuthenticating = false
                    }
                )
            }
        }

        if (isAuthenticating) {
            AetherAccentCard(accentColor = AetherColors.AccentCyan) {
                Text(
                    text = "Completing Xbox & Minecraft Sign-In...",
                    style = MaterialTheme.typography.titleMedium,
                    color = AetherColors.OnBackgroundDark
                )
                LinearProgressIndicator(
                    progress = { (currentStep.coerceIn(1, 5)) / 5f },
                    modifier = Modifier.fillMaxWidth(),
                    color = AetherColors.AccentCyan
                )
                Text(
                    text = stepMessage,
                    style = AetherMonoStyle.copy(color = AetherColors.AccentCyan)
                )
            }
        } else {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean = handleUrl(request?.url?.toString().orEmpty())

                                @Deprecated("Required for API compatibility")
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    url: String?
                                ): Boolean = handleUrl(url.orEmpty())

                                private fun handleUrl(url: String): Boolean {
                                    if (AuthAdapter.isRedirectSuccessUrl(url)) {
                                        val code = AuthAdapter.extractAuthorizationCode(url)
                                        if (code.isNullOrBlank()) {
                                            errorMessage = "Missing Microsoft authorization code in redirect."
                                            return true
                                        }
                                        isAuthenticating = true
                                        currentStep = 1
                                        stepMessage = AuthAdapter.describeLoginStep(1)
                                        AuthAdapter.performMicrosoftLogin(
                                            authCode = code,
                                            accountRepository = accountRepository,
                                            scope = scope,
                                            onStep = { step, msg ->
                                                currentStep = step
                                                stepMessage = msg
                                            },
                                            onSuccess = { account ->
                                                onLoginSuccess(account.username)
                                            },
                                            onError = { err ->
                                                val msg = err.localizedMessage ?: err.toString()
                                                isAuthenticating = false
                                                errorMessage = msg
                                                onLoginError(msg)
                                            }
                                        )
                                        return true
                                    }
                                    if (AuthAdapter.isCancelUrl(url)) {
                                        onBack()
                                        return true
                                    }
                                    return false
                                }
                            }
                            CookieManager.getInstance().removeAllCookies {
                                clearHistory()
                                clearCache(true)
                                clearFormData()
                                loadUrl(AuthAdapter.MICROSOFT_OAUTH_URL)
                            }
                        }
                    }
                )
            }
        }
    }
}
