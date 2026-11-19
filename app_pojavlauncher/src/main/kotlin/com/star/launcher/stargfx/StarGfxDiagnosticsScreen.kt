package com.star.launcher.stargfx

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.star.launcher.ui.components.AetherAccentCard
import com.star.launcher.ui.components.AetherOutlinedButton
import com.star.launcher.ui.components.AetherPrimaryButton
import com.star.launcher.ui.components.AetherSurfaceCard
import com.star.launcher.ui.components.AetherTonalButton
import com.star.launcher.ui.screens.AetherSubscreenHeader
import com.star.launcher.ui.theme.AetherColors
import com.star.launcher.ui.theme.AetherMonoStyle
import com.star.launcher.ui.theme.AetherSpacing
import com.star.launcher.ui.theme.studio.StarComponentTarget
import org.json.JSONObject
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StarGfxDiagnosticsScreen(
    onBack: () -> Unit,
    onStatusMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(StarGfxFallbackController.isEnabled(context)) }
    var selectedBackend by remember { mutableStateOf(StarGfxFallbackController.getSelectedBackend(context)) }
    var libVerification by remember { mutableStateOf(StarGfxNative.verifyLibraries()) }
    var systemProbe by remember { mutableStateOf(StarGfxNative.probeSystemGraphics()) }
    var lastReportText by remember {
        mutableStateOf(
            StarGfxFallbackController.getLastReportTxtPath(context)?.let { path ->
                val f = File(path)
                if (f.isFile) f.readText() else null
            }
        )
    }

    fun runHeadlessVerification(backendId: String) {
        val libVer = StarGfxNative.verifyLibraries()
        val probe = StarGfxNative.probeSystemGraphics()
        libVerification = libVer
        systemProbe = probe
        val ptr = StarGfxNative.createSession(backendId, null, 256, 256)
        val errors = mutableListOf<String>()
        var diag = JSONObject()
        var proof: JSONObject? = null
        var pngFile: File? = null
        if (ptr == 0L) {
            val err = StarGfxNative.getLastInitError().optString("message", "Session creation failed")
            errors += err
            StarGfxFallbackController.recordFailureAndRestoreStable(context, err)
            enabled = StarGfxFallbackController.isEnabled(context)
        } else {
            try {
                val dir = StarGfxDiagnosticExporter.diagnosticsDir(context)
                val ppm = File(dir, "headless_${backendId}_${System.currentTimeMillis()}.ppm")
                val png = File(dir, "headless_${backendId}_${System.currentTimeMillis()}.png")
                proof = StarGfxNative.captureOffscreenFrame(ptr, 0.75f, ppm.absolutePath)
                if (proof?.optBoolean("ok") == true && StarGfxDiagnosticExporter.convertPpmToPng(ppm, png)) {
                    pngFile = png
                }
                diag = StarGfxNative.getDiagnosticsJson(ptr)
            } finally {
                StarGfxNative.destroySession(ptr)
            }
        }
        val level = if (proof?.optBoolean("ok") == true) {
            StarGfxAcceptanceLevel.STAR_LAUNCHER_INTEGRATED
        } else if (diag.optBoolean("deviceInitialized")) {
            StarGfxAcceptanceLevel.BACKEND_INITIALIZED
        } else if (libVer.optBoolean("stargfxLoaded")) {
            StarGfxAcceptanceLevel.NATIVE_LIBRARY_LOADED
        } else {
            StarGfxAcceptanceLevel.BUILT
        }
        val exported = StarGfxDiagnosticExporter.exportReport(
            context = context,
            backendSelected = backendId,
            libVerification = libVer,
            systemProbe = probe,
            runtimeDiag = diag,
            renderProof = proof,
            benchmarkShort = null,
            benchmarkSustained = null,
            acceptanceLevel = level,
            runtimeErrors = errors,
            crashInfo = null,
            capturedPng = pngFile
        )
        if (proof?.optBoolean("ok") == true) {
            StarGfxFallbackController.recordSuccessfulRun(
                context,
                level,
                exported.jsonFile.absolutePath,
                exported.textFile.absolutePath,
                exported.pngFile?.absolutePath
            )
        }
        lastReportText = exported.textFile.readText()
        onStatusMessage("Exported STAR GFX report (${level.label}) to ${exported.jsonFile.name}")
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(AetherSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(AetherSpacing.Md)
    ) {
        item {
            AetherSubscreenHeader(
                title = "STAR GFX Runtime & Diagnostics",
                subtitle = "Native library verification, backend probing, standalone 3D test & fallback control",
                backLabel = "Settings",
                onBack = onBack
            )
        }

        item {
            AetherAccentCard(
                accentColor = AetherColors.AccentCyan,
                componentTarget = StarComponentTarget.SETTINGS_PANELS
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Enable STAR GFX Integration",
                            style = MaterialTheme.typography.titleMedium,
                            color = AetherColors.OnBackgroundDark
                        )
                        Text(
                            text = "Last status: ${StarGfxFallbackController.getLastAcceptanceLevel(context)} | Failures: ${StarGfxFallbackController.getConsecutiveFailures(context)}",
                            style = AetherMonoStyle.copy(color = AetherColors.AccentCyan)
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            StarGfxFallbackController.setEnabled(context, it)
                            onStatusMessage(if (it) "STAR GFX enabled" else "STAR GFX disabled (using stable fallback)")
                        }
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm),
                    verticalArrangement = Arrangement.spacedBy(AetherSpacing.Xs)
                ) {
                    StarGfxBackendPreference.values().forEach { b ->
                        FilterChip(
                            selected = selectedBackend == b,
                            onClick = {
                                selectedBackend = b
                                StarGfxFallbackController.setSelectedBackend(context, b)
                            },
                            label = { Text(b.label) }
                        )
                    }
                }
            }
        }

        item {
            AetherSurfaceCard(componentTarget = StarComponentTarget.SETTINGS_PANELS) {
                val vk = systemProbe.optJSONObject("vulkan")
                val gl = systemProbe.optJSONObject("opengles")
                Text(
                    text = "Native Libraries & Hardware Probe",
                    style = MaterialTheme.typography.titleLarge,
                    color = AetherColors.OnSurfaceDark
                )
                Text(
                    text = "STAR GFX v${libVerification.optString("apiVersion", "0.1.0")} | libstargfx=${libVerification.optBoolean("stargfxLoaded")} | bridge=${libVerification.optBoolean("bridgeLoaded")}",
                    style = AetherMonoStyle.copy(color = AetherColors.TierVerifiedFg)
                )
                Text(
                    text = "Mapped: ${libVerification.optString("stargfxPath", "not loaded")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherColors.OnSurfaceVariantDark
                )
                Text(
                    text = "Vulkan: avail=${vk?.optBoolean("available")} | API=${vk?.optString("deviceApiVersion", "N/A")} | GPU=${vk?.optString("deviceName", "N/A")} | Driver=${vk?.optString("driverVersion", "N/A")}",
                    style = AetherMonoStyle.copy(color = AetherColors.OnSurfaceDark)
                )
                Text(
                    text = "OpenGL ES: avail=${gl?.optBoolean("available")} | ${gl?.optString("glVersion", "N/A")} | ${gl?.optString("glRenderer", "N/A")}",
                    style = AetherMonoStyle.copy(color = AetherColors.OnSurfaceDark)
                )
            }
        }

        item {
            AetherSurfaceCard(componentTarget = StarComponentTarget.SETTINGS_PANELS) {
                Text(
                    text = "Verification & Standalone 3D Test Scene",
                    style = MaterialTheme.typography.titleLarge,
                    color = AetherColors.OnSurfaceDark
                )
                Text(
                    text = "Launches a 3D textured cube + depth test + orbiting camera rendered directly via STAR GFX on a live SurfaceView.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherColors.OnSurfaceVariantDark
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
                ) {
                    AetherPrimaryButton(
                        text = "3D Test (Vulkan)",
                        onClick = {
                            context.startActivity(StarGfxStandaloneActivity.createIntent(context, "vulkan", autoVerify = true))
                        },
                        modifier = Modifier.weight(1f)
                    )
                    AetherTonalButton(
                        text = "3D Test (GLES)",
                        onClick = {
                            context.startActivity(StarGfxStandaloneActivity.createIntent(context, "gles", autoVerify = true))
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
                ) {
                    AetherOutlinedButton(
                        text = "Run Offscreen Probe",
                        onClick = { runHeadlessVerification(selectedBackend.id) },
                        modifier = Modifier.weight(1f)
                    )
                    AetherOutlinedButton(
                        text = "Restore MobileGlues",
                        onClick = {
                            val restored = StarGfxFallbackController.recordFailureAndRestoreStable(
                                context,
                                "Manual fallback to stable renderer",
                                forceDisable = true
                            )
                            enabled = false
                            onStatusMessage("STAR GFX disabled; stable renderer restored: $restored")
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        item {
            val mcCompat = remember { StarGfxFallbackController.checkMinecraftCompatibility(context, "Java") }
            AetherSurfaceCard(componentTarget = StarComponentTarget.SETTINGS_PANELS) {
                Text(
                    text = "Minecraft Java Compatibility Gate",
                    style = MaterialTheme.typography.titleLarge,
                    color = AetherColors.OnSurfaceDark
                )
                Text(
                    text = "Direct Minecraft Java Replacement: INCOMPATIBLE",
                    style = AetherMonoStyle.copy(color = AetherColors.TierBlockedFg)
                )
                Text(
                    text = mcCompat.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherColors.OnSurfaceVariantDark
                )
            }
        }

        if (!lastReportText.isNullOrBlank()) {
            item {
                AetherSurfaceCard(componentTarget = StarComponentTarget.SETTINGS_PANELS) {
                    Text(
                        text = "Latest Diagnostic Report",
                        style = MaterialTheme.typography.titleMedium,
                        color = AetherColors.OnSurfaceDark
                    )
                    Text(
                        text = lastReportText!!,
                        style = AetherMonoStyle.copy(color = AetherColors.OnSurfaceVariantDark)
                    )
                }
            }
        }
    }
}
