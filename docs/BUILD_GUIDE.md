# Windows Build Guide (`M0.1` / `M0.2`)

> Documents reproducible Windows build requirements and the verified Phase 0 baseline build diagnostic.

---

## 1. Prerequisites

1. **Path Without Spaces (CRITICAL)**:
   - Upstream `README.md` warns against spaces in the workspace path due to an `ndk-build` issue (`android/ndk#1400`).
   - **Historical Note**: Early Phase 0 baseline tests encountered a path with spaces (`D:\Aether Launcher` / historical `D:\STAR Launcher`), and NDK compilation on Windows fails on paths with spaces. Ensure the active repository stays in a spaceless path (such as `D:\what\AetherLauncher`) before running full NDK builds.
2. **Java Development Kits (Two Versions Required)**:
   - **Gradle / AGP 9.3.1 Host JDK**: JDK **17** or **21** (Android Studio JBR 25.0.3 also runs Gradle 9.6.1, though CI uses Temurin JDK 21 in `.github/workflows/android.yml:25-29`).
   - **JDK 8 Toolchain (Required by `:MioLibPatcher`)**: `MioLibPatcher/build.gradle.kts` (L16–L20) declares `java.toolchain.languageVersion.set(JavaLanguageVersion.of(8))`, and `settings.gradle` does **not** configure a toolchain download repository (such as Foojay). Therefore, a local **JDK 8** installation must be discoverable by Gradle (e.g., via `org.gradle.java.installations.paths` or standard install path).
3. **Android SDK & NDK**:
   - `ANDROID_HOME` / `ANDROID_SDK_ROOT` pointing to the SDK (e.g., `D:\tools\android-sdk`).
   - Platforms: `android-34`, `android-36.1` (`androidnsbypass`), `android-37.0` (`app_pojavlauncher` `compileSdk = 37`).
   - Build Tools: `34.0.0`, `36.0.0`.
   - NDK (Side-by-side): `27.3.13750724` (pinned in `app_pojavlauncher/build.gradle:161`).
   - CMake: `3.22.1` (pinned in `androidnsbypass/build.gradle.kts:31`).
4. **Optional Bundled Java 8 Runtime**:
   - Upstream git does not include `app_pojavlauncher/src/main/assets/components/jre/`. CI downloads `jre8-pojav` from `AngelAuraMC/angelauramc-openjdk-build` (`android.yml:31-39`). Without it, the APK still builds, and JREs can be downloaded at runtime via `NewJREUtil`.
5. **Language List Script**:
   - Run `scripts\languagelist_updater.bat` before release/CI builds when translations change.

---

## 2. Phase 0 Baseline Build Result (Recorded Verbatim)

### Initial Probe (Pre-Toolchain Provisioning)
- **Commit Hash**: `166af615dbdca1a507b8c2d379822cca02795132` (`v3_openjdk`)
- **Gradle Wrapper Version**: `9.6.1`
- **Android Gradle Plugin**: `9.3.1`
- **Commands Run**:
  1. `.\gradlew.bat --version` (default shell):
     - **Failed immediately**: `ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.`
  2. With transient process env (`JAVA_HOME=C:\Program Files\Android\Android Studio\jbr`, `ANDROID_HOME=D:\tools\android-sdk`):
     - `.\gradlew.bat --version`: **Succeeded** (`Gradle 9.6.1`, `Launcher JVM: 25.0.3`, `Windows 11 10.0 amd64`).
     - `.\gradlew.bat :app_pojavlauncher:assembleDebug --stacktrace --console=plain`: **FAILED in 4m 27s** (after automatically installing `NDK 27.3.13750724`, `Android SDK Platform 37.0`, and `Android SDK Platform 36.1`).

#### First Meaningful Error & Root Cause
```text
> Failed to calculate the value of task ':MioLibPatcher:compileJava' property 'javaCompiler'.
   > Cannot find a Java installation on your machine (Windows 11 10.0 amd64) matching: {languageVersion=8, vendor=any vendor, implementation=vendor-specific, nativeImageCapable=false}. Toolchain download repositories have not been configured.
```
- **Category**: **Environment / Toolchain** (NOT source code). `:MioLibPatcher` requests Java 8 via `JavaLanguageVersion.of(8)`, while `settings.gradle` has no toolchain download repository.
- **Note on CurseForge key**: `BUILD: You have no CurseForge key, the curseforge api will get disabled !` is a non-fatal warning (`DUMMY` fallback).

### Verified Green Baseline (Post-Toolchain Provisioning)
- **Environment Applied (Zero Tracked Repo Edits)**:
  - Portable Eclipse Temurin **JDK 8** (`1.8.0_512-b09`) at `D:\tools\jdks\temurin-8` and **JDK 21** (`21.0.13+9-LTS`) at `D:\tools\jdks\temurin-21`.
  - **CMake `3.22.1`** installed via `sdkmanager "cmake;3.22.1"` at `D:\tools\android-sdk\cmake\3.22.1`.
  - Gitignored `local.properties`: `sdk.dir=D\:\\tools\\android-sdk`.
  - User `C:\Users\jackp\.gradle\gradle.properties`: `org.gradle.java.installations.auto-detect=true` and `org.gradle.java.installations.paths=D\:/tools/jdks/temurin-8,D\:/tools/jdks/temurin-21`.
  - NTFS directory junction `D:\AetherLauncher` -> `D:\Aether Launcher` to eliminate `ndk-build` Windows path-with-spaces failures (`android/ndk#1400`).
- **Command**: From `D:\AetherLauncher` (and `D:\what\AetherLauncher` for STAR Launcher builds), `.\gradlew.bat :app_pojavlauncher:assembleDebug --console=plain` (`JAVA_HOME=D:\tools\jdks\temurin-21`, `ANDROID_HOME=D:\tools\android-sdk`).
- **Baseline Outcome**: **BUILD SUCCESSFUL in 4m 6s** (`246.62s`, `90 actionable tasks: 89 executed, 1 from cache`).
- **Historical Baseline Variant**: `debug` (`org.angelauramc.amethyst.debug` before Aether/STAR rebrand; current debug identity is `com.star.launcher.debug`)
- **Commit Hash**: `166af615dbdca1a507b8c2d379822cca02795132` (`v3_openjdk`)
- **Output APK**: `app_pojavlauncher\build\outputs\apk\debug\app_pojavlauncher-debug.apk`
- **APK Size**: `142,782,102` bytes (`136.17 MB`)
- **SHA-256**: `E95A8DC7B8B34213FE5154C49727F0D22A77E32110BCA81B1929E4035821A6E0`
- **Archived Baseline Copy**: `C:\Users\jackp\.gemini\antigravity\brain\c0fd0333-52b6-4f9a-8870-8de53bdf4ce8\scratch\baseline\amethyst-baseline-166af615-debug.apk`
