package com.star.launcher.bridge

import com.star.launcher.bridge.auth.BridgeAuthenticationManager
import com.star.launcher.bridge.auth.FloodgateManager
import com.star.launcher.bridge.config.GeyserConfigManager
import com.star.launcher.bridge.config.LocalServerConfigManager
import com.star.launcher.bridge.logging.BridgeLogManager
import com.star.launcher.bridge.model.BridgeAuthMode
import com.star.launcher.bridge.model.GeyserConfig
import com.star.launcher.bridge.model.LocalServerConfig
import com.star.launcher.bridge.model.LocalServerSoftware
import com.star.launcher.bridge.network.BridgeNetworkManager
import com.star.launcher.bridge.network.BridgePortManager
import com.star.launcher.bridge.process.BridgeIpcProtocol
import com.star.launcher.bridge.process.BridgeProcessRole
import com.star.launcher.bridge.process.BridgeProcessState
import com.star.launcher.bridge.process.BridgeProcessStatusRecord
import com.star.launcher.bridge.runtime.BridgeRuntimeEnvironment
import com.star.launcher.bridge.runtime.BridgeUpdateManager
import com.star.launcher.bridge.runtime.BridgeVersionManager
import com.star.launcher.bridge.runtime.HashAlgorithm
import com.star.launcher.bridge.runtime.InstalledRuntimeCandidate
import com.star.launcher.bridge.runtime.JsonHttpFetcher
import com.star.launcher.bridge.runtime.RuntimeValidator
import com.star.launcher.bridge.runtime.VerifiedArtifactRequest
import com.star.launcher.bridge.storage.BridgeStoragePaths
import com.star.launcher.data.security.TokenVault
import com.star.launcher.download.HttpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.security.MessageDigest

private class FakeTokenVault : TokenVault {
    private val map = linkedMapOf<String, String>()
    override fun putSecret(alias: String, plaintextSecret: String) {
        map[alias] = plaintextSecret
    }
    override fun getSecret(alias: String): String? = map[alias]
    override fun deleteSecret(alias: String) {
        map.remove(alias)
    }
    override fun containsSecret(alias: String): Boolean = map.containsKey(alias)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BridgeCoreAndNetworkTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createPaths(): BridgeStoragePaths {
        val dataRoot = tempFolder.newFolder("data")
        val gameRoot = tempFolder.newFolder("game")
        return BridgeStoragePaths({ dataRoot }, { gameRoot })
    }

    @Test
    fun `runtime validator enforces Java 21+, ABI match, libjvm, and RAM for Geyser`() {
        val paths = createPaths()
        val rt21Dir = tempFolder.newFolder("rt21")
        val rt17Dir = tempFolder.newFolder("rt17")

        val env = object : BridgeRuntimeEnvironment {
            override fun listCandidates() = listOf(
                InstalledRuntimeCandidate("External-17", 17, "aarch64", rt17Dir, hasLibJvm = true, isArchCompatible = true),
                InstalledRuntimeCandidate("External-21", 21, "aarch64", rt21Dir, hasLibJvm = true, isArchCompatible = true)
            )
            override fun devicePrimaryAbi() = "arm64-v8a"
            override fun availableRamMb() = 2048L
            override fun probeSocketBind() = true
        }
        val validator = RuntimeValidator(paths, env)

        val geyserOk = validator.validateForGeyser(preferredRuntimeName = null, requiredMemoryMb = 384)
        assertTrue(geyserOk.isCompatible)
        assertEquals("External-21", geyserOk.selectedRuntimeName)

        val pinned17 = validator.validateForGeyser(preferredRuntimeName = "External-17", requiredMemoryMb = 384)
        // Falls back to compatible 21 when preferred 17 is too low, or fails if only 17 installed:
        assertEquals("External-21", pinned17.selectedRuntimeName)

        val only17Validator = RuntimeValidator(
            paths,
            object : BridgeRuntimeEnvironment {
                override fun listCandidates() = listOf(
                    InstalledRuntimeCandidate("External-17", 17, "aarch64", rt17Dir, hasLibJvm = true, isArchCompatible = true)
                )
                override fun devicePrimaryAbi() = "arm64-v8a"
                override fun availableRamMb() = 2048L
                override fun probeSocketBind() = true
            }
        )
        val fail17 = only17Validator.validateForGeyser(requiredMemoryMb = 384)
        assertFalse(fail17.isCompatible)
        assertTrue(fail17.issues.any { it.contains("Java 21+") })
    }

