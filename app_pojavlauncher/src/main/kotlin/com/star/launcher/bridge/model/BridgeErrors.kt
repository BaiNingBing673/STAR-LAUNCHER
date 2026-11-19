package com.star.launcher.bridge.model

enum class BridgeErrorCode {
    BRIDGE_RUNTIME_MISSING,
    LOCAL_SERVER_JAR_MISSING,
    JAVA_VERSION_UNSUPPORTED,
    GEYSER_STARTUP_FAILED,
    JAVA_SERVER_UNREACHABLE,
    BEDROCK_PORT_OCCUPIED,
    NETWORK_PERMISSION_ERROR,
    FLOODGATE_CONFIGURATION_INVALID,
    SERVER_NOT_GEYSER_ENABLED,
    BEDROCK_VERSION_UNSUPPORTED,
    RUNTIME_PROCESS_TERMINATED,
    INSUFFICIENT_MEMORY,
    ARTIFACT_DOWNLOAD_FAILED,
    EULA_NOT_ACCEPTED,
    CONFIGURATION_INVALID
}

data class BridgeDiagnosticError(
    val code: BridgeErrorCode,
    val title: String,
    val technicalExplanation: String,
    val possibleCause: String,
    val suggestedResolution: String,
    val relevantLogs: List<String> = emptyList(),
    val timestampMs: Long = System.currentTimeMillis()
) {
    fun formatReport(): String = buildString {
        append('[').append(code.name).append("] ").append(title).append('\n')
        append("Explanation: ").append(technicalExplanation).append('\n')
        append("Possible Cause: ").append(possibleCause).append('\n')
        append("Suggested Resolution: ").append(suggestedResolution)
        if (relevantLogs.isNotEmpty()) {
            append("\n\nRelevant Logs:\n")
            relevantLogs.takeLast(15).forEach { append("  ").append(it).append('\n') }
        }
    }.trim()
}

