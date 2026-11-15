# Compatibility Strategy (`M0.2` / `M0.7`)

> **Core Principle**: Use **capability detection**, never fragile device-name or marketing-model assumptions. Never promise universal compatibility without runtime verification.

---

## 1. Compatibility Dimensions

| Dimension | Supported / Target Values | Upstream Detection / Constraints |
|---|---|---|
| **CPU ABI** | `arm64-v8a` (primary), `armeabi-v7a`, `x86_64`, `x86` | `Application.mk` targets all 4 ABIs. **Constraints**: `x86` cannot download JRE 21/25 (`MultiRTUtils.java:67`, `NewJREUtil.java:186`); Turnip (`libvulkan_freedreno.so`) & `ADRENO_POSSIBLE` apply only to `arm64-v8a` (`Android.mk:45`); 32-bit devices hit `ADDRESS_SPACE_LIMIT_32_BIT` (`0xbfffffffL` -> ~3 GB virtual address limit, forcing lower `-Xmx`). |
| **GPU & Driver** | **Adreno** (Qualcomm), **Mali** (ARM), **Xclipse** (Samsung/AMD RDNA), PowerVR / other | `GLInfoUtils` probes `EGL14` (`GL_VENDOR`, `GL_RENDERER`, `GL_VERSION`, `glesMajorVersion`). **Known Quirks**: Adreno GLES3 buffer-name exhaustion on LTW above render distance 7 without Sodium (`Tools.java:380–404`); Adreno 740 on OneUI needs `FD_DEV_FEATURES=enable_tp_ubwc_flag_hint=1` (`Tools.shouldUseUBWC()`); Turnip (`libvulkan_freedreno.so`) targets Adreno `arm64-v8a` only. |
| **Android API Level** | `minSdk = 21` (Android 5.0) to `targetSdk = 34` / `compileSdk = 37` | `API < 29`: legacy `/sdcard/games/Amethyst` + `WRITE_EXTERNAL_STORAGE` (`TestStorageActivity`); `API >= 29`: app-scoped `getExternalFilesDir(null)`; `API >= 26`: `requestPointerCapture()`; `API >= 24`: `androidnsbypass` active + `FEATURE_VULKAN_HARDWARE_*`; `API >= 29` ARM64: BTI enforcement handled by `nsbypass.c`; `API >= 33`: `POST_NOTIFICATIONS`; Huawei EMUI 3.1 (`API < 26`): `POJAV_EMUI_ITERATOR_MITIGATE`. |
| **Java Runtime** | Java `8`, `17`, `21`, `25` | Matched via `versionInfo.javaVersion.majorVersion` (`NewJREUtil`, `Tools.pickRuntime`). Legacy MC <= 1.16.5 -> Java 8; 1.17 -> Java 16/17; 1.18–1.20.4 -> Java 17; 1.20.5+ -> Java 21 (or 25 experimental). |
| **Minecraft Version** | `rd-132211` through modern `1.21+` / snapshots | Legacy (`< 1.13`, `arguments == null`) uses LWJGL2 mode (`isUseStackQueueCall = false`) + `lwjglx` + `SimpleFPEWrapper` (for `< 1.17` on MobileGlues). Modern (`>= 1.13`) uses LWJGL3 (`3.3.3` or `3.4.1`) queue mode. |
| **Renderer** | `NG-GL4ES`, `MobileGlues` (+ `SimpleFPEWrapper`), `ANGLE`, `Kopper-Zink`, `OSMesa Zink`, `LTW`, `System GLES` | Filtered by `FEATURE_VULKAN_HARDWARE_LEVEL/VERSION`, `glesMajorVersion >= 3`, and presence of optional `.so` binaries (`libOSMesa.so`, `libltw.so`, `libng_gl4es.so`). |
| **Mod Loaders & Mods** | Vanilla, Forge, NeoForge, Fabric, Quilt, OptiFine, LWJGL3ify | Special mitigations detected in `Tools.java`: `sodium`/`embeddium`/`rubidium`/`xenon` mixin config tweaks; `controllable` native SDL2 race mitigation; `legacy4j` SDL fix; `touchcontroller` socket proxy; `lwjgl3ify-3` SDL3 mode; `zstd-jni` Distant Horizons path. |

---

## 2. Four-Tier Compatibility Model (Strict Separation)

STAR **MUST** store and display these four states independently in `CompatibilityResults` and `DeviceInfo`:

1. **Detected Capability (`DETECTED`)**:
   - Objective, measured facts gathered on-device at startup or probe time:
     - CPU ABI list (`Build.SUPPORTED_ABIS`), 64-bit vs 32-bit, total & free RAM, 32-bit continuous address space limit.
     - Android SDK level (`Build.VERSION.SDK_INT`), Vulkan feature level/version, EGL/GLES major version (`GLInfoUtils`), `GL_VENDOR` / `GL_RENDERER` / `GL_VERSION`, Adreno KGSL node presence (`/sys/class/kgsl/kgsl-3d0/gpu_model`), BTI/linker behavior.
     - Available renderer `.so` binaries and installed `RuntimeRegistry` versions.
2. **Theoretical Compatibility (`THEORETICAL`)**:
   - Deterministic rule-engine output combining `Detected Capability` + target `GameVersions` + `ModLoader` + `RendererProfiles`:
     - Example: *Arm64 + Vulkan 1.1 + Adreno GPU + MC 1.20.4 + Java 17 + Kopper-Zink (Turnip)* -> `THEORETICAL_PASS` (with warning if Adreno 740 OneUI UBWC flag needed).
     - Example: *x86 32-bit + MC 1.21 (requires Java 21)* -> `THEORETICAL_UNSUPPORTED` (JRE 21 not available for x86).
     - Example: *Mali GPU + Kopper-Zink* -> `THEORETICAL_EXPERIMENTAL` (depends on vendor Mali Vulkan driver extensions; Turnip unavailable).
3. **Tested Compatibility (`TESTED`)**:
   - Curated compatibility matrix entries backed by documented QA runs on known SoC/GPU/API/renderer/version tuples (imported as versioned read-only baseline rules, never extrapolated to untested GPUs).
4. **Verified Runtime Compatibility (`VERIFIED_RUNTIME`)**:
   - Recorded **only** after an actual `GameSessions` run on the user's device reaches `SURFACE_READY` -> `GL_CONTEXT_INITIALIZED` -> clean gameplay duration without fatal `SIGABRT` or non-zero exit code.
   - Automatically updated by `Diagnostics Manager` at the end of a session, including exit code, duration, and observed crash signature if failed.

---

## 3. Capability-Detection Rules (No Device-Name Hardcoding)
- **Avoid** matching `Build.MODEL` like `"SM-S918B"` or `"Pixel 8"` to guess GPU support.
- **Always** probe EGL/GLES/Vulkan extensions, ABI list, RAM/address space, and sysfs driver nodes directly, using `Build.MANUFACTURER` / `ro.build.version.oneui` only for strictly verified OS-level driver bugs (such as existing `Tools.shouldUseUBWC()` and `Tools.deviceHasHangingLinker()`).
