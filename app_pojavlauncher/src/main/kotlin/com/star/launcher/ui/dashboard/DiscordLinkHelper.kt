package com.star.launcher.ui.dashboard

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri

enum class DiscordOpenOutcome {
    OPENED,
    NOT_CONFIGURED,
    INVALID_URL,
    NO_HANDLER_COPIED
}

object DiscordLinkHelper {
    const val DEFAULT_DISCORD_INVITE_URL = "https://discord.gg/DdtPb8ehqR"

    private val VALID_DISCORD_HOSTS = setOf(
        "discord.gg",
        "www.discord.gg",
        "discord.com",
        "www.discord.com",
        "discordapp.com",
        "www.discordapp.com"
    )

    fun normalizeAndValidateInviteUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if (trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("http://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "https://$trimmed"
        }
        val parsed = runCatching { Uri.parse(withScheme) }.getOrNull() ?: return null
        val scheme = parsed.scheme?.lowercase() ?: return null
        if (scheme != "https" && scheme != "http") return null
        val host = parsed.host?.lowercase() ?: return null
        if (host !in VALID_DISCORD_HOSTS) return null
        val path = parsed.encodedPath.orEmpty().trim('/')
        if (path.isBlank()) return null
        return parsed.toString()
    }

    fun openDiscordInvite(context: Context, configuredUrl: String): DiscordOpenOutcome {
        if (configuredUrl.isBlank()) return DiscordOpenOutcome.NOT_CONFIGURED
        val normalized = normalizeAndValidateInviteUrl(configuredUrl)
            ?: return DiscordOpenOutcome.INVALID_URL

        val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(normalized)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(viewIntent)
            DiscordOpenOutcome.OPENED
        } catch (_: ActivityNotFoundException) {
            copyToClipboard(context, normalized)
            DiscordOpenOutcome.NO_HANDLER_COPIED
        } catch (_: SecurityException) {
            copyToClipboard(context, normalized)
            DiscordOpenOutcome.NO_HANDLER_COPIED
        }
    }

    fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("Discord Invite", text))
    }
}
