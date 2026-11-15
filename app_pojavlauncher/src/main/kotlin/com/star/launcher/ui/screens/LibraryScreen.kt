package com.star.launcher.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.star.launcher.data.db.entity.GameInstanceEntity
import com.star.launcher.ui.components.AetherEmptyState
import com.star.launcher.ui.components.AetherIcons
import com.star.launcher.ui.components.AetherOutlinedButton
import com.star.launcher.ui.components.AetherPrimaryButton
import com.star.launcher.ui.components.AetherSectionHeader
import com.star.launcher.ui.components.AetherSurfaceCard
import com.star.launcher.ui.components.AetherTonalButton
import com.star.launcher.ui.dashboard.AetherDashboardUiState
import com.star.launcher.ui.theme.AetherColors
import com.star.launcher.ui.theme.AetherMonoStyle
import com.star.launcher.ui.theme.AetherSpacing
import com.star.launcher.ui.theme.studio.StarComponentTarget

@Composable
fun LibraryScreen(
    state: AetherDashboardUiState,
    onOpenCreateInstance: () -> Unit,
    onImportLegacy: () -> Unit,
    onSelectFeatured: (String) -> Unit,
    onLaunchInstance: (String) -> Unit,
    onOpenInstanceSettings: (GameInstanceEntity) -> Unit,
    onEvaluateCompat: (GameInstanceEntity) -> Unit,
    onOpenModInstaller: () -> Unit,
    onOpenModpackImport: () -> Unit,
    onOpenInstanceDetail: (GameInstanceEntity) -> Unit = onOpenInstanceSettings,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredInstances = remember(state.instances, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) {
            state.instances
        } else {
            state.instances.filter {
                it.name.contains(q, ignoreCase = true) ||
                    (it.versionId?.contains(q, ignoreCase = true) == true)
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(AetherSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(AetherSpacing.Md)
    ) {
        item {
            AetherSectionHeader(
                title = "Game Library",
                subtitle = "${state.instances.size} isolated instance(s)"
            ) {
                AetherPrimaryButton(
                    text = "New",
                    icon = AetherIcons.Plus,
                    onClick = onOpenCreateInstance
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
            ) {
                AetherOutlinedButton(
                    text = "Import Legacy",
                    onClick = onImportLegacy,
                    modifier = Modifier.weight(1f)
                )
                AetherOutlinedButton(
                    text = "Install .jar",
                    onClick = onOpenModInstaller,
                    modifier = Modifier.weight(1f)
                )
                AetherOutlinedButton(
                    text = "Modpack",
                    onClick = onOpenModpackImport,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (state.instances.size > 3) {
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Filter instances by name or version") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        if (filteredInstances.isEmpty()) {
            item {
                AetherEmptyState(
                    icon = AetherIcons.Library,
                    title = if (state.instances.isEmpty()) "Your Library is Empty" else "No Matching Instances",
                    description = if (state.instances.isEmpty()) {
                        "Create an isolated Minecraft Java instance or import existing profiles safely."
                    } else {
                        "Clear your filter or create a new instance for that version."
                    },
                    actionLabel = "Create Instance",
                    onAction = onOpenCreateInstance
                )
            }
        } else {
            items(filteredInstances, key = { it.instanceId }) { instance ->
                val isFeatured = instance.instanceId == state.featuredInstance?.instanceId
                LibraryInstanceCard(
                    instance = instance,
                    isFeatured = isFeatured,
                    rendererLabel = state.rendererProfiles
                        .firstOrNull { it.profileId == instance.rendererProfileId }?.displayName
                        ?: "Default Renderer",
                    runtimeLabel = state.runtimes
                        .firstOrNull { it.runtimeId == instance.runtimeId }?.displayName
                        ?: (instance.runtimeId ?: "Auto-pick JRE"),
                    onSelectFeatured = { onSelectFeatured(instance.instanceId) },
                    onLaunch = { onLaunchInstance(instance.instanceId) },
                    onConfigure = { onOpenInstanceSettings(instance) },
                    onOpenDetail = { onOpenInstanceDetail(instance) },
                    onEvaluateCompat = { onEvaluateCompat(instance) }
                )
            }
        }
        item { Spacer(modifier = Modifier.height(AetherSpacing.Md)) }
    }
}

@Composable
private fun LibraryInstanceCard(
    instance: GameInstanceEntity,
    isFeatured: Boolean,
    rendererLabel: String,
    runtimeLabel: String,
    onSelectFeatured: () -> Unit,
    onLaunch: () -> Unit,
    onConfigure: () -> Unit,
    onOpenDetail: () -> Unit,
    onEvaluateCompat: () -> Unit
) {
    AetherSurfaceCard(
        borderColor = if (isFeatured) AetherColors.AccentCyan.copy(alpha = 0.5f) else null,
        componentTarget = StarComponentTarget.PROFILE_COMPONENTS,
        onClick = onOpenDetail
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Album-art-inspired geometric monogram tile
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                AetherColors.AccentViolet.copy(alpha = 0.35f),
                                AetherColors.AccentBlue.copy(alpha = 0.35f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = instance.name.take(2).uppercase(),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = AetherColors.OnBackgroundDark
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
                ) {
                    Text(
                        text = instance.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = AetherColors.OnSurfaceDark,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isFeatured) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = AetherColors.AccentCyan.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, AetherColors.AccentCyan.copy(alpha = 0.4f))
                        ) {
                            Text(
                                text = "FEATURED",
                                style = MaterialTheme.typography.labelSmall,
                                color = AetherColors.AccentCyan,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Text(
                    text = "${instance.versionId ?: "No version"} · $runtimeLabel",
                    style = AetherMonoStyle.copy(color = AetherColors.OnSurfaceVariantDark),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "$rendererLabel · ${instance.allocatedRamMb} MB",
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherColors.OnSurfaceVariantDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
        ) {
            AetherPrimaryButton(
                text = "Launch",
                icon = AetherIcons.Play,
                onClick = onLaunch
            )
            AetherTonalButton(
                text = "Details",
                onClick = onOpenDetail
            )
            AetherOutlinedButton(
                text = "Settings",
                onClick = onConfigure
            )
            if (!isFeatured) {
                AetherOutlinedButton(
                    text = "Pin Home",
                    onClick = onSelectFeatured
                )
            } else {
                AetherOutlinedButton(
                    text = "Compat",
                    onClick = onEvaluateCompat
                )
            }
        }
    }
}
