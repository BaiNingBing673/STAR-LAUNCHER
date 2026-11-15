package com.star.launcher.ui.dashboard

import android.content.ClipboardManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.star.launcher.core.AetherSettingsRepository
import com.star.launcher.data.db.AetherDatabase
import com.star.launcher.data.db.entity.GameInstanceEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AetherRedesignTest {

    @Test
    fun `discord invite validator accepts valid discord links and rejects non-discord or empty input`() {
        assertNull(DiscordLinkHelper.normalizeAndValidateInviteUrl(""))
        assertNull(DiscordLinkHelper.normalizeAndValidateInviteUrl("   "))
        assertNull(DiscordLinkHelper.normalizeAndValidateInviteUrl("https://discord.gg/"))
        assertNull(DiscordLinkHelper.normalizeAndValidateInviteUrl("https://evil.example.com/invite"))
        assertEquals(
            "https://discord.gg/aether-demo",
            DiscordLinkHelper.normalizeAndValidateInviteUrl("discord.gg/aether-demo")
        )
        assertEquals(
            "https://discord.com/invite/aether-demo",
            DiscordLinkHelper.normalizeAndValidateInviteUrl("https://discord.com/invite/aether-demo")
        )
        assertEquals(
            "https://discord.gg/DdtPb8ehqR",
            DiscordLinkHelper.DEFAULT_DISCORD_INVITE_URL
        )
        assertEquals(
            DiscordLinkHelper.DEFAULT_DISCORD_INVITE_URL,
            DiscordLinkHelper.normalizeAndValidateInviteUrl(DiscordLinkHelper.DEFAULT_DISCORD_INVITE_URL)
        )
        assertEquals(
            DiscordLinkHelper.DEFAULT_DISCORD_INVITE_URL,
            AetherDashboardUiState().discordInviteUrl
        )
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(
            DiscordLinkHelper.DEFAULT_DISCORD_INVITE_URL,
            context.getString(net.kdt.pojavlaunch.R.string.discord_invite)
        )
    }

    @Test
    fun `openDiscordInvite handles unconfigured invalid and missing browser or discord app safely`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertEquals(DiscordOpenOutcome.NOT_CONFIGURED, DiscordLinkHelper.openDiscordInvite(context, ""))
        assertEquals(DiscordOpenOutcome.INVALID_URL, DiscordLinkHelper.openDiscordInvite(context, "https://example.com/abc"))

        val invite = "https://discord.gg/aether-fallback"
        val outcome = DiscordLinkHelper.openDiscordInvite(context, invite)
        // Robolectric either resolves no external browser/Discord app and copies to clipboard, or dispatches if registered
        if (outcome == DiscordOpenOutcome.NO_HANDLER_COPIED) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
            assertEquals(invite, clipText)
        } else {
            assertEquals(DiscordOpenOutcome.OPENED, outcome)
        }
    }

    @Test
    fun `featuredInstance resolves explicit selection then most recently played instance`() {
        val old = GameInstanceEntity(
            instanceId = "inst-old",
            name = "Old World",
            gameDirPath = "/tmp/old",
            versionId = "1.20.1",
            runtimeId = null,
            rendererProfileId = null,
            allocatedRamMb = 2048,
            customJvmArgs = null,
            controlMapPath = null,
            lastPlayedAt = 100L,
            createdAt = 50L
        )
        val recent = GameInstanceEntity(
            instanceId = "inst-recent",
            name = "Recent World",
            gameDirPath = "/tmp/recent",
            versionId = "1.21.4",
            runtimeId = null,
            rendererProfileId = null,
            allocatedRamMb = 4096,
            customJvmArgs = null,
            controlMapPath = null,
            lastPlayedAt = 500L,
            createdAt = 60L
        )

        val emptyState = AetherDashboardUiState()
        assertNull(emptyState.featuredInstance)

        val autoSelected = AetherDashboardUiState(instances = listOf(old, recent))
        assertEquals("inst-recent", autoSelected.featuredInstance?.instanceId)

        val pinned = AetherDashboardUiState(selectedInstanceId = "inst-old", instances = listOf(old, recent))
        assertEquals("inst-old", pinned.featuredInstance?.instanceId)
    }

    @Test
    fun `settings repository persists configurable discord invite and reduced motion without Amethyst default`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AetherDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = AetherSettingsRepository(db.settingsDao())
            assertEquals("", repo.getString(AetherDashboardController.KEY_DISCORD_INVITE_URL, ""))
            assertTrue(!repo.getBoolean(AetherDashboardController.KEY_REDUCED_MOTION, false))

            val valid = DiscordLinkHelper.normalizeAndValidateInviteUrl("https://discord.gg/custom-aether")
            assertNotNull(valid)
            repo.setString(AetherDashboardController.KEY_DISCORD_INVITE_URL, valid!!)
            repo.setBoolean(AetherDashboardController.KEY_REDUCED_MOTION, true)

            assertEquals("https://discord.gg/custom-aether", repo.getString(AetherDashboardController.KEY_DISCORD_INVITE_URL))
            assertTrue(repo.getBoolean(AetherDashboardController.KEY_REDUCED_MOTION, false))
        } finally {
            db.close()
        }
    }
}
