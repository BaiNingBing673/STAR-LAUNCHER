package com.star.launcher.bridge

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.star.launcher.bridge.auth.BridgeAuthenticationManager
import com.star.launcher.bridge.auth.FloodgateManager
import com.star.launcher.bridge.compat.BridgeCompatibilityEngine
import com.star.launcher.bridge.discovery.ServerDiscoveryManager
import com.star.launcher.bridge.discovery.ServerProfileManager
import com.star.launcher.bridge.logging.BridgeLogManager
import com.star.launcher.bridge.model.BridgeAuthMode
import com.star.launcher.bridge.model.CompatibilityItemStatus
import com.star.launcher.bridge.model.ExternalServerSoftware
import com.star.launcher.bridge.model.GeyserAvailability
import com.star.launcher.bridge.model.GeyserConfig
import com.star.launcher.bridge.model.InstalledArtifactMetadata
import com.star.launcher.bridge.model.LocalServerConfig
import com.star.launcher.bridge.model.LocalServerSoftware
import com.star.launcher.bridge.model.NetworkEndpointsSnapshot
import com.star.launcher.bridge.model.RuntimeCheckDetails
import com.star.launcher.bridge.network.BedrockPingResult
import com.star.launcher.bridge.network.BridgeNetworkManager
import com.star.launcher.bridge.network.BridgePortManager
import com.star.launcher.bridge.network.JavaPingResult
import com.star.launcher.bridge.runtime.BridgeUpdateManager
import com.star.launcher.bridge.runtime.BridgeVersionManager
import com.star.launcher.bridge.storage.BridgeStoragePaths
import com.star.launcher.data.db.AetherDatabase
import com.star.launcher.data.security.TokenVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BridgeProfileAndCompatTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: AetherDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AetherDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `server profile manager CRUD, duplicate, favorite, export-import, and safe delete`() = runTest {
        val profileMgr = ServerProfileManager(db.serverProfileDao())
        val serverDirSentinel = tempFolder.newFolder("world_sentinel")
        val sentinelFile = File(serverDirSentinel, "level.dat").apply { writeText("world-data") }

        val added = profileMgr.addProfile(
            name = "STAR SMP",
            description = "Cross-play Survival",
            javaAddress = "play.example.org",
            javaPort = 25565,
            bedrockAddress = "br.example.org",
            bedrockPort = 19132,
            serverSoftware = ExternalServerSoftware.PAPER,
            authMode = BridgeAuthMode.FLOODGATE
        )
        assertEquals(25565, added.javaPort)
        assertEquals(19132, added.bedrockPort)

        val starred = profileMgr.toggleFavorite(added.profileId)
        assertTrue(starred?.isFavorite == true)

        val duplicated = profileMgr.duplicateProfile(added.profileId)
        assertNotNull(duplicated)
        assertEquals(2, profileMgr.listProfiles().size)

        val exported = profileMgr.exportProfilesJson()
        profileMgr.deleteProfile(added.profileId)
        profileMgr.deleteProfile(duplicated!!.profileId)
        assertEquals(0, profileMgr.listProfiles().size)
        // Deleting profiles never deletes actual world files
        assertTrue(sentinelFile.isFile)

        val imported = profileMgr.importProfilesJson(exported)
        assertEquals(2, imported.size)
    }

    @Test
    fun `discovery manager detects Java-only server and clearly reports server-side Geyser requirement`() = runTest {
        val profileMgr = ServerProfileManager(db.serverProfileDao())
        val portMgr = BridgePortManager()
        val netMgr = BridgeNetworkManager(portMgr)
        val discoveryMgr = ServerDiscoveryManager(profileMgr, netMgr)

        val tcpServer = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 5000 }
        val javaPort = tcpServer.localPort
        val tcpJob = launch(Dispatchers.IO) {
            runCatching {
                tcpServer.accept().use { client ->
                    client.soTimeout = 5000
                    val inp = DataInputStream(client.getInputStream())
                    val out = DataOutputStream(client.getOutputStream())
                    val hsLen = readVarInt(inp)
                    inp.readFully(ByteArray(hsLen))
                    val reqLen = readVarInt(inp)
                    inp.readFully(ByteArray(reqLen))

                    val json = """{"version":{"name":"Vanilla 1.21.4","protocol":769},"players":{"max":10,"online":1},"description":{"text":"Java Only"}}"""
                    val jb = json.toByteArray(Charsets.UTF_8)
                    val body = ByteArrayOutputStream()
                    val bodyOut = DataOutputStream(body)
                    writeVarInt(bodyOut, 0x00)
                    writeVarInt(bodyOut, jb.size)
                    bodyOut.write(jb)
                    val pkt = body.toByteArray()
                    writeVarInt(out, pkt.size)
                    out.write(pkt)
                    out.flush()
                }
            }
        }

        // Closed high UDP port to ensure no Bedrock listener responds
        val unusedUdpPort = 49150
        val profile = profileMgr.addProfile(
            name = "Java Only External",
            javaAddress = "127.0.0.1",
            javaPort = javaPort,
            bedrockAddress = "127.0.0.1",
            bedrockPort = unusedUdpPort,
            serverSoftware = ExternalServerSoftware.VANILLA
        )

        val summary = discoveryMgr.probeAndUpdateProfile(profile)
        tcpJob.join()
        tcpServer.close()

        assertTrue(summary.javaPing.reachable)
        assertFalse(summary.bedrockPing.reachable)
        assertEquals(GeyserAvailability.NOT_DETECTED, summary.profile.geyserAvailability)
        assertTrue(summary.guidanceMessage.contains("server-side", ignoreCase = true))

        val d1 = tempFolder.newFolder("d")
        val g1 = tempFolder.newFolder("g")
        val paths = BridgeStoragePaths({ d1 }, { g1 })
        val vault = object : TokenVault {
            override fun putSecret(alias: String, plaintextSecret: String) {}
            override fun getSecret(alias: String): String? = null
            override fun deleteSecret(alias: String) {}
            override fun containsSecret(alias: String): Boolean = false
        }
        val logs = BridgeLogManager(paths)
        val fgMgr = FloodgateManager(paths, BridgeVersionManager(), BridgeUpdateManager(paths, logs), vault, logs)
        val authMgr = BridgeAuthenticationManager(fgMgr)
        val compatEngine = BridgeCompatibilityEngine()

        val report = compatEngine.evaluateExternalServer(
            profile = summary.profile,
            javaPing = summary.javaPing,
            bedrockPing = summary.bedrockPing,
            endpoints = NetworkEndpointsSnapshot(),
            authManager = authMgr,
            geyserConfig = GeyserConfig()
        )
        assertFalse(report.overallCompatible)
        assertNotNull(report.serverSideGeyserRequiredNote)
        assertTrue(report.toReadableText().contains("Bedrock Version: Incompatible"))
    }

    @Test
    fun `compatibility engine builds readable local hosting report`() {
        val d2 = tempFolder.newFolder("d2")
        val g2 = tempFolder.newFolder("g2")
        val paths = BridgeStoragePaths({ d2 }, { g2 })
        val vault = object : TokenVault {
            override fun putSecret(alias: String, plaintextSecret: String) {}
            override fun getSecret(alias: String): String? = null
            override fun deleteSecret(alias: String) {}
            override fun containsSecret(alias: String): Boolean = false
        }
        val logs = BridgeLogManager(paths)
        val fgMgr = FloodgateManager(paths, BridgeVersionManager(), BridgeUpdateManager(paths, logs), vault, logs)
        val authMgr = BridgeAuthenticationManager(fgMgr)
        val compatEngine = BridgeCompatibilityEngine()

        val report = compatEngine.evaluateLocalHosting(
            geyserRuntime = RuntimeCheckDetails(true, "External-21", 21, 21, "arm64-v8a", "aarch64", true, 3000, 384, true, 5000, true, "OK"),
            serverRuntime = RuntimeCheckDetails(true, "External-21", 21, 21, "arm64-v8a", "aarch64", true, 3000, 1024, true, 5000, true, "OK"),
            installedGeyser = InstalledArtifactMetadata("geyser", "2.12.1", 1303, "Geyser-Standalone.jar", "abc", 1000L, "/tmp/geyser.jar"),
            latestGeyserBuild = 1303,
            localConfig = LocalServerConfig(software = LocalServerSoftware.PAPER, eulaAccepted = true),
            geyserConfig = GeyserConfig(authType = BridgeAuthMode.ONLINE),
            endpoints = NetworkEndpointsSnapshot(lanIpv4Addresses = listOf("192.168.1.50"), isLanConnected = true),
            authManager = authMgr,
            javaPing = JavaPingResult(true, 3L, "Paper 1.21.4", 769, 2, 10),
            bedrockPing = BedrockPingResult(true, 4L, "MCPE", "STAR", "Crossplay", 786, "1.21.60", 2, 10)
        )
        assertTrue(report.overallCompatible)
        val text = report.toReadableText()
        assertTrue(text.contains("Geyser Runtime: Compatible"))
        assertTrue(text.contains("Java Runtime: Compatible"))
        assertTrue(text.contains("Network: Compatible"))
        assertEquals(CompatibilityItemStatus.COMPATIBLE, report.items.first().status)
    }

    private fun writeVarInt(out: DataOutputStream, value: Int) {
        var v = value
        while (true) {
            if ((v and 0x7F.inv()) == 0) {
                out.writeByte(v)
                return
            }
            out.writeByte((v and 0x7F) or 0x80)
            v = v ushr 7
        }
    }

    private fun readVarInt(input: DataInputStream): Int {
        var numRead = 0
        var result = 0
        while (true) {
            val read = input.readByte().toInt()
            val value = read and 0x7F
            result = result or (value shl (7 * numRead))
            numRead++
            if ((read and 0x80) == 0) break
        }
        return result
    }
}
