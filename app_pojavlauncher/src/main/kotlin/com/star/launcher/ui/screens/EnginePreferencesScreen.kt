package com.star.launcher.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.star.launcher.engine.adapter.EnginePreferencesAdapter
import com.star.launcher.engine.adapter.EnginePreferencesSnapshot
import com.star.launcher.ui.components.AetherAccentCard
import com.star.launcher.ui.components.AetherIcons
import com.star.launcher.ui.components.AetherOutlinedButton
import com.star.launcher.ui.components.AetherSurfaceCard
import com.star.launcher.ui.components.AetherTonalButton
import com.star.launcher.ui.navigation.PreferenceCategory
import com.star.launcher.ui.theme.AetherColors
import com.star.launcher.ui.theme.AetherMonoStyle
import com.star.launcher.ui.theme.AetherSpacing
import net.kdt.pojavlaunch.R
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EnginePreferencesScreen(
    initialCategory: PreferenceCategory,
    onBack: () -> Unit,
    onOpenRuntimeManagement: () -> Unit,
    onOpenCustomControls: () -> Unit,
    onOpenGamepadMapper: () -> Unit,
    onStatusMessage: (String) -> Unit,
    onPreferencesUpdated: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var category by remember(initialCategory) { mutableStateOf(initialCategory) }
    var prefs by remember { mutableStateOf(EnginePreferencesAdapter.readSnapshot(context)) }
    var glslCacheInput by remember(prefs.mgGlslCacheSizeMb) { mutableStateOf(prefs.mgGlslCacheSizeMb) }
    var javaArgsInput by remember(prefs.javaArgs) { mutableStateOf(prefs.javaArgs) }

    var hasNotifPerm by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var hasMicPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotifPerm = granted
        onStatusMessage(if (granted) "Notification permission granted" else "Notification permission denied")
    }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPerm = granted
        onStatusMessage(if (granted) "Microphone permission granted" else "Microphone permission denied")
    }

    val angleNames = remember { context.resources.getStringArray(R.array.mg_renderer_names_angle) }
    val angleValues = remember { context.resources.getStringArray(R.array.mg_renderer_values_angle) }
    val errorNames = remember { context.resources.getStringArray(R.array.mg_renderer_names_errorSetting) }
    val errorValues = remember { context.resources.getStringArray(R.array.mg_renderer_values_errorSetting) }
    val sourceNames = remember { context.resources.getStringArray(R.array.download_source_names) }
    val sourceValues = remember { context.resources.getStringArray(R.array.download_source_values) }

    fun updateBool(key: String, value: Boolean) {
        prefs = EnginePreferencesAdapter.setBoolean(context, key, value)
        onPreferencesUpdated()
    }

    fun updateInt(key: String, value: Int) {
        prefs = EnginePreferencesAdapter.setInt(context, key, value)
        onPreferencesUpdated()
    }

    fun updateString(key: String, value: String) {
        prefs = EnginePreferencesAdapter.setString(context, key, value)
        onPreferencesUpdated()
    }

    fun showCategory(target: PreferenceCategory): Boolean =
        category == PreferenceCategory.ALL || category == target

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(AetherSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(AetherSpacing.Md)
    ) {
        item {
            AetherSubscreenHeader(
                title = "Engine Preferences",
                subtitle = "Native STAR configuration backed by LauncherPreferences",
                backLabel = "Settings",
                onBack = onBack
            )
        }

        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm),
                verticalArrangement = Arrangement.spacedBy(AetherSpacing.Xs)
            ) {
                PreferenceCategory.values().forEach { item ->
                    FilterChip(
                        selected = category == item,
                        onClick = { category = item },
                        label = { Text(item.title) }
                    )
                }
            }
        }

        if (showCategory(PreferenceCategory.VIDEO)) {
            item {
                AetherAccentCard(accentColor = AetherColors.AccentCyan) {
                    Text(
                        text = "Video & Renderer",
                        style = MaterialTheme.typography.titleLarge,
                        color = AetherColors.OnBackgroundDark
                    )
                    Text(
                        text = "Active Global Renderer",
                        style = MaterialTheme.typography.labelLarge,
                        color = AetherColors.OnSurfaceDark
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm),
                        verticalArrangement = Arrangement.spacedBy(AetherSpacing.Xs)
                    ) {
                        prefs.compatibleRenderers.forEach { (id, name) ->
                            FilterChip(
                                selected = prefs.rendererId == id,
                                onClick = { updateString("renderer", id) },
                                label = { Text(name) }
                            )
                        }
                    }

                    IntSliderPreference(
                        label = "Resolution Scale",
                        value = prefs.resolutionRatioPercent,
                        unit = "%",
                        range = 25f..150f,
                        onCommit = { updateInt("resolutionRatio", it) }
                    )

                    if (prefs.notchAvailable) {
                        SwitchPreferenceRow(
                            title = "Ignore Display Notch",
                            subtitle = "Expand Minecraft surface across display cutouts",
                            checked = prefs.ignoreNotch,
                            onCheckedChange = { updateBool("ignoreNotch", it) }
                        )
                    }
                    SwitchPreferenceRow(
                        title = "Sustained Performance Mode",
                        subtitle = "Reduces thermal throttling spikes on supported devices",
                        checked = prefs.sustainedPerformance,
                        onCheckedChange = { updateBool("sustainedPerformance", it) }
                    )
                    SwitchPreferenceRow(
                        title = "Use Alternate Surface",
                        subtitle = "Enables SurfaceView/alternate presentation path",
                        checked = prefs.alternateSurface,
                        onCheckedChange = { updateBool("alternate_surface", it) }
                    )
                    if (prefs.alternateSurface) {
                        SwitchPreferenceRow(
                            title = "Force VSync",
                            subtitle = "Synchronize frame presentation with display refresh",
                            checked = prefs.forceVsync,
                            onCheckedChange = { updateBool("force_vsync", it) }
                        )
                    }
                    SwitchPreferenceRow(
                        title = "VSync in Zink",
                        subtitle = "Enable FIFO/VSync when using Vulkan Zink renderer",
                        checked = prefs.vsyncInZink,
                        onCheckedChange = { updateBool("vsync_in_zink", it) }
                    )
                }
            }

            item {
                AetherSurfaceCard {
                    Text(
                        text = "MobileGlues Renderer Options",
                        style = MaterialTheme.typography.titleMedium,
                        color = AetherColors.OnSurfaceDark
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = glslCacheInput,
                            onValueChange = { value ->
                                glslCacheInput = value.filter { it.isDigit() }.ifEmpty { "0" }
                            },
                            label = { Text("GLSL Cache Size (MB, 0 = Off)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        AetherTonalButton(
                            text = "Apply",
                            onClick = {
                                updateString("mg_renderer_setting_glsl_cache_size", glslCacheInput.ifBlank { "0" })
                                onStatusMessage("Updated MobileGlues GLSL cache size")
                            }
                        )
                    }

                    Text(
                        text = "ANGLE Backend Mode",
                        style = MaterialTheme.typography.labelLarge,
                        color = AetherColors.OnSurfaceDark
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm),
                        verticalArrangement = Arrangement.spacedBy(AetherSpacing.Xs)
                    ) {
                        angleValues.forEachIndexed { idx, value ->
                            val label = angleNames.getOrNull(idx) ?: value
                            FilterChip(
                                selected = prefs.mgAngleMode == value,
                                onClick = { updateString("mg_renderer_setting_angle", value) },
                                label = { Text(label) }
                            )
                        }
                    }

                    Text(
                        text = "OpenGL Error Reporting",
                        style = MaterialTheme.typography.labelLarge,
                        color = AetherColors.OnSurfaceDark
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm),
                        verticalArrangement = Arrangement.spacedBy(AetherSpacing.Xs)
                    ) {
                        errorValues.forEachIndexed { idx, value ->
                            val label = errorNames.getOrNull(idx) ?: value
                            FilterChip(
                                selected = prefs.mgErrorSetting == value,
                                onClick = { updateString("mg_renderer_setting_errorSetting", value) },
                                label = { Text(label) }
                            )
                        }
                    }

                    SwitchPreferenceRow(
                        title = "Timer Query Extension",
                        subtitle = "Expose EXT_disjoint_timer_query to mods & shaders",
                        checked = prefs.mgTimerQueryExt,
                        onCheckedChange = { updateBool("mg_renderer_setting_timerQueryExt", it) }
                    )
                    SwitchPreferenceRow(
                        title = "ANGLE Depth Clear Fix",
                        subtitle = "Workaround for depth clearing artifacts on ANGLE drivers",
                        checked = prefs.mgAngleDepthClearFixMode,
                        onCheckedChange = { updateBool("mg_renderer_setting_angleDepthClearFixMode", it) }
                    )
                    SwitchPreferenceRow(
                        title = "Compute Shader Extension",
                        subtitle = "Experimental GLES compute shader pass-through",
                        checked = prefs.mgComputeShaderExt,
                        onCheckedChange = { updateBool("mg_renderer_computeShaderext", it) }
                    )
                    SwitchPreferenceRow(
                        title = "DSA Extension",
                        subtitle = "Experimental Direct State Access support",
                        checked = prefs.mgDsaExt,
                        onCheckedChange = { updateBool("mg_renderer_dsaExt", it) }
                    )
                }
            }
        }

        if (showCategory(PreferenceCategory.CONTROLS)) {
            item {
                AetherSurfaceCard {
                    Text(
                        text = "Controls, Mouse & Gamepad",
                        style = MaterialTheme.typography.titleLarge,
                        color = AetherColors.OnSurfaceDark
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
                    ) {
                        AetherTonalButton(
                            text = "Touch Controls Editor",
                            icon = AetherIcons.Controls,
                            onClick = onOpenCustomControls,
                            modifier = Modifier.weight(1f)
                        )
                        AetherOutlinedButton(
                            text = "Gamepad Mapper",
                            onClick = onOpenGamepadMapper,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    SwitchPreferenceRow(
                        title = "Disable Touch Gestures",
                        subtitle = "Disable hotbar/long-press gestures over viewport",
                        checked = prefs.disableGestures,
                        onCheckedChange = { updateBool("disableGestures", it) }
                    )
                    SwitchPreferenceRow(
                        title = "Disable Double-Tap Swap Hand",
                        subtitle = "Prevent double-tap gestures from swapping held item",
                        checked = prefs.disableDoubleTap,
                        onCheckedChange = { updateBool("disableDoubleTap", it) }
                    )
                    if (!prefs.disableGestures) {
                        IntSliderPreference(
                            label = "Long-Press Trigger Delay",
                            value = prefs.timeLongPressTriggerMs,
                            unit = "ms",
                            range = 100f..1000f,
                            onCommit = { updateInt("timeLongPressTrigger", it) }
                        )
                    }
                    IntSliderPreference(
                        label = "On-Screen Button Scale",
                        value = prefs.buttonScalePercent,
                        unit = "%",
                        range = 50f..200f,
                        onCommit = { updateInt("buttonscale", it) }
                    )
                    SwitchPreferenceRow(
                        title = "Uppercase Control Labels",
                        subtitle = "Render virtual button names in uppercase",
                        checked = prefs.buttonAllCaps,
                        onCheckedChange = { updateBool("buttonAllCaps", it) }
                    )
                    SwitchPreferenceRow(
                        title = "IME Keyboard Panning",
                        subtitle = "Translate canvas vertically when soft keyboard opens",
                        checked = prefs.keyboardPanning,
                        onCheckedChange = { updateBool("keyboardPanning", it) }
                    )
                    IntSliderPreference(
                        label = "Virtual Mouse Scale",
                        value = prefs.mouseScalePercent,
                        unit = "%",
                        range = 25f..300f,
                        onCommit = { updateInt("mousescale", it) }
                    )
                    IntSliderPreference(
                        label = "Mouse Sensitivity",
                        value = prefs.mouseSpeedPercent,
                        unit = "%",
                        range = 25f..300f,
                        onCommit = { updateInt("mousespeed", it) }
                    )
                    SwitchPreferenceRow(
                        title = "Start With Virtual Mouse",
                        subtitle = "Enable touch cursor immediately on launch",
                        checked = prefs.mouseStart,
                        onCheckedChange = { updateBool("mouse_start", it) }
                    )
                    SwitchPreferenceRow(
                        title = "Always Grab Mouse",
                        subtitle = "Capture physical mouse movement when unfocused",
                        checked = prefs.alwaysGrabMouse,
                        onCheckedChange = { updateBool("always_grab_mouse", it) }
                    )
                    IntSliderPreference(
                        label = "Gamepad Deadzone Scale",
                        value = prefs.gamepadDeadzoneScalePercent,
                        unit = "%",
                        range = 50f..200f,
                        onCommit = { updateInt("gamepad_deadzone_scale", it) }
                    )
                    SwitchPreferenceRow(
                        title = "Force SDL Gamepad Passthrough",
                        subtitle = "Forward raw controller events to mods supporting SDL",
                        checked = prefs.gamepadPassthruForced,
                        onCheckedChange = { updateBool("gamepadPassthruForced", it) }
                    )
                    SwitchPreferenceRow(
                        title = "Force Enable TouchController",
                        subtitle = "Enable TouchController mod protocol in all sessions",
                        checked = prefs.forceEnableTouchController,
                        onCheckedChange = { updateBool("forceEnableTouchController", it) }
                    )
                    IntSliderPreference(
                        label = "TouchController Haptic Length",
                        value = prefs.touchControllerVibrateLengthMs,
                        unit = "ms",
                        range = 0f..500f,
                        onCommit = { updateInt("touchControllerVibrateLength", it) }
                    )

                    if (prefs.gyroAvailable) {
                        SwitchPreferenceRow(
                            title = "Enable Gyroscope Aiming",
                            subtitle = "Use hardware gyro sensor for camera motion",
                            checked = prefs.enableGyro,
                            onCheckedChange = { updateBool("enableGyro", it) }
                        )
                        if (prefs.enableGyro) {
                            IntSliderPreference(
                                label = "Gyro Sensitivity",
                                value = prefs.gyroSensitivityPercent,
                                unit = "%",
                                range = 25f..300f,
                                onCommit = { updateInt("gyroSensitivity", it) }
                            )
                            IntSliderPreference(
                                label = "Gyro Sample Rate",
                                value = prefs.gyroSampleRateMs,
                                unit = "ms",
                                range = 2f..50f,
                                onCommit = { updateInt("gyroSampleRate", it) }
                            )
                            SwitchPreferenceRow(
                                title = "Gyro Smoothing",
                                subtitle = "Filter micro-jitter from gyroscope samples",
                                checked = prefs.gyroSmoothing,
                                onCheckedChange = { updateBool("gyroSmoothing", it) }
                            )
                            SwitchPreferenceRow(
                                title = "Invert Gyro X",
                                subtitle = "Invert horizontal gyroscope axis",
                                checked = prefs.gyroInvertX,
                                onCheckedChange = { updateBool("gyroInvertX", it) }
                            )
                            SwitchPreferenceRow(
                                title = "Invert Gyro Y",
                                subtitle = "Invert vertical gyroscope axis",
                                checked = prefs.gyroInvertY,
                                onCheckedChange = { updateBool("gyroInvertY", it) }
                            )
                        }
                    }

                    AetherOutlinedButton(
                        text = "Wipe Saved Gamepad Mappings",
                        onClick = {
                            prefs = EnginePreferencesAdapter.wipeGamepadMappings(context)
                            onStatusMessage("Cleared all custom gamepad button mappings")
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        if (showCategory(PreferenceCategory.JAVA)) {
            item {
                AetherSurfaceCard {
                    Text(
                        text = "Java & Runtime Tweaks",
                        style = MaterialTheme.typography.titleLarge,
                        color = AetherColors.OnSurfaceDark
                    )
                    AetherTonalButton(
                        text = "Open STAR Runtime Manager",
                        icon = AetherIcons.Runtime,
                        onClick = onOpenRuntimeManagement,
                        modifier = Modifier.fillMaxWidth()
                    )
                    IntSliderPreference(
                        label = "Global Memory Allocation",
                        value = prefs.ramAllocationMb.coerceIn(256, 8192),
                        unit = "MB",
                        range = 256f..8192f,
                        onCommit = { updateInt("allocation", it) }
                    )
                    OutlinedTextField(
                        value = javaArgsInput,
                        onValueChange = { javaArgsInput = it },
                        label = { Text("Global Custom JVM Arguments") },
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth()
                    )
                    AetherTonalButton(
                        text = "Save JVM Arguments",
                        onClick = {
                            updateString("javaArgs", javaArgsInput.trim())
                            onStatusMessage("Saved global JVM arguments")
                        }
                    )
                    SwitchPreferenceRow(
                        title = "Disable Automatic JRE Selection",
                        subtitle = "Always honor manually selected runtime instead of auto-picking",
                        checked = prefs.disableAutoJreSelect,
                        onCheckedChange = { updateBool("disable_autojre_select", it) }
                    )
                }
            }
        }

        if (showCategory(PreferenceCategory.MISC)) {
            item {
                AetherSurfaceCard {
                    Text(
                        text = "General, Network & Permissions",
                        style = MaterialTheme.typography.titleLarge,
                        color = AetherColors.OnSurfaceDark
                    )
                    SwitchPreferenceRow(
                        title = "Verify Library SHA Checksums",
                        subtitle = "Re-check library hashes before launching",
                        checked = prefs.checkLibraries,
                        onCheckedChange = { updateBool("checkLibraries", it) }
                    )
                    SwitchPreferenceRow(
                        title = "ARC Capes Support",
                        subtitle = "Use ARC capes server integration",
                        checked = prefs.arcCapes,
                        onCheckedChange = { updateBool("arc_capes", it) }
                    )
                    Text(
                        text = "Download Source",
                        style = MaterialTheme.typography.labelLarge,
                        color = AetherColors.OnSurfaceDark
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm),
                        verticalArrangement = Arrangement.spacedBy(AetherSpacing.Xs)
                    ) {
                        sourceValues.forEachIndexed { idx, value ->
                            val label = sourceNames.getOrNull(idx) ?: value
                            FilterChip(
                                selected = prefs.downloadSource == value,
                                onClick = { updateString("downloadSource", value) },
                                label = { Text(label) }
                            )
                        }
                    }
                    SwitchPreferenceRow(
                        title = "Verify Modpack Manifests",
                        subtitle = "Validate hashes when importing or updating packs",
                        checked = prefs.verifyManifest,
                        onCheckedChange = { updateBool("verifyManifest", it) }
                    )
                    if (prefs.zinkPreferSystemSupported) {
                        SwitchPreferenceRow(
                            title = "Zink: Prefer System Vulkan Driver",
                            subtitle = "Use OS Vulkan ICD instead of bundled driver wrapper",
                            checked = prefs.zinkPreferSystemDriver,
                            onCheckedChange = { updateBool("zinkPreferSystemDriver", it) }
                        )
                    }
                    SwitchPreferenceRow(
                        title = "Force English Locale",
                        subtitle = "Restart launcher to apply UI language override",
                        checked = prefs.forceEnglish,
                        onCheckedChange = { updateBool("force_english", it) }
                    )
                    if (!hasNotifPerm && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        AetherOutlinedButton(
                            text = "Grant Notification Permission",
                            onClick = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (!hasMicPerm) {
                        AetherOutlinedButton(
                            text = "Grant Microphone Permission",
                            onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        if (showCategory(PreferenceCategory.EXPERIMENTAL)) {
            item {
                AetherSurfaceCard {
                    Text(
                        text = "Experimental Flags",
                        style = MaterialTheme.typography.titleLarge,
                        color = AetherColors.OnSurfaceDark
                    )
                    SwitchPreferenceRow(
                        title = "Dump Shaders to Disk",
                        subtitle = "Write translated shader sources for debugging",
                        checked = prefs.dumpShaders,
                        onCheckedChange = { updateBool("dump_shaders", it) }
                    )
                    SwitchPreferenceRow(
                        title = "Big-Core Thread Affinity",
                        subtitle = "Pin JVM render threads toward performance CPU cores",
                        checked = prefs.bigCoreAffinity,
                        onCheckedChange = { updateBool("bigCoreAffinity", it) }
                    )
                    SwitchPreferenceRow(
                        title = "Sodium Workaround Override",
                        subtitle = "Allow Sodium compatibility patches on mobile GPUs",
                        checked = prefs.sodiumOverride,
                        onCheckedChange = { updateBool("sodium_override", it) }
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(AetherSpacing.Md)) }
    }
}

@Composable
private fun SwitchPreferenceRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = AetherColors.OnSurfaceDark
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = AetherColors.OnSurfaceVariantDark
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun IntSliderPreference(
    label: String,
    value: Int,
    unit: String,
    range: ClosedFloatingPointRange<Float>,
    onCommit: (Int) -> Unit
) {
    val clampedInitial = value.toFloat().coerceIn(range.start, range.endInclusive)
    var sliderState by remember(value, range) { mutableFloatStateOf(clampedInitial) }
    Column(verticalArrangement = Arrangement.spacedBy(AetherSpacing.Xxs)) {
        Text(
            text = "$label: ${sliderState.roundToInt()} $unit",
            style = AetherMonoStyle.copy(color = AetherColors.AccentCyan)
        )
        Slider(
            value = sliderState,
            onValueChange = { sliderState = it },
            valueRange = range,
            onValueChangeFinished = { onCommit(sliderState.roundToInt()) }
        )
    }
}
