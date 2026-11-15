package com.star.launcher.data.db.model

enum class SettingValueType {
    BOOL,
    INT,
    FLOAT,
    STRING,
    JSON
}

enum class ModLoaderType {
    VANILLA,
    FORGE,
    NEOFORGE,
    FABRIC,
    QUILT,
    LWJGL3IFY,
    OTHER
}

enum class DownloadTaskType {
    VERSION_MANIFEST,
    CLIENT_JAR,
    LIBRARY,
    ASSET,
    RUNTIME,
    MODLOADER,
    MOD
}

enum class DownloadStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    VERIFYING,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * Strictly separates hardware observation, theoretical analysis, matrix tests,
 * and confirmed runtime runs on the target device.
 */
enum class CompatibilityTier {
    DETECTED,
    THEORETICAL,
    TESTED,
    VERIFIED_RUNTIME
}

enum class CompatibilityStatus {
    PASS,
    WARN,
    FAIL,
    EXPERIMENTAL,
    UNSUPPORTED
}

enum class SessionStatus {
    LAUNCHING,
    RUNNING,
    EXITED_OK,
    CRASHED,
    KILLED
}

enum class DiagnosticLogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
    FATAL
}
