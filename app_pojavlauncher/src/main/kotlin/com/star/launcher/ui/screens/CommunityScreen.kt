package com.star.launcher.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.star.launcher.ui.components.AetherAccentCard
import com.star.launcher.ui.components.AetherIcons
import com.star.launcher.ui.components.AetherOutlinedButton
import com.star.launcher.ui.components.AetherPrimaryButton
import com.star.launcher.ui.components.AetherSectionHeader
import com.star.launcher.ui.components.AetherSurfaceCard
import com.star.launcher.ui.components.AetherTonalButton
import com.star.launcher.ui.dashboard.AetherDashboardUiState
import com.star.launcher.ui.dashboard.DiscordLinkHelper
import com.star.launcher.ui.dashboard.DiscordOpenOutcome
import com.star.launcher.ui.theme.AetherColors
import com.star.launcher.ui.theme.AetherMonoStyle
import com.star.launcher.ui.theme.AetherSpacing

@Composable
fun CommunityScreen(
    state: AetherDashboardUiState,
    onSaveDiscordInviteUrl: (String) -> Unit,
    onStatusMessage: (String) -> Unit,
    onOpenModInstaller: () -> Unit,
    onOpenModpackImport: () -> Unit,
    onOpenGameDirectory: () -> Unit,
    onShareLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var inviteInput by remember(state.discordInviteUrl) {
        mutableStateOf(state.discordInviteUrl)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(AetherSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(AetherSpacing.Md)
    ) {
        item {
            AetherSectionHeader(
                title = "Community",
                subtitle = "Discord hub, announcements, and creator tools"
            )
        }

        // Configurable Discord Community card (never hardcoded to upstream/fake URLs)
        item {
            AetherAccentCard(accentColor = AetherColors.AccentViolet) {
                Text(
                    text = "Discord Community Server",
                    style = MaterialTheme.typography.titleLarge,
                    color = AetherColors.OnBackgroundDark
                )
                Text(
                    text = if (state.discordInviteUrl.isNotBlank()) {
                        "Invite link ready. Opening launches Discord if installed or falls back to your browser."
                    } else {
                        "Discord invite URL is not configured yet. Enter a valid discord.gg or discord.com/invite link below to enable 1-tap opening."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherColors.OnSurfaceVariantDark
                )
                if (state.discordInviteUrl.isNotBlank()) {
                    Text(
                        text = state.discordInviteUrl,
                        style = AetherMonoStyle.copy(color = AetherColors.AccentCyan)
                    )
                }

                OutlinedTextField(
                    value = inviteInput,
                    onValueChange = { inviteInput = it },
                    label = { Text("Discord invite URL (optional)") },
                    placeholder = { Text("https://discord.gg/your-invite") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
                ) {
                    AetherPrimaryButton(
                        text = "Open Discord",
                        icon = AetherIcons.ExternalLink,
                        enabled = state.discordInviteUrl.isNotBlank(),
                        onClick = {
                            when (DiscordLinkHelper.openDiscordInvite(context, state.discordInviteUrl)) {
                                DiscordOpenOutcome.OPENED -> Unit
                                DiscordOpenOutcome.NOT_CONFIGURED ->
                                    onStatusMessage("Configure a valid Discord invite link first")
                                DiscordOpenOutcome.INVALID_URL ->
                                    onStatusMessage("Saved Discord link is invalid")
                                DiscordOpenOutcome.NO_HANDLER_COPIED ->
                                    onStatusMessage("Discord app/browser unavailable — invite link copied to clipboard")
                            }
                        }
                    )
                    AetherTonalButton(
                        text = "Save Link",
                        onClick = { onSaveDiscordInviteUrl(inviteInput) }
                    )
                    if (state.discordInviteUrl.isNotBlank()) {
                        AetherOutlinedButton(
                            text = "Copy",
                            onClick = {
                                DiscordLinkHelper.copyToClipboard(context, state.discordInviteUrl)
                                onStatusMessage("Copied Discord invite link to clipboard")
                            }
                        )
                    }
                }
            }
        }

        // Announcements & Launcher Updates
        item {
            AetherSectionHeader(
                title = "News & Advisories",
                subtitle = "Updates for STAR Launcher & Minecraft Java on Android"
            )
        }

        item {
            AetherSurfaceCard {
                Text(
                    text = "STAR Identity & Runtime Recovery",
                    style = MaterialTheme.typography.titleMedium,
                    color = AetherColors.OnSurfaceDark
                )
                Text(
                    text = "Home now highlights your featured instance alongside runtime health and recent launch logs. " +
                        "Missing, corrupted, or ABI-mismatched Java runtimes are flagged before launch with direct download options.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherColors.OnSurfaceVariantDark
                )
            }
        }

        item {
            AetherSurfaceCard {
                Text(
                    text = "Mod & Modpack Ecosystem",
                    style = MaterialTheme.typography.titleMedium,
                    color = AetherColors.OnSurfaceDark
                )
                Text(
                    text = "Import modpacks, run Java mod installers (.jar), or inspect game files using the upstream tools below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherColors.OnSurfaceVariantDark
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
                ) {
                    AetherTonalButton(
                        text = "Install .jar Mod",
                        onClick = onOpenModInstaller,
                        modifier = Modifier.weight(1f)
                    )
                    AetherTonalButton(
                        text = "Import Modpack",
                        onClick = onOpenModpackImport,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
                ) {
                    AetherOutlinedButton(
                        text = "Game Directory",
                        onClick = onOpenGameDirectory,
                        modifier = Modifier.weight(1f)
                    )
                    AetherOutlinedButton(
                        text = "Share Latest Log",
                        onClick = onShareLogs,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(AetherSpacing.Md)) }
    }
}
