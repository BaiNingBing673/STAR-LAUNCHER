package com.star.launcher.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Custom crisp vector iconography for STAR Launcher so no upstream/generic icon sets are copied.
 */
object AetherIcons {
    val Home: ImageVector by lazy {
        strokeIcon("AetherHome") {
            moveTo(3.5f, 10.5f)
            lineTo(12f, 3.5f)
            lineTo(20.5f, 10.5f)
            moveTo(5.5f, 9.5f)
            lineTo(5.5f, 19.5f)
            lineTo(10f, 19.5f)
            lineTo(10f, 14f)
            lineTo(14f, 14f)
            lineTo(14f, 19.5f)
            lineTo(18.5f, 19.5f)
            lineTo(18.5f, 9.5f)
        }
    }

    val Library: ImageVector by lazy {
        strokeIcon("AetherLibrary") {
            // Album-stack / instance shelf motif
            moveTo(4f, 5f)
            lineTo(9f, 5f)
            lineTo(9f, 19f)
            lineTo(4f, 19f)
            close()
            moveTo(11.5f, 5f)
            lineTo(15.5f, 5f)
            lineTo(15.5f, 19f)
            lineTo(11.5f, 19f)
            close()
            moveTo(17.5f, 6.2f)
            lineTo(20.8f, 18.6f)
        }
    }

    val Versions: ImageVector by lazy {
        strokeIcon("AetherVersions") {
            // Layered release disc / cube strata
            moveTo(12f, 3.5f)
            lineTo(20f, 7.8f)
            lineTo(12f, 12.1f)
            lineTo(4f, 7.8f)
            close()
            moveTo(4f, 12.2f)
            lineTo(12f, 16.5f)
            lineTo(20f, 12.2f)
            moveTo(4f, 16.2f)
            lineTo(12f, 20.5f)
            lineTo(20f, 16.2f)
        }
    }

    val Mods: ImageVector by lazy {
        strokeIcon("AetherMods") {
            // Precision hex-cube / module crystal
            moveTo(12f, 3.2f)
            lineTo(19.5f, 7.5f)
            lineTo(19.5f, 16.5f)
            lineTo(12f, 20.8f)
            lineTo(4.5f, 16.5f)
            lineTo(4.5f, 7.5f)
            close()
            moveTo(12f, 12f)
            lineTo(12f, 20.8f)
            moveTo(12f, 12f)
            lineTo(19.5f, 7.5f)
            moveTo(12f, 12f)
            lineTo(4.5f, 7.5f)
        }
    }

    val Community: ImageVector by lazy {
        strokeIcon("AetherCommunity") {
            // Signal waveform + community node
            moveTo(4f, 13f)
            lineTo(4f, 11f)
            moveTo(8f, 16f)
            lineTo(8f, 8f)
            moveTo(12f, 19f)
            lineTo(12f, 5f)
            moveTo(16f, 15.5f)
            lineTo(16f, 8.5f)
            moveTo(20f, 13.5f)
            lineTo(20f, 10.5f)
        }
    }

    val Settings: ImageVector by lazy {
        strokeIcon("AetherSettings") {
            // Precision audio-console faders
            moveTo(4f, 7f)
            lineTo(20f, 7f)
            moveTo(4f, 12f)
            lineTo(20f, 12f)
            moveTo(4f, 17f)
            lineTo(20f, 17f)
            moveTo(9f, 5f)
            lineTo(9f, 9f)
            moveTo(15.5f, 10f)
            lineTo(15.5f, 14f)
            moveTo(10.5f, 15f)
            lineTo(10.5f, 19f)
        }
    }

    val Play: ImageVector by lazy {
        fillIcon("AetherPlay") {
            moveTo(7.5f, 5.2f)
            lineTo(19f, 12f)
            lineTo(7.5f, 18.8f)
            close()
        }
    }

    val Download: ImageVector by lazy {
        strokeIcon("AetherDownload") {
            moveTo(12f, 4f)
            lineTo(12f, 15.5f)
            moveTo(7.5f, 11f)
            lineTo(12f, 15.5f)
            lineTo(16.5f, 11f)
            moveTo(4.5f, 19f)
            lineTo(19.5f, 19f)
        }
    }

    val Runtime: ImageVector by lazy {
        strokeIcon("AetherRuntime") {
            moveTo(7f, 7f)
            lineTo(17f, 7f)
            lineTo(17f, 17f)
            lineTo(7f, 17f)
            close()
            moveTo(10f, 4f)
            lineTo(10f, 7f)
            moveTo(14f, 4f)
            lineTo(14f, 7f)
            moveTo(10f, 17f)
            lineTo(10f, 20f)
            moveTo(14f, 17f)
            lineTo(14f, 20f)
            moveTo(4f, 10f)
            lineTo(7f, 10f)
            moveTo(4f, 14f)
            lineTo(7f, 14f)
            moveTo(17f, 10f)
            lineTo(20f, 10f)
            moveTo(17f, 14f)
            lineTo(20f, 14f)
        }
    }

