package com.star.launcher.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.star.launcher.ui.theme.AetherColors
import com.star.launcher.ui.theme.AetherSpacing

@Composable
fun AetherLoadingState(
    message: String,
    modifier: Modifier = Modifier
) {
    AetherSurfaceCard(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Md)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = AetherColors.AccentCyan,
                strokeWidth = 2.4.dp
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = AetherColors.OnSurfaceDark
            )
        }
    }
}

@Composable
fun AetherEmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    AetherSurfaceCard(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Md)
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = AetherColors.SurfaceVariantDark
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = AetherColors.AccentBlue,
                    modifier = Modifier
                        .padding(12.dp)
                        .size(22.dp)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(AetherSpacing.Xs)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = AetherColors.OnSurfaceDark
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherColors.OnSurfaceVariantDark
                )
            }
        }
        if (actionLabel != null && onAction != null) {
            AetherTonalButton(
                text = actionLabel,
                onClick = onAction,
                containerColor = AetherColors.PrimaryContainerDark,
                contentColor = AetherColors.OnPrimaryContainerDark
            )
        }
    }
}

@Composable
fun AetherErrorCard(
    title: String,
    message: String,
    recoveryLabel: String? = null,
    onRecovery: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = AetherColors.TierBlockedBg,
        border = BorderStroke(1.dp, AetherColors.TierBlockedFg.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier.padding(AetherSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
            ) {
                Icon(
                    imageVector = AetherIcons.Warning,
                    contentDescription = null,
                    tint = AetherColors.TierBlockedFg,
                    modifier = Modifier.size(19.dp)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = AetherColors.TierBlockedFg
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = AetherColors.OnErrorContainerDark
            )
            if (recoveryLabel != null && onRecovery != null || onDismiss != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)) {
                    if (recoveryLabel != null && onRecovery != null) {
                        AetherPrimaryButton(
                            text = recoveryLabel,
                            onClick = onRecovery,
                            icon = AetherIcons.Runtime
                        )
                    }
                    if (onDismiss != null) {
                        Spacer(modifier = Modifier.width(2.dp))
                        AetherOutlinedButton(text = "Dismiss", onClick = onDismiss)
                    }
                }
            }
        }
    }
}

@Composable
fun AetherRuntimeAlertCard(
    requiredJava: Int?,
    deviceAbi: String,
    runtimeCount: Int,
    detailReason: String?,
    onInstallRuntime: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    AetherErrorCard(
        title = if (runtimeCount == 0) "No Compatible Java Runtime Installed" else "Runtime Check Required",
        message = detailReason ?: buildString {
            append("Minecraft requires Java ")
            append(requiredJava ?: 8)
            append("+ compatible with ")
            append(deviceAbi)
            append(". Install a verified Java runtime before launching.")
        },
        recoveryLabel = "Install Runtime",
        onRecovery = onInstallRuntime,
        onDismiss = null,
        modifier = modifier
    )
}
