package com.star.launcher.ui.dashboard

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import net.kdt.pojavlaunch.R

enum class PrivacyPolicyOpenOutcome {
    OPENED,
    INVALID_URL,
    NO_HANDLER_COPIED
}

/**
 * Validates and opens STAR Launcher's hosted Privacy Policy URL in an external browser,
 * falling back to clipboard copy if no browser handler is available.
 */
object PrivacyPolicyLinkHelper {

    fun getConfiguredUrl(context: Context): String =
        context.getString(R.string.privacy_policy_url).trim()

    fun normalizeAndValidatePolicyUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val parsed = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null
        val scheme = parsed.scheme?.lowercase() ?: return null
        if (scheme != "https" && scheme != "http") return null
        val host = parsed.host?.lowercase() ?: return null
        if (host.isBlank()) return null
        return parsed.toString()
    }

    fun openPrivacyPolicy(
        context: Context,
        configuredUrl: String = getConfiguredUrl(context)
    ): PrivacyPolicyOpenOutcome {
        val normalized = normalizeAndValidatePolicyUrl(configuredUrl)
            ?: return PrivacyPolicyOpenOutcome.INVALID_URL

        val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(normalized)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(viewIntent)
            PrivacyPolicyOpenOutcome.OPENED
        } catch (_: ActivityNotFoundException) {
            copyToClipboard(context, normalized)
            PrivacyPolicyOpenOutcome.NO_HANDLER_COPIED
        } catch (_: SecurityException) {
            copyToClipboard(context, normalized)
            PrivacyPolicyOpenOutcome.NO_HANDLER_COPIED
        }
    }

    fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("STAR Launcher Privacy Policy", text))
    }
}
