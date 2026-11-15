package com.star.launcher.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.star.launcher.ui.components.AetherAccentCard
import com.star.launcher.ui.components.AetherBrandMark
import com.star.launcher.ui.components.AetherIcons
import com.star.launcher.ui.components.AetherOutlinedButton
import com.star.launcher.ui.components.AetherPrimaryButton
import com.star.launcher.ui.components.AetherSurfaceCard
import com.star.launcher.ui.components.AetherTonalButton
import com.star.launcher.ui.components.StarColorPickerDialog
import com.star.launcher.ui.theme.AetherMonoStyle
import com.star.launcher.ui.theme.AetherSpacing
import com.star.launcher.ui.theme.AetherTheme
import com.star.launcher.ui.theme.liquid.LocalStarTheme
import com.star.launcher.ui.theme.liquid.StarGlassDialog
import com.star.launcher.ui.theme.liquid.StarLiquidGlassSurface
import com.star.launcher.ui.theme.liquid.StarWallpaperBackdrop
import com.star.launcher.ui.theme.studio.BackgroundImportResult
import com.star.launcher.ui.theme.studio.StarBackgroundConfig
import com.star.launcher.ui.theme.studio.StarBackgroundEngine
import com.star.launcher.ui.theme.studio.StarColorPalette
import com.star.launcher.ui.theme.studio.StarComponentTarget
import com.star.launcher.ui.theme.studio.StarEffectQuality
import com.star.launcher.ui.theme.studio.StarGlassConfig
import com.star.launcher.ui.theme.studio.StarPresetId
import com.star.launcher.ui.theme.studio.StarResolvedTheme
import com.star.launcher.ui.theme.studio.StarSavedThemeEntry
import com.star.launcher.ui.theme.studio.StarStudioCategory
import com.star.launcher.ui.theme.studio.StarThemeConfig
import com.star.launcher.ui.theme.studio.StarThemeImportResult
import com.star.launcher.ui.theme.studio.StarThemeJsonSerializer
import com.star.launcher.ui.theme.studio.StarThemeManager
import com.star.launcher.ui.theme.studio.StarThemePresets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class ColorEditField(val label: String) {
    PRIMARY_ACCENT("Primary Accent"),
    SECONDARY_ACCENT("Secondary Accent"),
    BACKGROUND_TINT("Background Tint"),
    TEXT_COLOR("Text Color"),
    BORDER_COLOR("Border Color"),
    GRADIENT_START("Gradient Start"),
    GRADIENT_END("Gradient End"),
    HIGHLIGHT("Highlight Color"),
    OVERLAY_TOP("Overlay Top Color"),
    OVERLAY_BOTTOM("Overlay Bottom Color"),
    COMPONENT_ACCENT("Component Accent"),
    COMPONENT_GRAD_START("Component Gradient Start"),
    COMPONENT_GRAD_END("Component Gradient End")
}

private enum class PreviewSceneTab(val label: String) {
    OVERVIEW("Overview"),
    VERSIONS_MODS("Versions & Mods"),
    DIALOG("Dialog")
}

/**
 * STAR UI Studio — Dedicated Liquid Glass customization workspace with live draft preview,
 * custom wallpaper cropping/adjustments, component-level overrides, presets, color picker,
 * and versioned JSON theme import/export.
 */