    @Test
    fun `geyser and server config managers validate and round-trip configurations`() {
        val paths = createPaths()
        val geyserCfgMgr = GeyserConfigManager(paths)
        val serverCfgMgr = LocalServerConfigManager(paths)

        val invalidEula = LocalServerConfig(eulaAccepted = false)
        assertFalse(serverCfgMgr.validate(invalidEula).isValid)

        val validServer = LocalServerConfig(
            enabled = true,
            software = LocalServerSoftware.PAPER,
            minecraftVersion = "1.21.4",
            javaPort = 25565,
            worldName = "crossplay_world",
            maxPlayers = 16,
            onlineMode = true,
            eulaAccepted = true
        )
        assertTrue(serverCfgMgr.validate(validServer).isValid)
        serverCfgMgr.writeServerConfiguration(validServer)
        val loadedServer = serverCfgMgr.readServerProperties()
        assertEquals(25565, loadedServer.javaPort)
        assertEquals("crossplay_world", loadedServer.worldName)
        assertTrue(loadedServer.eulaAccepted)

        val validGeyser = GeyserConfig(
            bedrockPort = 19132,
            remoteAddress = "127.0.0.1",
            remotePort = 25565,
            authType = BridgeAuthMode.FLOODGATE
        )
        geyserCfgMgr.writeConfig(validGeyser)
        val parsedGeyser = geyserCfgMgr.readActiveConfig()
        assertEquals(19132, parsedGeyser.bedrockPort)
        assertEquals("127.0.0.1", parsedGeyser.remoteAddress)
        assertEquals(25565, parsedGeyser.remotePort)
        assertEquals(BridgeAuthMode.FLOODGATE, parsedGeyser.authType)

        val conflictGeyser = validGeyser.copy(bedrockPort = 25565)
        assertFalse(geyserCfgMgr.validate(conflictGeyser).isValid)
    }

    @Test
    fun `update manager verifies sha256, skips duplicate downloads, and supports rollback`() = runTest {
        val paths = createPaths()
        val logs = BridgeLogManager(paths)
        val v1Bytes = "geyser-v1-jar-bytes".toByteArray()
        val v2Bytes = "geyser-v2-jar-bytes".toByteArray()
        val v1Hash = sha256Hex(v1Bytes)
        val v2Hash = sha256Hex(v2Bytes)

        var downloadCalls = 0
        val fakeTransport = object : HttpTransport {
            override suspend fun downloadToFile(
                url: String,
                destinationFile: File,
                onProgress: suspend (Long, Long) -> Unit
            ) {
                downloadCalls++
                destinationFile.parentFile?.mkdirs()
                val payload = if (url.contains("v2")) v2Bytes else v1Bytes
                destinationFile.writeBytes(payload)
                onProgress(payload.size.toLong(), payload.size.toLong())
            }
        }
        val updateMgr = BridgeUpdateManager(paths, logs, fakeTransport)

        // Install v1
        val req1 = VerifiedArtifactRequest(
            projectId = "geyser",
            version = "2.12.0",
            buildNumber = 100,
            fileName = "Geyser-Standalone.jar",
            downloadUrl = "https://example.invalid/v1",
            expectedHash = v1Hash,
            hashAlgorithm = HashAlgorithm.SHA_256,
            targetJarFile = paths.geyserJarFile(),
            previousDir = paths.geyserPreviousDir(),
            metadataFile = paths.geyserMetadataFile()
        )
        val res1 = updateMgr.installVerifiedArtifact(req1, isRuntimeActive = { false })
        assertFalse(res1.skippedDuplicateDownload)
        assertEquals(1, downloadCalls)

        // Repeat v1 -> skipped duplicate download
        val res1Dup = updateMgr.installVerifiedArtifact(req1, isRuntimeActive = { false })
        assertTrue(res1Dup.skippedDuplicateDownload)
        assertEquals(1, downloadCalls)

        // Install v2 -> preserves v1 backup
        val req2 = req1.copy(version = "2.12.1", buildNumber = 101, downloadUrl = "https://example.invalid/v2", expectedHash = v2Hash)
        val res2 = updateMgr.installVerifiedArtifact(req2, isRuntimeActive = { false })
        assertEquals("2.12.0", res2.metadata.previousVersion)
        assertTrue(paths.geyserJarFile().readBytes().contentEquals(v2Bytes))

        // Rollback -> restores v1
        val rolledBack = updateMgr.rollback(paths.geyserMetadataFile(), paths.geyserJarFile()) { false }
        assertEquals("2.12.0", rolledBack.version)
        assertTrue(paths.geyserJarFile().readBytes().contentEquals(v1Bytes))

        // Corrupted checksum -> rejected without destroying v1
        val badReq = req2.copy(expectedHash = "0".repeat(64))
        var threw = false
        try {
            updateMgr.installVerifiedArtifact(badReq, isRuntimeActive = { false })
        } catch (_: IOException) {
            threw = true
        }
        assertTrue(threw)
        assertTrue(paths.geyserJarFile().readBytes().contentEquals(v1Bytes))
    }

