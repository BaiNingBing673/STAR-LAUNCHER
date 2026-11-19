package com.star.launcher.bridge

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.star.launcher.bridge.auth.BridgeAuthenticationManager
import com.star.launcher.bridge.auth.FloodgateManager
import com.star.launcher.bridge.compat.BridgeCompatibilityEngine
import com.star.launcher.bridge.config.GeyserConfigManager
import com.star.launcher.bridge.config.LocalServerConfigManager
import com.star.launcher.bridge.diagnostics.BridgeDiagnostics
import com.star.launcher.bridge.discovery.ServerDiscoveryManager
import com.star.launcher.bridge.discovery.ServerProfileManager
import com.star.launcher.bridge.logging.BridgeLogManager
import com.star.launcher.bridge.model.BridgeAuthMode
import com.star.launcher.bridge.model.BridgeErrorCode
import com.star.launcher.bridge.model.BridgeLifecycleState
import com.star.launcher.bridge.model.LocalServerSoftware
import com.star.launcher.bridge.network.BridgeNetworkManager
import com.star.launcher.bridge.network.BridgePortManager
import com.star.launcher.bridge.network.PortAvailabilityReport
import com.star.launcher.bridge.process.BridgeConnectionMonitor
import com.star.launcher.bridge.process.BridgeHealthMonitor
import com.star.launcher.bridge.process.BridgeProcessController
import com.star.launcher.bridge.process.BridgeProcessRole
import com.star.launcher.bridge.process.BridgeProcessState
import com.star.launcher.bridge.process.BridgeProcessStatusRecord
import com.star.launcher.bridge.runtime.BridgeRuntimeEnvironment
import com.star.launcher.bridge.runtime.BridgeRuntimeManager
import com.star.launcher.bridge.runtime.BridgeUpdateManager
import com.star.launcher.bridge.runtime.BridgeVersionManager
import com.star.launcher.bridge.runtime.GeyserRuntimeManager
import com.star.launcher.bridge.runtime.InstalledRuntimeCandidate
import com.star.launcher.bridge.runtime.RuntimeValidator
import com.star.launcher.bridge.storage.BridgeStoragePaths
import com.star.launcher.core.AetherSettingsRepository
import com.star.launcher.data.db.AetherDatabase
import com.star.launcher.data.security.TokenVault
import com.star.launcher.ui.navigation.AetherRoute
import kotlinx.coroutines.Dispatchers
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

private class InMemoryTokenVault : TokenVault {
    private val store = linkedMapOf<String, String>()
    override fun putSecret(alias: String, plaintextSecret: String) { store[alias] = plaintextSecret }
    override fun getSecret(alias: String): String? = store[alias]
    override fun deleteSecret(alias: String) { store.remove(alias) }
    override fun containsSecret(alias: String): Boolean = store.containsKey(alias)
}

private class FakeBridgeProcessController : BridgeProcessController {
    var serverStarted = false
    var geyserStarted = false
    var serverStatus: BridgeProcessStatusRecord? = null
    var geyserStatus: BridgeProcessStatusRecord? = null
    val sentServerCmds = mutableListOf<String>()
    val sentGeyserCmds = mutableListOf<String>()

    override fun startLocalServer(runtimeName: String, memoryMb: Int, customJvmArgs: String) {
        serverStarted = true
        serverStatus = BridgeProcessStatusRecord(
            role = BridgeProcessRole.LOCAL_SERVER,
            state = BridgeProcessState.RUNNING,
            pid = 1001,
            rssMb = 420,
            startedAtMs = System.currentTimeMillis(),
            heartbeatAtMs = System.currentTimeMillis()
        )
    }

    override fun stopLocalServer(graceful: Boolean) {
        serverStarted = false
        serverStatus = BridgeProcessStatusRecord(
            role = BridgeProcessRole.LOCAL_SERVER,
            state = BridgeProcessState.STOPPED,
            pid = 1001
        )
    }