@Composable
fun StarUiStudioWorkspace(
    isExpandedWidth: Boolean = false,
    reducedMotion: Boolean = false,
    onStatusMessage: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(context) {
        StarThemeManager.ensureInitialized(context)
    }

    val appliedTheme by StarThemeManager.activeThemeFlow.collectAsStateWithLifecycle()
    val savedThemes by StarThemeManager.savedThemesFlow.collectAsStateWithLifecycle()

    var draftTheme by remember { mutableStateOf(appliedTheme) }
    var selectedCategory by remember { mutableStateOf(StarStudioCategory.PRESETS) }
    var selectedComponent by remember { mutableStateOf(StarComponentTarget.CARDS) }
    var previewVisible by remember { mutableStateOf(true) }
    var previewExpanded by remember { mutableStateOf(false) }
    var previewSceneTab by remember { mutableStateOf(PreviewSceneTab.OVERVIEW) }

    // Dialog states
    var editingColorField by remember { mutableStateOf<ColorEditField?>(null) }
    var showSaveThemeDialog by remember { mutableStateOf(false) }
    var showResetConfirmDialog by remember { mutableStateOf(false) }
    var renamingEntry by remember { mutableStateOf<StarSavedThemeEntry?>(null) }
    var deletingEntry by remember { mutableStateOf<StarSavedThemeEntry?>(null) }
    var showPasteImportDialog by remember { mutableStateOf(false) }
    var pendingExportJson by remember { mutableStateOf<String?>(null) }

    val hasUnappliedChanges = remember(draftTheme, appliedTheme) {
        draftTheme.sanitized() != appliedTheme.sanitized()
    }
    val draftResolved = remember(draftTheme) { StarResolvedTheme.resolve(draftTheme) }

    suspend fun handlePickedImageUri(uri: Uri?) {
        if (uri == null) return
        when (val res = StarBackgroundEngine.importBackgroundFromUri(context, uri)) {
            is BackgroundImportResult.Success -> {
                draftTheme = draftTheme.copy(
                    presetId = StarPresetId.CUSTOM,
                    background = draftTheme.background.copy(
                        enabled = true,
                        imagePath = res.savedPath
                    )
                )
                onStatusMessage("Wallpaper imported into preview. Tap Apply Changes to keep it live.")
            }
            is BackgroundImportResult.Error -> {
                onStatusMessage(res.reason)
            }
        }
    }

    // Modern Android Photo Picker (zero storage permissions required)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        scope.launch { handlePickedImageUri(uri) }
    }

    // SAF file picker fallback for PNG / JPG / WEBP
    val imageDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        scope.launch { handlePickedImageUri(uri) }
    }

    // SAF Theme Export (.json)
    val exportThemeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val json = pendingExportJson
        pendingExportJson = null
        if (uri != null && json != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { os ->
                            os.write(json.toByteArray(Charsets.UTF_8))
                        }
                        true
                    }.getOrDefault(false)
                }
                onStatusMessage(if (ok) "Exported STAR theme JSON file." else "Failed writing exported theme file.")
            }
        }
    }

    // SAF Theme Import (.json)
    val importThemeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val raw = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                        val buffer = CharArray(StarThemeJsonSerializer.MAX_IMPORT_CHARS + 1)
                        val read = reader.read(buffer)
                        if (read <= 0) "" else String(buffer, 0, read)
                    }
                }.getOrNull()
            }
            if (raw.isNullOrBlank()) {
                onStatusMessage("Could not read selected theme JSON file.")
                return@launch
            }
            when (val res = StarThemeManager.importThemeJson(context, raw, saveToLibrary = true, applyImmediately = false)) {
                is StarThemeImportResult.Success -> {
                    draftTheme = res.config
                    val warnNote = if (res.warnings.isNotEmpty()) " (${res.warnings.first()})" else ""
                    onStatusMessage("Imported '${res.config.themeName}' into preview & library$warnNote")
                }
                is StarThemeImportResult.Error -> {
                    onStatusMessage("Import rejected: ${res.reason}")
                }
            }
        }
    }

    // Dialogs
    editingColorField?.let { field ->
        val initialArgb = when (field) {
            ColorEditField.PRIMARY_ACCENT -> draftTheme.colors.primaryAccentArgb
            ColorEditField.SECONDARY_ACCENT -> draftTheme.colors.secondaryAccentArgb
            ColorEditField.BACKGROUND_TINT -> draftTheme.colors.backgroundTintArgb
            ColorEditField.TEXT_COLOR -> draftTheme.colors.textColorArgb
            ColorEditField.BORDER_COLOR -> draftTheme.colors.borderColorArgb
            ColorEditField.GRADIENT_START -> draftTheme.colors.gradientStartArgb
            ColorEditField.GRADIENT_END -> draftTheme.colors.gradientEndArgb
            ColorEditField.HIGHLIGHT -> draftTheme.colors.highlightColorArgb
            ColorEditField.OVERLAY_TOP -> draftTheme.background.gradientOverlayTopArgb
            ColorEditField.OVERLAY_BOTTOM -> draftTheme.background.gradientOverlayBottomArgb
            ColorEditField.COMPONENT_ACCENT ->
                draftTheme.overrideFor(selectedComponent).accentColorArgb ?: draftTheme.colors.primaryAccentArgb
            ColorEditField.COMPONENT_GRAD_START ->
                draftTheme.overrideFor(selectedComponent).gradientStartArgb ?: draftTheme.colors.gradientStartArgb
            ColorEditField.COMPONENT_GRAD_END ->
                draftTheme.overrideFor(selectedComponent).gradientEndArgb ?: draftTheme.colors.gradientEndArgb
        }
        StarColorPickerDialog(
            title = "Pick ${field.label}",
            initialArgb = initialArgb,
            onDismiss = { editingColorField = null },
            onConfirm = { newArgb ->
                draftTheme = when (field) {
                    ColorEditField.PRIMARY_ACCENT -> draftTheme.copy(
                        presetId = StarPresetId.CUSTOM,
                        colors = draftTheme.colors.copy(primaryAccentArgb = newArgb)
                    )
                    ColorEditField.SECONDARY_ACCENT -> draftTheme.copy(
                        presetId = StarPresetId.CUSTOM,
                        colors = draftTheme.colors.copy(secondaryAccentArgb = newArgb)
                    )
                    ColorEditField.BACKGROUND_TINT -> draftTheme.copy(
                        presetId = StarPresetId.CUSTOM,
                        colors = draftTheme.colors.copy(backgroundTintArgb = newArgb)
                    )
                    ColorEditField.TEXT_COLOR -> draftTheme.copy(
                        presetId = StarPresetId.CUSTOM,
                        colors = draftTheme.colors.copy(textColorArgb = newArgb)
                    )
                    ColorEditField.BORDER_COLOR -> draftTheme.copy(
                        presetId = StarPresetId.CUSTOM,
                        colors = draftTheme.colors.copy(borderColorArgb = newArgb)
                    )
                    ColorEditField.GRADIENT_START -> draftTheme.copy(
                        presetId = StarPresetId.CUSTOM,
                        colors = draftTheme.colors.copy(gradientStartArgb = newArgb)
                    )
                    ColorEditField.GRADIENT_END -> draftTheme.copy(
                        presetId = StarPresetId.CUSTOM,
                        colors = draftTheme.colors.copy(gradientEndArgb = newArgb)
                    )
                    ColorEditField.HIGHLIGHT -> draftTheme.copy(
                        presetId = StarPresetId.CUSTOM,
                        colors = draftTheme.colors.copy(highlightColorArgb = newArgb)
                    )
                    ColorEditField.OVERLAY_TOP -> draftTheme.copy(
                        presetId = StarPresetId.CUSTOM,
                        background = draftTheme.background.copy(gradientOverlayTopArgb = newArgb)
                    )
                    ColorEditField.OVERLAY_BOTTOM -> draftTheme.copy(
                        presetId = StarPresetId.CUSTOM,
                        background = draftTheme.background.copy(gradientOverlayBottomArgb = newArgb)
                    )
                    ColorEditField.COMPONENT_ACCENT -> draftTheme.withComponentOverride(selectedComponent) {
                        it.copy(accentColorArgb = newArgb)
                    }
                    ColorEditField.COMPONENT_GRAD_START -> draftTheme.withComponentOverride(selectedComponent) {
                        it.copy(gradientStartArgb = newArgb)
                    }
                    ColorEditField.COMPONENT_GRAD_END -> draftTheme.withComponentOverride(selectedComponent) {
                        it.copy(gradientEndArgb = newArgb)
                    }
                }
                editingColorField = null
            }
        )
    }

    if (showSaveThemeDialog) {
        val currentCustom = savedThemes.firstOrNull { !it.isBuiltIn && it.id == draftTheme.themeId }
        SaveThemeDialog(
            defaultName = if (currentCustom != null) currentCustom.name else "${draftTheme.themeName} Custom",
            existingCustomEntry = currentCustom,
            onDismiss = { showSaveThemeDialog = false },
            onSaveNew = { name ->
                val saved = StarThemeManager.saveThemeAsNew(context, name, draftTheme, activate = true)
                draftTheme = saved.config
                showSaveThemeDialog = false
                onStatusMessage("Saved & applied custom theme '${saved.name}'")
            },
            onOverwriteExisting = { entry ->
                val updated = StarThemeManager.overwriteSavedTheme(context, entry.id, draftTheme)
                if (updated != null) {
                    draftTheme = updated.config
                    onStatusMessage("Updated saved theme '${updated.name}'")
                }
                showSaveThemeDialog = false
            }
        )
    }

    if (showResetConfirmDialog) {
        StarGlassDialog(
            onDismissRequest = { showResetConfirmDialog = false },
            title = { Text("Reset to Default Theme?") },
            text = {
                Text(
                    text = "This restores STAR Liquid Glass default colors, glass parameters, and clears active custom wallpaper overrides.",
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                AetherPrimaryButton(
                    text = "Reset",
                    onClick = {
                        val reset = StarThemeManager.resetToDefault(context)
                        draftTheme = reset
                        showResetConfirmDialog = false
                        onStatusMessage("Reset to STAR Liquid Glass default")
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    renamingEntry?.let { entry ->
        var newName by remember(entry.id) { mutableStateOf(entry.name) }
        StarGlassDialog(
            onDismissRequest = { renamingEntry = null },
            title = { Text("Rename Theme") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it.take(StarThemeConfig.MAX_THEME_NAME_LENGTH) },
                    label = { Text("Theme name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                AetherPrimaryButton(
                    text = "Rename",
                    enabled = newName.isNotBlank(),
                    onClick = {
                        val renamed = StarThemeManager.renameTheme(context, entry.id, newName)
                        if (renamed != null && draftTheme.themeId == renamed.id) {
                            draftTheme = draftTheme.copy(themeName = renamed.name)
                        }
                        renamingEntry = null
                        onStatusMessage("Theme renamed to '${renamed?.name ?: newName}'")
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { renamingEntry = null }) { Text("Cancel") }
            }
        )
    }

    deletingEntry?.let { entry ->
        StarGlassDialog(
            onDismissRequest = { deletingEntry = null },
            title = { Text("Delete '${entry.name}'?") },
            text = {
                Text(
                    text = "This custom theme will be permanently removed from your STAR UI Studio library.",
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                AetherPrimaryButton(
                    text = "Delete",
                    onClick = {
                        StarThemeManager.deleteTheme(context, entry.id)
                        deletingEntry = null
                        onStatusMessage("Deleted custom theme '${entry.name}'")
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { deletingEntry = null }) { Text("Cancel") }
            }
        )
    }

    if (showPasteImportDialog) {
        var jsonText by remember { mutableStateOf("") }
        var importError by remember { mutableStateOf<String?>(null) }
        StarGlassDialog(
            onDismissRequest = { showPasteImportDialog = false },
            title = { Text("Import Theme JSON") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)) {
                    Text(
                        text = "Paste a versioned STAR UI theme JSON configuration. All fields are strictly validated before loading.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = jsonText,
                        onValueChange = {
                            jsonText = it
                            importError = null
                        },
                        label = { Text("Theme JSON") },
                        minLines = 5,
                        maxLines = 9,
                        textStyle = AetherMonoStyle,
                        modifier = Modifier.fillMaxWidth()
                    )
                    importError?.let { err ->
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                AetherPrimaryButton(
                    text = "Validate & Import",
                    enabled = jsonText.isNotBlank(),
                    onClick = {
                        when (val res = StarThemeManager.importThemeJson(context, jsonText, saveToLibrary = true, applyImmediately = false)) {
                            is StarThemeImportResult.Success -> {
                                draftTheme = res.config
                                showPasteImportDialog = false
                                onStatusMessage("Imported '${res.config.themeName}' into preview")
                            }
                            is StarThemeImportResult.Error -> {
                                importError = res.reason
                            }
                        }
                    }
                )
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Xs)) {
                    TextButton(onClick = {
                        clipboard.getText()?.text?.let { jsonText = it }
                    }) {
                        Text("Paste")
                    }
                    TextButton(onClick = { showPasteImportDialog = false }) {
                        Text("Cancel")
                    }
                }
            }
        )
    }

    if (previewExpanded) {
        val modalTheme = LocalStarTheme.current
        Dialog(
            onDismissRequest = { previewExpanded = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.94f)
                    .height(380.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(modalTheme.backgroundBase.copy(alpha = 0.96f))
            ) {
                StarLiquidGlassSurface(
                    componentTarget = StarComponentTarget.DIALOGS,
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(22.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Interactive Live Preview",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = modalTheme.textPrimary
                                )
                                Text(
                                    text = "${draftTheme.themeName} · ${draftTheme.effectQuality.displayName} Quality",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = modalTheme.secondaryAccent,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            AetherTonalButton(
                                text = "Done",
                                onClick = { previewExpanded = false }
                            )
                        }
                        StarStudioLivePreview(
                            draftResolved = draftResolved,
                            sceneTab = previewSceneTab,
                            onSelectSceneTab = { previewSceneTab = it },
                            reducedMotion = reducedMotion,
                            isCompactPreview = false,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        )
                    }
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Studio workspace header & global action controls
        StudioActionHeader(
            draftTheme = draftTheme,
            hasUnappliedChanges = hasUnappliedChanges,
            isCompact = !isExpandedWidth,
            previewVisible = previewVisible,
            onTogglePreview = { previewVisible = !previewVisible },
            onExpandPreview = { previewExpanded = true },
            onApply = {
                val applied = StarThemeManager.applyTheme(context, draftTheme)
                draftTheme = applied
                onStatusMessage("Applied '${applied.themeName}' across STAR Launcher")
            },
            onSaveTheme = { showSaveThemeDialog = true },
            onResetDefault = { showResetConfirmDialog = true },
            onRevertDraft = {
                draftTheme = appliedTheme
                onStatusMessage("Reverted preview draft to active theme")
            }
        )

        if (isExpandedWidth) {
            // Side-by-side layout on tablets / landscape: sticky Live Preview on left, controls on right
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = AetherSpacing.Md),
                horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Md)
            ) {
                Column(
                    modifier = Modifier
                        .weight(0.45f)
                        .fillMaxHeight()
                        .padding(vertical = AetherSpacing.Sm),
                    verticalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
                ) {
                    StarStudioLivePreview(
                        draftResolved = draftResolved,
                        sceneTab = previewSceneTab,
                        onSelectSceneTab = { previewSceneTab = it },
                        reducedMotion = reducedMotion,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(0.55f)
                        .fillMaxHeight()
                ) {
                    StudioCategoryBar(
                        selected = selectedCategory,
                        onSelect = { selectedCategory = it }
                    )
                    StudioControlsList(
                        category = selectedCategory,
                        draftTheme = draftTheme,
                        savedThemes = savedThemes,
                        selectedComponent = selectedComponent,
                        reducedMotion = reducedMotion,
                        onUpdateDraft = { draftTheme = it },
                        onSelectComponent = { selectedComponent = it },
                        onPickColor = { editingColorField = it },
                        onPickPhoto = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onPickImageFile = {
                            imageDocumentLauncher.launch(arrayOf("image/png", "image/jpeg", "image/webp"))
                        },
                        onResetBackground = {
                            draftTheme = draftTheme.copy(
                                presetId = StarPresetId.CUSTOM,
                                background = StarBackgroundConfig()
                            )
                            onStatusMessage("Background cleared in preview")
                        },
                        onSelectSavedTheme = { entry ->
                            val loaded = StarThemeManager.switchToTheme(context, entry.id)
                            if (loaded != null) {
                                draftTheme = loaded
                                onStatusMessage("Switched active theme to '${entry.name}'")
                            }
                        },
                        onPreviewSavedTheme = { entry ->
                            draftTheme = entry.config
                            onStatusMessage("Loaded '${entry.name}' into preview")
                        },
                        onRenameSavedTheme = { renamingEntry = it },
                        onDuplicateSavedTheme = { entry ->
                            val dup = StarThemeManager.duplicateTheme(context, entry.id)
                            if (dup != null) {
                                onStatusMessage("Duplicated '${entry.name}' as '${dup.name}'")
                            }
                        },
                        onDeleteSavedTheme = { deletingEntry = it },
                        onExportFile = {
                            val json = StarThemeManager.exportThemeJson(draftTheme)
                            pendingExportJson = json
                            val safeFile = draftTheme.themeName
                                .lowercase()
                                .replace(Regex("[^a-z0-9]+"), "_")
                                .trim('_')
                                .ifEmpty { "star_theme" }
                            exportThemeLauncher.launch("$safeFile.star-theme.json")
                        },
                        onCopyExportJson = {
                            val json = StarThemeManager.exportThemeJson(draftTheme)
                            clipboard.setText(AnnotatedString(json))
                            onStatusMessage("Copied versioned theme JSON to clipboard")
                        },
                        onImportFile = {
                            importThemeLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                        },
                        onOpenPasteImport = { showPasteImportDialog = true },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        } else {
            // Portrait phone layout: sleek collapsible live preview + full scrollable customization canvas
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (previewVisible) {
                    StarStudioLivePreview(
                        draftResolved = draftResolved,
                        sceneTab = previewSceneTab,
                        onSelectSceneTab = { previewSceneTab = it },
                        reducedMotion = reducedMotion,
                        isCompactPreview = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AetherSpacing.Md, vertical = 2.dp)
                            .height(152.dp)
                    )
                }

                StudioCategoryBar(
                    selected = selectedCategory,
                    onSelect = { selectedCategory = it }
                )

                StudioControlsList(
                    category = selectedCategory,
                    draftTheme = draftTheme,
                    savedThemes = savedThemes,
                    selectedComponent = selectedComponent,
                    reducedMotion = reducedMotion,
                    onUpdateDraft = { draftTheme = it },
                    onSelectComponent = { selectedComponent = it },
                    onPickColor = { editingColorField = it },
                    onPickPhoto = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onPickImageFile = {
                        imageDocumentLauncher.launch(arrayOf("image/png", "image/jpeg", "image/webp"))
                    },
                    onResetBackground = {
                        draftTheme = draftTheme.copy(
                            presetId = StarPresetId.CUSTOM,
                            background = StarBackgroundConfig()
                        )
                        onStatusMessage("Background cleared in preview")
                    },
                    onSelectSavedTheme = { entry ->
                        val loaded = StarThemeManager.switchToTheme(context, entry.id)
                        if (loaded != null) {
                            draftTheme = loaded
                            onStatusMessage("Switched active theme to '${entry.name}'")
                        }
                    },
                    onPreviewSavedTheme = { entry ->
                        draftTheme = entry.config
                        onStatusMessage("Loaded '${entry.name}' into preview")
                    },
                    onRenameSavedTheme = { renamingEntry = it },
                    onDuplicateSavedTheme = { entry ->
                        val dup = StarThemeManager.duplicateTheme(context, entry.id)
                        if (dup != null) {
                            onStatusMessage("Duplicated '${entry.name}' as '${dup.name}'")
                        }
                    },
                    onDeleteSavedTheme = { deletingEntry = it },
                    onExportFile = {
                        val json = StarThemeManager.exportThemeJson(draftTheme)
                        pendingExportJson = json
                        val safeFile = draftTheme.themeName
                            .lowercase()
                            .replace(Regex("[^a-z0-9]+"), "_")
                            .trim('_')
                            .ifEmpty { "star_theme" }
                        exportThemeLauncher.launch("$safeFile.star-theme.json")
                    },
                    onCopyExportJson = {
                        val json = StarThemeManager.exportThemeJson(draftTheme)
                        clipboard.setText(AnnotatedString(json))
                        onStatusMessage("Copied versioned theme JSON to clipboard")
                    },
                    onImportFile = {
                        importThemeLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                    },
                    onOpenPasteImport = { showPasteImportDialog = true },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StudioActionHeader(
    draftTheme: StarThemeConfig,
    hasUnappliedChanges: Boolean,
    isCompact: Boolean,
    previewVisible: Boolean,
    onTogglePreview: () -> Unit,
    onExpandPreview: () -> Unit,
    onApply: () -> Unit,
    onSaveTheme: () -> Unit,
    onResetDefault: () -> Unit,
    onRevertDraft: () -> Unit
) {
    val theme = LocalStarTheme.current
    if (isCompact) {
        StarLiquidGlassSurface(
            componentTarget = StarComponentTarget.SETTINGS_PANELS,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AetherSpacing.Md, vertical = 2.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(if (hasUnappliedChanges) theme.secondaryAccent else theme.primaryAccent)
                        )
                        Text(
                            text = if (hasUnappliedChanges) {
                                "Unsaved draft · ${draftTheme.effectQuality.displayName}"
                            } else {
                                "${draftTheme.themeName} · ${draftTheme.effectQuality.displayName}"
                            },
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = if (hasUnappliedChanges) theme.secondaryAccent else theme.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (hasUnappliedChanges) {
                            Text(
                                text = "Discard",
                                style = MaterialTheme.typography.labelSmall,
                                color = theme.textSecondary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(onClick = onRevertDraft)
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                        Text(
                            text = if (previewVisible) "Hide Preview" else "Show Preview",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = theme.secondaryAccent,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(onClick = onTogglePreview)
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                        Text(
                            text = "Expand",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = theme.textPrimary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(onClick = onExpandPreview)
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CompactStudioPillButton(
                        label = if (hasUnappliedChanges) "Apply Changes" else "Applied",
                        enabled = hasUnappliedChanges,
                        isPrimary = true,
                        onClick = onApply,
                        modifier = Modifier.weight(1.2f)
                    )
                    CompactStudioPillButton(
                        label = "Save Theme",
                        enabled = true,
                        isPrimary = false,
                        onClick = onSaveTheme,
                        modifier = Modifier.weight(1f)
                    )
                    CompactStudioPillButton(
                        label = "Reset",
                        enabled = true,
                        isPrimary = false,
                        onClick = onResetDefault,
                        modifier = Modifier.weight(0.75f)
                    )
                }
            }
        }
    } else {
        AetherSurfaceCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AetherSpacing.Md, vertical = AetherSpacing.Xs),
            componentTarget = StarComponentTarget.SETTINGS_PANELS
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "STAR UI Studio",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = theme.textPrimary
                    )
                    Text(
                        text = if (hasUnappliedChanges) {
                            "Previewing unsaved changes · ${draftTheme.effectQuality.displayName} Quality"
                        } else {
                            "Active: ${draftTheme.themeName} · ${draftTheme.effectQuality.displayName} Quality"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (hasUnappliedChanges) theme.secondaryAccent else theme.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (hasUnappliedChanges) {
                    TextButton(onClick = onRevertDraft) {
                        Text("Discard", color = theme.textSecondary)
                    }
                }
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm),
                verticalArrangement = Arrangement.spacedBy(AetherSpacing.Xs)
            ) {
                AetherPrimaryButton(
                    text = if (hasUnappliedChanges) "Apply Changes" else "Applied",
                    enabled = hasUnappliedChanges,
                    onClick = onApply
                )
                AetherTonalButton(
                    text = "Save Theme",
                    onClick = onSaveTheme
                )
                AetherOutlinedButton(
                    text = "Reset Default",
                    onClick = onResetDefault
                )
            }
        }
    }
}

@Composable
private fun CompactStudioPillButton(
    label: String,
    enabled: Boolean,
    isPrimary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = LocalStarTheme.current
    val fill = when {
        isPrimary && enabled -> theme.primaryAccent
        isPrimary && !enabled -> theme.surfaceVariant.copy(alpha = 0.72f)
        else -> theme.surfaceElevated.copy(alpha = 0.78f)
    }
    val border = when {
        isPrimary && enabled -> theme.highlightColor.copy(alpha = 0.5f)
        else -> theme.borderColor.copy(alpha = 0.55f)
    }
    val fg = when {
        isPrimary && enabled -> Color(0xFF051320)
        !enabled -> theme.textSecondary.copy(alpha = 0.8f)
        else -> theme.textPrimary
    }
    StarLiquidGlassSurface(
        componentTarget = StarComponentTarget.BUTTONS,
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        containerColorOverride = fill,
        borderColorOverride = border,
        onClick = onClick,
        enabled = enabled,
        contentColor = fg
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (isPrimary) FontWeight.Bold else FontWeight.SemiBold
            ),
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 8.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun StudioCategoryBar(
    selected: StarStudioCategory,
    onSelect: (StarStudioCategory) -> Unit
) {
    val theme = LocalStarTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = AetherSpacing.Md, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Xs)
    ) {
        StarStudioCategory.values().forEach { cat ->
            val isSelected = cat == selected
            StarLiquidGlassSurface(
                componentTarget = StarComponentTarget.BUTTONS,
                shape = RoundedCornerShape(999.dp),
                containerColorOverride = if (isSelected) theme.primaryAccent.copy(alpha = 0.28f) else theme.surfaceBase,
                borderColorOverride = if (isSelected) theme.secondaryAccent else theme.borderColor.copy(alpha = 0.45f),
                onClick = { onSelect(cat) }
            ) {
                Text(
                    text = cat.title,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    ),
                    color = if (isSelected) theme.textPrimary else theme.textSecondary,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun PreviewMiniButton(
    label: String,
    isPrimary: Boolean,
    draftResolved: StarResolvedTheme,
    modifier: Modifier = Modifier
) {
    val btnStyle = draftResolved.styleFor(StarComponentTarget.BUTTONS)
    val fg = if (isPrimary) Color(0xFF051320) else draftResolved.textPrimary
    StarLiquidGlassSurface(
        componentTarget = StarComponentTarget.BUTTONS,
        modifier = modifier,
        shape = RoundedCornerShape(btnStyle.cornerRadiusDp.dp),
        containerColorOverride = if (isPrimary) btnStyle.accentColor else draftResolved.surfaceBase.copy(alpha = 0.45f),
        borderColorOverride = if (isPrimary) draftResolved.highlightColor.copy(alpha = 0.45f) else draftResolved.borderColor,
        contentColor = fg
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = if (isPrimary) FontWeight.Bold else FontWeight.Medium
            ),
            color = fg,
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun StarStudioLivePreview(
    draftResolved: StarResolvedTheme,
    sceneTab: PreviewSceneTab,
    onSelectSceneTab: (PreviewSceneTab) -> Unit,
    reducedMotion: Boolean,
    isCompactPreview: Boolean = false,
    modifier: Modifier = Modifier
) {
    val outerBorder = draftResolved.borderColor.copy(alpha = 0.55f)
    val innerPad = if (isCompactPreview) 6.dp else 10.dp
    AetherTheme(resolvedTheme = draftResolved) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, outerBorder, RoundedCornerShape(16.dp))
        ) {
            StarWallpaperBackdrop(
                theme = draftResolved,
                reducedMotion = reducedMotion,
                modifier = Modifier.fillMaxSize()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPad),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // Mini Main Navigation Top Bar + Scene Switcher
                    StarLiquidGlassSurface(
                        componentTarget = StarComponentTarget.MAIN_NAVIGATION,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = if (isCompactPreview) 4.dp else 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                AetherBrandMark(modifier = Modifier.size(if (isCompactPreview) 14.dp else 18.dp))
                                Text(
                                    text = "STAR",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontSize = if (isCompactPreview) 10.sp else 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.1.sp
                                    ),
                                    color = draftResolved.textPrimary
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                PreviewSceneTab.values().forEach { tab ->
                                    val active = tab == sceneTab
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(
                                                if (active) draftResolved.primaryAccent.copy(alpha = 0.28f)
                                                else Color.Transparent
                                            )
                                            .clickable { onSelectSceneTab(tab) }
                                            .padding(horizontal = 7.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = if (isCompactPreview && tab == PreviewSceneTab.VERSIONS_MODS) "Mods" else tab.label,
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                                            color = if (active) draftResolved.secondaryAccent else draftResolved.textSecondary,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Interactive Center Stage
                    when (sceneTab) {
                        PreviewSceneTab.OVERVIEW -> {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .verticalScroll(rememberScrollState())
                                    .padding(vertical = if (isCompactPreview) 4.dp else 6.dp),
                                verticalArrangement = Arrangement.spacedBy(if (isCompactPreview) 4.dp else 6.dp)
                            ) {
                                StarLiquidGlassSurface(
                                    componentTarget = StarComponentTarget.PANELS,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 8.dp, vertical = if (isCompactPreview) 5.dp else 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = if (isCompactPreview) "INSTANCE · 1.21.4" else "FEATURED INSTANCE",
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp),
                                                color = draftResolved.secondaryAccent
                                            )
                                            Text(
                                                text = "Survival Horizon",
                                                style = if (isCompactPreview) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium,
                                                color = draftResolved.textPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            StarLiquidGlassSurface(
                                                componentTarget = StarComponentTarget.PROFILE_COMPONENTS,
                                                shape = RoundedCornerShape(999.dp)
                                            ) {
                                                Text(
                                                    text = if (isCompactPreview) "Alex" else "Alex · MSA",
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                                                    color = draftResolved.textPrimary,
                                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                                )
                                            }
                                            if (isCompactPreview) {
                                                PreviewMiniButton(
                                                    label = "Play",
                                                    isPrimary = true,
                                                    draftResolved = draftResolved
                                                )
                                            }
                                        }
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    StarLiquidGlassSurface(
                                        componentTarget = StarComponentTarget.CARDS,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 8.dp, vertical = if (isCompactPreview) 5.dp else 8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column {
                                                Text(
                                                    text = "Cards",
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontWeight = FontWeight.SemiBold,
                                                        fontSize = if (isCompactPreview) 10.sp else 11.sp
                                                    ),
                                                    color = draftResolved.textPrimary
                                                )
                                                Text(
                                                    text = "4096 MB",
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                                    color = draftResolved.textSecondary,
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                    }
                                    StarLiquidGlassSurface(
                                        componentTarget = StarComponentTarget.SETTINGS_PANELS,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 8.dp, vertical = if (isCompactPreview) 5.dp else 8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column {
                                                Text(
                                                    text = "Settings",
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontWeight = FontWeight.SemiBold,
                                                        fontSize = if (isCompactPreview) 10.sp else 11.sp
                                                    ),
                                                    color = draftResolved.textPrimary
                                                )
                                                Text(
                                                    text = "MobileGlues",
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                                    color = draftResolved.secondaryAccent,
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                    }
                                }

                                if (!isCompactPreview) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        AetherPrimaryButton(
                                            text = "Launch Game",
                                            onClick = {},
                                            modifier = Modifier.weight(1f)
                                        )
                                        AetherOutlinedButton(
                                            text = "Configure",
                                            onClick = {},
                                            modifier = Modifier.weight(0.8f)
                                        )
                                    }
                                }
                            }
                        }

                        PreviewSceneTab.VERSIONS_MODS -> {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .verticalScroll(rememberScrollState())
                                    .padding(vertical = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                StarLiquidGlassSurface(
                                    componentTarget = StarComponentTarget.VERSION_PANELS,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Minecraft 1.21.4 (Fabric)",
                                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                                color = draftResolved.textPrimary
                                            )
                                            Text(
                                                text = "Installed · Java 21",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = draftResolved.secondaryAccent
                                            )
                                        }
                                        if (isCompactPreview) {
                                            PreviewMiniButton(
                                                label = "Play",
                                                isPrimary = true,
                                                draftResolved = draftResolved
                                            )
                                        } else {
                                            AetherTonalButton(text = "Play", onClick = {})
                                        }
                                    }
                                }

                                StarLiquidGlassSurface(
                                    componentTarget = StarComponentTarget.MOD_MANAGER_PANELS,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Sodium Optimization",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = draftResolved.textPrimary
                                            )
                                            Text(
                                                text = "Mod Manager · Compatible",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = draftResolved.textSecondary
                                            )
                                        }
                                    }
                                }

                                StarLiquidGlassSurface(
                                    componentTarget = StarComponentTarget.DOWNLOAD_PROGRESS_PANELS,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = "Downloading Assets...",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = draftResolved.textPrimary
                                            )
                                            Text(
                                                text = "68%",
                                                style = AetherMonoStyle.copy(color = draftResolved.secondaryAccent)
                                            )
                                        }
                                        LinearProgressIndicator(
                                            progress = { 0.68f },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(6.dp)
                                                .clip(RoundedCornerShape(3.dp)),
                                            color = draftResolved.secondaryAccent,
                                            trackColor = draftResolved.surfaceVariant,
                                            strokeCap = StrokeCap.Round
                                        )
                                    }
                                }
                            }
                        }

                        PreviewSceneTab.DIALOG -> {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                StarLiquidGlassSurface(
                                    componentTarget = StarComponentTarget.DIALOGS,
                                    modifier = Modifier.fillMaxWidth(0.92f)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(if (isCompactPreview) 8.dp else 12.dp),
                                        verticalArrangement = Arrangement.spacedBy(if (isCompactPreview) 4.dp else 6.dp)
                                    ) {
                                        Text(
                                            text = "Glass Dialog Preview",
                                            style = if (isCompactPreview) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium,
                                            color = draftResolved.textPrimary
                                        )
                                        if (!isCompactPreview) {
                                            Text(
                                                text = "Dialogs reflect DIALOGS opacity, blur, borders, and radius.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = draftResolved.textSecondary
                                            )
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                                        ) {
                                            if (isCompactPreview) {
                                                PreviewMiniButton(
                                                    label = "Cancel",
                                                    isPrimary = false,
                                                    draftResolved = draftResolved
                                                )
                                                PreviewMiniButton(
                                                    label = "Confirm",
                                                    isPrimary = true,
                                                    draftResolved = draftResolved
                                                )
                                            } else {
                                                AetherOutlinedButton(text = "Cancel", onClick = {})
                                                AetherPrimaryButton(text = "Confirm", onClick = {})
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Mini Bottom Navigation Bar
                    StarLiquidGlassSurface(
                        componentTarget = StarComponentTarget.MAIN_NAVIGATION,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = if (isCompactPreview) 8.dp else 12.dp,
                                    vertical = if (isCompactPreview) 4.dp else 6.dp
                                ),
                            horizontalArrangement = Arrangement.SpaceAround,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            listOf(
                                "Home" to AetherIcons.Home,
                                "Library" to AetherIcons.Library,
                                "Versions" to AetherIcons.Versions,
                                "Mods" to AetherIcons.Mods,
                                "Settings" to AetherIcons.Settings
                            ).forEachIndexed { idx, (lbl, icon) ->
                                val active = idx == 0
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = lbl,
                                        tint = if (active) draftResolved.secondaryAccent else draftResolved.textSecondary,
                                        modifier = Modifier.size(if (isCompactPreview) 12.dp else 14.dp)
                                    )
                                    Text(
                                        text = lbl,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = if (isCompactPreview) 8.5.sp else 9.5.sp
                                        ),
                                        color = if (active) draftResolved.secondaryAccent else draftResolved.textSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StudioControlsList(
    category: StarStudioCategory,
    draftTheme: StarThemeConfig,
    savedThemes: List<StarSavedThemeEntry>,
    selectedComponent: StarComponentTarget,
    reducedMotion: Boolean,
    onUpdateDraft: (StarThemeConfig) -> Unit,
    onSelectComponent: (StarComponentTarget) -> Unit,
    onPickColor: (ColorEditField) -> Unit,
    onPickPhoto: () -> Unit,
    onPickImageFile: () -> Unit,
    onResetBackground: () -> Unit,
    onSelectSavedTheme: (StarSavedThemeEntry) -> Unit,
    onPreviewSavedTheme: (StarSavedThemeEntry) -> Unit,
    onRenameSavedTheme: (StarSavedThemeEntry) -> Unit,
    onDuplicateSavedTheme: (StarSavedThemeEntry) -> Unit,
    onDeleteSavedTheme: (StarSavedThemeEntry) -> Unit,
    onExportFile: () -> Unit,
    onCopyExportJson: () -> Unit,
    onImportFile: () -> Unit,
    onOpenPasteImport: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = AetherSpacing.Md, vertical = AetherSpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
    ) {
        when (category) {
            StarStudioCategory.PRESETS -> {
                item {
                    PresetsAndLibrarySection(
                        draftTheme = draftTheme,
                        savedThemes = savedThemes,
                        onLoadPreset = { preset ->
                            val presetCfg = StarThemePresets.presetConfig(preset, draftTheme.effectQuality)
                            // Preserve active custom wallpaper only when user is in Custom, otherwise load full functional preset
                            onUpdateDraft(presetCfg)
                        },
                        onSelectSavedTheme = onSelectSavedTheme,
                        onPreviewSavedTheme = onPreviewSavedTheme,
                        onRenameSavedTheme = onRenameSavedTheme,
                        onDuplicateSavedTheme = onDuplicateSavedTheme,
                        onDeleteSavedTheme = onDeleteSavedTheme,
                        onExportFile = onExportFile,
                        onCopyExportJson = onCopyExportJson,
                        onImportFile = onImportFile,
                        onOpenPasteImport = onOpenPasteImport
                    )
                }
            }

            StarStudioCategory.BACKGROUND -> {
                item {
                    BackgroundStudioSection(
                        bg = draftTheme.background,
                        draftTheme = draftTheme,
                        reducedMotion = reducedMotion,
                        onUpdateBackground = { updatedBg ->
                            onUpdateDraft(
                                draftTheme.copy(
                                    presetId = StarPresetId.CUSTOM,
                                    background = updatedBg
                                )
                            )
                        },
                        onPickPhoto = onPickPhoto,
                        onPickImageFile = onPickImageFile,
                        onResetBackground = onResetBackground,
                        onPickColor = onPickColor
                    )
                }
            }

            StarStudioCategory.LIQUID_GLASS -> {
                item {
                    LiquidGlassStudioSection(
                        glass = draftTheme.glass,
                        onUpdateGlass = { updatedGlass ->
                            onUpdateDraft(
                                draftTheme.copy(
                                    presetId = StarPresetId.CUSTOM,
                                    glass = updatedGlass
                                )
                            )
                        }
                    )
                }
            }

            StarStudioCategory.COLORS -> {
                item {
                    ColorStudioSection(
                        colors = draftTheme.colors,
                        onApplyPalette = { palette ->
                            onUpdateDraft(
                                draftTheme.copy(
                                    presetId = StarPresetId.CUSTOM,
                                    colors = palette
                                )
                            )
                        },
                        onPickColor = onPickColor
                    )
                }
            }

            StarStudioCategory.COMPONENTS -> {
                item {
                    ComponentsStudioSection(
                        draftTheme = draftTheme,
                        selectedTarget = selectedComponent,
                        onSelectTarget = onSelectComponent,
                        onUpdateDraft = onUpdateDraft,
                        onPickColor = onPickColor
                    )
                }
            }

            StarStudioCategory.PERFORMANCE -> {
                item {
                    PerformanceQualitySection(
                        quality = draftTheme.effectQuality,
                        onSelectQuality = { quality ->
                            onUpdateDraft(draftTheme.copy(effectQuality = quality))
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetsAndLibrarySection(
    draftTheme: StarThemeConfig,
    savedThemes: List<StarSavedThemeEntry>,
    onLoadPreset: (StarPresetId) -> Unit,
    onSelectSavedTheme: (StarSavedThemeEntry) -> Unit,
    onPreviewSavedTheme: (StarSavedThemeEntry) -> Unit,
    onRenameSavedTheme: (StarSavedThemeEntry) -> Unit,
    onDuplicateSavedTheme: (StarSavedThemeEntry) -> Unit,
    onDeleteSavedTheme: (StarSavedThemeEntry) -> Unit,
    onExportFile: () -> Unit,
    onCopyExportJson: () -> Unit,
    onImportFile: () -> Unit,
    onOpenPasteImport: () -> Unit
) {
    val theme = LocalStarTheme.current
    Column(verticalArrangement = Arrangement.spacedBy(AetherSpacing.Md)) {
        AetherAccentCard(
            accentColor = theme.secondaryAccent,
            componentTarget = StarComponentTarget.SETTINGS_PANELS
        ) {
            Text(
                text = "Built-in Theme Presets",
                style = MaterialTheme.typography.titleMedium,
                color = theme.textPrimary
            )
            Text(
                text = "Select any functional Liquid Glass preset to preview or switch instantly.",
                style = MaterialTheme.typography.bodySmall,
                color = theme.textSecondary
            )
            StarPresetId.values().forEach { preset ->
                val isCurrent = draftTheme.presetId == preset
                val previewPalette = remember(preset) {
                    StarThemePresets.presetConfig(preset).colors
                }
                StarLiquidGlassSurface(
                    componentTarget = StarComponentTarget.CARDS,
                    borderColorOverride = if (isCurrent) theme.secondaryAccent else theme.borderColor.copy(alpha = 0.4f),
                    onClick = { onLoadPreset(preset) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.linearGradient(
                                            listOf(
                                                Color(previewPalette.primaryAccentArgb.toInt()),
                                                Color(previewPalette.secondaryAccentArgb.toInt())
                                            )
                                        )
                                    )
                            )
                            Column {
                                Text(
                                    text = preset.displayName,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (isCurrent) theme.secondaryAccent else theme.textPrimary
                                )
                                Text(
                                    text = preset.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = theme.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        if (isCurrent) {
                            Text(
                                text = "ACTIVE",
                                style = MaterialTheme.typography.labelSmall,
                                color = theme.secondaryAccent
                            )
                        }
                    }
                }
            }
        }

        // Theme Library (Saved Custom Themes + Duplicate/Rename/Delete)
        AetherSurfaceCard(componentTarget = StarComponentTarget.SETTINGS_PANELS) {
            Text(
                text = "Saved Themes Library",
                style = MaterialTheme.typography.titleMedium,
                color = theme.textPrimary
            )
            Text(
                text = "Duplicate built-in presets or manage your saved custom Liquid Glass themes.",
                style = MaterialTheme.typography.bodySmall,
                color = theme.textSecondary
            )

            savedThemes.forEach { entry ->
                val isDraftMatch = draftTheme.themeId == entry.id
                StarLiquidGlassSurface(
                    componentTarget = StarComponentTarget.CARDS,
                    borderColorOverride = if (isDraftMatch) theme.secondaryAccent else null
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(AetherSpacing.Xs)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.name,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = theme.textPrimary
                                )
                                Text(
                                    text = if (entry.isBuiltIn) "Built-in Preset" else "Custom Saved Theme",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = theme.textSecondary
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                AetherTonalButton(
                                    text = "Preview",
                                    onClick = { onPreviewSavedTheme(entry) }
                                )
                                AetherPrimaryButton(
                                    text = "Use",
                                    onClick = { onSelectSavedTheme(entry) }
                                )
                            }
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            AetherOutlinedButton(
                                text = "Duplicate",
                                onClick = { onDuplicateSavedTheme(entry) }
                            )
                            if (!entry.isBuiltIn) {
                                AetherOutlinedButton(
                                    text = "Rename",
                                    onClick = { onRenameSavedTheme(entry) }
                                )
                                AetherOutlinedButton(
                                    text = "Delete",
                                    onClick = { onDeleteSavedTheme(entry) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Import / Export Theme JSON
        AetherSurfaceCard(componentTarget = StarComponentTarget.SETTINGS_PANELS) {
            Text(
                text = "Import & Export Theme (Versioned JSON)",
                style = MaterialTheme.typography.titleMedium,
                color = theme.textPrimary
            )
            Text(
                text = "Schema v${StarThemeConfig.CURRENT_SCHEMA_VERSION} JSON configuration. External imports are validated and sanitized before loading.",
                style = MaterialTheme.typography.bodySmall,
                color = theme.textSecondary
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
            ) {
                AetherPrimaryButton(
                    text = "Export .json",
                    onClick = onExportFile,
                    modifier = Modifier.weight(1f)
                )
                AetherTonalButton(
                    text = "Copy JSON",
                    onClick = onCopyExportJson,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
            ) {
                AetherOutlinedButton(
                    text = "Import .json File",
                    onClick = onImportFile,
                    modifier = Modifier.weight(1f)
                )
                AetherOutlinedButton(
                    text = "Paste JSON",
                    onClick = onOpenPasteImport,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun BackgroundStudioSection(
    bg: StarBackgroundConfig,
    draftTheme: StarThemeConfig,
    reducedMotion: Boolean,
    onUpdateBackground: (StarBackgroundConfig) -> Unit,
    onPickPhoto: () -> Unit,
    onPickImageFile: () -> Unit,
    onResetBackground: () -> Unit,
    onPickColor: (ColorEditField) -> Unit
) {
    val theme = LocalStarTheme.current
    Column(verticalArrangement = Arrangement.spacedBy(AetherSpacing.Md)) {
        AetherAccentCard(
            accentColor = theme.primaryAccent,
            componentTarget = StarComponentTarget.SETTINGS_PANELS
        ) {
            Text(
                text = "Custom Launcher Wallpaper",
                style = MaterialTheme.typography.titleMedium,
                color = theme.textPrimary
            )
            Text(
                text = "Import PNG, JPG/JPEG, or WEBP via Android Photo Picker without storage permissions.",
                style = MaterialTheme.typography.bodySmall,
                color = theme.textSecondary
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
            ) {
                AetherPrimaryButton(
                    text = "Photo Picker",
                    onClick = onPickPhoto,
                    modifier = Modifier.weight(1f)
                )
                AetherTonalButton(
                    text = "Browse Image",
                    onClick = onPickImageFile,
                    modifier = Modifier.weight(1f)
                )
            }
            if (!bg.imagePath.isNullOrBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Enable Custom Wallpaper",
                        style = MaterialTheme.typography.bodyMedium,
                        color = theme.textPrimary
                    )
                    Switch(
                        checked = bg.enabled,
                        onCheckedChange = { onUpdateBackground(bg.copy(enabled = it)) }
                    )
                }

                // Interactive Drag-to-Pan & Pinch-to-Zoom Crop Pad
                Text(
                    text = "Drag to position · Pinch to zoom & crop",
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.secondaryAccent
                )
                val previewResolved = remember(draftTheme) { StarResolvedTheme.resolve(draftTheme) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, theme.secondaryAccent.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                        .pointerInput(bg.imagePath, bg.zoom, bg.offsetX, bg.offsetY) {
                            detectTransformGestures { _, pan, zoomChange, _ ->
                                val nextZoom = (bg.zoom * zoomChange).coerceIn(1f, 3.5f)
                                val sensX = if (size.width > 0) (pan.x / size.width) * 2.5f else 0f
                                val sensY = if (size.height > 0) (pan.y / size.height) * 2.5f else 0f
                                onUpdateBackground(
                                    bg.copy(
                                        zoom = nextZoom,
                                        offsetX = (bg.offsetX + sensX).coerceIn(-1f, 1f),
                                        offsetY = (bg.offsetY + sensY).coerceIn(-1f, 1f)
                                    )
                                )
                            }
                        }
                ) {
                    StarWallpaperBackdrop(
                        theme = previewResolved,
                        reducedMotion = reducedMotion,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(8.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black.copy(alpha = 0.55f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = String.format("%.2fx · (%+.2f, %+.2f)", bg.zoom, bg.offsetX, bg.offsetY),
                                style = AetherMonoStyle.copy(color = Color.White, fontSize = 10.sp)
                            )
                        }
                    }
                }
            }

            AetherOutlinedButton(
                text = "Reset Background",
                onClick = onResetBackground,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Positioning, Zoom & Color Adjustments
        AetherSurfaceCard(componentTarget = StarComponentTarget.SETTINGS_PANELS) {
            Text(
                text = "Framing & Color Adjustments",
                style = MaterialTheme.typography.titleMedium,
                color = theme.textPrimary
            )
            StudioSliderRow(
                label = "Zoom & Crop",
                valueText = String.format("%.2fx", bg.zoom),
                value = bg.zoom,
                valueRange = 1f..3.5f,
                onValueChange = { onUpdateBackground(bg.copy(zoom = it)) }
            )
            StudioSliderRow(
                label = "Horizontal Position",
                valueText = String.format("%+.2f", bg.offsetX),
                value = bg.offsetX,
                valueRange = -1f..1f,
                onValueChange = { onUpdateBackground(bg.copy(offsetX = it)) }
            )
            StudioSliderRow(
                label = "Vertical Position",
                valueText = String.format("%+.2f", bg.offsetY),
                value = bg.offsetY,
                valueRange = -1f..1f,
                onValueChange = { onUpdateBackground(bg.copy(offsetY = it)) }
            )
            HorizontalDivider(color = theme.borderColor.copy(alpha = 0.25f))
            StudioSliderRow(
                label = "Brightness",
                valueText = String.format("%+.2f", bg.brightness),
                value = bg.brightness,
                valueRange = -0.5f..0.5f,
                onValueChange = { onUpdateBackground(bg.copy(brightness = it)) }
            )
            StudioSliderRow(
                label = "Saturation",
                valueText = String.format("%.2f", bg.saturation),
                value = bg.saturation,
                valueRange = 0f..2f,
                onValueChange = { onUpdateBackground(bg.copy(saturation = it)) }
            )
            StudioSliderRow(
                label = "Contrast",
                valueText = String.format("%.2f", bg.contrast),
                value = bg.contrast,
                valueRange = 0.5f..1.8f,
                onValueChange = { onUpdateBackground(bg.copy(contrast = it)) }
            )
            StudioSliderRow(
                label = "Background Blur (Independent)",
                valueText = "${bg.blurRadiusDp.roundToInt()} dp",
                value = bg.blurRadiusDp,
                valueRange = 0f..36f,
                onValueChange = { onUpdateBackground(bg.copy(blurRadiusDp = it)) }
            )
        }

        // Overlays
        AetherSurfaceCard(componentTarget = StarComponentTarget.SETTINGS_PANELS) {
            Text(
                text = "Dark & Gradient Overlays",
                style = MaterialTheme.typography.titleMedium,
                color = theme.textPrimary
            )
            StudioSliderRow(
                label = "Dark Overlay",
                valueText = "${(bg.darkOverlayAlpha * 100).roundToInt()}%",
                value = bg.darkOverlayAlpha,
                valueRange = 0f..0.88f,
                onValueChange = { onUpdateBackground(bg.copy(darkOverlayAlpha = it)) }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Gradient Overlay",
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.textPrimary
                )
                Switch(
                    checked = bg.gradientOverlayEnabled,
                    onCheckedChange = { onUpdateBackground(bg.copy(gradientOverlayEnabled = it)) }
                )
            }
            if (bg.gradientOverlayEnabled) {
                StudioSliderRow(
                    label = "Gradient Opacity",
                    valueText = "${(bg.gradientOverlayAlpha * 100).roundToInt()}%",
                    value = bg.gradientOverlayAlpha,
                    valueRange = 0f..1f,
                    onValueChange = { onUpdateBackground(bg.copy(gradientOverlayAlpha = it)) }
                )
                StudioSliderRow(
                    label = "Gradient Angle",
                    valueText = "${bg.gradientAngleDeg.roundToInt()}°",
                    value = bg.gradientAngleDeg,
                    valueRange = 0f..360f,
                    onValueChange = { onUpdateBackground(bg.copy(gradientAngleDeg = it)) }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
                ) {
                    ColorSwatchButton(
                        label = "Overlay Start",
                        argb = bg.gradientOverlayTopArgb,
                        onClick = { onPickColor(ColorEditField.OVERLAY_TOP) },
                        modifier = Modifier.weight(1f)
                    )
                    ColorSwatchButton(
                        label = "Overlay End",
                        argb = bg.gradientOverlayBottomArgb,
                        onClick = { onPickColor(ColorEditField.OVERLAY_BOTTOM) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun LiquidGlassStudioSection(
    glass: StarGlassConfig,
    onUpdateGlass: (StarGlassConfig) -> Unit
) {
    val theme = LocalStarTheme.current
    Column(verticalArrangement = Arrangement.spacedBy(AetherSpacing.Md)) {
        AetherAccentCard(
            accentColor = theme.secondaryAccent,
            componentTarget = StarComponentTarget.SETTINGS_PANELS
        ) {
            Text(
                text = "Liquid Glass Engine",
                style = MaterialTheme.typography.titleMedium,
                color = theme.textPrimary
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Enable Glass Effect", style = MaterialTheme.typography.bodyMedium, color = theme.textPrimary)
                    Text("Translucent backdrop blur, refraction & specular rims", style = MaterialTheme.typography.bodySmall, color = theme.textSecondary)
                }
                Switch(
                    checked = glass.glassEffectEnabled,
                    onCheckedChange = { onUpdateGlass(glass.copy(glassEffectEnabled = it)) }
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Subtle Surface Gradient", style = MaterialTheme.typography.bodyMedium, color = theme.textPrimary)
                Switch(
                    checked = glass.gradientEnabled,
                    onCheckedChange = { onUpdateGlass(glass.copy(gradientEnabled = it)) }
                )
            }
            StudioSliderRow(
                label = "Blur Intensity",
                valueText = "${glass.blurIntensity.roundToInt()} dp",
                value = glass.blurIntensity,
                valueRange = 0f..36f,
                onValueChange = { onUpdateGlass(glass.copy(blurIntensity = it)) }
            )
            StudioSliderRow(
                label = "Glass Background Opacity",
                valueText = "${(glass.backgroundOpacity * 100).roundToInt()}%",
                value = glass.backgroundOpacity,
                valueRange = 0.06f..0.98f,
                onValueChange = { onUpdateGlass(glass.copy(backgroundOpacity = it)) }
            )
            StudioSliderRow(
                label = "Glass Border Opacity",
                valueText = "${(glass.borderOpacity * 100).roundToInt()}%",
                value = glass.borderOpacity,
                valueRange = 0f..1f,
                onValueChange = { onUpdateGlass(glass.copy(borderOpacity = it)) }
            )
            StudioSliderRow(
                label = "Corner Radius",
                valueText = "${glass.cornerRadiusDp.roundToInt()} dp",
                value = glass.cornerRadiusDp,
                valueRange = 0f..32f,
                onValueChange = { onUpdateGlass(glass.copy(cornerRadiusDp = it)) }
            )
            StudioSliderRow(
                label = "Shadow Depth",
                valueText = "${(glass.shadowIntensity * 100).roundToInt()}%",
                value = glass.shadowIntensity,
                valueRange = 0f..1f,
                onValueChange = { onUpdateGlass(glass.copy(shadowIntensity = it)) }
            )
        }

        // Refraction & Specular Reflections
        AetherSurfaceCard(componentTarget = StarComponentTarget.SETTINGS_PANELS) {
            Text(
                text = "Refraction & Edge Reflections",
                style = MaterialTheme.typography.titleMedium,
                color = theme.textPrimary
            )
            StudioSliderRow(
                label = "Glass Refraction",
                valueText = "${(glass.refractionIntensity * 100).roundToInt()}%",
                value = glass.refractionIntensity,
                valueRange = 0f..1f,
                onValueChange = { onUpdateGlass(glass.copy(refractionIntensity = it)) }
            )
            StudioSliderRow(
                label = "Specular Highlight",
                valueText = "${(glass.highlightIntensity * 100).roundToInt()}%",
                value = glass.highlightIntensity,
                valueRange = 0f..1f,
                onValueChange = { onUpdateGlass(glass.copy(highlightIntensity = it)) }
            )
            StudioSliderRow(
                label = "Edge Reflection Rim",
                valueText = "${(glass.edgeReflectionIntensity * 100).roundToInt()}%",
                value = glass.edgeReflectionIntensity,
                valueRange = 0f..1f,
                onValueChange = { onUpdateGlass(glass.copy(edgeReflectionIntensity = it)) }
            )
        }

        // Water Droplets
        AetherSurfaceCard(componentTarget = StarComponentTarget.SETTINGS_PANELS) {
            Text(
                text = "Water Droplets Overlay",
                style = MaterialTheme.typography.titleMedium,
                color = theme.textPrimary
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Enable Water Droplets", style = MaterialTheme.typography.bodyMedium, color = theme.textPrimary)
                    Text("Subtle refractive condensation on glass backdrop (High/Ultra)", style = MaterialTheme.typography.bodySmall, color = theme.textSecondary)
                }
                Switch(
                    checked = glass.waterDropletsEnabled,
                    onCheckedChange = { onUpdateGlass(glass.copy(waterDropletsEnabled = it)) }
                )
            }
            if (glass.waterDropletsEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Animate Droplets (When Visible)", style = MaterialTheme.typography.bodyMedium, color = theme.textPrimary)
                    Switch(
                        checked = glass.waterDropletsAnimated,
                        onCheckedChange = { onUpdateGlass(glass.copy(waterDropletsAnimated = it)) }
                    )
                }
                StudioSliderRow(
                    label = "Droplet Intensity & Density",
                    valueText = "${(glass.waterDropletIntensity * 100).roundToInt()}%",
                    value = glass.waterDropletIntensity,
                    valueRange = 0.05f..1f,
                    onValueChange = { onUpdateGlass(glass.copy(waterDropletIntensity = it)) }
                )
            }
        }
    }
}

@Composable
private fun ColorStudioSection(
    colors: StarColorPalette,
    onApplyPalette: (StarColorPalette) -> Unit,
    onPickColor: (ColorEditField) -> Unit
) {
    val theme = LocalStarTheme.current
    Column(verticalArrangement = Arrangement.spacedBy(AetherSpacing.Md)) {
        AetherAccentCard(
            accentColor = theme.primaryAccent,
            componentTarget = StarComponentTarget.SETTINGS_PANELS
        ) {
            Text(
                text = "Predefined Color Palettes",
                style = MaterialTheme.typography.titleMedium,
                color = theme.textPrimary
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
            ) {
                StarThemePresets.namedPalettes.forEach { named ->
                    StarLiquidGlassSurface(
                        componentTarget = StarComponentTarget.CARDS,
                        onClick = { onApplyPalette(named.palette) }
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                listOf(
                                    named.palette.primaryAccentArgb,
                                    named.palette.secondaryAccentArgb,
                                    named.palette.backgroundTintArgb
                                ).forEach { argb ->
                                    Box(
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clip(CircleShape)
                                            .background(Color(argb.toInt()))
                                            .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                                    )
                                }
                            }
                            Text(
                                text = named.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = theme.textPrimary
                            )
                        }
                    }
                }
            }
        }

        AetherSurfaceCard(componentTarget = StarComponentTarget.SETTINGS_PANELS) {
            Text(
                text = "Custom Color Tokens",
                style = MaterialTheme.typography.titleMedium,
                color = theme.textPrimary
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
            ) {
                ColorSwatchButton(
                    label = "Primary Accent",
                    argb = colors.primaryAccentArgb,
                    onClick = { onPickColor(ColorEditField.PRIMARY_ACCENT) },
                    modifier = Modifier.weight(1f)
                )
                ColorSwatchButton(
                    label = "Secondary Accent",
                    argb = colors.secondaryAccentArgb,
                    onClick = { onPickColor(ColorEditField.SECONDARY_ACCENT) },
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
            ) {
                ColorSwatchButton(
                    label = "Background Tint",
                    argb = colors.backgroundTintArgb,
                    onClick = { onPickColor(ColorEditField.BACKGROUND_TINT) },
                    modifier = Modifier.weight(1f)
                )
                ColorSwatchButton(
                    label = "Text Color",
                    argb = colors.textColorArgb,
                    onClick = { onPickColor(ColorEditField.TEXT_COLOR) },
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
            ) {
                ColorSwatchButton(
                    label = "Border Color",
                    argb = colors.borderColorArgb,
                    onClick = { onPickColor(ColorEditField.BORDER_COLOR) },
                    modifier = Modifier.weight(1f)
                )
                ColorSwatchButton(
                    label = "Highlight Color",
                    argb = colors.highlightColorArgb,
                    onClick = { onPickColor(ColorEditField.HIGHLIGHT) },
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
            ) {
                ColorSwatchButton(
                    label = "Gradient Start",
                    argb = colors.gradientStartArgb,
                    onClick = { onPickColor(ColorEditField.GRADIENT_START) },
                    modifier = Modifier.weight(1f)
                )
                ColorSwatchButton(
                    label = "Gradient End",
                    argb = colors.gradientEndArgb,
                    onClick = { onPickColor(ColorEditField.GRADIENT_END) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun ComponentsStudioSection(
    draftTheme: StarThemeConfig,
    selectedTarget: StarComponentTarget,
    onSelectTarget: (StarComponentTarget) -> Unit,
    onUpdateDraft: (StarThemeConfig) -> Unit,
    onPickColor: (ColorEditField) -> Unit
) {
    val theme = LocalStarTheme.current
    val currentOverride = draftTheme.overrideFor(selectedTarget)
    val isCustomOverridden = draftTheme.componentOverrides[selectedTarget]?.overrideEnabled == true

    Column(verticalArrangement = Arrangement.spacedBy(AetherSpacing.Md)) {
        AetherAccentCard(
            accentColor = theme.secondaryAccent,
            componentTarget = StarComponentTarget.SETTINGS_PANELS
        ) {
            Text(
                text = "Component Selector",
                style = MaterialTheme.typography.titleMedium,
                color = theme.textPrimary
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Xs)
            ) {
                StarComponentTarget.values().forEach { target ->
                    val active = target == selectedTarget
                    val overridden = draftTheme.componentOverrides[target]?.overrideEnabled == true
                    StarLiquidGlassSurface(
                        componentTarget = StarComponentTarget.BUTTONS,
                        shape = RoundedCornerShape(10.dp),
                        containerColorOverride = if (active) theme.primaryAccent.copy(alpha = 0.25f) else theme.surfaceBase,
                        borderColorOverride = if (active) theme.secondaryAccent else theme.borderColor.copy(alpha = 0.4f),
                        onClick = { onSelectTarget(target) }
                    ) {
                        Text(
                            text = if (overridden) "${target.displayName} •" else target.displayName,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (active) theme.secondaryAccent else theme.textPrimary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                        )
                    }
                }
            }
            Text(
                text = selectedTarget.description,
                style = MaterialTheme.typography.bodySmall,
                color = theme.textSecondary
            )
        }

        AetherSurfaceCard(componentTarget = StarComponentTarget.SETTINGS_PANELS) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Custom Override for ${selectedTarget.displayName}",
                        style = MaterialTheme.typography.titleMedium,
                        color = theme.textPrimary
                    )
                    Text(
                        text = if (isCustomOverridden) "Using component-specific styling" else "Inheriting global Liquid Glass style",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isCustomOverridden) theme.secondaryAccent else theme.textSecondary
                    )
                }
                Switch(
                    checked = isCustomOverridden,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            onUpdateDraft(draftTheme.withComponentOverride(selectedTarget) { it })
                        } else {
                            onUpdateDraft(draftTheme.clearComponentOverride(selectedTarget))
                        }
                    }
                )
            }

            if (isCustomOverridden) {
                AetherOutlinedButton(
                    text = "Reset ${selectedTarget.displayName} to Global",
                    onClick = { onUpdateDraft(draftTheme.clearComponentOverride(selectedTarget)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Glass Effect", style = MaterialTheme.typography.bodyMedium, color = theme.textPrimary)
                Switch(
                    checked = currentOverride.glassEffectEnabled,
                    onCheckedChange = { enabled ->
                        onUpdateDraft(draftTheme.withComponentOverride(selectedTarget) {
                            it.copy(glassEffectEnabled = enabled)
                        })
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Component Gradient", style = MaterialTheme.typography.bodyMedium, color = theme.textPrimary)
                Switch(
                    checked = currentOverride.gradientEnabled,
                    onCheckedChange = { enabled ->
                        onUpdateDraft(draftTheme.withComponentOverride(selectedTarget) {
                            it.copy(gradientEnabled = enabled)
                        })
                    }
                )
            }

            StudioSliderRow(
                label = "Background Opacity",
                valueText = "${(currentOverride.backgroundOpacity * 100).roundToInt()}%",
                value = currentOverride.backgroundOpacity,
                valueRange = 0.06f..0.98f,
                onValueChange = { v ->
                    onUpdateDraft(draftTheme.withComponentOverride(selectedTarget) { it.copy(backgroundOpacity = v) })
                }
            )
            StudioSliderRow(
                label = "Blur Intensity",
                valueText = "${currentOverride.blurIntensity.roundToInt()} dp",
                value = currentOverride.blurIntensity,
                valueRange = 0f..36f,
                onValueChange = { v ->
                    onUpdateDraft(draftTheme.withComponentOverride(selectedTarget) { it.copy(blurIntensity = v) })
                }
            )
            StudioSliderRow(
                label = "Border Opacity",
                valueText = "${(currentOverride.borderOpacity * 100).roundToInt()}%",
                value = currentOverride.borderOpacity,
                valueRange = 0f..1f,
                onValueChange = { v ->
                    onUpdateDraft(draftTheme.withComponentOverride(selectedTarget) { it.copy(borderOpacity = v) })
                }
            )
            StudioSliderRow(
                label = "Corner Radius",
                valueText = "${currentOverride.cornerRadiusDp.roundToInt()} dp",
                value = currentOverride.cornerRadiusDp,
                valueRange = 0f..32f,
                onValueChange = { v ->
                    onUpdateDraft(draftTheme.withComponentOverride(selectedTarget) { it.copy(cornerRadiusDp = v) })
                }
            )
            StudioSliderRow(
                label = "Shadow Intensity",
                valueText = "${(currentOverride.shadowIntensity * 100).roundToInt()}%",
                value = currentOverride.shadowIntensity,
                valueRange = 0f..1f,
                onValueChange = { v ->
                    onUpdateDraft(draftTheme.withComponentOverride(selectedTarget) { it.copy(shadowIntensity = v) })
                }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
            ) {
                ColorSwatchButton(
                    label = "Accent Color",
                    argb = currentOverride.accentColorArgb ?: draftTheme.colors.primaryAccentArgb,
                    onClick = { onPickColor(ColorEditField.COMPONENT_ACCENT) },
                    modifier = Modifier.weight(1f)
                )
                ColorSwatchButton(
                    label = "Gradient Start",
                    argb = currentOverride.gradientStartArgb ?: draftTheme.colors.gradientStartArgb,
                    onClick = { onPickColor(ColorEditField.COMPONENT_GRAD_START) },
                    modifier = Modifier.weight(1f)
                )
            }
            ColorSwatchButton(
                label = "Gradient End",
                argb = currentOverride.gradientEndArgb ?: draftTheme.colors.gradientEndArgb,
                onClick = { onPickColor(ColorEditField.COMPONENT_GRAD_END) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun PerformanceQualitySection(
    quality: StarEffectQuality,
    onSelectQuality: (StarEffectQuality) -> Unit
) {
    val theme = LocalStarTheme.current
    val recommended = StarThemeManager.recommendedQualityForDevice()

    AetherAccentCard(
        accentColor = theme.secondaryAccent,
        componentTarget = StarComponentTarget.SETTINGS_PANELS
    ) {
        Text(
            text = "Effect Quality & Performance",
            style = MaterialTheme.typography.titleMedium,
            color = theme.textPrimary
        )
        Text(
            text = "Recommended for this device: ${recommended.displayName}. Blurred backdrops are cached off the UI thread and animations pause when not visible.",
            style = MaterialTheme.typography.bodySmall,
            color = theme.textSecondary
        )
        StarEffectQuality.values().forEach { tier ->
            val selected = tier == quality
            StarLiquidGlassSurface(
                componentTarget = StarComponentTarget.CARDS,
                borderColorOverride = if (selected) theme.secondaryAccent else theme.borderColor.copy(alpha = 0.4f),
                onClick = { onSelectQuality(tier) }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (tier == recommended) "${tier.displayName} (Recommended)" else tier.displayName,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = if (selected) theme.secondaryAccent else theme.textPrimary
                        )
                        Text(
                            text = tier.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = theme.textSecondary
                        )
                    }
                    if (selected) {
                        Text(
                            text = "SELECTED",
                            style = MaterialTheme.typography.labelSmall,
                            color = theme.secondaryAccent
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StudioSliderRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    val theme = LocalStarTheme.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = theme.textPrimary
            )
            Text(
                text = valueText,
                style = AetherMonoStyle.copy(color = theme.secondaryAccent)
            )
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = theme.secondaryAccent,
                activeTrackColor = theme.primaryAccent,
                inactiveTrackColor = theme.surfaceVariant
            )
        )
    }
}

@Composable
private fun ColorSwatchButton(
    label: String,
    argb: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = LocalStarTheme.current
    StarLiquidGlassSurface(
        componentTarget = StarComponentTarget.CARDS,
        modifier = modifier,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color(argb.toInt()))
                    .border(1.dp, theme.highlightColor.copy(alpha = 0.6f), CircleShape)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
                    color = theme.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = String.format("#%06X", (argb and 0x00FFFFFFL).toInt()),
                    style = AetherMonoStyle.copy(color = theme.textSecondary, fontSize = 10.sp),
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun SaveThemeDialog(
    defaultName: String,
    existingCustomEntry: StarSavedThemeEntry?,
    onDismiss: () -> Unit,
    onSaveNew: (String) -> Unit,
    onOverwriteExisting: (StarSavedThemeEntry) -> Unit
) {
    var name by remember(defaultName) { mutableStateOf(defaultName) }
    StarGlassDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save Theme") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(StarThemeConfig.MAX_THEME_NAME_LENGTH) },
                    label = { Text("Theme name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (existingCustomEntry != null) {
                    AetherOutlinedButton(
                        text = "Update '${existingCustomEntry.name}'",
                        onClick = { onOverwriteExisting(existingCustomEntry) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            AetherPrimaryButton(
                text = "Save as New",
                enabled = name.isNotBlank(),
                onClick = { onSaveNew(name) }
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
