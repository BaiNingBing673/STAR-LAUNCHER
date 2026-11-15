# Building STAR Launcher

Verified build instructions for **STAR Launcher** (`com.star.launcher`, branch `v3_openjdk`).

---

## 1. Prerequisites

- **JDK 21** (`JAVA_HOME`): Required for AGP `9.3.1`, Kotlin, Jetpack Compose, and Android/unit test tasks.
- **JDK 8**: Required on the host for Gradle Java toolchain auto-detection (`:MioLibPatcher` and helper JVM agents compile to Java 8 bytecode).
- **Android SDK** (`ANDROID_HOME` / `sdk.dir` in `local.properties`):
  - Compile SDK `37` (`app_pojavlauncher`), `36.1` (`androidnsbypass`), Target SDK `34`, Min SDK `21`.
  - Build Tools `36.0.0` (or `34.0.0`+) for `aapt2`, `apksigner`, and `apkanalyzer`.
  - CMake and NDK (`25.2.9519653`+ / matching local SDK NDK installation).
- **Spaceless Working Directory**: On Windows, keep the repository in a path without spaces (for example `D:\STARLauncher`) because `ndk-build` splits unquoted paths on spaces.

---

## 2. Clone & Apply Submodule Overlay

```bash
git clone --recursive https://github.com/BaiNingBing673/STAR-LAUNCHER.git STAR-Launcher
cd STAR-Launcher
```

Ensure `MioLibPatcher` includes STAR's custom bytecode transformers (`patches/miolibpatcher-star.patch`):

```bash
if [ ! -f MioLibPatcher/src/main/java/com/mio/libpatcher/transformer/VulkanSurfaceTransformer.java ]; then
  git -C MioLibPatcher apply ../patches/miolibpatcher-star.patch
fi
```

Update the tracked language index on Linux/macOS before assembling APKs:

```bash
./scripts/languagelist_updater.sh
```

*(Note: Pre-built JRE 8 binaries under `app_pojavlauncher/src/main/assets/components/jre/` are not stored in Git. CI downloads them from `AngelAuraMC/angelauramc-openjdk-build`; local developers can omit them and install OpenJDK 8/17/21/25 inside the app via Runtime Management.)*

---

## 3. Version Source of Truth

Version metadata lives in `app_pojavlauncher/build.gradle`:

- `starVersionCode` (e.g. `10000002`)
- `starVersionName` (e.g. `"3.0.1"`)

`BuildConfig.VERSION_CODE`, `BuildConfig.VERSION_NAME`, in-app About info, HTTP User-Agent headers, and CI APK/tag validation all read from these values.

---

## 4. Running Tests & Debug Builds

```bash
# Run MioLibPatcher + Android JVM/Robolectric unit tests
./gradlew :MioLibPatcher:test :app_pojavlauncher:testDebugUnitTest --console=plain

# Build Debug APK (signed automatically with app_pojavlauncher/debug.keystore)
./gradlew :app_pojavlauncher:assembleDebug --console=plain
```

Output:
`app_pojavlauncher/build/outputs/apk/debug/app_pojavlauncher-debug.apk`

---

## 5. Production Release Signing (Required)

**Release builds (`:app_pojavlauncher:assembleRelease`, `bundleRelease`) NEVER fall back to `debug.keystore`.**
If production signing credentials are missing, Gradle intentionally fails before packaging an unsigned or debug-signed release APK.

Supply the **existing STAR Launcher production signing keystore** using environment variables, Gradle properties (`-P...`), or git-ignored `local.properties` (never commit secrets or keystores):

- `STAR_KEYSTORE_FILE` — Path to the `.jks`/`.keystore` file (absolute or relative to repo/module root)
- `STAR_KEYSTORE_PASSWORD` — Keystore store password
- `STAR_KEY_ALIAS` — Signing key alias
- `STAR_KEY_PASSWORD` — Key password

Optional CurseForge key (if absent, Mods Hub still works via keyless `ModrinthFallbackCatalog`):
- `CURSEFORGE_API_KEY` env var / `local.properties`, or git-ignored `app_pojavlauncher/curseforge_key.txt`.

Build the signed Release APK:

```bash
./gradlew :app_pojavlauncher:assembleRelease --console=plain
```

---

## 6. Post-Build APK Verification & Checksums

Verify APK metadata, native libraries, signature certificate, and generate SHA-256 checksums:

```bash
APK="out/STAR-Launcher-v3.0.1.apk"

# 1. Inspect metadata & permissions
"$ANDROID_HOME/build-tools/36.0.0/aapt2" dump badging "$APK"

# 2. Verify APK signature and inspect SHA-256 certificate digest
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --print-certs --verbose "$APK"

# 3. Verify native ABI directories (arm64-v8a, armeabi-v7a, x86, x86_64)
"$ANDROID_HOME/build-tools/36.0.0/apkanalyzer" files list "$APK" | grep '^/lib/'

# 4. Generate SHA-256 checksum
sha256sum "$APK" > "${APK}.sha256"
```

---

## 7. Known Build Caveat (`clean` with Native Prefab Caches)

Directly running `./gradlew clean` when CMake/ndk-build prefab caches already exist can fail inside `:androidnsbypass` / `:app_pojavlauncher:externalNativeBuildCleanDebug`. If you need a full clean rebuild, remove `app_pojavlauncher/.cxx`, `androidnsbypass/.cxx`, and `build/` folders before rebuilding.
