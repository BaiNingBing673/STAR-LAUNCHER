package com.star.launcher.bridge.network

import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

data class PortAvailabilityReport(
    val port: Int,
    val tcpAvailable: Boolean,
    val udpAvailable: Boolean,
    val errorDetail: String? = null
) {
    val isFullyAvailable: Boolean
        get() = tcpAvailable && udpAvailable
}

/**
 * Checks TCP and UDP socket bind availability and detects port conflicts on Android.
 */
class BridgePortManager(
    private val probeRunner: (Int, Boolean, Boolean) -> PortAvailabilityReport = ::defaultProbe
) {
    fun checkTcpPort(port: Int): PortAvailabilityReport = probeRunner(port, true, false)

    fun checkUdpPort(port: Int): PortAvailabilityReport = probeRunner(port, false, true)

    fun checkBedrockPort(port: Int): PortAvailabilityReport = probeRunner(port, true, true)

    fun findNextAvailableBedrockPort(preferredPort: Int = 19132, excludedPorts: Set<Int> = emptySet()): Int? {
        val candidates = listOf(preferredPort) + ((preferredPort + 1)..(preferredPort + 25)).toList()
        return candidates.firstOrNull { candidate ->
            candidate in 1024..65535 &&
                candidate !in excludedPorts &&
                checkBedrockPort(candidate).isFullyAvailable
        }
    }

    fun findNextAvailableJavaPort(preferredPort: Int = 25565, excludedPorts: Set<Int> = emptySet()): Int? {
        val candidates = listOf(preferredPort) + ((preferredPort + 1)..(preferredPort + 25)).toList()
        return candidates.firstOrNull { candidate ->
            candidate in 1024..65535 &&
                candidate !in excludedPorts &&
                checkTcpPort(candidate).tcpAvailable
        }
    }

    companion object {
        fun defaultProbe(port: Int, checkTcp: Boolean, checkUdp: Boolean): PortAvailabilityReport {
            if (port !in 1024..65535) {
                return PortAvailabilityReport(
                    port = port,
                    tcpAvailable = false,
                    udpAvailable = false,
                    errorDetail = "Port $port is outside Android unprivileged port range (1024-65535)"
                )
            }
            var tcpOk = true
            var udpOk = true
            val errors = mutableListOf<String>()

            if (checkTcp) {
                try {
                    ServerSocket().use { server ->
                        server.reuseAddress = false
                        server.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), port))
                    }
                } catch (t: Throwable) {
                    tcpOk = false
                    errors += "TCP $port in use (${t.message ?: t::class.java.simpleName})"
                }
            }

            if (checkUdp) {
                try {
                    DatagramSocket(null).use { udp ->
                        udp.reuseAddress = false
                        udp.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), port))
                    }
                } catch (t: Throwable) {
                    udpOk = false
                    errors += "UDP $port in use (${t.message ?: t::class.java.simpleName})"
                }
            }

            return PortAvailabilityReport(
                port = port,
                tcpAvailable = tcpOk,
                udpAvailable = udpOk,
                errorDetail = errors.takeIf { it.isNotEmpty() }?.joinToString("; ")
            )
        }
    }
}