object BridgeErrorCatalog {
    fun build(
        code: BridgeErrorCode,
        technicalDetail: String,
        relevantLogs: List<String> = emptyList(),
        overrideResolution: String? = null
    ): BridgeDiagnosticError = when (code) {
        BridgeErrorCode.BRIDGE_RUNTIME_MISSING -> BridgeDiagnosticError(
            code = code,
            title = "Geyser Runtime or JAR Missing",
            technicalExplanation = technicalDetail,
            possibleCause = "No compatible Java 21+ runtime is installed or Geyser-Standalone.jar has not been downloaded yet.",
            suggestedResolution = overrideResolution
                ?: "Install Java 21+ in Settings -> Java Runtimes and download Geyser Standalone in Bedrock Bridge.",
            relevantLogs = relevantLogs
        )
        BridgeErrorCode.LOCAL_SERVER_JAR_MISSING -> BridgeDiagnosticError(
            code = code,
            title = "Local Java Server JAR Missing",
            technicalExplanation = technicalDetail,
            possibleCause = "The local PaperMC or Vanilla server.jar has not been downloaded yet or a previous download failed.",
            suggestedResolution = overrideResolution
                ?: "Tap 'Download Server' in Local Hosting (or start Local Bridge while online to download it automatically).",
            relevantLogs = relevantLogs
        )
        BridgeErrorCode.JAVA_VERSION_UNSUPPORTED -> BridgeDiagnosticError(
            code = code,
            title = "Unsupported Java Runtime Version",
            technicalExplanation = technicalDetail,
            possibleCause = "Geyser Standalone requires Java 21 or newer, or the selected Java server version requires a newer OpenJDK release.",
            suggestedResolution = overrideResolution
                ?: "Install External-21 (or Internal-21+) for your device ABI and assign it to the bridge runtime.",
            relevantLogs = relevantLogs
        )
        BridgeErrorCode.GEYSER_STARTUP_FAILED -> BridgeDiagnosticError(
            code = code,
            title = "Geyser Standalone Startup Failed",
            technicalExplanation = technicalDetail,
            possibleCause = "Geyser exited during startup due to invalid config.yml, missing Floodgate key.pem, port conflict, or insufficient memory.",
            suggestedResolution = overrideResolution
                ?: "Inspect the Bridge Console logs, verify Bedrock port availability, and validate authentication settings.",
            relevantLogs = relevantLogs
        )
        BridgeErrorCode.JAVA_SERVER_UNREACHABLE -> BridgeDiagnosticError(
            code = code,
            title = "Java Backend Server Unreachable",
            technicalExplanation = technicalDetail,
            possibleCause = "The target Java server is offline, still booting, listening on a different TCP port, or blocked by a firewall.",
            suggestedResolution = overrideResolution
                ?: "Verify the Java endpoint host and port (default 25565) and confirm the Java server finished starting.",
            relevantLogs = relevantLogs
        )
        BridgeErrorCode.BEDROCK_PORT_OCCUPIED -> BridgeDiagnosticError(
            code = code,
            title = "Bedrock Listener Port Occupied",
            technicalExplanation = technicalDetail,
            possibleCause = "Another service or previous bridge process is already bound to the configured Bedrock UDP/TCP port.",
            suggestedResolution = overrideResolution
                ?: "Stop any conflicting process or change the Bedrock port (e.g. 19132 -> 19134) in Local Hosting.",
            relevantLogs = relevantLogs
        )
        BridgeErrorCode.NETWORK_PERMISSION_ERROR -> BridgeDiagnosticError(
            code = code,
            title = "Network Socket or Permission Error",
            technicalExplanation = technicalDetail,
            possibleCause = "Android network permission or socket bind/connect access was denied, or the device has no active network interface.",
            suggestedResolution = overrideResolution
                ?: "Verify Wi-Fi/LAN connectivity and Android network permissions for STAR Launcher.",
            relevantLogs = relevantLogs
        )
        BridgeErrorCode.FLOODGATE_CONFIGURATION_INVALID -> BridgeDiagnosticError(
            code = code,
            title = "Floodgate Configuration Invalid",
            technicalExplanation = technicalDetail,
            possibleCause = "Geyser auth-type is set to 'floodgate', but key.pem is missing, corrupted, or not synchronized with the Java server's Floodgate plugin.",
            suggestedResolution = overrideResolution
                ?: "Ensure floodgate-spigot.jar is installed on the Paper backend and sync or regenerate key.pem in Authentication settings.",
            relevantLogs = relevantLogs
        )
        BridgeErrorCode.SERVER_NOT_GEYSER_ENABLED -> BridgeDiagnosticError(
            code = code,
            title = "External Server Has No Bedrock / Geyser Endpoint",
            technicalExplanation = technicalDetail,
            possibleCause = "The remote Java server responded to Java ping, but no Geyser/Bedrock listener responded on the configured Bedrock port.",
            suggestedResolution = overrideResolution
                ?: "Bedrock players cannot join an external Java server unless Geyser is installed server-side (or a dedicated Geyser instance with access to that server is running). Ask the server owner for the Bedrock host/port or install Geyser on the server.",
            relevantLogs = relevantLogs
        )
        BridgeErrorCode.BEDROCK_VERSION_UNSUPPORTED -> BridgeDiagnosticError(
            code = code,
            title = "Bedrock Client Protocol Mismatch",
            technicalExplanation = technicalDetail,
            possibleCause = "The installed Geyser version does not support the connected Bedrock client version or the backend Java server version.",
            suggestedResolution = overrideResolution
                ?: "Update Geyser Standalone to the latest verified build in Overview / Advanced settings.",
            relevantLogs = relevantLogs
        )
        BridgeErrorCode.RUNTIME_PROCESS_TERMINATED -> BridgeDiagnosticError(
            code = code,
            title = "Bridge Process Terminated Unexpectedly",
            technicalExplanation = technicalDetail,
            possibleCause = "The isolated Android JVM service process exited due to an unhandled exception, native signal, or Android low-memory killer (LMK).",
            suggestedResolution = overrideResolution
                ?: "Lower server/Geyser heap limits in Advanced settings, review Console logs, and restart the bridge.",
            relevantLogs = relevantLogs
        )
        BridgeErrorCode.INSUFFICIENT_MEMORY -> BridgeDiagnosticError(
            code = code,
            title = "Insufficient Device Memory",
            technicalExplanation = technicalDetail,
            possibleCause = "Available Android RAM is lower than the combined heap and native overhead requested by the local Java server and Geyser.",
            suggestedResolution = overrideResolution
                ?: "Reduce Local Server / Geyser memory limits or close background applications before starting Local Hosting.",
            relevantLogs = relevantLogs
        )
        BridgeErrorCode.ARTIFACT_DOWNLOAD_FAILED -> BridgeDiagnosticError(
            code = code,
            title = "Bridge Artifact Download or Verification Failed",
            technicalExplanation = technicalDetail,
            possibleCause = "Network interruption occurred or the downloaded file checksum did not match the official GeyserMC/PaperMC/Mojang manifest.",
            suggestedResolution = overrideResolution
                ?: "Check internet connectivity and retry the download. Corrupted partial artifacts are never promoted.",
            relevantLogs = relevantLogs
        )
        BridgeErrorCode.EULA_NOT_ACCEPTED -> BridgeDiagnosticError(
            code = code,
            title = "Minecraft Server EULA Not Accepted",
            technicalExplanation = technicalDetail,
            possibleCause = "Hosting a local Minecraft Java server requires explicit acceptance of the Mojang EULA.",
            suggestedResolution = overrideResolution
                ?: "Read and explicitly enable 'Accept Minecraft EULA' in Local Hosting before starting the local Java server.",
            relevantLogs = relevantLogs
        )
        BridgeErrorCode.CONFIGURATION_INVALID -> BridgeDiagnosticError(
            code = code,
            title = "Invalid Bridge Configuration",
            technicalExplanation = technicalDetail,
            possibleCause = "One or more host, port, memory, or path parameters failed validation.",
            suggestedResolution = overrideResolution
                ?: "Verify ports (1024-65535), hostnames, and memory settings in Bedrock Bridge.",
            relevantLogs = relevantLogs
        )
    }
}
