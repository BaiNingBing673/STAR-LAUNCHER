# STAR Lab — Compatibility, Diagnostics & Telemetry Architecture

> **Status Summary**: STAR Lab combines STAR Launcher's hardware capability engine, redacted crash/session diagnostics, and cross-process session tracking. Core backend subsystems are **implemented and tested**, while dedicated unified STAR Lab screens, curated QA hardware matrices, and optional telemetry remain **partially implemented / planned** (see [`FEATURE_STATUS.md`](FEATURE_STATUS.md)).

---

## 1. Compatibility Passport

### 1.1 Capability-First Design (`DeviceCapabilityProbe.kt`)
Rather than guessing support from marketing model names (`Build.MODEL`), `AndroidDeviceCapabilityProbe` reads real hardware and OS signals:

- **CPU & Address Space**: `Build.SUPPORTED_ABIS`, primary ABI, 64-bit capability (`arm64-v8a` / `x86_64` vs `armeabi-v7a` / `x86`), and total physical RAM (`ActivityManager.MemoryInfo`).
- **Graphics Stack (`GLInfoUtils`)**: `GL_VENDOR`, `GL_RENDERER`, OpenGL ES major version (`glesMajorVersion`), Vulkan hardware support/version (`PackageManager.FEATURE_VULKAN_HARDWARE_VERSION`), GPU family classification (`ADRENO`, `MALI`, `XCLIPSE`, `POWERVR`, `UNKNOWN`), and Adreno KGSL sysfs node detection (`/sys/class/kgsl/kgsl-3d0/gpu_model`).
- **Device Fingerprint**: Deterministic SHA-256 hash computed from `cpuAbiPrimary | androidApiLevel | gpuVendor | gpuRenderer | vulkanVersion`, persisted locally in Room (`DeviceInfoEntity`).

### 1.2 Four-Tier Compatibility Model (`CompatibilityManager.kt`)
`CompatibilityEvaluator` strictly separates four distinct evidence tiers inside `CompatibilityResults`:

1. **`DETECTED`** *(Implemented)* — Objective on-device measurements (`ABI`, `64-bit`, `API`, `GPU`, `GLES`, `Vulkan`).
2. **`THEORETICAL`** *(Implemented)* — Deterministic rule evaluation combining detected capabilities with the instance's Minecraft version, required Java major version, mod loader, and renderer ID (for example: flagging `x86` 32-bit inability to run JRE 21+, missing Vulkan on Zink renderers, non-Adreno experimental Vulkan caveats, OpenGL ES `< 3.0` incompatibilities, and 32-bit MobileGlues heap limits).
3. **`TESTED`** *(Interface Implemented; Data Stub)* — `TestedMatrixProvider` is currently wired to `EmptyTestedMatrixProvider`, which explicitly returns `null` rather than fabricating QA verdicts until a curated matrix dataset is shipped.
4. **`VERIFIED_RUNTIME`** *(Implemented)* — Recorded **only** when an actual `:game` session finishes and reports exit status and `reachedGlContextInit` back from the device itself.

---

## 2. STAR Doctor (Diagnostics & Bundle Export)

- **Redaction (`SensitiveLogRedactor.kt`)**: Strips `--accessToken`, `--uuid`, `Authorization: Bearer ...`, JSON secret fields (`accessToken`, `refreshToken`, `clientToken`, `msaRefreshToken`, `password`, `xstsToken`), and JWT (`eyJ...`) payloads before persisting `DiagnosticLogEntity` rows or writing export bundles.
- **Native Library & Runtime Auditing**:
  - `PojavNativeEngineAdapter.verifyEngineLibrariesPresent()` checks `libpojavexec.so`, `libpojavexec_awt.so`, and `libexithook.so` in `Tools.NATIVE_LIB_DIR`.
  - `MultiRTUtils.inspectRuntime()` validates runtime directory integrity, `libjvm.so`, CPU ABI match, and required Java major version.
- **Support Bundle Export (`DiagnosticExporter.kt`)**: Exports a redacted plain-text report (`star-support-<timestamp>.txt`) containing device capabilities, recent session records, and a scrubbed `latestlog.txt` tail via **Settings -> Hardware & Compatibility -> Export Diagnostics**.
- **Pending Work**: Automated rule-based crash-cause diagnosis cards (matching known JVM/renderer failure signatures to one-tap remediation actions) are planned for the dedicated STAR Lab screen.

---

## 3. STAR Pulse (Cross-Process Session Tracking & Planned Telemetry)

Because `:game` (`MainActivity`) runs in a separate OS process and `:launcher` exits during launch handoff to reclaim RAM, SQLite cannot safely accept concurrent writes from both processes:

1. `:launcher` starts a `GameSessionEntity` (`LAUNCHING`) via `DiagnosticsManager.startSession()` and flushes Room WAL checkpoints before launching `:game`.
2. When `:game` exits or crashes, `MainActivity` invokes `CrossProcessSpoolBridge.writeGameExitSpoolJvm(...)` to write an atomic redacted JSON spool record into `<app_data>/aether_spool/<sessionId>.json`.
3. On the next `:launcher` startup, `CrossProcessSpoolBridge.ingestPendingSpools()` ingests pending JSON spools into `GameSessions` and updates `VERIFIED_RUNTIME` records cleanly inside `:launcher`.

**Privacy & Planned Scope**: All STAR Pulse data currently remains 100% local in Room (`aether_launcher.db`) and local spool files. Any future anonymized compatibility reporting or live performance telemetry HUD will be strictly opt-in and free of authentication credentials or personal paths.
