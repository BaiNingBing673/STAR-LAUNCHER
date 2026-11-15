package com.star.launcher.ui.dashboard

import com.star.launcher.data.db.model.CompatibilityStatus
import com.star.launcher.diagnostics.CrossProcessSpoolBridge
import com.star.launcher.ui.theme.CompatibilityBadgeTier
import net.kdt.pojavlaunch.Tools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AetherDashboardStateTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `mapStatusToBadgeTier maps unsupported and fail statuses to BLOCKED badge`() {
        assertEquals(
            CompatibilityBadgeTier.BLOCKED,
            mapStatusToBadgeTier(CompatibilityBadgeTier.THEORETICAL, CompatibilityStatus.UNSUPPORTED)
        )
        assertEquals(
            CompatibilityBadgeTier.BLOCKED,
            mapStatusToBadgeTier(CompatibilityBadgeTier.VERIFIED_RUNTIME, CompatibilityStatus.FAIL)
        )
        assertEquals(
            CompatibilityBadgeTier.THEORETICAL,
            mapStatusToBadgeTier(CompatibilityBadgeTier.THEORETICAL, CompatibilityStatus.PASS)
        )
        assertEquals(
            CompatibilityBadgeTier.TESTED,
            mapStatusToBadgeTier(CompatibilityBadgeTier.TESTED, CompatibilityStatus.WARN)
        )
    }

    @Test
    fun `dashboard tabs cover home library versions mods community and settings`() {
        val tabs = DashboardTab.values().toList()
        assertEquals(6, tabs.size)
        assertEquals(
            listOf(
                DashboardTab.HOME,
                DashboardTab.LIBRARY,
                DashboardTab.VERSIONS,
                DashboardTab.MODS,
                DashboardTab.COMMUNITY,
                DashboardTab.SETTINGS
            ),
            tabs
        )
    }

    @Test
    fun `writeGameExitSpoolJvm redacts credentials without initializing Room`() {
        val dataRoot = tempFolder.newFolder("aether_data")
        Tools.DIR_DATA = dataRoot.absolutePath
        CrossProcessSpoolBridge.writeGameExitSpoolJvm(
            sessionId = "sess-jvm-1",
            crashed = false,
            reachedGlContextInit = true,
            rendererId = "opengles_mobileglues",
            summaryMessage = "Launched --accessToken mySecretToken123"
        )
        val spoolFile = File(dataRoot, "aether_spool/sess-jvm-1.json")
        assertTrue(spoolFile.isFile)
        val parsed = CrossProcessSpoolBridge.parseSpoolJson(spoolFile.readText())
        assertTrue(parsed.reachedGlContextInit)
        assertTrue(parsed.summaryMessage.contains("[REDACTED]"))
    }
}
