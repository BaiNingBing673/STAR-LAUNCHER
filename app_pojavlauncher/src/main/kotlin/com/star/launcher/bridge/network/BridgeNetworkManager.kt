package com.star.launcher.bridge.network

import com.star.launcher.bridge.model.BridgeTransportType
import com.star.launcher.bridge.model.NetworkEndpointsSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.nio.ByteBuffer
import java.security.SecureRandom

data class JavaPingResult(
    val reachable: Boolean,
    val latencyMs: Long? = null,
    val versionName: String? = null,
    val protocolVersion: Int? = null,
    val onlinePlayers: Int = 0,
    val maxPlayers: Int = 0,
    val motd: String? = null,
    val faviconBase64: String? = null,
    val errorMessage: String? = null
)

data class BedrockPingResult(
    val reachable: Boolean,
    val latencyMs: Long? = null,
    val edition: String? = null,
    val motdLine1: String? = null,
    val motdLine2: String? = null,
    val protocolVersion: Int? = null,
    val versionName: String? = null,
    val onlinePlayers: Int = 0,
    val maxPlayers: Int = 0,
    val serverSoftwareHint: String? = null,
    val errorMessage: String? = null
)

/**
 * Provides interface address discovery (Localhost vs LAN IPv4/IPv6 vs Public guidance)
 * and real protocol-level Java SLP (TCP) and Bedrock RakNet Unconnected Ping (UDP) diagnostics.
 */
