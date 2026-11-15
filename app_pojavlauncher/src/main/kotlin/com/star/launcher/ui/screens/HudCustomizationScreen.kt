package com.star.launcher.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.star.launcher.hud.StarHudActionRegistry
import com.star.launcher.hud.StarHudActionType
import com.star.launcher.hud.StarHudButtonConfig
import com.star.launcher.hud.StarHudConfig
import com.star.launcher.hud.StarHudConfigStore
import com.star.launcher.hud.StarHudElementId
import com.star.launcher.hud.StarHudIconId
import com.star.launcher.hud.StarHudIcons
import com.star.launcher.hud.StarHudInteractionMode
import com.star.launcher.hud.StarHudLayoutSolver
import com.star.launcher.hud.StarHudSafeInsets
import com.star.launcher.ui.components.AetherAccentCard
import com.star.launcher.ui.components.AetherIcons
import com.star.launcher.ui.components.AetherOutlinedButton
import com.star.launcher.ui.components.AetherPrimaryButton
import com.star.launcher.ui.components.AetherSectionHeader
import com.star.launcher.ui.components.AetherSurfaceCard
import com.star.launcher.ui.components.AetherTonalButton
import com.star.launcher.ui.theme.AetherColors
import com.star.launcher.ui.theme.AetherMonoStyle
import com.star.launcher.ui.theme.AetherSpacing
import net.kdt.pojavlaunch.BuildConfig
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HudCustomizationScreen(
    onBack: () -> Unit,
    onOpenLegacyControls: () -> Unit,
    onStatusMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var config by remember { mutableStateOf(StarHudConfigStore.load(context)) }
    var layouts by remember { mutableStateOf(StarHudConfigStore.listLayouts(context)) }
    var presets by remember { mutableStateOf(StarHudConfigStore.listPresets(context)) }

    var selectedButtonId by remember {
        mutableStateOf<String?>(config.buttons.firstOrNull()?.id)
    }
    var selectedStructural by remember { mutableStateOf<StarHudElementId?>(null) }

    var showNewLayoutDialog by remember { mutableStateOf(false) }
    var showRenameLayoutDialog by remember { mutableStateOf(false) }
    var layoutNameInput by remember { mutableStateOf("") }

    var showAddButtonDialog by remember { mutableStateOf(false) }
    var showSavePresetDialog by remember { mutableStateOf(false) }
    var newPresetName by remember { mutableStateOf("") }

    fun refreshLayouts() {
        layouts = StarHudConfigStore.listLayouts(context)
    }

    fun persistConfig(updated: StarHudConfig) {
        config = StarHudConfigStore.save(context, updated)
        refreshLayouts()
    }

    // Dialog: New Layout
    if (showNewLayoutDialog) {
        AlertDialog(
            onDismissRequest = { showNewLayoutDialog = false },
            containerColor = AetherColors.SurfaceElevatedDark,
            title = { Text("Create Controller Layout", color = AetherColors.OnSurfaceDark) },
            text = {
                OutlinedTextField(
                    value = layoutNameInput,
                    onValueChange = { layoutNameInput = it },
                    singleLine = true,
                    label = { Text("Layout name") },
                    placeholder = { Text("Combat Layout") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val created = StarHudConfigStore.createLayout(
                            context,
                            layoutNameInput.ifBlank { "Custom Layout" },
                            config
                        )
                        config = created
                        refreshLayouts()
                        layoutNameInput = ""
                        showNewLayoutDialog = false
                        onStatusMessage("Created layout '${created.layoutName}'.")
                    }
                ) { Text("Create", color = AetherColors.AccentCyan) }
            },
            dismissButton = {
                TextButton(onClick = { showNewLayoutDialog = false }) {
                    Text("Cancel", color = AetherColors.OnSurfaceVariantDark)
                }
            }
        )
    }

    // Dialog: Rename Active Layout
    if (showRenameLayoutDialog) {
        AlertDialog(
            onDismissRequest = { showRenameLayoutDialog = false },
            containerColor = AetherColors.SurfaceElevatedDark,
            title = { Text("Rename Layout", color = AetherColors.OnSurfaceDark) },
            text = {
                OutlinedTextField(
                    value = layoutNameInput,
                    onValueChange = { layoutNameInput = it },
                    singleLine = true,
                    label = { Text("Layout name") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val nextName = layoutNameInput.trim()
                        if (nextName.isNotEmpty()) {
                            StarHudConfigStore.renameLayout(context, config.layoutId, nextName)?.let {
                                config = it
                                refreshLayouts()
                                onStatusMessage("Renamed layout to '${it.layoutName}'.")
                            }
                        }
                        showRenameLayoutDialog = false
                    }
                ) { Text("Rename", color = AetherColors.AccentCyan) }
            },
            dismissButton = {
                TextButton(onClick = { showRenameLayoutDialog = false }) {
                    Text("Cancel", color = AetherColors.OnSurfaceVariantDark)
                }
            }
        )
    }

    // Dialog: Add Custom Button
    if (showAddButtonDialog) {
        AddButtonDialog(
            onDismiss = { showAddButtonDialog = false },
            onConfirm = { action, customKeycode, customLabel, mode ->
                val newId = config.nextUniqueCustomButtonId(action)
                var created = StarHudConfig.createButtonForAction(
                    id = newId,
                    action = action,
                    customKeycode = customKeycode,
                    xFraction = 0.50f,
                    yFraction = 0.42f
                ).copy(interactionMode = mode)
                if (customLabel.isNotBlank()) {
                    created = created.copy(label = customLabel.take(12))
                }
                persistConfig(config.withAddedButton(created))
                selectedButtonId = newId
                selectedStructural = null
                showAddButtonDialog = false
                onStatusMessage("Added '${created.action.displayName}' button.")
            }
        )
    }

    // Dialog: Save Quick Preset
    if (showSavePresetDialog) {
        AlertDialog(
            onDismissRequest = { showSavePresetDialog = false },
            containerColor = AetherColors.SurfaceElevatedDark,
            title = { Text("Save Quick Preset Backup", color = AetherColors.OnSurfaceDark) },
            text = {
                OutlinedTextField(
                    value = newPresetName,
                    onValueChange = { newPresetName = it },
                    singleLine = true,
                    label = { Text("Preset name") },
                    placeholder = { Text("claw_pvp") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val saved = StarHudConfigStore.savePreset(
                            context,
                            newPresetName.ifBlank { "custom" },
                            config
                        )
                        presets = StarHudConfigStore.listPresets(context)
                        newPresetName = ""
                        showSavePresetDialog = false
                        onStatusMessage("Saved backup preset '$saved'.")
                    }
                ) { Text("Save", color = AetherColors.AccentCyan) }
            },
            dismissButton = {
                TextButton(onClick = { showSavePresetDialog = false }) {
                    Text("Cancel", color = AetherColors.OnSurfaceVariantDark)
                }
            }
        )
    }

    val selectedButton = selectedButtonId?.let { config.findButton(it) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(AetherSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(AetherSpacing.Md)
    ) {
        // Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
            ) {
                AetherOutlinedButton(
                    text = "Back",
                    onClick = onBack
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Touch HUD Studio",
                        style = MaterialTheme.typography.headlineSmall,
                        color = AetherColors.OnSurfaceDark
                    )
                    Text(
                        text = "Add custom buttons, snap & resize controls, and manage saved layouts",
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherColors.OnSurfaceVariantDark
                    )
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (config.useStarHud) AetherColors.AccentViolet.copy(alpha = 0.22f)
                    else AetherColors.SurfaceElevatedDark,
                    border = BorderStroke(
                        1.dp,
                        if (config.useStarHud) AetherColors.AccentBlue else AetherColors.OutlineDark
                    )
                ) {
                    Text(
                        text = if (config.useStarHud) "STAR HUD" else "LEGACY JSON",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (config.useStarHud) AetherColors.AccentCyan else AetherColors.OnSurfaceVariantDark,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
        }

        // Phase 2: Saved Controller Layouts Manager
        item {
            AetherSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        AetherSectionHeader(title = "Controller Layouts")
                    }
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = AetherColors.SurfaceElevatedDark,
                        border = BorderStroke(1.dp, AetherColors.AccentCyan.copy(alpha = 0.55f))
                    ) {
                        Text(
                            text = config.layoutName,
                            style = AetherMonoStyle.copy(fontWeight = FontWeight.SemiBold),
                            color = AetherColors.AccentCyan,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
                Spacer(Modifier.height(AetherSpacing.Xs))

                if (layouts.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Xs),
                        verticalArrangement = Arrangement.spacedBy(AetherSpacing.Xs)
                    ) {
                        layouts.forEach { sum ->
                            val isActive = sum.layoutId == config.layoutId
                            FilterChip(
                                selected = isActive,
                                onClick = {
                                    if (!isActive) {
                                        StarHudConfigStore.switchLayout(context, sum.layoutId)?.let { switched ->
                                            config = switched
                                            selectedButtonId = switched.buttons.firstOrNull()?.id
                                            selectedStructural = null
                                            refreshLayouts()
                                            onStatusMessage("Switched to layout '${switched.layoutName}'.")
                                        }
                                    }
                                },
                                label = { Text("${sum.layoutName} (${sum.buttonCount})") }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(AetherSpacing.Sm))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Xs),
                    verticalArrangement = Arrangement.spacedBy(AetherSpacing.Xs)
                ) {
                    AetherPrimaryButton(
                        text = "+ New Layout",
                        onClick = {
                            layoutNameInput = ""
                            showNewLayoutDialog = true
                        }
                    )
                    AetherTonalButton(
                        text = "Save Layout",
                        onClick = {
                            persistConfig(config)
                            onStatusMessage("Saved '${config.layoutName}'.")
                        }
                    )
                    AetherOutlinedButton(
                        text = "Rename",
                        onClick = {
                            layoutNameInput = config.layoutName
                            showRenameLayoutDialog = true
                        }
                    )
                    AetherOutlinedButton(
                        text = "Duplicate",
                        onClick = {
                            StarHudConfigStore.duplicateLayout(context, config.layoutId)?.let { dup ->
                                config = dup
                                refreshLayouts()
                                onStatusMessage("Duplicated as '${dup.layoutName}'.")
                            }
                        }
                    )
                    if (config.layoutId != StarHudConfig.DEFAULT_LAYOUT_ID) {
                        AetherOutlinedButton(
                            text = "Delete",
                            onClick = {
                                val deletedName = config.layoutName
                                StarHudConfigStore.deleteLayout(context, config.layoutId)?.let { after ->
                                    config = after
                                    selectedButtonId = after.buttons.firstOrNull()?.id
                                    refreshLayouts()
                                    onStatusMessage("Deleted layout '$deletedName'.")
                                }
                            }
                        )
                    }
                    AetherOutlinedButton(
                        text = "Restore Default",
                        onClick = {
                            config = StarHudConfigStore.restoreDefaultLayout(context)
                            selectedButtonId = config.buttons.firstOrNull()?.id
                            selectedStructural = null
                            refreshLayouts()
                            onStatusMessage("Restored default STAR Controller layout.")
                        }
                    )
                }
            }
        }

        // Interactive Preview Canvas
        item {
            AetherAccentCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Interactive Controller Editor",
                            style = MaterialTheme.typography.titleMedium,
                            color = AetherColors.OnSurfaceDark
                        )
                        val selectionSummary = when {
                            selectedButton != null ->
                                "Selected: ${selectedButton.action.displayName} " +
                                    "(${(selectedButton.xFraction * 100).roundToInt()}%, ${(selectedButton.yFraction * 100).roundToInt()}%)"
                            selectedStructural != null -> {
                                val el = config.elementLayout(selectedStructural!!)
                                "Selected: ${selectedStructural!!.displayName} " +
                                    "(${(el.xFraction * 100).roundToInt()}%, ${(el.yFraction * 100).roundToInt()}%)"
                            }
                            else -> "Tap or drag any button, joystick, or hotbar on the canvas"
                        }
                        Text(
                            text = selectionSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = AetherColors.AccentCyan
                        )
                    }
                    AetherPrimaryButton(
                        text = "+ Add Button",
                        icon = AetherIcons.Controls,
                        onClick = { showAddButtonDialog = true }
                    )
                }

                Spacer(Modifier.height(AetherSpacing.Sm))

                StarHudPreviewCanvas(
                    config = config,
                    selectedButtonId = selectedButtonId,
                    selectedStructural = selectedStructural,
                    onSelectButton = { id ->
                        selectedButtonId = id
                        selectedStructural = null
                    },
                    onSelectStructural = { id ->
                        selectedStructural = id
                        selectedButtonId = null
                    },
                    onMoveButton = { id, fx, fy ->
                        persistConfig(config.withButtonPosition(id, fx, fy))
                    },
                    onMoveStructural = { id, fx, fy ->
                        persistConfig(config.withElementPosition(id, fx, fy))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                )

                Spacer(Modifier.height(AetherSpacing.Xs))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
                ) {
                    AetherOutlinedButton(
                        text = "Backup Preset…",
                        onClick = { showSavePresetDialog = true },
                        modifier = Modifier.weight(1f)
                    )
                    AetherOutlinedButton(
                        text = "Reset Default",
                        onClick = {
                            config = StarHudConfigStore.resetToDefault(context)
                            selectedButtonId = config.buttons.firstOrNull()?.id
                            selectedStructural = null
                            refreshLayouts()
                            onStatusMessage("Touch HUD reset to STAR default layout.")
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                if (presets.isNotEmpty()) {
                    Spacer(Modifier.height(AetherSpacing.Xs))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Xs),
                        verticalArrangement = Arrangement.spacedBy(AetherSpacing.Xs)
                    ) {
                        presets.forEach { preset ->
                            FilterChip(
                                selected = false,
                                onClick = {
                                    StarHudConfigStore.restorePreset(context, preset)?.let { restored ->
                                        config = restored
                                        selectedButtonId = restored.buttons.firstOrNull()?.id
                                        refreshLayouts()
                                        onStatusMessage("Loaded backup preset '$preset'.")
                                    }
                                },
                                label = { Text("Backup: $preset") }
                            )
                        }
                    }
                }
            }
        }

        // Phase 1: Selected Button Inspector
        if (selectedButton != null) {
            item {
                SelectedButtonInspectorCard(
                    button = selectedButton,
                    onUpdate = { updated -> persistConfig(config.withButtonUpdated(updated)) },
                    onDuplicate = {
                        val next = config.withDuplicatedButton(selectedButton.id)
                        persistConfig(next)
                        selectedButtonId = next.buttons.lastOrNull()?.id
                        onStatusMessage("Duplicated '${selectedButton.action.displayName}'.")
                    },
                    onReset = {
                        persistConfig(config.withResetButton(selectedButton.id))
                        onStatusMessage("Reset button properties.")
                    },
                    onDelete = {
                        val next = config.withRemovedButton(selectedButton.id)
                        persistConfig(next)
                        selectedButtonId = next.buttons.firstOrNull()?.id
                        onStatusMessage(
                            if (selectedButton.isBuiltIn) "Hidden built-in button." else "Deleted custom button."
                        )
                    }
                )
            }
        }

        // Core Behavior & Editor Assistance Switches
        item {
            AetherSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                AetherSectionHeader(title = "HUD Mode, Guides & Input Behavior")
                Spacer(Modifier.height(AetherSpacing.Xs))

                HudSwitchRow(
                    title = "Enable STAR Mobile HUD",
                    subtitle = "Use Bedrock-inspired joystick, customizable action buttons & bottom-center hotbar",
                    checked = config.useStarHud,
                    onCheckedChange = { persistConfig(config.copy(useStarHud = it)) }
                )
                HudSwitchRow(
                    title = "Snap to Alignment Guides",
                    subtitle = "Magnetically snap dragged buttons to screen center lines & sibling centers",
                    checked = config.snapToGuides,
                    onCheckedChange = {
                        persistConfig(config.copy(snapToGuides = it, showAlignmentGuides = it))
                    }
                )
                HudSwitchRow(
                    title = "Dynamic Joystick Position",
                    subtitle = "Spawns the movement stick wherever your left thumb lands in the movement zone",
                    checked = config.joystickDynamic,
                    onCheckedChange = { persistConfig(config.copy(joystickDynamic = it)) }
                )
                HudSwitchRow(
                    title = "Sneak Toggle Mode",
                    subtitle = if (config.sneakToggle) "Tap to toggle Sneak on/off" else "Hold to Sneak, release to stand",
                    checked = config.sneakToggle,
                    onCheckedChange = {
                        val nextMode = if (it) StarHudInteractionMode.TOGGLE else StarHudInteractionMode.HOLD
                        var next = config.copy(sneakToggle = it)
                        next.findButton("sneak")?.let { sb ->
                            next = next.withButtonUpdated(sb.copy(interactionMode = nextMode))
                        }
                        persistConfig(next)
                    }
                )
                HudSwitchRow(
                    title = "Sprint Toggle Mode",
                    subtitle = if (config.sprintToggle) "Tap to toggle Sprint on/off" else "Hold while moving to Sprint",
                    checked = config.sprintToggle,
                    onCheckedChange = {
                        val nextMode = if (it) StarHudInteractionMode.TOGGLE else StarHudInteractionMode.HOLD
                        var next = config.copy(sprintToggle = it)
                        next.findButton("sprint")?.let { sp ->
                            next = next.withButtonUpdated(sp.copy(interactionMode = nextMode))
                        }
                        persistConfig(next)
                    }
                )
                HudSwitchRow(
                    title = "Screen Tap & Long-Press Gestures",
                    subtitle = "Allow tapping/long-pressing directly on world canvas to interact or mine",
                    checked = config.screenGesturesEnabled,
                    onCheckedChange = { persistConfig(config.copy(screenGesturesEnabled = it)) }
                )

                // Phase 4: Dev-only Input Debug HUD toggle (stripped cleanly outside BuildConfig.DEBUG)
                if (BuildConfig.DEBUG) {
                    HudSwitchRow(
                        title = "Dev Input Monitor Overlay",
                        subtitle = "Show live pointer IDs, button leases, key ref-counts & Reset All button in-game (Debug builds only)",
                        checked = config.showInputDebugPanel,
                        onCheckedChange = { persistConfig(config.copy(showInputDebugPanel = it)) }
                    )
                }

                Spacer(Modifier.height(AetherSpacing.Xs))
                AetherOutlinedButton(
                    text = "Open Legacy JSON Controls Editor",
                    onClick = onOpenLegacyControls,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Global Sliders
        item {
            AetherSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                AetherSectionHeader(title = "Scale, Opacity & Sensitivity")
                Spacer(Modifier.height(AetherSpacing.Xs))

                HudSliderRow(
                    label = "Global HUD Scale",
                    valueLabel = "${config.scalePercent()}%",
                    value = config.hudScale,
                    range = StarHudConfig.MIN_HUD_SCALE..StarHudConfig.MAX_HUD_SCALE,
                    onValueChange = { config = config.copy(hudScale = it) },
                    onValueChangeFinished = { persistConfig(config) }
                )
                HudSliderRow(
                    label = "Global HUD Opacity",
                    valueLabel = "${config.opacityPercent()}%",
                    value = config.hudOpacity,
                    range = StarHudConfig.MIN_HUD_OPACITY..StarHudConfig.MAX_HUD_OPACITY,
                    onValueChange = { config = config.copy(hudOpacity = it) },
                    onValueChangeFinished = { persistConfig(config) }
                )
                HudSliderRow(
                    label = "Joystick Size",
                    valueLabel = "${config.joystickSizePercent()}%",
                    value = config.joystickSize,
                    range = StarHudConfig.MIN_JOYSTICK_SIZE..StarHudConfig.MAX_JOYSTICK_SIZE,
                    onValueChange = { config = config.copy(joystickSize = it) },
                    onValueChangeFinished = { persistConfig(config) }
                )
                HudSliderRow(
                    label = "Hotbar Height Scale",
                    valueLabel = "${config.hotbarScalePercent()}%",
                    value = config.hotbarScale,
                    range = StarHudConfig.MIN_HOTBAR_SCALE..StarHudConfig.MAX_HOTBAR_SCALE,
                    onValueChange = { config = config.copy(hotbarScale = it) },
                    onValueChangeFinished = { persistConfig(config) }
                )
                HudSliderRow(
                    label = "Camera Sensitivity",
                    valueLabel = "${config.cameraSensitivityPercent()}%",
                    value = config.cameraSensitivity,
                    range = StarHudConfig.MIN_CAMERA_SENSITIVITY..StarHudConfig.MAX_CAMERA_SENSITIVITY,
                    onValueChange = { config = config.copy(cameraSensitivity = it) },
                    onValueChangeFinished = { persistConfig(config) }
                )
            }
        }

        // Dynamic Visibility Toggles for Structural + All Buttons
        item {
            AetherSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                AetherSectionHeader(title = "Controls Visibility")
                Spacer(Modifier.height(AetherSpacing.Xs))

                HudSwitchRow(
                    title = StarHudElementId.JOYSTICK.displayName,
                    subtitle = StarHudElementId.JOYSTICK.description,
                    checked = config.isElementEnabled(StarHudElementId.JOYSTICK),
                    onCheckedChange = { persistConfig(config.withElementEnabled(StarHudElementId.JOYSTICK, it)) }
                )
                HudSwitchRow(
                    title = StarHudElementId.HOTBAR.displayName,
                    subtitle = StarHudElementId.HOTBAR.description,
                    checked = config.isElementEnabled(StarHudElementId.HOTBAR),
                    onCheckedChange = { persistConfig(config.withElementEnabled(StarHudElementId.HOTBAR, it)) }
                )
                config.buttons.forEach { btn ->
                    HudSwitchRow(
                        title = "${btn.action.displayName}${if (btn.label.isNotBlank()) " (${btn.label})" else ""}",
                        subtitle = "${btn.interactionMode.displayName} • ${btn.widthDp.roundToInt()}×${btn.heightDp.roundToInt()}dp",
                        checked = btn.visible,
                        onCheckedChange = {
                            persistConfig(config.withButtonUpdated(btn.copy(visible = it)))
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddButtonDialog(
    onDismiss: () -> Unit,
    onConfirm: (StarHudActionType, Int, String, StarHudInteractionMode) -> Unit
) {
    val actions = remember { StarHudActionType.assignableButtonActions() }
    val keys = remember { StarHudActionRegistry.supportedKeys }

    var selectedAction by remember { mutableStateOf(StarHudActionType.DROP_ITEM) }
    var selectedKeycode by remember { mutableStateOf(keys.first().keycode) }
    var labelInput by remember { mutableStateOf("") }
    var selectedMode by remember { mutableStateOf(selectedAction.defaultInteractionMode) }

    val validation = StarHudActionRegistry.validateBinding(
        selectedAction,
        if (selectedAction == StarHudActionType.CUSTOM_KEY) selectedKeycode else 0
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AetherColors.SurfaceElevatedDark,
        title = { Text("Add Controller Button", color = AetherColors.OnSurfaceDark) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Select Action:", style = MaterialTheme.typography.labelLarge, color = AetherColors.OnSurfaceDark)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    actions.forEach { act ->
                        FilterChip(
                            selected = selectedAction == act,
                            onClick = {
                                selectedAction = act
                                selectedMode = act.defaultInteractionMode
                            },
                            label = { Text(act.displayName) }
                        )
                    }
                }

                if (selectedAction == StarHudActionType.CUSTOM_KEY) {
                    Text(
                        "Supported Minecraft Key:",
                        style = MaterialTheme.typography.labelLarge,
                        color = AetherColors.OnSurfaceDark
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        keys.forEach { keyOpt ->
                            FilterChip(
                                selected = selectedKeycode == keyOpt.keycode,
                                onClick = { selectedKeycode = keyOpt.keycode },
                                label = { Text(keyOpt.shortLabel) }
                            )
                        }
                    }
                }

                if (selectedAction.allowHoldOrToggle) {
                    Text(
                        "Activation Behavior:",
                        style = MaterialTheme.typography.labelLarge,
                        color = AetherColors.OnSurfaceDark
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StarHudInteractionMode.values().forEach { m ->
                            FilterChip(
                                selected = selectedMode == m,
                                onClick = { selectedMode = m },
                                label = { Text(m.displayName) }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = labelInput,
                    onValueChange = { labelInput = it.take(12) },
                    singleLine = true,
                    label = { Text("Custom Label (optional)") },
                    placeholder = { Text(selectedAction.defaultShortLabel.ifBlank { "Auto" }) }
                )

                if (!validation.isSupported) {
                    Text(
                        text = validation.warningMessage ?: "Unsupported action or key binding.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFEF4444)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = validation.isSupported,
                onClick = {
                    onConfirm(
                        selectedAction,
                        if (selectedAction == StarHudActionType.CUSTOM_KEY) selectedKeycode else 0,
                        labelInput.trim(),
                        if (selectedAction.allowHoldOrToggle) selectedMode else StarHudInteractionMode.TAP
                    )
                }
            ) {
                Text("Add Button", color = if (validation.isSupported) AetherColors.AccentCyan else AetherColors.OnSurfaceVariantDark)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = AetherColors.OnSurfaceVariantDark)
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectedButtonInspectorCard(
    button: StarHudButtonConfig,
    onUpdate: (StarHudButtonConfig) -> Unit,
    onDuplicate: () -> Unit,
    onReset: () -> Unit,
    onDelete: () -> Unit
) {
    val actions = remember { StarHudActionType.assignableButtonActions() }
    val keys = remember { StarHudActionRegistry.supportedKeys }

    AetherSurfaceCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                AetherSectionHeader(title = "Button Settings: ${button.action.displayName}")
            }
            Switch(
                checked = button.visible,
                onCheckedChange = { onUpdate(button.copy(visible = it)) }
            )
        }

        Spacer(Modifier.height(AetherSpacing.Xs))

        OutlinedTextField(
            value = button.label,
            onValueChange = { onUpdate(button.copy(label = it.take(12))) },
            singleLine = true,
            label = { Text("Button Label") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(AetherSpacing.Xs))

        Text("Action Binding", style = MaterialTheme.typography.labelMedium, color = AetherColors.OnSurfaceVariantDark)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            actions.forEach { act ->
                FilterChip(
                    selected = button.action == act,
                    onClick = {
                        val nextKey = if (act == StarHudActionType.CUSTOM_KEY && !StarHudActionRegistry.isKeySupported(button.customKeycode)) {
                            keys.first().keycode
                        } else {
                            button.customKeycode
                        }
                        onUpdate(
                            button.copy(
                                action = act,
                                customKeycode = nextKey,
                                icon = act.defaultIcon,
                                interactionMode = if (act.allowHoldOrToggle) button.interactionMode else StarHudInteractionMode.TAP
                            )
                        )
                    },
                    label = { Text(act.displayName) }
                )
            }
        }

        if (button.action == StarHudActionType.CUSTOM_KEY) {
            Spacer(Modifier.height(AetherSpacing.Xs))
            Text("Custom Key", style = MaterialTheme.typography.labelMedium, color = AetherColors.OnSurfaceVariantDark)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                keys.forEach { keyOpt ->
                    FilterChip(
                        selected = button.customKeycode == keyOpt.keycode,
                        onClick = { onUpdate(button.copy(customKeycode = keyOpt.keycode)) },
                        label = { Text(keyOpt.shortLabel) }
                    )
                }
            }
        }

        if (button.action.allowHoldOrToggle) {
            Spacer(Modifier.height(AetherSpacing.Xs))
            Text("Activation Behavior", style = MaterialTheme.typography.labelMedium, color = AetherColors.OnSurfaceVariantDark)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StarHudInteractionMode.values().forEach { mode ->
                    FilterChip(
                        selected = button.interactionMode == mode,
                        onClick = { onUpdate(button.copy(interactionMode = mode)) },
                        label = { Text(mode.displayName) }
                    )
                }
            }
        }

        Spacer(Modifier.height(AetherSpacing.Xs))
        Text("Icon Style", style = MaterialTheme.typography.labelMedium, color = AetherColors.OnSurfaceVariantDark)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            StarHudIconId.values().forEach { iconId ->
                FilterChip(
                    selected = button.icon == iconId,
                    onClick = { onUpdate(button.copy(icon = iconId)) },
                    label = { Text(iconId.name.lowercase().replace('_', ' ')) }
                )
            }
        }

        HudSliderRow(
            label = "Width",
            valueLabel = "${button.widthDp.roundToInt()} dp",
            value = button.widthDp,
            range = StarHudButtonConfig.MIN_BUTTON_DP..StarHudButtonConfig.MAX_BUTTON_DP,
            onValueChange = { onUpdate(button.copy(widthDp = it)) },
            onValueChangeFinished = {}
        )
        HudSliderRow(
            label = "Height",
            valueLabel = "${button.heightDp.roundToInt()} dp",
            value = button.heightDp,
            range = StarHudButtonConfig.MIN_BUTTON_DP..StarHudButtonConfig.MAX_BUTTON_DP,
            onValueChange = { onUpdate(button.copy(heightDp = it)) },
            onValueChangeFinished = {}
        )
        HudSliderRow(
            label = "Button Opacity",
            valueLabel = "${(button.opacity * 100f).roundToInt()}%",
            value = button.opacity,
            range = StarHudButtonConfig.MIN_BUTTON_OPACITY..1.0f,
            onValueChange = { onUpdate(button.copy(opacity = it)) },
            onValueChangeFinished = {}
        )
        HudSliderRow(
            label = "Background Fill Alpha",
            valueLabel = "${(button.backgroundAlpha * 100f).roundToInt()}%",
            value = button.backgroundAlpha,
            range = 0f..1.0f,
            onValueChange = { onUpdate(button.copy(backgroundAlpha = it)) },
            onValueChangeFinished = {}
        )

        Spacer(Modifier.height(AetherSpacing.Xs))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Xs)
        ) {
            AetherTonalButton(text = "Duplicate", onClick = onDuplicate, modifier = Modifier.weight(1f))
            AetherOutlinedButton(text = "Reset", onClick = onReset, modifier = Modifier.weight(1f))
            AetherOutlinedButton(
                text = if (button.isBuiltIn) "Hide" else "Delete",
                onClick = onDelete,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun StarHudPreviewCanvas(
    config: StarHudConfig,
    selectedButtonId: String?,
    selectedStructural: StarHudElementId?,
    onSelectButton: (String) -> Unit,
    onSelectStructural: (StarHudElementId) -> Unit,
    onMoveButton: (String, Float, Float) -> Unit,
    onMoveStructural: (StarHudElementId, Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current.density
    val virtualWidthDp = 800f

    val strokePaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
    }
    val fillPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
        }
    }
    val textPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = android.graphics.Paint.Align.CENTER
            isFakeBoldText = true
        }
    }
    val scratchPath = remember { android.graphics.Path() }
    val iconRect = remember { android.graphics.RectF() }

    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(AetherColors.BackgroundDark)
            .border(1.dp, AetherColors.OutlineDark, RoundedCornerShape(14.dp))
    ) {
        val pxW = constraints.maxWidth
        val pxH = constraints.maxHeight
        val scaleFactor = if (pxW > 0) pxW.toFloat() / (virtualWidthDp * density) else 0.5f
        val previewDensity = density * scaleFactor
        val insets = remember { StarHudSafeInsets(0, 0, 0, 0) }

        val joyRect = if (config.isElementEnabled(StarHudElementId.JOYSTICK)) {
            StarHudLayoutSolver.solveElementRect(
                StarHudElementId.JOYSTICK, config, pxW, pxH, previewDensity, insets
            )
        } else null
        val hotbarRect = if (config.isElementEnabled(StarHudElementId.HOTBAR)) {
            StarHudLayoutSolver.solveElementRect(
                StarHudElementId.HOTBAR, config, pxW, pxH, previewDensity, insets
            )
        } else null
        val btnRects = config.buttons.map { btn ->
            btn to StarHudLayoutSolver.solveButtonRect(btn, config, pxW, pxH, previewDensity, insets)
        }

        var activeDragBtnId by remember { mutableStateOf<String?>(null) }
        var activeDragStruct by remember { mutableStateOf<StarHudElementId?>(null) }
        var dragLeft by remember { mutableStateOf(0f) }
        var dragTop by remember { mutableStateOf(0f) }
        var dragW by remember { mutableStateOf(1) }
        var dragH by remember { mutableStateOf(1) }
        var snapGuideX by remember { mutableStateOf<Int?>(null) }
        var snapGuideY by remember { mutableStateOf<Int?>(null) }

        fun findHitAt(x: Float, y: Float): Pair<String?, StarHudElementId?> {
            val hitBtn = btnRects.lastOrNull { (_, r) ->
                x >= r.left && x <= r.right && y >= r.top && y <= r.bottom
            }
            if (hitBtn != null) return hitBtn.first.id to null
            if (joyRect != null && x >= joyRect.left && x <= joyRect.right && y >= joyRect.top && y <= joyRect.bottom) {
                return null to StarHudElementId.JOYSTICK
            }
            if (hotbarRect != null && x >= hotbarRect.left && x <= hotbarRect.right && y >= hotbarRect.top && y <= hotbarRect.bottom) {
                return null to StarHudElementId.HOTBAR
            }
            return null to null
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(pxW, pxH, previewDensity, config) {
                    detectTapGestures { pos ->
                        val (btnId, structId) = findHitAt(pos.x, pos.y)
                        if (btnId != null) onSelectButton(btnId)
                        else if (structId != null) onSelectStructural(structId)
                    }
                }
                .pointerInput(pxW, pxH, previewDensity, config) {
                    detectDragGestures(
                        onDragStart = { pos ->
                            val (btnId, structId) = findHitAt(pos.x, pos.y)
                            activeDragBtnId = btnId
                            activeDragStruct = structId
                            if (btnId != null) {
                                onSelectButton(btnId)
                                val r = btnRects.firstOrNull { it.first.id == btnId }?.second
                                if (r != null) {
                                    dragLeft = r.left.toFloat()
                                    dragTop = r.top.toFloat()
                                    dragW = r.width
                                    dragH = r.height
                                }
                            } else if (structId != null) {
                                onSelectStructural(structId)
                                val r = if (structId == StarHudElementId.JOYSTICK) joyRect else hotbarRect
                                if (r != null) {
                                    dragLeft = r.left.toFloat()
                                    dragTop = r.top.toFloat()
                                    dragW = r.width
                                    dragH = r.height
                                }
                            }
                        },
                        onDrag = { change, dragAmount ->
                            if (activeDragBtnId == null && activeDragStruct == null) return@detectDragGestures
                            change.consume()
                            dragLeft += dragAmount.x
                            dragTop += dragAmount.y

                            var targetLeft = dragLeft.roundToInt()
                            var targetTop = dragTop.roundToInt()
                            if (config.snapToGuides) {
                                val peers = buildList {
                                    if (joyRect != null && activeDragStruct != StarHudElementId.JOYSTICK) {
                                        add((joyRect.left + joyRect.right) / 2 to (joyRect.top + joyRect.bottom) / 2)
                                    }
                                    if (hotbarRect != null && activeDragStruct != StarHudElementId.HOTBAR) {
                                        add((hotbarRect.left + hotbarRect.right) / 2 to (hotbarRect.top + hotbarRect.bottom) / 2)
                                    }
                                    btnRects.forEach { (b, r) ->
                                        if (b.id != activeDragBtnId) {
                                            add((r.left + r.right) / 2 to (r.top + r.bottom) / 2)
                                        }
                                    }
                                }
                                val snapped = StarHudLayoutSolver.snapDragPosition(
                                    targetLeft, targetTop, dragW, dragH, pxW, pxH, previewDensity, peers
                                )
                                targetLeft = snapped.leftPx
                                targetTop = snapped.topPx
                                snapGuideX = snapped.guideX
                                snapGuideY = snapped.guideY
                            } else {
                                snapGuideX = null
                                snapGuideY = null
                            }

                            activeDragBtnId?.let { id ->
                                val (fx, fy) = StarHudLayoutSolver.fractionsFromTopLeftForButton(
                                    targetLeft, targetTop, dragW, dragH, pxW, pxH, previewDensity, insets
                                )
                                onMoveButton(id, fx, fy)
                            }
                            activeDragStruct?.let { st ->
                                val (fx, fy) = StarHudLayoutSolver.fractionsFromTopLeft(
                                    st, targetLeft, targetTop, dragW, dragH, pxW, pxH, previewDensity, insets
                                )
                                onMoveStructural(st, fx, fy)
                            }
                        },
                        onDragEnd = {
                            activeDragBtnId = null
                            activeDragStruct = null
                            snapGuideX = null
                            snapGuideY = null
                        },
                        onDragCancel = {
                            activeDragBtnId = null
                            activeDragStruct = null
                            snapGuideX = null
                            snapGuideY = null
                        }
                    )
                }
        ) {
            drawRect(Color(0xFF0D1424), Offset.Zero, Size(size.width, size.height * 0.56f))
            drawRect(Color(0xFF0A101B), Offset(0f, size.height * 0.56f), Size(size.width, size.height * 0.44f))

            if (config.showAlignmentGuides) {
                val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                drawLine(AetherColors.OutlineDark.copy(alpha = 0.65f), Offset(size.width * 0.5f, 0f), Offset(size.width * 0.5f, size.height), strokeWidth = 1f, pathEffect = dash)
                drawLine(AetherColors.OutlineDark.copy(alpha = 0.65f), Offset(0f, size.height * 0.5f), Offset(size.width, size.height * 0.5f), strokeWidth = 1f, pathEffect = dash)
            }
            snapGuideX?.let { gx ->
                drawLine(AetherColors.AccentCyan, Offset(gx.toFloat(), 0f), Offset(gx.toFloat(), size.height), 1.8f)
            }
            snapGuideY?.let { gy ->
                drawLine(AetherColors.AccentCyan, Offset(0f, gy.toFloat()), Offset(size.width, gy.toFloat()), 1.8f)
            }

            // Center reticle
            val cx = size.width * 0.5f
            val cy = size.height * 0.5f
            drawLine(Color.White.copy(alpha = 0.55f), Offset(cx - 8f, cy), Offset(cx + 8f, cy), 1.8f)
            drawLine(Color.White.copy(alpha = 0.55f), Offset(cx, cy - 8f), Offset(cx, cy + 8f), 1.8f)

            val opacity = config.hudOpacity

            joyRect?.let { rect ->
                val sel = selectedStructural == StarHudElementId.JOYSTICK
                val border = if (sel) Color(0xFFF59E0B) else AetherColors.AccentBlue.copy(alpha = 0.8f * opacity)
                val fill = AetherColors.SurfaceTranslucentDark.copy(alpha = 0.85f * opacity)
                val r = min(rect.width, rect.height) * 0.5f
                val jx = rect.left + rect.width * 0.5f
                val jy = rect.top + rect.height * 0.5f
                drawCircle(fill, radius = r, center = Offset(jx, jy))
                drawCircle(border, radius = r, center = Offset(jx, jy), style = Stroke(width = if (sel) 2.5f else 1.5f))
                drawCircle(AetherColors.AccentViolet.copy(alpha = 0.7f * opacity), radius = r * 0.30f, center = Offset(jx, jy))
                drawCircle(AetherColors.AccentCyan.copy(alpha = 0.9f), radius = r * 0.30f, center = Offset(jx, jy), style = Stroke(width = 1.5f))
            }

            hotbarRect?.let { rect ->
                val sel = selectedStructural == StarHudElementId.HOTBAR
                val border = if (sel) Color(0xFFF59E0B) else AetherColors.AccentBlue.copy(alpha = 0.8f * opacity)
                val cr = 6f
                drawRoundRect(
                    AetherColors.SurfaceDark.copy(alpha = 0.35f * opacity),
                    Offset(rect.left.toFloat(), rect.top.toFloat()),
                    Size(rect.width.toFloat(), rect.height.toFloat()),
                    CornerRadius(cr, cr)
                )
                drawRoundRect(
                    border,
                    Offset(rect.left.toFloat(), rect.top.toFloat()),
                    Size(rect.width.toFloat(), rect.height.toFloat()),
                    CornerRadius(cr, cr),
                    style = Stroke(width = if (sel) 2.5f else 1.5f)
                )
                val slotW = rect.width / 9f
                for (i in 1 until 9) {
                    val sx = rect.left + slotW * i
                    drawLine(AetherColors.OutlineDark.copy(alpha = 0.8f * opacity), Offset(sx, rect.top + 2f), Offset(sx, rect.bottom - 2f), 1f)
                }
                drawRoundRect(AetherColors.AccentCyan, Offset(rect.left + 1.5f, rect.top + 1.5f), Size(slotW - 3f, rect.height - 3f), CornerRadius(4f, 4f), style = Stroke(width = 2f))
            }

            btnRects.forEach { (btn, rect) ->
                val sel = btn.id == selectedButtonId
                val alphaMul = if (btn.visible) (opacity * btn.opacity) else (opacity * btn.opacity * 0.35f)
                val fill = AetherColors.SurfaceTranslucentDark.copy(alpha = 0.85f * alphaMul * btn.backgroundAlpha)
                val border = when {
                    sel -> AetherColors.AccentCyan
                    !btn.visible -> AetherColors.OutlineDark.copy(alpha = 0.45f)
                    else -> AetherColors.AccentBlue.copy(alpha = 0.8f * alphaMul)
                }
                val minDim = min(rect.width, rect.height).toFloat()
                val corner = if (btn.action == StarHudActionType.JUMP) minDim * 0.5f else minDim * 0.28f
                drawRoundRect(fill, Offset(rect.left.toFloat(), rect.top.toFloat()), Size(rect.width.toFloat(), rect.height.toFloat()), CornerRadius(corner, corner))
                drawRoundRect(
                    border,
                    Offset(rect.left.toFloat(), rect.top.toFloat()),
                    Size(rect.width.toFloat(), rect.height.toFloat()),
                    CornerRadius(corner, corner),
                    style = Stroke(width = if (sel) 2.6f else 1.4f)
                )

                val iconRadius = minDim * 0.22f
                val icx = rect.left + rect.width * 0.5f
                val icy = rect.top + rect.height * (if (btn.effectiveShortLabel().isNotEmpty() && btn.icon != StarHudIconId.NONE) 0.42f else 0.5f)
                iconRect.set(icx - iconRadius, icy - iconRadius, icx + iconRadius, icy + iconRadius)
                strokePaint.color = android.graphics.Color.argb((230 * alphaMul).roundToInt().coerceIn(0, 255), 226, 232, 240)
                strokePaint.strokeWidth = (minDim * 0.055f).coerceAtLeast(1.3f)
                fillPaint.color = strokePaint.color
                drawIntoCanvas { canvas ->
                    if (btn.icon != StarHudIconId.NONE) {
                        StarHudIcons.drawIcon(canvas.nativeCanvas, btn.icon, iconRect, strokePaint, fillPaint, scratchPath)
                    }
                    val label = btn.effectiveShortLabel()
                    if (label.isNotEmpty()) {
                        textPaint.color = android.graphics.Color.argb((220 * alphaMul).roundToInt().coerceIn(0, 255), 226, 232, 240)
                        textPaint.textSize = (minDim * 0.22f).coerceAtLeast(8f)
                        val ty = if (btn.icon == StarHudIconId.NONE) rect.top + rect.height * 0.58f else rect.bottom - minDim * 0.14f
                        canvas.nativeCanvas.drawText(label, icx, ty, textPaint)
                    }
                }
            }
        }
    }
}

@Composable
private fun HudSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, color = AetherColors.OnSurfaceDark)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = AetherColors.OnSurfaceVariantDark)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun HudSliderRow(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium, color = AetherColors.OnSurfaceDark)
            Text(
                text = valueLabel,
                style = AetherMonoStyle.copy(fontWeight = FontWeight.SemiBold),
                color = AetherColors.AccentCyan
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = range
        )
    }
}
