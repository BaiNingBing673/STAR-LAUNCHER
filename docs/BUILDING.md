# Building STAR Launcher from Source

> Verified against `build.gradle`, `settings.gradle`, `app_pojavlauncher/build.gradle`, `app_pojavlauncher/src/main/jni/Application.mk`, and `.github/workflows/android.yml`.

---

## 1. System & Toolchain Prerequisites

| Dependency | Verified Version / Requirement | Notes |
| --- | --- | --- |
| **Android Gradle Plugin** | `9.3.1` | Configured in `build.gradle` & `app_pojavlauncher/build.gradle` |
| **Gradle Wrapper** | `9.6.1` | Invoke via `./gradlew` (macOS/Linux) or `.\gradlew.bat` (Windows) |
| **Primary JDK (`JAVA_HOME`)** | **JDK 21** (Eclipse Temurin recommended) | Used for Gradle daemon, Kotlin (`JvmTarget.JVM_1_8`), Compose, and unit tests (`testOptions` explicitly requests Java 21 launcher) |
| **Secondary JDK Toolchain** | **JDK 8** | Required by `:MioLibPatcher` (`JavaLanguageVersion.of(8)`). Gradle toolchain auto-detection must be able to locate JDK 8 on the host machine |
| **Android SDK Platforms** | `compileSdk = 37`, `android-36.1` (`androidnsbypass`), `targetSdk = 34`, `minSdk = 21` | `app_pojavlauncher` compiles against API `37`; `androidnsbypass` compiles against API `36.1` |
| **Android NDK** | `27.3.13750724` (pinned in `app_pojavlauncher/build.gradle`) | Compiles `pojavexec`, `exithook`, `linkerhook`, `glxshim`, `pojavexec_awt`, and `awt_xawt` |
| **CMake** | `3.22.1` (plus Ninja from Android SDK) | Required by `:androidnsbypass` CMake build |
| **Working Directory Path** | **No spaces in absolute path** | On Windows/Unix, `ndk-build` fails when the repository path contains spaces (`android/ndk#1400`) |

---

## 2. Repository Clone & Submodules

Active Git submodules declared in the index:

- `MioLibPatcher` (`AngelAuraMC/MioLibPatcher`)
- `androidnsbypass` (`alexytomi/androidnsbypass`)

*(Note: `.gitmodules` still lists `MobileGlues`, `SDL`, and `sdl2-compat`, though those dependencies are actually consumed as prebuilt `.aar` archives in `app_pojavlauncher/libs/`.)*

Clone recursively into a spaceless path:

```bash
git clone --recursive <repository-url> STAR-Launcher
cd STAR-Launcher
```

Ensure `local.properties` points to your Android SDK (gitignored):

```properties
sdk.dir=/path/to/android-sdk
```

And if Gradle does not automatically find JDK 8 alongside JDK 21, configure `~/.gradle/gradle.properties`:

```properties
org.gradle.java.installations.auto-detect=true
org.gradle.java.installations.paths=/path/to/jdk-8,/path/to/jdk-21
```

---

## 3. Optional Pre-Build Assets & Environment Variables

### 3.1 Embedded Java 8 Runtime (`components/jre/`)
Pre-built OpenJDK 8 binaries are **not** committed to Git.

- **Without bundled JRE 8**: The APK compiles and installs normally. Users can download OpenJDK 8, 17, 21, or 25 runtimes in-app via **Settings -> Runtime Management**, or import a `.tar.xz` runtime archive.
- **With bundled JRE 8 (matches CI)**: Place unpacked `jre8-pojav` contents inside `app_pojavlauncher/src/main/assets/components/jre/` before running `assembleDebug` or `assembleRelease`.

### 3.2 Language List Generator
When adding or updating Crowdin localizations under `app_pojavlauncher/src/main/res/values-*/`:

- **Linux / macOS**: `bash scripts/languagelist_updater.sh`
- **Windows**: `scripts\languagelist_updater.bat`

### 3.3 Optional CurseForge API Key
If neither `CURSEFORGE_API_KEY` (environment variable) nor `app_pojavlauncher/curseforge_key.txt` exists, Gradle logs:
`BUILD: You have no CurseForge key, the curseforge api will get disabled !`
This is **non-fatal** (`resValue 'string', 'curseforge_api_key', 'DUMMY'`); debug builds and unit tests still succeed.

---

## 4. Build & Test Commands

### Build Debug APK

```bash
# Linux / macOS
./gradlew :app_pojavlauncher:assembleDebug --console=plain
```

```powershell
# Windows PowerShell (from a spaceless directory)
$env:JAVA_HOME = "D:\tools\jdks\temurin-21"
$env:ANDROID_HOME = "D:\tools\android-sdk"
.\gradlew.bat :app_pojavlauncher:assembleDebug --console=plain
```

Output path:
`app_pojavlauncher/build/outputs/apk/debug/app_pojavlauncher-debug.apk`

### Run Unit & Robolectric Tests

```bash
./gradlew :app_pojavlauncher:testDebugUnitTest --console=plain
```

### Build Release / Google Play Variants
Release signing expects keystore credentials in environment variables (`GPLAY_KEYSTORE_PASSWORD`) and keystore files (`aamc_upload.jks` / `upload.jks`) referenced in `app_pojavlauncher/build.gradle`:

```bash
./gradlew :app_pojavlauncher:assembleRelease --console=plain
```

---

## 5. Known Build Caveat (`clean` Task Ordering with Prefab)

Running `./gradlew clean` directly while `:app_pojavlauncher:externalNativeBuildCleanDebug` executes can fail if `:androidnsbypass:clean` removes `libandroidnsbypass.so` before `:app_pojavlauncher` finishes running `ndk-build clean` against its cached prefab `.cxx` metadata.

To perform a guaranteed cold clean build:

```powershell
.\gradlew.bat --stop
Remove-Item -Recurse -Force -ErrorAction SilentlyContinue `
  app_pojavlauncher\build, app_pojavlauncher\.cxx, `
  androidnsbypass\build, androidnsbypass\.cxx
.\gradlew.bat :app_pojavlauncher:testDebugUnitTest :app_pojavlauncher:assembleDebug --console=plain
```
