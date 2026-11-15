package com.star.launcher.ui.navigation

import androidx.appcompat.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.star.launcher.engine.adapter.AetherLaunchPayload
import com.star.launcher.engine.adapter.AuthAdapter
import com.star.launcher.engine.adapter.EnginePreferencesAdapter
import com.star.launcher.engine.adapter.PojavLaunchBridge
import net.kdt.pojavlaunch.modloaders.modpacks.api.ModLoader
import net.kdt.pojavlaunch.modloaders.modpacks.api.NotificationDownloadListener
import com.star.launcher.ui.dashboard.AetherDashboardActivity
import com.star.launcher.ui.dashboard.DashboardTab
import com.star.launcher.ui.dashboard.DiscordLinkHelper
import com.star.launcher.ui.dashboard.DiscordOpenOutcome
import net.kdt.pojavlaunch.ExitActivity
import net.kdt.pojavlaunch.FatalErrorActivity
import net.kdt.pojavlaunch.LauncherActivity
import net.kdt.pojavlaunch.MainActivity
import net.kdt.pojavlaunch.TestStorageActivity
import net.kdt.pojavlaunch.lifecycle.ContextAwareDoneListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AetherNavigationRegressionTest {

    @Test
    fun `flow 1 launch app home settings home stays exclusively in AetherDashboardActivity`() {
        val controller = Robolectric.buildActivity(AetherDashboardActivity::class.java)
            .create()
            .start()
            .resume()
        val activity = controller.get()

        assertEquals(AetherRoute.Tab(DashboardTab.HOME), activity.stateViewModel.navigation.currentRoute)
        assertEquals(DashboardTab.HOME, activity.stateViewModel.controller.uiState.value.selectedTab)

        activity.stateViewModel.onTabSelected(DashboardTab.SETTINGS)
        assertEquals(AetherRoute.Tab(DashboardTab.SETTINGS), activity.stateViewModel.navigation.currentRoute)
        assertEquals(DashboardTab.SETTINGS, activity.stateViewModel.controller.uiState.value.selectedTab)

        // Both explicit Home tab tap and Back from Settings return cleanly to Home
        activity.onBackPressedDispatcher.onBackPressed()
        assertEquals(AetherRoute.Tab(DashboardTab.HOME), activity.stateViewModel.navigation.currentRoute)
        assertEquals(DashboardTab.HOME, activity.stateViewModel.controller.uiState.value.selectedTab)

        activity.stateViewModel.onTabSelected(DashboardTab.SETTINGS)
        activity.stateViewModel.onTabSelected(DashboardTab.HOME)
        assertEquals(AetherRoute.Tab(DashboardTab.HOME), activity.stateViewModel.navigation.currentRoute)

        val started = Shadows.shadowOf(activity).nextStartedActivity
        assertNull("Normal dashboard navigation must not launch any legacy activity", started)
        controller.pause().stop().destroy()
    }

    @Test
    fun `flow 2 home library instance details back preserves stack cleanly`() {
        val controller = Robolectric.buildActivity(AetherDashboardActivity::class.java)
            .create()
            .start()
            .resume()
        val activity = controller.get()

        activity.stateViewModel.onTabSelected(DashboardTab.LIBRARY)
        activity.stateViewModel.navigation.push(AetherRoute.InstanceDetail("survival-world"))
        activity.stateViewModel.syncControllerWithCurrentRoute()

        assertEquals(AetherRoute.InstanceDetail("survival-world"), activity.stateViewModel.navigation.currentRoute)
        assertEquals(DashboardTab.LIBRARY, activity.stateViewModel.controller.uiState.value.selectedTab)

        activity.onBackPressedDispatcher.onBackPressed()
        assertEquals(AetherRoute.Tab(DashboardTab.LIBRARY), activity.stateViewModel.navigation.currentRoute)

        activity.onBackPressedDispatcher.onBackPressed()
        assertEquals(AetherRoute.Tab(DashboardTab.HOME), activity.stateViewModel.navigation.currentRoute)
        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
        controller.pause().stop().destroy()
    }

    @Test
    fun `flow 3 home versions install progress back and modpack notification stay in Aether`() {
        val controller = Robolectric.buildActivity(AetherDashboardActivity::class.java)
            .create()
            .start()
            .resume()
        val activity = controller.get()

        activity.stateViewModel.onTabSelected(DashboardTab.VERSIONS)
        activity.stateViewModel.navigation.push(
            AetherRoute.VersionInstallProgress("1.21.4", "Install 1.21.4")
        )
        activity.stateViewModel.syncControllerWithCurrentRoute()

        assertEquals(
            AetherRoute.VersionInstallProgress("1.21.4", "Install 1.21.4"),
            activity.stateViewModel.navigation.currentRoute
        )
        assertEquals(DashboardTab.VERSIONS, activity.stateViewModel.controller.uiState.value.selectedTab)

        activity.onBackPressedDispatcher.onBackPressed()
        assertEquals(AetherRoute.Tab(DashboardTab.VERSIONS), activity.stateViewModel.navigation.currentRoute)

        // Verify notification tap routes to AetherDashboardActivity (never LauncherActivity)
        val listener = NotificationDownloadListener(
            activity,
            ModLoader(ModLoader.MOD_LOADER_FORGE, "47.2.0", "1.20.1")
        )
        val field = NotificationDownloadListener::class.java.getDeclaredField("mIntent")
        field.isAccessible = true
        val notifIntent = field.get(listener) as Intent
        assertEquals(AetherDashboardActivity::class.java.name, notifIntent.component?.className)
        assertEquals("VERSIONS", notifIntent.getStringExtra(AetherDashboardActivity.EXTRA_INITIAL_ROUTE))
        controller.pause().stop().destroy()
    }

    @Test
    fun `flow 4 runtime management runtime details back and settings subscreens stay in Aether`() {
        val controller = Robolectric.buildActivity(AetherDashboardActivity::class.java)
            .create()
            .start()
            .resume()
        val activity = controller.get()

        activity.stateViewModel.onTabSelected(DashboardTab.SETTINGS)
        activity.openRuntimeManagement()
        assertEquals(AetherRoute.RuntimeManagement, activity.stateViewModel.navigation.currentRoute)

        activity.stateViewModel.navigation.push(AetherRoute.RuntimeDetail("Internal-17"))
        assertEquals(AetherRoute.RuntimeDetail("Internal-17"), activity.stateViewModel.navigation.currentRoute)

        activity.onBackPressedDispatcher.onBackPressed()
        assertEquals(AetherRoute.RuntimeManagement, activity.stateViewModel.navigation.currentRoute)

        activity.onBackPressedDispatcher.onBackPressed()
        assertEquals(AetherRoute.Tab(DashboardTab.SETTINGS), activity.stateViewModel.navigation.currentRoute)

        // Verify MicrosoftAuth, EnginePreferences, and GamepadMapper push inside Aether without LauncherActivity
        activity.stateViewModel.navigation.push(AetherRoute.MicrosoftAuth)
        assertEquals(AetherRoute.MicrosoftAuth, activity.stateViewModel.navigation.currentRoute)
        activity.onBackPressedDispatcher.onBackPressed()

        activity.stateViewModel.navigation.push(AetherRoute.EnginePreferences(PreferenceCategory.VIDEO))
        assertEquals(AetherRoute.EnginePreferences(PreferenceCategory.VIDEO), activity.stateViewModel.navigation.currentRoute)
        val updatedPrefs = EnginePreferencesAdapter.setBoolean(activity, "sustainedPerformance", true)
        assertTrue(updatedPrefs.sustainedPerformance)
        activity.onBackPressedDispatcher.onBackPressed()

        activity.stateViewModel.navigation.push(AetherRoute.GamepadMapper)
        assertEquals(AetherRoute.GamepadMapper, activity.stateViewModel.navigation.currentRoute)
        activity.onBackPressedDispatcher.onBackPressed()

        assertEquals(AetherRoute.Tab(DashboardTab.SETTINGS), activity.stateViewModel.navigation.currentRoute)
        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
        controller.pause().stop().destroy()
    }

    @Test
    fun `flow 5 community discord external link does not corrupt Aether stack`() {
        val controller = Robolectric.buildActivity(AetherDashboardActivity::class.java)
            .create()
            .start()
            .resume()
        val activity = controller.get()

        activity.stateViewModel.onTabSelected(DashboardTab.COMMUNITY)
        val outcome = DiscordLinkHelper.openDiscordInvite(activity, "https://discord.gg/aether-test")
        assertTrue(outcome == DiscordOpenOutcome.OPENED || outcome == DiscordOpenOutcome.NO_HANDLER_COPIED)
        assertEquals(AetherRoute.Tab(DashboardTab.COMMUNITY), activity.stateViewModel.navigation.currentRoute)

        val next = Shadows.shadowOf(activity).nextStartedActivity
        if (next != null) {
            assertEquals(Intent.ACTION_VIEW, next.action)
            assertNotEquals(LauncherActivity::class.java.name, next.component?.className)
        }
        controller.pause().stop().destroy()
    }

    @Test
    fun `flow 6 play launches MainActivity directly via PojavLaunchBridge and ContextAwareDoneListener`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val payload = AetherLaunchPayload(
            instanceId = "inst-play",
            sessionId = "sess-play-1",
            instanceName = "Play Pack",
            versionId = "1.21.4",
            gameDirPath = context.filesDir.absolutePath,
            runtimeId = "Internal-21",
            rendererId = "opengles_mobileglues",
            allocatedRamMb = 2048,
            customJvmArgs = null,
            controlMapPath = null,
            accountName = "Tester"
        )
        val extras = PojavLaunchBridge.toBundle(payload)
        val doneListener = ContextAwareDoneListener(context, "1.21.4", extras)
        val startIntent = doneListener.createGameStartIntent(context)

        assertEquals(MainActivity::class.java.name, startIntent.component?.className)
        assertEquals("1.21.4", startIntent.getStringExtra(MainActivity.INTENT_MINECRAFT_VERSION))
        assertEquals("inst-play", startIntent.getStringExtra(PojavLaunchBridge.EXTRA_INSTANCE_ID))
    }

    @Test
    fun `flow 7 minecraft exit returns cleanly to AetherDashboardActivity`() {
        val exitIntent = Intent(ApplicationProvider.getApplicationContext(), ExitActivity::class.java).apply {
            putExtra("code", 0)
            putExtra("isSignal", false)
        }
        val exitController = Robolectric.buildActivity(ExitActivity::class.java, exitIntent)
            .create()
            .start()
            .resume()
        val exitActivity = exitController.get()

        exitActivity.returnToAether()
        val returnedIntent = Shadows.shadowOf(exitActivity).nextStartedActivity
        assertNotNull(returnedIntent)
        assertEquals(AetherDashboardActivity::class.java.name, returnedIntent.component?.className)
        assertTrue(exitActivity.isFinishing)

        // Verify FatalErrorActivity Restart button also routes to AetherDashboardActivity, never LauncherActivity
        val errorIntent = Intent(ApplicationProvider.getApplicationContext(), FatalErrorActivity::class.java).apply {
            putExtra("storageAllow", false)
            putExtra("throwable", RuntimeException("boom"))
            putExtra("savePath", "")
        }
        val fatalController = Robolectric.buildActivity(FatalErrorActivity::class.java, errorIntent)
            .create()
            .start()
            .resume()
        val dialog = ShadowAlertDialog.getLatestDialog() as AlertDialog
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val restartIntent = Shadows.shadowOf(fatalController.get()).nextStartedActivity
        assertNotNull(restartIntent)
        assertEquals(AetherDashboardActivity::class.java.name, restartIntent.component?.className)
    }

    @Test
    fun `flow 8 screen rotation preserves active nested Aether route and back stack`() {
        val controller = Robolectric.buildActivity(AetherDashboardActivity::class.java)
            .create()
            .start()
            .resume()
        val initial = controller.get()

        initial.stateViewModel.onTabSelected(DashboardTab.SETTINGS)
        initial.openRuntimeManagement()
        initial.stateViewModel.navigation.push(AetherRoute.RuntimeDetail("Internal-21"))
        initial.stateViewModel.syncControllerWithCurrentRoute()

        controller.recreate()
        val rotated = controller.get()

        assertEquals(
            AetherRoute.RuntimeDetail("Internal-21"),
            rotated.stateViewModel.navigation.currentRoute
        )
        assertEquals(DashboardTab.SETTINGS, rotated.stateViewModel.controller.uiState.value.selectedTab)

        rotated.onBackPressedDispatcher.onBackPressed()
        assertEquals(AetherRoute.RuntimeManagement, rotated.stateViewModel.navigation.currentRoute)
        controller.pause().stop().destroy()
    }

    @Test
    fun `flow 9 process recreation restores exact Aether destination from SavedStateHandle`() {
        val savedBundle = Bundle()
        val firstController = Robolectric.buildActivity(AetherDashboardActivity::class.java)
            .create()
            .start()
            .resume()
        val firstActivity = firstController.get()

        firstActivity.stateViewModel.onTabSelected(DashboardTab.LIBRARY)
        firstActivity.stateViewModel.navigation.push(AetherRoute.InstanceDetail("modded-fabric"))
        firstActivity.stateViewModel.syncControllerWithCurrentRoute()

        firstController.saveInstanceState(savedBundle).pause().stop().destroy()

        // Recreate a brand-new Activity instance simulating OS process kill & restart
        val restoredController = Robolectric.buildActivity(AetherDashboardActivity::class.java)
            .create(savedBundle)
            .start()
            .resume()
        val restored = restoredController.get()

        assertEquals(
            AetherRoute.InstanceDetail("modded-fabric"),
            restored.stateViewModel.navigation.currentRoute
        )
        assertEquals(DashboardTab.LIBRARY, restored.stateViewModel.controller.uiState.value.selectedTab)

        restored.onBackPressedDispatcher.onBackPressed()
        assertEquals(AetherRoute.Tab(DashboardTab.LIBRARY), restored.stateViewModel.navigation.currentRoute)
        assertFalse(restored.isFinishing)
        restoredController.pause().stop().destroy()
    }

    @Test
    fun `auth adapter extracts microsoft redirect code without LauncherActivity`() {
        val sampleRedirect = "ms-xal-00000000402b5328://auth?code=M.R3_BAY.12345"
        assertTrue(AuthAdapter.isRedirectSuccessUrl(sampleRedirect))
        assertEquals("M.R3_BAY.12345", AuthAdapter.extractAuthorizationCode(sampleRedirect))
        assertFalse(AuthAdapter.isCancelUrl(sampleRedirect))
        assertTrue(AuthAdapter.isCancelUrl("https://login.live.com/err?res=cancel"))
    }
}
