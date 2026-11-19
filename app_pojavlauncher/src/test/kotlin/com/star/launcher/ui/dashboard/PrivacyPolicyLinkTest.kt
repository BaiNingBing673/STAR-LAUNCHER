package com.star.launcher.ui.dashboard

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.star.launcher.core.AetherContainer
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import net.kdt.pojavlaunch.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrivacyPolicyLinkTest {

    private val trackedControllers = mutableListOf<ActivityController<AetherDashboardActivity>>()
    private val trackedJobs = mutableListOf<Job>()

    @After
    fun tearDown() {
        trackedControllers.forEach { controller ->
            if (!controller.get().isDestroyed) {
                runCatching { controller.pause().stop().destroy() }
            }
        }
        trackedControllers.clear()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        runBlocking {
            withTimeoutOrNull(10_000) {
                trackedJobs.forEach { job ->
                    job.cancel()
                    job.join()
                }
            }
        }
        trackedJobs.clear()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        AetherDashboardActivity.resetForTesting()
        AetherContainer.resetForTesting()
    }

    @Test
    fun `policy url validator accepts http and https urls and rejects invalid schemes`() {
        assertNull(PrivacyPolicyLinkHelper.normalizeAndValidatePolicyUrl(""))
        assertNull(PrivacyPolicyLinkHelper.normalizeAndValidatePolicyUrl("   "))
        assertNull(PrivacyPolicyLinkHelper.normalizeAndValidatePolicyUrl("javascript:alert(1)"))
        assertNull(PrivacyPolicyLinkHelper.normalizeAndValidatePolicyUrl("file:///etc/passwd"))
        assertNull(PrivacyPolicyLinkHelper.normalizeAndValidatePolicyUrl("https://"))

        assertEquals(
            "https://bainingbing673.github.io/STAR-LAUNCHER/privacy-policy.html",
            PrivacyPolicyLinkHelper.normalizeAndValidatePolicyUrl(
                "  https://bainingbing673.github.io/STAR-LAUNCHER/privacy-policy.html  "
            )
        )
    }

    @Test
    fun `configured privacy policy string resource is valid https url`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val title = context.getString(R.string.privacy_policy_title)
        val url = PrivacyPolicyLinkHelper.getConfiguredUrl(context)

        assertEquals("Privacy Policy", title)
        assertTrue(url.startsWith("https://"))
        assertNotNull(PrivacyPolicyLinkHelper.normalizeAndValidatePolicyUrl(url))
    }

    @Test
    fun `openPrivacyPolicy launches ACTION_VIEW intent with hosted policy url`() {
        val controller = Robolectric.buildActivity(AetherDashboardActivity::class.java)
            .create()
            .start()
            .resume()
        trackedControllers += controller
        val activity = controller.get()
        trackedJobs += activity.lifecycleScope.coroutineContext.job
        trackedJobs += activity.stateViewModel.viewModelScope.coroutineContext.job

        activity.stateViewModel.onTabSelected(DashboardTab.SETTINGS)
        val expectedUrl = PrivacyPolicyLinkHelper.getConfiguredUrl(activity)

        val outcome = PrivacyPolicyLinkHelper.openPrivacyPolicy(activity)
        assertEquals(PrivacyPolicyOpenOutcome.OPENED, outcome)

        val startedIntent = Shadows.shadowOf(activity).nextStartedActivity
        assertNotNull(startedIntent)
        assertEquals(Intent.ACTION_VIEW, startedIntent.action)
        assertEquals(Uri.parse(expectedUrl), startedIntent.data)

        controller.pause().stop().destroy()
    }

    @Test
    fun `openPrivacyPolicy returns INVALID_URL for invalid input`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(
            PrivacyPolicyOpenOutcome.INVALID_URL,
            PrivacyPolicyLinkHelper.openPrivacyPolicy(context, "not-a-url")
        )
    }

    @Test
    fun `copyToClipboard stores privacy policy url`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val expectedUrl = PrivacyPolicyLinkHelper.getConfiguredUrl(context)
        PrivacyPolicyLinkHelper.copyToClipboard(context, expectedUrl)

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(expectedUrl, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }
}