    @Test
    fun `version manager parses official GeyserMC and PaperMC metadata`() = runTest {
        val geyserJson = """
            {
              "project_id": "geyser",
              "version": "2.12.1",
              "build": 1303,
              "channel": "default",
              "downloads": {
                "standalone": {
                  "name": "Geyser-Standalone.jar",
                  "sha256": "1b0c239e97117853cc4618753e23a0e06c7dc351c81abdd46926012b9fce6710"
                }
              }
            }
        """.trimIndent()
        val paperJson = """
            {
              "id": 232,
              "channel": "STABLE",
              "downloads": {
                "server:default": {
                  "name": "paper-1.21.4-232.jar",
                  "checksums": {
                    "sha256": "5ee4f542f628a14c644410b08c94ea42e772ef4d29fe92973636b6813d4eaffc"
                  },
                  "url": "https://fill-data.papermc.io/v1/objects/5ee4f542f628a14c644410b08c94ea42e772ef4d29fe92973636b6813d4eaffc/paper-1.21.4-232.jar"
                }
              }
            }
        """.trimIndent()
        val mgr = BridgeVersionManager(
            fetcher = object : JsonHttpFetcher {
                override suspend fun fetchText(url: String): String =
                    if (url.contains("geysermc")) geyserJson else paperJson
            }
        )
        val geyser = mgr.fetchLatestGeyserStandalone()
        assertEquals("2.12.1", geyser.version)
        assertEquals(1303, geyser.buildNumber)
        assertEquals("Geyser-Standalone.jar", geyser.artifactName)

        val paper = mgr.fetchLatestPaperBuild("1.21.4")
        assertEquals(232, paper.buildNumber)
        assertEquals("paper-1.21.4-232.jar", paper.fileName)
        assertTrue(paper.downloadUrl.startsWith("https://fill-data.papermc.io/"))
    }

    @Test
    fun `network manager executes real Java SLP TCP ping and Bedrock RakNet UDP ping`() = runTest {
        val portMgr = BridgePortManager()
        val netMgr = BridgeNetworkManager(portMgr)

        // Start a real loopback TCP Minecraft SLP server
        val tcpServer = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 5000 }
        val tcpPort = tcpServer.localPort
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