    val Account: ImageVector by lazy {
        strokeIcon("AetherAccount") {
            moveTo(12f, 11.5f)
            curveTo(14.2f, 11.5f, 15.8f, 9.8f, 15.8f, 7.6f)
            curveTo(15.8f, 5.4f, 14.2f, 3.8f, 12f, 3.8f)
            curveTo(9.8f, 3.8f, 8.2f, 5.4f, 8.2f, 7.6f)
            curveTo(8.2f, 9.8f, 9.8f, 11.5f, 12f, 11.5f)
            close()
            moveTo(5f, 20f)
            curveTo(5.8f, 16.2f, 8.5f, 14.5f, 12f, 14.5f)
            curveTo(15.5f, 14.5f, 18.2f, 16.2f, 19f, 20f)
        }
    }

    val Warning: ImageVector by lazy {
        strokeIcon("AetherWarning") {
            moveTo(12f, 4f)
            lineTo(20.5f, 19f)
            lineTo(3.5f, 19f)
            close()
            moveTo(12f, 9.5f)
            lineTo(12f, 13.8f)
            moveTo(12f, 16.4f)
            lineTo(12f, 16.6f)
        }
    }

    val ShieldCheck: ImageVector by lazy {
        strokeIcon("AetherShieldCheck") {
            moveTo(12f, 3.5f)
            lineTo(19f, 6.2f)
            lineTo(19f, 11.5f)
            curveTo(19f, 16.2f, 15.8f, 19.3f, 12f, 20.6f)
            curveTo(8.2f, 19.3f, 5f, 16.2f, 5f, 11.5f)
            lineTo(5f, 6.2f)
            close()
            moveTo(9f, 12.2f)
            lineTo(11.1f, 14.3f)
            lineTo(15.4f, 9.8f)
        }
    }

    val ExternalLink: ImageVector by lazy {
        strokeIcon("AetherExternalLink") {
            moveTo(14f, 4.5f)
            lineTo(19.5f, 4.5f)
            lineTo(19.5f, 10f)
            moveTo(19.2f, 4.8f)
            lineTo(11f, 13f)
            moveTo(18f, 14f)
            lineTo(18f, 19f)
            lineTo(5f, 19f)
            lineTo(5f, 6f)
            lineTo(10f, 6f)
        }
    }

    val Plus: ImageVector by lazy {
        strokeIcon("AetherPlus") {
            moveTo(12f, 5f)
            lineTo(12f, 19f)
            moveTo(5f, 12f)
            lineTo(19f, 12f)
        }
    }

    val Refresh: ImageVector by lazy {
        strokeIcon("AetherRefresh") {
            moveTo(19f, 9f)
            curveTo(17.8f, 6.2f, 15.1f, 4.5f, 12f, 4.5f)
            curveTo(7.9f, 4.5f, 4.5f, 7.9f, 4.5f, 12f)
            moveTo(19.5f, 4.8f)
            lineTo(19.5f, 9.2f)
            lineTo(15.2f, 9.2f)
            moveTo(5f, 15f)
            curveTo(6.2f, 17.8f, 8.9f, 19.5f, 12f, 19.5f)
            curveTo(16.1f, 19.5f, 19.5f, 16.1f, 19.5f, 12f)
            moveTo(4.5f, 19.2f)
            lineTo(4.5f, 14.8f)
            lineTo(8.8f, 14.8f)
        }
    }

    val Controls: ImageVector by lazy {
        strokeIcon("AetherControls") {
            moveTo(5.5f, 8f)
            lineTo(18.5f, 8f)
            curveTo(20.2f, 8f, 21f, 9.5f, 21f, 12f)
            curveTo(21f, 15f, 19.6f, 17.5f, 17.5f, 17.5f)
            lineTo(15.2f, 14.5f)
            lineTo(8.8f, 14.5f)
            lineTo(6.5f, 17.5f)
            curveTo(4.4f, 17.5f, 3f, 15f, 3f, 12f)
            curveTo(3f, 9.5f, 3.8f, 8f, 5.5f, 8f)
            close()
            moveTo(7.5f, 11.2f)
            lineTo(10f, 11.2f)
            moveTo(8.75f, 10f)
            lineTo(8.75f, 12.4f)
            moveTo(15.8f, 11.2f)
            lineTo(16.1f, 11.2f)
        }
    }

    private inline fun strokeIcon(
        name: String,
        block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.85f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            block()
        }
    }.build()

    private inline fun fillIcon(
        name: String,
        block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = SolidColor(Color.White),
            pathFillType = PathFillType.NonZero
        ) {
            block()
        }
    }.build()
}
