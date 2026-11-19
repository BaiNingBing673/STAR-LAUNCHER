package com.star.launcher.stargfx

enum class StarGfxBackendPreference(val id: String, val label: String) {
    AUTO("auto", "Auto (Vulkan -> GLES)"),
    VULKAN("vulkan", "STAR GFX Vulkan"),
    OPENGLES("gles", "STAR GFX OpenGL ES");

    companion object {
        fun fromId(id: String?): StarGfxBackendPreference =
            values().firstOrNull { it.id.equals(id, ignoreCase = true) } ?: AUTO
    }
}

enum class StarGfxAcceptanceLevel(val label: String) {
    NOT_BUILT("NOT BUILT"),
    BUILT("BUILT"),
    NATIVE_LIBRARY_LOADED("NATIVE LIBRARY LOADED"),
    RUNTIME_INITIALIZED("RUNTIME INITIALIZED"),
    BACKEND_INITIALIZED("BACKEND INITIALIZED"),
    RENDERING_VERIFIED("RENDERING VERIFIED"),
    STAR_LAUNCHER_INTEGRATED("STAR LAUNCHER INTEGRATED"),
    MINECRAFT_INTEGRATED("MINECRAFT INTEGRATED"),
    PERFORMANCE_VALIDATED("PERFORMANCE VALIDATED"),
    PRODUCTION_READY("PRODUCTION READY")
}

data class StarGfxMinecraftCompatibilityStatus(
    val compatible: Boolean,
    val reason: String,
    val requiredTranslationLayer: String
)