class BridgeNetworkManager(
    private val portManager: BridgePortManager
) {
    fun inspectEndpoints(
        javaPort: Int,
        bedrockPort: Int,
        transport: BridgeTransportType,
        checkPortConflicts: Boolean = true,
        localServerRunning: Boolean = false,
        geyserRunning: Boolean = false
    ): NetworkEndpointsSnapshot {
        val (ipv4List, ipv6List) = enumerateLanAddresses()
        val javaReport = if (checkPortConflicts && !localServerRunning) {
            portManager.checkTcpPort(javaPort)
        } else {
            PortAvailabilityReport(javaPort, tcpAvailable = true, udpAvailable = true)
        }
        val bedrockReport = if (checkPortConflicts && !geyserRunning) {
            portManager.checkBedrockPort(bedrockPort)
        } else {
            PortAvailabilityReport(bedrockPort, tcpAvailable = true, udpAvailable = true)
        }

        val conflictReasons = mutableListOf<String>()
        if (javaPort == bedrockPort) {
            conflictReasons += "Java port ($javaPort) and Bedrock port ($bedrockPort) are identical"
        }
        if (!javaReport.tcpAvailable && !localServerRunning) {
            conflictReasons += javaReport.errorDetail ?: "Java TCP port $javaPort is occupied"
        }
        if (!bedrockReport.isFullyAvailable && !geyserRunning) {
            conflictReasons += bedrockReport.errorDetail ?: "Bedrock port $bedrockPort is occupied"
        }

        return NetworkEndpointsSnapshot(
            localhostAddress = "127.0.0.1",
            lanIpv4Addresses = ipv4List,
            lanIpv6Addresses = ipv6List,
            isLanConnected = ipv4List.isNotEmpty() || ipv6List.isNotEmpty(),
            javaPort = javaPort,
            bedrockPort = bedrockPort,
            bedrockTransport = transport,
            javaPortAvailable = javaReport.tcpAvailable,
            bedrockPortAvailable = bedrockReport.isFullyAvailable,
            portConflictReason = conflictReasons.takeIf { it.isNotEmpty() }?.joinToString("; ")
        )
    }

    fun enumerateLanAddresses(): Pair<List<String>, List<String>> {
        val ipv4 = linkedSetOf<String>()
        val ipv6 = linkedSetOf<String>()
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList<String>() to emptyList()
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback || nif.isVirtual) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr.isLoopbackAddress || addr.isAnyLocalAddress) continue
                    when (addr) {
                        is Inet4Address -> {
                            addr.hostAddress?.takeIf { it.isNotBlank() }?.let { ipv4 += it }
                        }
                        is Inet6Address -> {
                            if (!addr.isLinkLocalAddress) {
                                val raw = addr.hostAddress?.substringBefore('%')?.trim()
                                if (!raw.isNullOrBlank()) ipv6 += raw
                            }
                        }
                    }
                }
            }
        }
        return ipv4.toList() to ipv6.toList()
    }

    /**
     * Performs a real Minecraft Java Edition Server List Ping (SLP) over TCP.
     */
    suspend fun pingJavaServer(
        host: String,
        port: Int,
        timeoutMs: Int = 3500
    ): JavaPingResult = withContext(Dispatchers.IO) {
        if (host.isBlank() || port !in 1..65535) {
            return@withContext JavaPingResult(reachable = false, errorMessage = "Invalid Java host/port ($host:$port)")
        }
        val startedNs = System.nanoTime()
        try {
            Socket().use { socket ->
                socket.soTimeout = timeoutMs
                socket.connect(InetSocketAddress(InetAddress.getByName(host.trim()), port), timeoutMs)
                val out = DataOutputStream(socket.getOutputStream())
                val input = DataInputStream(socket.getInputStream())

                // 1. Handshake packet (ID 0x00, protocol -1, host, port, next state 1 = Status)
                val handshakePayload = ByteArrayOutputStream()
                val hsOut = DataOutputStream(handshakePayload)
                writeVarInt(hsOut, 0x00)
                writeVarInt(hsOut, -1)
                writeMcString(hsOut, host.trim())
                hsOut.writeShort(port)
                writeVarInt(hsOut, 1)
                val hsBytes = handshakePayload.toByteArray()
                writeVarInt(out, hsBytes.size)
                out.write(hsBytes)

                // 2. Status Request packet (ID 0x00)
                writeVarInt(out, 1)
                writeVarInt(out, 0x00)
                out.flush()

                // 3. Read Status Response
                val packetLength = readVarInt(input)
                if (packetLength <= 0 || packetLength > 2 * 1024 * 1024) {
                    return@withContext JavaPingResult(
                        reachable = false,
                        errorMessage = "Invalid Java SLP packet length: $packetLength"
                    )
                }
                val packetId = readVarInt(input)
                if (packetId != 0x00) {
                    return@withContext JavaPingResult(
                        reachable = false,
                        errorMessage = "Unexpected Java SLP packet ID: 0x${packetId.toString(16)}"
                    )
                }
                val jsonLen = readVarInt(input)
                if (jsonLen <= 0 || jsonLen > 2 * 1024 * 1024) {
                    return@withContext JavaPingResult(
                        reachable = false,
                        errorMessage = "Invalid Java status JSON length: $jsonLen"
                    )
                }
                val jsonBytes = ByteArray(jsonLen)
                input.readFully(jsonBytes)
                val latencyMs = ((System.nanoTime() - startedNs) / 1_000_000L).coerceAtLeast(1L)
                parseJavaStatusJson(String(jsonBytes, Charsets.UTF_8), latencyMs)
            }
        } catch (t: Throwable) {
            JavaPingResult(
                reachable = false,
                errorMessage = "${t::class.java.simpleName}: ${t.message ?: "Connection failed"}"
            )
        }
    }

    /**
     * Performs a real Minecraft Bedrock Edition RakNet Unconnected Ping (0x01 -> 0x1C) over UDP.
     */
    suspend fun pingBedrockServer(
        host: String,
        port: Int,
        timeoutMs: Int = 3000
    ): BedrockPingResult = withContext(Dispatchers.IO) {
        if (host.isBlank() || port !in 1..65535) {
            return@withContext BedrockPingResult(reachable = false, errorMessage = "Invalid Bedrock host/port ($host:$port)")
        }
        val startedNs = System.nanoTime()
        try {
            val address = InetAddress.getByName(host.trim())
            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMs
                val pingPacket = buildRakNetUnconnectedPingPacket(
                    timestamp = System.currentTimeMillis(),
                    clientGuid = SecureRandom().nextLong()
                )
                socket.send(DatagramPacket(pingPacket, pingPacket.size, address, port))

                val buffer = ByteArray(2048)
                val response = DatagramPacket(buffer, buffer.size)
                socket.receive(response)
                val latencyMs = ((System.nanoTime() - startedNs) / 1_000_000L).coerceAtLeast(1L)
                parseRakNetUnconnectedPong(response.data, response.length, latencyMs)
            }
        } catch (t: Throwable) {
            BedrockPingResult(
                reachable = false,
                errorMessage = "${t::class.java.simpleName}: ${t.message ?: "No UDP RakNet pong"}"
            )
        }
    }

    fun parseJavaStatusJson(rawJson: String, latencyMs: Long?): JavaPingResult {
        val root = JSONObject(rawJson)
        val versionObj = root.optJSONObject("version")
        val playersObj = root.optJSONObject("players")
        val descRaw = root.opt("description")
        val motd = when (descRaw) {
            is String -> descRaw
            is JSONObject -> descRaw.optString("text", descRaw.toString())
            else -> descRaw?.toString()
        }?.trim()
        val favicon = root.optString("favicon")
            .takeIf { it.startsWith("data:image/png;base64,") }
            ?.removePrefix("data:image/png;base64,")
        return JavaPingResult(
            reachable = true,
            latencyMs = latencyMs,
            versionName = versionObj?.optString("name")?.takeIf { it.isNotBlank() },
            protocolVersion = versionObj?.optInt("protocol"),
            onlinePlayers = playersObj?.optInt("online", 0) ?: 0,
            maxPlayers = playersObj?.optInt("max", 0) ?: 0,
            motd = motd,
            faviconBase64 = favicon
        )
    }

    fun buildRakNetUnconnectedPingPacket(timestamp: Long, clientGuid: Long): ByteArray {
        val buf = ByteBuffer.allocate(33)
        buf.put(0x01.toByte())
        buf.putLong(timestamp)
        buf.put(RAKNET_OFFLINE_MAGIC)
        buf.putLong(clientGuid)
        return buf.array()
    }

    fun parseRakNetUnconnectedPong(data: ByteArray, length: Int, latencyMs: Long?): BedrockPingResult {
        if (length < 35 || data[0] != 0x1C.toByte()) {
            return BedrockPingResult(
                reachable = false,
                errorMessage = "Unexpected Bedrock RakNet response packet (len=$length)"
            )
        }
        val buf = ByteBuffer.wrap(data, 0, length)
        buf.get() // 0x1C
        buf.long // timestamp
        buf.long // server GUID
        val magic = ByteArray(16)
        buf.get(magic)
        if (!magic.contentEquals(RAKNET_OFFLINE_MAGIC)) {
            return BedrockPingResult(reachable = false, errorMessage = "Invalid RakNet magic in pong")
        }
        val strLen = buf.short.toInt() and 0xFFFF
        if (strLen <= 0 || buf.remaining() < strLen) {
            return BedrockPingResult(reachable = false, errorMessage = "Truncated RakNet pong payload")
        }
        val strBytes = ByteArray(strLen)
        buf.get(strBytes)
        val payload = String(strBytes, Charsets.UTF_8)
        val fields = payload.split(';')
        // MCPE;MOTD;Protocol;Version;Players;MaxPlayers;ServerID;SubMOTD;GameMode;...
        return BedrockPingResult(
            reachable = true,
            latencyMs = latencyMs,
            edition = fields.getOrNull(0),
            motdLine1 = fields.getOrNull(1),
            protocolVersion = fields.getOrNull(2)?.toIntOrNull(),
            versionName = fields.getOrNull(3),
            onlinePlayers = fields.getOrNull(4)?.toIntOrNull() ?: 0,
            maxPlayers = fields.getOrNull(5)?.toIntOrNull() ?: 0,
            serverSoftwareHint = fields.getOrNull(7)
        )
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
            if (numRead > 5) throw IllegalStateException("VarInt too big")
            if ((read and 0x80) == 0) break
        }
        return result
    }

    private fun writeMcString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeVarInt(out, bytes.size)
        out.write(bytes)
    }

    companion object {
        val RAKNET_OFFLINE_MAGIC = byteArrayOf(
            0x00, 0xFF.toByte(), 0xFF.toByte(), 0x00,
            0xFE.toByte(), 0xFE.toByte(), 0xFE.toByte(), 0xFE.toByte(),
            0xFD.toByte(), 0xFD.toByte(), 0xFD.toByte(), 0xFD.toByte(),
            0x12, 0x34, 0x56, 0x78
        )
    }
}