    override fun sendServerCommand(command: String) {
        sentServerCmds += command
    }

    override fun readServerStatus() = serverStatus

    override fun startGeyser(runtimeName: String, memoryMb: Int, debugMode: Boolean) {
        geyserStarted = true
        geyserStatus = BridgeProcessStatusRecord(
            role = BridgeProcessRole.GEYSER,
            state = BridgeProcessState.RUNNING,
            pid = 1002,
            rssMb = 180,
            startedAtMs = System.currentTimeMillis(),
            heartbeatAtMs = System.currentTimeMillis()
        )
    }

    override fun stopGeyser(graceful: Boolean) {
        geyserStarted = false
        geyserStatus = BridgeProcessStatusRecord(
            role = BridgeProcessRole.GEYSER,
            state = BridgeProcessState.STOPPED,
            pid = 1002
        )
    }

    override fun sendGeyserCommand(command: String) {
        sentGeyserCmds += command
    }

    override fun readGeyserStatus() = geyserStatus

    override fun isProcessAlive(record: BridgeProcessStatusRecord?): Boolean =
        record?.state == BridgeProcessState.RUNNING || record?.state == BridgeProcessState.STARTING
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BridgeControllerTest {

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

    private fun buildController(
        occupiedPorts: Set<Int> = emptySet(),
        processCtrl: FakeBridgeProcessController = FakeBridgeProcessController()
    ): Triple<BridgeController, BridgeStoragePaths, FakeBridgeProcessController> {
        val dRoot = tempFolder.newFolder("d")
        val gRoot = tempFolder.newFolder("g")
        val paths = BridgeStoragePaths({ dRoot }, { gRoot })
        val rtDir = tempFolder.newFolder("rt21")
        val env = object : BridgeRuntimeEnvironment {
            override fun listCandidates() = listOf(
                InstalledRuntimeCandidate("External-21", 21, "aarch64", rtDir, hasLibJvm = true, isArchCompatible = true)
            )
            override fun devicePrimaryAbi() = "arm64-v8a"
            override fun availableRamMb() = 4096L
            override fun probeSocketBind() = true
        }
        val settingsRepo = AetherSettingsRepository(db.settingsDao())
        val logs = BridgeLogManager(paths)
        val versionMgr = BridgeVersionManager()
        val updateMgr = BridgeUpdateManager(paths, logs)
        val validator = RuntimeValidator(paths, env)
        val rtMgr = BridgeRuntimeManager(paths, validator, versionMgr, updateMgr)
        val geyserRtMgr = GeyserRuntimeManager(paths, versionMgr, updateMgr)
        val geyserCfgMgr = GeyserConfigManager(paths)
        val srvCfgMgr = LocalServerConfigManager(paths)
        val portMgr = BridgePortManager { port, tcp, udp ->
            val free = port !in occupiedPorts
            PortAvailabilityReport(port, free || !tcp, free || !udp, if (free) null else "Port $port busy")
        }
        val netMgr = BridgeNetworkManager(portMgr)
        val fgMgr = FloodgateManager(paths, versionMgr, updateMgr, InMemoryTokenVault(), logs)
        val authMgr = BridgeAuthenticationManager(fgMgr)
        val healthMon = BridgeHealthMonitor(processCtrl)
        val connMon = BridgeConnectionMonitor(netMgr)
        val profileMgr = ServerProfileManager(db.serverProfileDao())
        val discMgr = ServerDiscoveryManager(profileMgr, netMgr)
        val compat = BridgeCompatibilityEngine()
        val diag = BridgeDiagnostics(paths, logs)

        val controller = BridgeController(
            paths = paths,
            settingsRepository = settingsRepo,
            runtimeManager = rtMgr,
            geyserRuntimeManager = geyserRtMgr,
            versionManager = versionMgr,
            geyserConfigManager = geyserCfgMgr,
            localServerConfigManager = srvCfgMgr,
            networkManager = netMgr,
            portManager = portMgr,
            floodgateManager = fgMgr,
            authManager = authMgr,
            processController = processCtrl,
            healthMonitor = healthMon,
            connectionMonitor = connMon,
            profileManager = profileMgr,
            discoveryManager = discMgr,
            compatibilityEngine = compat,
            logManager = logs,
            diagnostics = diag,
            ioDispatcher = Dispatchers.Unconfined
        )
        return Triple(controller, paths, processCtrl)
    }

    @Test
    fun `bedrock bridge navigation route encodes and decodes cleanly`() {
        assertEquals("bedrock_bridge", AetherRoute.BedrockBridge.encode())
        assertEquals(AetherRoute.BedrockBridge, AetherRoute.decode("bedrock_bridge"))
        assertEquals(AetherRoute.BedrockBridge, AetherRoute.decode("BEDROCK_BRIDGE"))
    }

    @Test
    fun `bridge controller blocks local start without EULA and succeeds once ready`() = runTest {
        val (controller, paths, procCtrl) = buildController()
        controller.initialize()

        // Without EULA accepted -> fails cleanly with EULA_NOT_ACCEPTED
        val noEula = controller.startLocalHostingBridge()
        assertTrue(noEula.isFailure)
        assertEquals(BridgeErrorCode.EULA_NOT_ACCEPTED, controller.status.value.lastError?.code)
        assertFalse(procCtrl.serverStarted)

        // Provide JARs, accept EULA, configure Paper + Floodgate
        paths.serverJarFile().writeText("paper-jar")
        paths.geyserJarFile().writeText("geyser-jar")
        paths.floodgatePluginJarFile().parentFile?.mkdirs()
        paths.floodgatePluginJarFile().writeText("floodgate-jar")

        controller.updateLocalServerConfig {
            it.copy(
                enabled = true,
                software = LocalServerSoftware.PAPER,
                eulaAccepted = true,
                javaPort = 25565
            )
        }
        controller.updateGeyserConfig {
            it.copy(
                bedrockPort = 19132,
                authType = BridgeAuthMode.FLOODGATE
            )
        }

        val started = controller.startLocalHostingBridge()
        assertTrue(started.isSuccess)
        assertTrue(procCtrl.serverStarted)
        assertTrue(procCtrl.geyserStarted)
        assertTrue(paths.serverEulaFile().readText().contains("eula=true"))
        assertTrue(paths.geyserConfigFile().readText().contains("auth-type: floodgate"))

        controller.refreshEnvironmentSnapshot()
        assertEquals(BridgeLifecycleState.RUNNING, controller.status.value.lifecycleState)

        // Independent stop
        controller.stopAllBridgeProcesses(graceful = true)
        assertFalse(procCtrl.serverStarted)
        assertFalse(procCtrl.geyserStarted)
    }

    @Test
    fun `bridge controller detects bedrock port occupied before launching Geyser`() = runTest {
        val (controller, paths, procCtrl) = buildController(occupiedPorts = setOf(19132))
        controller.initialize()
        paths.geyserJarFile().writeText("geyser-jar")

        val res = controller.startGeyserStandalone()
        assertTrue(res.isFailure)
        assertEquals(BridgeErrorCode.BEDROCK_PORT_OCCUPIED, controller.status.value.lastError?.code)
        assertFalse(procCtrl.geyserStarted)
        assertNotNull(controller.buildDiagnosticReport())
    }

    @Test
    fun `bridge controller reports LOCAL_SERVER_JAR_MISSING when server jar absent and auto-download disabled`() = runTest {
        val (controller, _, procCtrl) = buildController()
        controller.initialize()
        controller.updateLocalServerConfig { it.copy(eulaAccepted = true) }

        val res = controller.startLocalJavaServer(autoDownloadMissing = false)
        assertTrue(res.isFailure)
        assertEquals(BridgeErrorCode.LOCAL_SERVER_JAR_MISSING, controller.status.value.lastError?.code)
        assertFalse(procCtrl.serverStarted)
    }
}