                    val json = """{"version":{"name":"Paper 1.21.4","protocol":769},"players":{"max":20,"online":3},"description":{"text":"STAR World"}}"""
                    val jsonBytes = json.toByteArray(Charsets.UTF_8)
                    val body = ByteArrayOutputStream()
                    val bodyOut = DataOutputStream(body)
                    writeVarInt(bodyOut, 0x00)
                    writeVarInt(bodyOut, jsonBytes.size)
                    bodyOut.write(jsonBytes)
                    val pkt = body.toByteArray()
                    writeVarInt(out, pkt.size)
                    out.write(pkt)
                    out.flush()
                }
            }
        }

        // Start a real loopback UDP RakNet Pong responder
        val udpSocket = DatagramSocket(0, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 5000 }
        val udpPort = udpSocket.localPort
        val udpJob = launch(Dispatchers.IO) {
            runCatching {
                val rx = ByteArray(1024)
                val pkt = DatagramPacket(rx, rx.size)
                udpSocket.receive(pkt)
                val motd = "MCPE;STAR Bridge;786;1.21.60;3;20;123456;Geyser;Survival;".toByteArray(Charsets.UTF_8)
                val pong = ByteBuffer.allocate(35 + motd.size).apply {
                    put(0x1C.toByte())
                    putLong(System.currentTimeMillis())
                    putLong(9999L)
                    put(BridgeNetworkManager.RAKNET_OFFLINE_MAGIC)
                    putShort(motd.size.toShort())
                    put(motd)
                }.array()
                udpSocket.send(DatagramPacket(pong, pong.size, pkt.address, pkt.port))
            }
        }

        try {
            // Also check port conflict detection while tcpServer is bound
            assertFalse(portMgr.checkTcpPort(tcpPort).tcpAvailable)

            val javaRes = netMgr.pingJavaServer("127.0.0.1", tcpPort)
            assertTrue(javaRes.reachable)
            assertEquals("Paper 1.21.4", javaRes.versionName)
            assertEquals(3, javaRes.onlinePlayers)
            assertEquals(20, javaRes.maxPlayers)

            val bedrockRes = netMgr.pingBedrockServer("127.0.0.1", udpPort)
            assertTrue(bedrockRes.reachable)
            assertEquals("STAR Bridge", bedrockRes.motdLine1)
            assertEquals("1.21.60", bedrockRes.versionName)
            assertEquals(786, bedrockRes.protocolVersion)
        } finally {
            tcpJob.join()
            udpJob.join()
            tcpServer.close()
            udpSocket.close()
        }
    }

    @Test
    fun `floodgate manager syncs keys securely, backs up in vault, and redacts logs`() {
        val paths = createPaths()
        val vault = FakeTokenVault()
        val logs = BridgeLogManager(paths)
        val versionMgr = BridgeVersionManager()
        val updateMgr = BridgeUpdateManager(paths, logs)
        val fgMgr = FloodgateManager(paths, versionMgr, updateMgr, vault, logs)
        val authMgr = BridgeAuthenticationManager(fgMgr)

        // No plugin yet -> invalid
        val invalidAuth = authMgr.validateLocalHostingAuth(
            LocalServerConfig(software = LocalServerSoftware.PAPER, onlineMode = true),
            GeyserConfig(authType = BridgeAuthMode.FLOODGATE)
        )
        assertFalse(invalidAuth.isValid)

        // Create fake floodgate plugin & sync key
        paths.floodgatePluginJarFile().parentFile?.mkdirs()
        paths.floodgatePluginJarFile().writeText("jar")
        assertTrue(fgMgr.synchronizeOrGenerateKeys("key.pem"))
        assertTrue(paths.geyserFloodgateKeyFile("key.pem").isFile)
        assertTrue(paths.serverFloodgateKeyFile().isFile)
        assertTrue(vault.containsSecret(FloodgateManager.VAULT_ALIAS_FLOODGATE_KEY))

        val validAuth = authMgr.validateLocalHostingAuth(
            LocalServerConfig(software = LocalServerSoftware.PAPER, onlineMode = true),
            GeyserConfig(authType = BridgeAuthMode.FLOODGATE)
        )
        assertTrue(validAuth.isValid)

        // Verify PEM secret redaction in logs
        logs.logError("Test", "Leaked -----BEGIN PRIVATE KEY-----\nSECRET123\n-----END PRIVATE KEY-----")
        assertFalse(logs.recentMessages(5).joinToString().contains("SECRET123"))
    }

    @Test
    fun `ipc protocol writes and reads atomic status and drains commands`() {
        val paths = createPaths()
        val rec = BridgeProcessStatusRecord(
            role = BridgeProcessRole.GEYSER,
            state = BridgeProcessState.RUNNING,
            pid = 4321,
            rssMb = 210,
            startedAtMs = 1000L,
            heartbeatAtMs = 2000L,
            detail = "Geyser running"
        )
        BridgeIpcProtocol.writeStatus(paths.geyserStatusFile(), rec)
        val read = BridgeIpcProtocol.readStatus(paths.geyserStatusFile())
        assertNotNull(read)
        assertEquals(4321, read?.pid)
        assertEquals(BridgeProcessState.RUNNING, read?.state)

        BridgeIpcProtocol.appendCommand(paths.serverCommandSpoolFile(), "say Hello Crossplay")
        BridgeIpcProtocol.appendCommand(paths.serverCommandSpoolFile(), "stop")
        val drained = BridgeIpcProtocol.drainCommands(paths.serverCommandSpoolFile())
        assertEquals(listOf("say Hello Crossplay", "stop"), drained)
        assertTrue(BridgeIpcProtocol.drainCommands(paths.serverCommandSpoolFile()).isEmpty())
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

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
