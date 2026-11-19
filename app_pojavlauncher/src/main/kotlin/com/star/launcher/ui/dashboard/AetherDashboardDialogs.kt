package com.star.launcher.ui.dashboard

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.star.launcher.ui.theme.AetherColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.star.launcher.data.db.entity.GameInstanceEntity
import com.star.launcher.data.db.entity.GameVersionEntity
import com.star.launcher.data.db.entity.RendererProfileEntity
import com.star.launcher.data.db.entity.RuntimeRegistryEntity
import com.star.launcher.instance.InstanceContentSummary
import com.star.launcher.instance.InstanceManager
import com.star.launcher.ui.theme.AetherMonoStyle
import com.star.launcher.ui.theme.AetherSpacing
import com.star.launcher.ui.theme.liquid.StarGlassDialog
import kotlin.math.roundToInt

@Composable
fun CreateInstanceDialog(
    versions: List<GameVersionEntity>,
    runtimes: List<RuntimeRegistryEntity>,
    rendererProfiles: List<RendererProfileEntity>,
    onDismiss: () -> Unit,
    onCreate: (
        name: String,
        versionId: String?,
        runtimeId: String?,
        rendererProfileId: String?,
        allocatedRamMb: Int,
        customJvmArgs: String?
    ) -> Unit
) {
    var name by remember { mutableStateOf("Survival World") }
    var selectedVersion by remember { mutableStateOf(versions.firstOrNull()?.versionId ?: "1.21.4") }
    var selectedRuntimeId by remember { mutableStateOf(runtimes.firstOrNull()?.runtimeId) }
    var selectedRendererProfileId by remember { mutableStateOf(rendererProfiles.firstOrNull()?.profileId) }
    var ramMb by remember { mutableFloatStateOf(InstanceManager.DEFAULT_RAM_MB.toFloat()) }
    var customJvmArgs by remember { mutableStateOf("") }
    val hasValidVersion = InstanceManager.isValidVersionId(selectedVersion)

    StarGlassDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Isolated Instance") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Instance Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = selectedVersion,
                    onValueChange = { selectedVersion = it },
                    label = { Text("Minecraft Version ID (e.g. 1.21.4)") },
                    isError = !hasValidVersion,
                    supportingText = if (!hasValidVersion) {
                        { Text("Enter a Minecraft version ID before creating") }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (versions.isNotEmpty()) {
                    ChipSelectorRow(
                        title = "Installed Versions",
                        options = versions.map { it.versionId to it.versionId },
                        selectedId = selectedVersion.takeIf { it.isNotBlank() },
                        onSelect = { selectedVersion = it ?: "" }
                    )
                }
                if (runtimes.isNotEmpty()) {
                    ChipSelectorRow(
                        title = "Java Runtime",
                        options = listOf(null to "Auto") + runtimes.map {
                            it.runtimeId to "${it.displayName} (J${it.majorVersion})"
                        },
                        selectedId = selectedRuntimeId,
                        onSelect = { selectedRuntimeId = it }
                    )
                }
                if (rendererProfiles.isNotEmpty()) {
                    ChipSelectorRow(
                        title = "Renderer",
                        options = listOf(null to "Auto") + rendererProfiles.map {
                            it.profileId to it.displayName
                        },
                        selectedId = selectedRendererProfileId,
                        onSelect = { selectedRendererProfileId = it }
                    )
                }
                Text("RAM Allocation: ${ramMb.roundToInt()} MB", style = AetherMonoStyle)
                Slider(
                    value = ramMb,
                    onValueChange = { ramMb = ((it / 128f).roundToInt() * 128).coerceIn(512, 8192).toFloat() },
                    valueRange = 512f..8192f
                )
                OutlinedTextField(
                    value = customJvmArgs,
                    onValueChange = { customJvmArgs = it },
                    label = { Text("Custom JVM Args (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank() && hasValidVersion,
                onClick = {
                    onCreate(
                        name,
                        selectedVersion.trim().takeIf { InstanceManager.isValidVersionId(it) },
                        selectedRuntimeId,
                        selectedRendererProfileId,
                        ramMb.roundToInt(),
                        customJvmArgs.trim().takeIf { it.isNotBlank() }
                    )
                }
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun InstanceSettingsDialog(
    instance: GameInstanceEntity,
    versions: List<GameVersionEntity>,
    runtimes: List<RuntimeRegistryEntity>,
    rendererProfiles: List<RendererProfileEntity>,
    contentSummary: InstanceContentSummary?,
    onToggleMod: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (
        instanceId: String,
        name: String,
        versionId: String?,
        runtimeId: String?,
        rendererProfileId: String?,
        allocatedRamMb: Int,
        customJvmArgs: String?,
        controlMapPath: String?
    ) -> Unit,
    onDelete: (String) -> Unit
) {
    var name by remember(instance.instanceId) { mutableStateOf(instance.name) }
    var versionId by remember(instance.instanceId) { mutableStateOf(instance.versionId.orEmpty()) }
    var runtimeId by remember(instance.instanceId) { mutableStateOf(instance.runtimeId) }
    var rendererProfileId by remember(instance.instanceId) { mutableStateOf(instance.rendererProfileId) }
    var ramMb by remember(instance.instanceId) {
        mutableFloatStateOf(instance.allocatedRamMb.coerceIn(256, 8192).toFloat())
    }
    var customJvmArgs by remember(instance.instanceId) { mutableStateOf(instance.customJvmArgs.orEmpty()) }
    var controlMapPath by remember(instance.instanceId) { mutableStateOf(instance.controlMapPath.orEmpty()) }
    var showUnsavedDialog by remember(instance.instanceId) { mutableStateOf(false) }

    val hasValidVersion = InstanceManager.isValidVersionId(versionId)
    val hasUnavailableRenderer = rendererProfileId != null &&
        rendererProfiles.isNotEmpty() &&
        rendererProfiles.none { it.profileId == rendererProfileId }
    val hasUnsavedChanges = name != instance.name ||
        versionId != instance.versionId.orEmpty() ||
        runtimeId != instance.runtimeId ||
        rendererProfileId != instance.rendererProfileId ||
        ramMb.roundToInt() != instance.allocatedRamMb.coerceIn(256, 8192) ||
        customJvmArgs != instance.customJvmArgs.orEmpty() ||
        controlMapPath != instance.controlMapPath.orEmpty()

    fun commitSave() {
        onSave(
            instance.instanceId,
            name,
            versionId.trim().takeIf { InstanceManager.isValidVersionId(it) },
            runtimeId,
            rendererProfileId,
            ramMb.roundToInt(),
            customJvmArgs.trim().takeIf { it.isNotBlank() },
            controlMapPath.trim().takeIf { it.isNotBlank() }
        )
    }

    fun requestClose() {
        if (hasUnsavedChanges) {
            showUnsavedDialog = true
        } else {
            onDismiss()
        }
    }

    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            containerColor = AetherColors.SurfaceElevatedDark,
            title = { Text("Unsaved Instance Changes", color = AetherColors.OnSurfaceDark) },
            text = {
                Text(
                    "You have unsaved changes for '${instance.name}'. Save before closing?",
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherColors.OnSurfaceVariantDark
                )
            },
            confirmButton = {
                Button(
                    enabled = name.isNotBlank() && hasValidVersion && !hasUnavailableRenderer,
                    onClick = {
                        showUnsavedDialog = false
                        commitSave()
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Xs)) {
                    TextButton(onClick = { showUnsavedDialog = false }) {
                        Text("Stay")
                    }
                    TextButton(
                        onClick = {
                            showUnsavedDialog = false
                            onDismiss()
                        }
                    ) {
                        Text("Discard", color = AetherColors.ErrorDark)
                    }
                }
            }
        )
    }

    StarGlassDialog(
        onDismissRequest = ::requestClose,
        title = { Text("Instance Settings") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Display Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = versionId,
                    onValueChange = { versionId = it },
                    label = { Text("Target Version ID") },
                    isError = !hasValidVersion,
                    supportingText = if (!hasValidVersion) {
                        { Text("Enter a Minecraft version ID (e.g. 1.21.4)") }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (versions.isNotEmpty()) {
                    ChipSelectorRow(
                        title = "Local Versions",
                        options = versions.map { it.versionId to it.versionId },
                        selectedId = versionId.takeIf { it.isNotBlank() },
                        onSelect = { versionId = it ?: "" }
                    )
                }
                if (runtimes.isNotEmpty()) {
                    ChipSelectorRow(
                        title = "Java Runtime",
                        options = listOf(null to "Auto") + runtimes.map {
                            it.runtimeId to "${it.displayName} (J${it.majorVersion})"
                        },
                        selectedId = runtimeId,
                        onSelect = { runtimeId = it }
                    )
                }
                if (rendererProfiles.isNotEmpty()) {
                    ChipSelectorRow(
                        title = "Renderer Profile",
                        options = listOf(null to "Auto") + rendererProfiles.map {
                            it.profileId to it.displayName
                        },
                        selectedId = rendererProfileId,
                        onSelect = { rendererProfileId = it }
                    )
                    if (hasUnavailableRenderer) {
                        Text(
                            text = "Saved renderer '$rendererProfileId' is unavailable on this device. Select Auto or a supported renderer above.",
                            style = MaterialTheme.typography.bodySmall,
                            color = AetherColors.ErrorDark
                        )
                    }
                }
                Text("Allocated RAM: ${ramMb.roundToInt()} MB", style = AetherMonoStyle)
                Slider(
                    value = ramMb,
                    onValueChange = { ramMb = ((it / 128f).roundToInt() * 128).coerceIn(256, 8192).toFloat() },
                    valueRange = 256f..8192f
                )
                OutlinedTextField(
                    value = customJvmArgs,
                    onValueChange = { customJvmArgs = it },
                    label = { Text("Custom JVM Arguments") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = controlMapPath,
                    onValueChange = { controlMapPath = it },
                    label = { Text("Control Map File (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Directory: ${instance.gameDirPath}",
                    style = AetherMonoStyle
                )
                contentSummary?.let { summary ->
                    Text(
                        "Worlds: ${summary.worldCount} | ResourcePacks: ${summary.resourcePackCount} | Shaders: ${summary.shaderPackCount}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (summary.mods.isNotEmpty()) {
                        Text("Mods (${summary.mods.size})", style = MaterialTheme.typography.labelLarge)
                        summary.mods.forEach { mod ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "${mod.displayName} [${if (mod.isEnabled) "ON" else "OFF"}]",
                                    style = AetherMonoStyle,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { onToggleMod(mod.fileName) }) {
                                    Text(if (mod.isEnabled) "Disable" else "Enable")
                                }
                            }
                        }
                    }
                }
                OutlinedButton(
                    onClick = { onDelete(instance.instanceId) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Remove Instance")
                }
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank() && hasValidVersion && !hasUnavailableRenderer,
                onClick = ::commitSave
            ) {
                Text(if (hasUnsavedChanges) "Save Changes" else "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = ::requestClose) { Text("Cancel") }
        }
    )
}

@Composable
private fun ChipSelectorRow(
    title: String,
    options: List<Pair<String?, String>>,
    selectedId: String?,
    onSelect: (String?) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(AetherSpacing.Xs)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Xs)
        ) {
            options.forEach { (id, label) ->
                FilterChip(
                    selected = selectedId == id,
                    onClick = { onSelect(id) },
                    label = { Text(label) }
                )
            }
        }
    }
}
