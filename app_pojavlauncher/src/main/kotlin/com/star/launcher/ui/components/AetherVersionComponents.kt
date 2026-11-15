package com.star.launcher.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.star.launcher.ui.theme.AetherColors
import com.star.launcher.ui.theme.AetherMonoStyle
import com.star.launcher.ui.theme.AetherSpacing
import com.star.launcher.ui.theme.studio.StarComponentTarget
import com.star.launcher.version.InstallErrorCategory
import com.star.launcher.version.InstallationProgress
import com.star.launcher.version.VersionCatalogItem
import com.star.launcher.version.VersionReleaseType
import com.star.launcher.version.VersionState
import java.text.DateFormat
import java.util.Date
import java.util.Locale

fun formatVersionBytes(bytes: Long?): String? {
    if (bytes == null || bytes <= 0L) return null
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
        mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
        kb >= 1.0 -> String.format(Locale.US, "%.0f KB", kb)
        else -> "$bytes B"
    }
}

fun formatVersionDate(epochMs: Long?): String? {
    if (epochMs == null || epochMs <= 0L) return null
    return runCatching {
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMs))
    }.getOrNull()
}

fun VersionReleaseType.displayLabel(): String = label

@Composable
fun VersionStatusPill(
    text: String,
    backgroundColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = backgroundColor
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun VersionStateBadge(
    state: VersionState,
    modifier: Modifier = Modifier
) {
    val (label, bg, fg) = when (state) {
        VersionState.INSTALLED -> Triple("Installed", AetherColors.TierVerifiedBg, AetherColors.TierVerifiedFg)
        VersionState.NOT_INSTALLED -> Triple("Not Installed", AetherColors.SurfaceVariantDark, AetherColors.OnSurfaceVariantDark)
        VersionState.INSTALLING -> Triple("Installing", AetherColors.PrimaryContainerDark, AetherColors.AccentCyan)
        VersionState.FAILED -> Triple("Failed", AetherColors.ErrorContainerDark, AetherColors.ErrorDark)
        VersionState.UNSUPPORTED -> Triple("Unsupported", AetherColors.TierTestedBg, AetherColors.TierTestedFg)
    }
    VersionStatusPill(text = label, backgroundColor = bg, contentColor = fg, modifier = modifier)
}

@Composable
fun VersionReleaseTypeTag(
    releaseType: VersionReleaseType,
    modifier: Modifier = Modifier
) {
    val (bg, fg) = when (releaseType) {
        VersionReleaseType.RELEASE -> AetherColors.SurfaceVariantDark to AetherColors.OnSurfaceDark
        VersionReleaseType.SNAPSHOT -> AetherColors.SecondaryContainerDark to AetherColors.AccentViolet
        VersionReleaseType.OLD_BETA, VersionReleaseType.OLD_ALPHA -> AetherColors.TierDetectedBg to AetherColors.TierDetectedFg
        VersionReleaseType.MODDED -> AetherColors.PrimaryContainerDark to AetherColors.AccentCyan
    }
    VersionStatusPill(text = releaseType.displayLabel(), backgroundColor = bg, contentColor = fg, modifier = modifier)
}

@Composable
fun VersionInstallProgressCard(
    progress: InstallationProgress,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = false
) {
    val percent = progress.progressPercent
    val hasKnownTotal = progress.totalBytes != null && progress.totalBytes > 0L && percent != null
    val targetFraction = if (hasKnownTotal) (percent / 100f).coerceIn(0f, 1f) else 0f
    val animatedFraction by animateFloatAsState(
        targetValue = targetFraction,
        label = "versionInstallProgress"
    )
    val effectiveFraction = if (reducedMotion && hasKnownTotal) targetFraction else animatedFraction

    AetherSurfaceCard(
        modifier = modifier.fillMaxWidth(),
        containerColor = AetherColors.SurfaceElevatedDark,
        borderColor = AetherColors.AccentViolet.copy(alpha = 0.45f),
        componentTarget = StarComponentTarget.DOWNLOAD_PROGRESS_PANELS
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Minecraft ${progress.versionId}",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = AetherColors.OnSurfaceDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Downloading game files...",
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherColors.OnSurfaceVariantDark
                )
            }
            if (hasKnownTotal) {
                Text(
                    text = "${percent.toInt()}%",
                    style = AetherMonoStyle.copy(
                        color = AetherColors.AccentCyan,
                        fontWeight = FontWeight.Bold
                    )
                )
            } else {
                VersionStatusPill(
                    text = "Live",
                    backgroundColor = AetherColors.PrimaryContainerDark,
                    contentColor = AetherColors.AccentCyan
                )
            }
        }

        if (hasKnownTotal) {
            LinearProgressIndicator(
                progress = { effectiveFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = AetherColors.AccentCyan,
                trackColor = AetherColors.SurfaceVariantDark,
                strokeCap = StrokeCap.Round
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = AetherColors.AccentViolet,
                trackColor = AetherColors.SurfaceVariantDark,
                strokeCap = StrokeCap.Round
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val sizeSummary = when {
                progress.totalBytes != null && progress.totalBytes > 0L ->
                    "${formatVersionBytes(progress.downloadedBytes) ?: "0 KB"} / ${formatVersionBytes(progress.totalBytes)}"
                progress.totalFiles > 0 ->
                    "Files: ${progress.processedFiles} / ${progress.totalFiles}"
                progress.downloadedBytes > 0L ->
                    "${formatVersionBytes(progress.downloadedBytes)} downloaded"
                else -> "Waiting for server byte totals..."
            }
            Text(
                text = sizeSummary,
                style = AetherMonoStyle.copy(color = AetherColors.OnSurfaceDark),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "Step ${progress.stage.stepOrder}/7",
                style = MaterialTheme.typography.labelSmall,
                color = AetherColors.OnSurfaceVariantDark
            )
        }

        Text(
            text = "Current stage: ${progress.stage.displayTitle}",
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = AetherColors.AccentCyan,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        if (!progress.currentFile.isNullOrBlank()) {
            Text(
                text = "Component: ${progress.currentFile}",
                style = AetherMonoStyle.copy(color = AetherColors.OnSurfaceVariantDark),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (progress.isCancellable) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                AetherOutlinedButton(
                    text = "Cancel",
                    onClick = onCancel
                )
            }
        }
    }
}

@Composable
fun VersionErrorDiagnosticsCard(
    versionId: String,
    category: InstallErrorCategory?,
    message: String,
    diagnostics: String?,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var expanded by rememberSaveable(versionId) { mutableStateOf(false) }

    AetherSurfaceCard(
        modifier = modifier.fillMaxWidth(),
        containerColor = AetherColors.ErrorContainerDark.copy(alpha = 0.72f),
        borderColor = AetherColors.ErrorDark.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = AetherIcons.Warning,
                contentDescription = null,
                tint = AetherColors.ErrorDark,
                modifier = Modifier.size(18.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = category?.title ?: "Installation failed",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = AetherColors.OnErrorContainerDark
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherColors.OnErrorContainerDark.copy(alpha = 0.92f)
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!diagnostics.isNullOrBlank()) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        text = if (expanded) "Hide diagnostics" else "Technical diagnostics",
                        style = MaterialTheme.typography.labelMedium,
                        color = AetherColors.ErrorDark
                    )
                }
            } else {
                Spacer(modifier = Modifier.width(1.dp))
            }
            if (onRetry != null) {
                AetherPrimaryButton(
                    text = "Retry",
                    onClick = onRetry
                )
            }
        }

        AnimatedVisibility(visible = expanded && !diagnostics.isNullOrBlank()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = AetherColors.BackgroundDark.copy(alpha = 0.85f),
                border = BorderStroke(1.dp, AetherColors.OutlineVariantDark)
            ) {
                Text(
                    text = diagnostics.orEmpty(),
                    modifier = Modifier.padding(AetherSpacing.Sm),
                    style = AetherMonoStyle.copy(color = AetherColors.OnSurfaceVariantDark)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VersionListRow(
    item: VersionCatalogItem,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onPrimaryAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = when {
        isSelected -> AetherColors.AccentCyan.copy(alpha = 0.85f)
        item.isInstalling -> AetherColors.AccentViolet.copy(alpha = 0.55f)
        item.isFailed -> AetherColors.ErrorDark.copy(alpha = 0.45f)
        else -> null
    }
    val containerColor = if (isSelected) {
        AetherColors.SurfaceElevatedDark
    } else {
        null
    }

    AetherSurfaceCard(
        modifier = modifier.fillMaxWidth(),
        containerColor = containerColor,
        borderColor = borderColor,
        componentTarget = StarComponentTarget.VERSION_PANELS,
        onClick = onSelect
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(AetherSpacing.Xs)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
                ) {
                    Text(
                        text = item.versionId,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = if (isSelected) AetherColors.AccentCyan else AetherColors.OnSurfaceDark,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    VersionStateBadge(state = item.state)
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Xs),
                    verticalArrangement = Arrangement.spacedBy(AetherSpacing.Xs)
                ) {
                    VersionReleaseTypeTag(releaseType = item.releaseType)
                    if (!item.loaderLabel.isNullOrBlank()) {
                        VersionStatusPill(
                            text = item.loaderLabel,
                            backgroundColor = AetherColors.PrimaryContainerDark,
                            contentColor = AetherColors.AccentCyan
                        )
                    }
                    if (item.updateAvailable) {
                        VersionStatusPill(
                            text = "Update available",
                            backgroundColor = AetherColors.TierDetectedBg,
                            contentColor = AetherColors.AccentCyan
                        )
                    }
                }

                val sizeText = formatVersionBytes(item.diskSizeBytes ?: item.downloadSizeBytes)
                val dateText = formatVersionDate(item.installedAt ?: item.entity.releaseTimeEpochMs.takeIf { it > 0L })
                val metaParts = listOfNotNull(
                    sizeText,
                    dateText?.let { if (item.isInstalled) "Installed $it" else it },
                    "Java ${item.entity.requiredJavaMajor}"
                )
                if (metaParts.isNotEmpty()) {
                    Text(
                        text = metaParts.joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherColors.OnSurfaceVariantDark,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(AetherSpacing.Sm))

            when (item.state) {
                VersionState.INSTALLED -> AetherTonalButton(
                    text = "Play",
                    onClick = onPrimaryAction,
                    icon = AetherIcons.Play,
                    containerColor = AetherColors.PrimaryDark,
                    contentColor = AetherColors.OnPrimaryDark
                )
                VersionState.NOT_INSTALLED -> AetherOutlinedButton(
                    text = "Install",
                    onClick = onPrimaryAction
                )
                VersionState.INSTALLING -> {
                    val pct = item.progress?.progressPercent?.toInt()?.let { "$it%" } ?: "..."
                    AetherTonalButton(
                        text = pct,
                        onClick = onSelect,
                        containerColor = AetherColors.SecondaryContainerDark,
                        contentColor = AetherColors.OnSecondaryContainerDark
                    )
                }
                VersionState.FAILED -> AetherTonalButton(
                    text = "Retry",
                    onClick = onPrimaryAction,
                    containerColor = AetherColors.ErrorContainerDark,
                    contentColor = AetherColors.OnErrorContainerDark
                )
                VersionState.UNSUPPORTED -> VersionStatusPill(
                    text = "Blocked",
                    backgroundColor = AetherColors.TierBlockedBg,
                    contentColor = AetherColors.TierBlockedFg
                )
            }
        }

        if (item.isInstalling && item.progress != null) {
            val progress = item.progress
            val pct = progress.progressPercent
            Spacer(modifier = Modifier.height(AetherSpacing.Xxs))
            if (progress.totalBytes != null && progress.totalBytes > 0L && pct != null) {
                LinearProgressIndicator(
                    progress = { (pct / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = AetherColors.AccentCyan,
                    trackColor = AetherColors.SurfaceVariantDark
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = AetherColors.AccentViolet,
                    trackColor = AetherColors.SurfaceVariantDark
                )
            }
            Text(
                text = progress.stage.displayTitle +
                    (progress.currentFile?.let { " ($it)" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = AetherColors.AccentCyan,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VersionDetailPanel(
    item: VersionCatalogItem,
    onPlay: () -> Unit,
    onInstall: () -> Unit,
    onCancelInstall: () -> Unit,
    onRetryInstall: () -> Unit,
    onVerifyInstall: () -> Unit,
    onDeleteInstall: () -> Unit,
    onCreateInstance: () -> Unit,
    onOpenInstallProgressScreen: () -> Unit,
    reducedMotion: Boolean = false,
    modifier: Modifier = Modifier
) {
    AetherAccentCard(
        modifier = modifier.fillMaxWidth(),
        componentTarget = StarComponentTarget.VERSION_PANELS,
        accentColor = when (item.state) {
            VersionState.INSTALLED -> AetherColors.TierVerifiedFg
            VersionState.INSTALLING -> AetherColors.AccentViolet
            VersionState.FAILED -> AetherColors.ErrorDark
            VersionState.UNSUPPORTED -> AetherColors.TierTestedFg
            VersionState.NOT_INSTALLED -> AetherColors.AccentBlue
        }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Minecraft ${item.versionId}",
                    style = MaterialTheme.typography.titleLarge,
                    color = AetherColors.OnSurfaceDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = when (item.state) {
                        VersionState.INSTALLED -> "Verified and ready to launch"
                        VersionState.NOT_INSTALLED -> "Download required before launching"
                        VersionState.INSTALLING -> "Installation in progress"
                        VersionState.FAILED -> "Previous installation did not complete"
                        VersionState.UNSUPPORTED -> "Blocked on this hardware or runtime"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherColors.OnSurfaceVariantDark
                )
            }
            VersionStateBadge(state = item.state)
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Xs),
            verticalArrangement = Arrangement.spacedBy(AetherSpacing.Xs)
        ) {
            VersionReleaseTypeTag(releaseType = item.releaseType)
            if (!item.loaderLabel.isNullOrBlank()) {
                VersionStatusPill(
                    text = item.loaderLabel,
                    backgroundColor = AetherColors.PrimaryContainerDark,
                    contentColor = AetherColors.AccentCyan
                )
            } else {
                VersionStatusPill(
                    text = "Vanilla",
                    backgroundColor = AetherColors.SurfaceVariantDark,
                    contentColor = AetherColors.OnSurfaceVariantDark
                )
            }
            if (item.updateAvailable) {
                VersionStatusPill(
                    text = "Manifest Update Available",
                    backgroundColor = AetherColors.TierDetectedBg,
                    contentColor = AetherColors.AccentCyan
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
        ) {
            AetherMetricChip(
                label = "Java",
                value = "JRE ${item.entity.requiredJavaMajor}",
                accentColor = AetherColors.AccentBlue,
                modifier = Modifier.weight(1f)
            )
            AetherMetricChip(
                label = if (item.isInstalled) "On Disk" else "Size",
                value = formatVersionBytes(item.diskSizeBytes ?: item.downloadSizeBytes) ?: "Dynamic",
                accentColor = AetherColors.AccentCyan,
                modifier = Modifier.weight(1f)
            )
            AetherMetricChip(
                label = if (item.isInstalled) "Installed" else "Released",
                value = formatVersionDate(item.installedAt ?: item.entity.releaseTimeEpochMs.takeIf { it > 0L }) ?: "Catalog",
                accentColor = AetherColors.AccentViolet,
                modifier = Modifier.weight(1f)
            )
        }

        if (item.isInstalling && item.progress != null) {
            VersionInstallProgressCard(
                progress = item.progress,
                onCancel = onCancelInstall,
                reducedMotion = reducedMotion
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                AetherOutlinedButton(
                    text = "Expand progress view",
                    onClick = onOpenInstallProgressScreen
                )
            }
        }

        if (item.isFailed) {
            VersionErrorDiagnosticsCard(
                versionId = item.versionId,
                category = item.errorCategory,
                message = item.errorMessage ?: "Installation of ${item.versionId} failed.",
                diagnostics = item.diagnosticDetails,
                onRetry = null
            )
        }

        if (item.isUnsupported && !item.unsupportedReason.isNullOrBlank()) {
            AetherSurfaceCard(
                containerColor = AetherColors.TierBlockedBg.copy(alpha = 0.7f),
                borderColor = AetherColors.TierBlockedFg.copy(alpha = 0.4f)
            ) {
                Text(
                    text = "Why this version cannot launch",
                    style = MaterialTheme.typography.titleMedium,
                    color = AetherColors.TierBlockedFg
                )
                Text(
                    text = item.unsupportedReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherColors.OnErrorContainerDark
                )
            }
        }

        HorizontalDivider(color = AetherColors.OutlineVariantDark)

        when (item.state) {
            VersionState.INSTALLED -> {
                AetherPrimaryPlayButton(
                    label = "Play ${item.versionId}",
                    subtitle = item.loaderLabel ?: "Verified installation",
                    enabled = true,
                    onClick = onPlay
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm),
                    verticalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
                ) {
                    AetherTonalButton(
                        text = "New Instance",
                        onClick = onCreateInstance
                    )
                    AetherOutlinedButton(
                        text = if (item.updateAvailable) "Re-verify & Update" else "Verify Files",
                        onClick = onVerifyInstall
                    )
                    AetherOutlinedButton(
                        text = "Remove Local",
                        onClick = onDeleteInstall
                    )
                }
            }
            VersionState.NOT_INSTALLED -> {
                AetherPrimaryButton(
                    text = "Install ${item.versionId}",
                    onClick = onInstall,
                    icon = AetherIcons.Download,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            VersionState.INSTALLING -> {
                AetherOutlinedButton(
                    text = "Installing... Tap to open monitor",
                    onClick = onOpenInstallProgressScreen,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            VersionState.FAILED -> {
                AetherPrimaryButton(
                    text = "Retry Installation",
                    onClick = onRetryInstall,
                    icon = AetherIcons.Download,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            VersionState.UNSUPPORTED -> {
                AetherPrimaryButton(
                    text = "Unsupported on this device",
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
