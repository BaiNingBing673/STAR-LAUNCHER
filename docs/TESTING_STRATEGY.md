# Testing Strategy (`M0.2` / `M0.7`)

> Defines baseline, unit, integration, native smoke, and compatibility regression testing for STAR Launcher.

---

## 1. Test Tiers

| Tier | Execution Environment | Scope | Gate |
|---|---|---|---|
| **T0: Baseline Build & Static Verification** | Windows / CI host (`gradlew`) | `:app_pojavlauncher:assembleDebug`, `:MioLibPatcher:test`, `:forge_installer:jar`, `:arc_dns_injector:jar`, `:jre_lwjgl3glfw:jar`, lint, APK packaging checks | Must pass on every PR and before milestone sign-off |
| **T1: JVM Unit Tests** | Local JVM (`src/test/`) | Argument builder (`Native Engine Adapter`), version JSON inheritance resolver, compatibility rule engine (`THEORETICAL` tier), diagnostic log redactor, download state machine & SHA-1 verifier | Fast (<30s), runs in CI without emulator |
| **T2: Android Instrumentation Tests** | Android Emulator / Physical Device (`src/androidTest/`) | Room `AetherDatabase` CRUD + `@Transaction` + `Migration` tests (`MigrationTestHelper`), Android Keystore token vault encryption/decryption (verifying no plaintext token persists), `DeviceInfo` capability probe (`GLInfoUtils`, Vulkan feature query, ABI probe) | Required for `M0.5` and `M0.7` acceptance |
| **T3: Native & JNI Smoke Tests** | Physical Device / ABI Emulator | Verify `libpojavexec.so`, `libexithook.so`, `liblinkerhook.so`, `libandroidnsbypass.so`, `libglxshim.so`, `libpojavexec_awt.so`, `libawt_xawt.so` load cleanly across `arm64-v8a`, `armeabi-v7a`, `x86_64` | Required whenever NDK/Gradle/JNI boundaries are touched |
| **T4: Runtime Launch Regression Matrix** | Physical Android Devices | End-to-end launch of Minecraft 1.8.9 (LWJGL2/Java 8), 1.12.2 (Forge), 1.20.4 (LWJGL3/Java 17), 1.21.x (Java 21) across renderers (`NG-GL4ES`, `MobileGlues`, `Kopper-Zink`) and input modes (Touch, Virtual Mouse, Pointer Capture, Gamepad) | Promotes compatibility entries from `TESTED` -> `VERIFIED_RUNTIME` |

---

## 2. Phase 0 Verification Requirements
- **M0.1–M0.2**: Documentation completeness + baseline build diagnosis (`docs/BUILD_GUIDE.md`).
- **M0.3**: Application ID / branding isolation check (`assembleDebug` produces installable APK without breaking `net.kdt.pojavlaunch` JNI symbols!).
- **M0.4**: Compose preview/smoke render test + theme token check.
- **M0.5**: Room schema export verification + `AetherDatabaseTest` (FK cascade, index usage, migration test) + Keystore vault unit/instrumentation test confirming zero plaintext tokens on disk.
- **M0.6**: Contract tests for `NativeEngineAdapter` mapping STAR `LaunchConfig` -> legacy `Tools` / `JREUtils` / `MinecraftProfile` inputs without mutating existing user profiles.
- **M0.7**: Capability-probe tests (`DeviceCapabilityProbe`) + rule-engine unit tests verifying strict separation of `DETECTED`, `THEORETICAL`, `TESTED`, and `VERIFIED_RUNTIME`.
