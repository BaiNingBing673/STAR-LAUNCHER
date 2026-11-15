package com.star.launcher.ui.components

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.star.launcher.ui.theme.AetherMonoStyle
import com.star.launcher.ui.theme.AetherSpacing
import com.star.launcher.ui.theme.liquid.LocalStarTheme
import com.star.launcher.ui.theme.liquid.StarGlassDialog
import com.star.launcher.ui.theme.studio.StarThemeJsonSerializer

private val QUICK_COLOR_SWATCHES = listOf(
    0xFF5FA8FFL,
    0xFF5EE2FFL,
    0xFF38BDF8L,
    0xFF38E8C6L,
    0xFFA78BFAL,
    0xFFF472B6L,
    0xFFFB7185L,
    0xFFFBBF24L,
    0xFF94A3B8L,
    0xFFECEFF6L,
    0xFF173566L,
    0xFF0A0F1AL
)

@Composable
fun StarColorPickerDialog(
    title: String,
    initialArgb: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit
) {
    val theme = LocalStarTheme.current
    val initialHsv = remember(initialArgb) {
        FloatArray(3).apply { AndroidColor.colorToHSV(initialArgb.toInt(), this) }
    }
    var hue by remember(initialArgb) { mutableFloatStateOf(initialHsv[0]) }
    var sat by remember(initialArgb) { mutableFloatStateOf(initialHsv[1]) }
    var value by remember(initialArgb) { mutableFloatStateOf(initialHsv[2]) }

    val currentArgb = remember(hue, sat, value) {
        val intColor = AndroidColor.HSVToColor(floatArrayOf(hue, sat, value))
        (intColor.toLong() and 0x00FFFFFFL) or 0xFF000000L
    }
    var hexInput by remember(currentArgb) {
        mutableStateOf(String.format("#%06X", (currentArgb and 0x00FFFFFFL).toInt()))
    }
    var hexError by remember { mutableStateOf(false) }

    fun applyArgb(argb: Long) {
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(argb.toInt(), hsv)
        hue = hsv[0]
        sat = hsv[1]
        value = hsv[2]
        hexInput = String.format("#%06X", (argb and 0x00FFFFFFL).toInt())
        hexError = false
    }

    StarGlassDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)) {
                // Saturation / Value 2D selector
                val hueColor = remember(hue) {
                    Color(AndroidColor.HSVToColor(floatArrayOf(hue, 1f, 1f)))
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(164.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, theme.borderColor.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .pointerInput(Unit) {
                            detectTapGestures { pos ->
                                sat = (pos.x / size.width.toFloat()).coerceIn(0f, 1f)
                                value = 1f - (pos.y / size.height.toFloat()).coerceIn(0f, 1f)
                                hexError = false
                            }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                sat = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                                value = 1f - (change.position.y / size.height.toFloat()).coerceIn(0f, 1f)
                                hexError = false
                            }
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(Color.White, hueColor)
                            )
                        )
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black)
                            )
                        )
                        val cx = sat * size.width
                        val cy = (1f - value) * size.height
                        drawCircle(
                            color = Color.Black.copy(alpha = 0.5f),
                            radius = 9.dp.toPx(),
                            center = Offset(cx, cy),
                            style = Stroke(width = 3.dp.toPx())
                        )
                        drawCircle(
                            color = Color.White,
                            radius = 9.dp.toPx(),
                            center = Offset(cx, cy),
                            style = Stroke(width = 1.8.dp.toPx())
                        )
                    }
                }

                // Hue spectrum bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(26.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .border(1.dp, theme.borderColor.copy(alpha = 0.5f), RoundedCornerShape(999.dp))
                        .pointerInput(Unit) {
                            detectTapGestures { pos ->
                                hue = ((pos.x / size.width.toFloat()) * 360f).coerceIn(0f, 360f)
                                hexError = false
                            }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                hue = ((change.position.x / size.width.toFloat()) * 360f).coerceIn(0f, 360f)
                                hexError = false
                            }
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawRect(
                            brush = Brush.horizontalGradient(
                                listOf(
                                    Color.Red,
                                    Color.Yellow,
                                    Color.Green,
                                    Color.Cyan,
                                    Color.Blue,
                                    Color.Magenta,
                                    Color.Red
                                )
                            )
                        )
                        val x = (hue / 360f) * size.width
                        drawCircle(
                            color = Color.White,
                            radius = 10.dp.toPx(),
                            center = Offset(x, size.height * 0.5f),
                            style = Stroke(width = 2.5.dp.toPx())
                        )
                    }
                }

                // Swatch comparison + Hex input
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
                ) {
                    Surface(
                        modifier = Modifier.size(44.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = Color(currentArgb.toInt()),
                        border = BorderStroke(1.dp, theme.highlightColor.copy(alpha = 0.6f))
                    ) {}
                    OutlinedTextField(
                        value = hexInput,
                        onValueChange = { raw ->
                            hexInput = raw.take(9)
                            val parsed = StarThemeJsonSerializer.parseColorHexOrNull(raw)
                            if (parsed != null) {
                                hexError = false
                                val hsv = FloatArray(3)
                                AndroidColor.colorToHSV(parsed.toInt(), hsv)
                                hue = hsv[0]
                                sat = hsv[1]
                                value = hsv[2]
                            } else {
                                hexError = true
                            }
                        },
                        label = { Text("Hex (#RRGGBB)") },
                        isError = hexError,
                        textStyle = AetherMonoStyle,
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Text(
                    text = "Quick Swatches",
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.textSecondary
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QUICK_COLOR_SWATCHES.forEach { swatchArgb ->
                        val isSelected = (swatchArgb and 0x00FFFFFFL) == (currentArgb and 0x00FFFFFFL)
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(Color(swatchArgb.toInt()))
                                .border(
                                    width = if (isSelected) 2.5.dp else 1.dp,
                                    color = if (isSelected) theme.highlightColor else theme.borderColor.copy(alpha = 0.6f),
                                    shape = CircleShape
                                )
                                .clickable { applyArgb(swatchArgb) }
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }
            }
        },
        confirmButton = {
            AetherPrimaryButton(
                text = "Select Color",
                onClick = { onConfirm(currentArgb) }
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = theme.textSecondary)
            }
        }
    )
}
