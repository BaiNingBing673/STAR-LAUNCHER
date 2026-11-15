package com.star.launcher.engine.adapter

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import net.kdt.pojavlaunch.MainActivity
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.lifecycle.ContextAwareDoneListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PojavLaunchBridgeTest {

    @Test
    fun `launch payload roundtrips across Intent and builds transient profile`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val spec = EngineLaunchSpec(
            instanceId = "inst-42",
            versionId = "1.21.4",
            gameDirPath = "/storage/emulated/0/Android/data/com.star.launcher/files/aether_instances/inst-42/.minecraft",
            runtime = RuntimeDescriptor(
                runtimeId = "jre-21-arm64",
                displayName = "JRE 21",
                majorJavaVersion = 21,
                arch = "aarch64",
                versionString = "21.0.5",
                isInternal = false
            ),
            renderer = RendererSpec(
                rendererId = "opengles_mobileglues",
                displayName = "MobileGlues",
                requiresVulkan = false,
                requiresGles3 = true
            ),
            inputProfile = InputProfileSpec(controlMapPath = "custom.json"),
            allocatedRamMb = 4096,
            customJvmArgs = "-XX:+UseG1GC"
        )

        val payload = PojavLaunchBridge.fromSpec(
            spec = spec,
            sessionId = "sess-99",
            instanceName = "Hardcore",
            accountName = "Steve",
            useAngle = true
        )
        val intent = Intent(context, MainActivity::class.java)
        PojavLaunchBridge.writeToIntent(intent, payload)

        val parsed = PojavLaunchBridge.fromIntent(intent)
        assertNotNull(parsed)
        assertEquals("inst-42", parsed!!.instanceId)
        assertEquals("sess-99", parsed.sessionId)
        assertEquals("Hardcore", parsed.instanceName)
        assertEquals("1.21.4", parsed.versionId)
        assertEquals(4096, parsed.allocatedRamMb)
        assertEquals("Steve", parsed.accountName)
        assertTrue(parsed.useAngle)

        val home = "/storage/emulated/0/Android/data/com.star.launcher/files"
        val profile = PojavLaunchBridge.toTransientMinecraftProfile(parsed, gameHomeDir = home)
        assertEquals("amethyst://aether_instances/inst-42/.minecraft", profile.gameDir)
        assertEquals("amethyst://jre-21-arm64", profile.javaDir)
        assertEquals("opengles_mobileglues", profile.pojavRendererName)
        assertEquals("-XX:+UseG1GC", profile.javaArgs)
        assertEquals("custom.json", profile.controlFile)
        assertTrue(profile.useANGLE)

        Tools.DIR_GAME_HOME = home
        Tools.DIR_GAME_NEW = "$home/.minecraft"
        assertEquals(
            File("$home/aether_instances/inst-42/.minecraft").path,
            Tools.getGameDirPath(profile).path
        )
    }

    @Test
    fun `Tools getGameDirPath preserves external absolute paths and ignores blank Intent`() {
        assertNull(PojavLaunchBridge.fromIntent(Intent()))

        val externalPath = File("/data/local/tmp/aether_custom_instance/.minecraft").absolutePath
        val payload = AetherLaunchPayload(
            instanceId = "ext-1",
            sessionId = "sess-1",
            instanceName = "External",
            versionId = "1.20.1",
            gameDirPath = externalPath,
            runtimeId = "Internal",
            rendererId = "opengles2",
            allocatedRamMb = 100, // clamped to MIN_RAM_MB via bundle
            customJvmArgs = null,
            controlMapPath = null,
            accountName = null,
            useAngle = false
        )
        val context = ApplicationProvider.getApplicationContext<Context>()
        val listener = ContextAwareDoneListener(context, "1.20.1", PojavLaunchBridge.toBundle(payload))
        assertNotNull(listener)

        val profile = PojavLaunchBridge.toTransientMinecraftProfile(payload, gameHomeDir = "/other/home")
        assertNull(profile.javaDir)
        assertEquals(externalPath, Tools.getGameDirPath(profile).path)
    }
}
