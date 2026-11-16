# STAR Launcher — Release ZIP Extraction & Debug Play Protect Root-Cause Audit

**Date:** `2026-11-16`  
**Repository:** `STAR-LAUNCHER` (`v3_openjdk`, base commit `d9538839d`)  
**Target Versions Audited:** `3.0.0` (`10000001`) and `3.0.1` (`10000002`)

---

## Executive Summary

1. **Bug A (Release ZIP extraction failure on Nubia Neo):**  
   `.github/workflows/android.yml` did not create a release `.zip` archive itself; instead, `actions/upload-artifact@v4` (`@actions/artifact` / Node `archiver`) generated a **streaming ZIP** using **Deflate (`method=8`)**, **Data Descriptors (`GPBF bit 3 = 0x0008`)**, and **no ZIP64**. Streaming ZIPs write `crc32 = 0`, `compressed_size = 0`, and `uncompressed_size = 0` in the Local File Header (`PK\x03\x04`) and place actual sizes/CRCs in a trailing `PK\x07\x08` Data Descriptor and the Central Directory (`PK\x01\x02`). Desktop tools (`7-Zip`, `unzip`, Python `zipfile`) read the Central Directory first and pass CRC checks, whereas Android sequential / Local-File-Header-first extractors (such as OEM file managers) fail or reject `0x0008` Data Descriptors and zeroed Local File Headers on large (~164 MB) entries.  
   **Fix:** Added [`scripts/package_release_zip.py`](../scripts/package_release_zip.py) and [`scripts/verify_release_package.py`](../scripts/verify_release_package.py) to produce and validate conservative `ZIP_STORED` (`method=0`, `flags=0x0000`, `version_needed=10`, `allowZip64=False`) archives with exact pre-populated CRC-32 and sizes in both Local File Headers and the Central Directory, and updated [`.github/workflows/android.yml`](../.github/workflows/android.yml) to build Debug and Release independently, verify both `ZipFile` and `ZipInputStream` compatibility, attach raw `.apk` + conservative `.zip` + `.sha256` to Draft Releases, and perform round-trip artifact SHA-256 verification.

2. **Bug B (Debug APK blocked by Google Play Protect):**  
   The Debug APK (`com.star.launcher.debug`, `STAR-Launcher-v3.0.1-debug.apk`) is signed with the publicly committed PojavLauncher/Amethyst `app_pojavlauncher/debug.keystore` (**1024-bit weak RSA key**, `CN=Android Debug, O=Android, C=US`, SHA-256 `d0d0886a0d7e3486e6627f9b8011027fe3c6b0fb09424530b6d7be14f8c2cc33`, v1+v2 only) and sets `android:debuggable="true"` plus debug-only exported `androidx.compose.ui.tooling.PreviewActivity`. Combined with STAR Launcher's inherent low-level runtime mechanics (`libandroidnsbypass.so` private Bionic linker symbol resolution and `mprotect(..., PROT_WRITE | PROT_READ | PROT_EXEC)`, `libbytehook.so` PLT/GOT hooks in `libexithook.so` / `liblinkerhook.so`, `forkAndExec` JNI hooking in `libpojavexec.so`, in-process OpenJDK JRE `tar.xz` extraction + `dlopen()` + `-javaagent` bytecode instrumentation, `RECORD_AUDIO`, and `NotificationListenerService`), sideloading a `debuggable=true` APK signed with a publicly shared `Android Debug` key triggers Google Play Protect's unknown-developer / unsafe-app blocking.  
   **Policy:** Do **not** bypass or evade Play Protect. Keep Debug builds strictly for developer testing, and distribute only production-signed `release` builds (`com.star.launcher`, `debuggable=false`, `STAR_*` keystore).

---

## Bug A — Release ZIP Extraction Failure

### Root Cause
1. **GitHub Actions `actions/upload-artifact@v4` streaming ZIP format:**  
   In `.github/workflows/android.yml`, both `verify-and-debug` and `release` uploaded `out/*.apk` and `out/*.apk.sha256` directly via `actions/upload-artifact@v4`. Downloading an artifact from the GitHub Actions UI returns a streaming ZIP produced by `archiver('zip')` with:
   - `compression = 8` (Deflate, level 6)
   - `GPBF = 0x0008` (Bit 3: Data Descriptor enabled)
   - Local File Header (`PK\x03\x04`) fields `crc32 = 0`, `compressed_size = 0`, `uncompressed_size = 0`
   - Trailing `PK\x07\x08` Data Descriptor after the ~164 MB Deflate stream
   - `allowZip64 = false` (`version_needed = 20`)
2. **Why desktop passes while Android OEM extractors fail:**  
   - Desktop extractors (`unzip`, `7-Zip`, Windows Explorer, Python `zipfile`) seek to the End of Central Directory (`PK\x05\x06`) and read the Central Directory first, where `crc32`, `compressed_size`, and `uncompressed_size` are populated.
   - Sequential / Local-File-Header-first Android extractors (and `java.util.zip.ZipInputStream` callers inspecting `ZipEntry.getSize()`/`getCrc()` upfront, or OEM file managers on Nubia MyOS) see `uncompressed_size == 0` / `compressed_size == 0`, fail on `GPBF 0x0008` Data Descriptors, or fail scanning/decompressing a ~164 MB Deflate stream.
   - Furthermore, empirical testing confirmed that Deflate on an already-compressed STAR Launcher APK saves only **1.75%** (`138,159,070 B -> 135,740,544 B`), while `STORED` + Data Descriptor (`0x0008`) causes `java.util.zip.ZipInputStream` to throw `java.util.zip.ZipException: only DEFLATED entries can have EXT descriptor`.

### Resolution & Compatibility Matrix
- Added [`scripts/package_release_zip.py`](../scripts/package_release_zip.py) using:
  - `ZIP_STORED` (method `0`, zero decompression overhead on Android)
  - `allowZip64=False` (pure 32-bit classic ZIP; fails cleanly if payload exceeds 2 GiB)
  - `flag_bits = 0x0000` (Bit 3 Data Descriptor **disabled**)
  - `version_needed = 10` (`PKZIP 1.0`)
  - Exact `CRC-32`, `compressed_size`, and `uncompressed_size` written identically to both Local File Headers and the Central Directory
  - Atomic temporary-file write + `.sha256` generation
- Added [`scripts/verify_release_package.py`](../scripts/verify_release_package.py) verifying:
  - Binary EOCD / Central Directory / Local File Header parity (rejects Data Descriptors, ZIP64 `0x0001` extra fields, non-contiguous gaps, path traversal)
  - `java.util.zip.ZipFile` AND `java.util.zip.ZipInputStream` (asserting `getSize() >= 0`, `getCompressedSize() >= 0`, `getCrc() >= 0` immediately on `getNextEntry()`)
  - Python `zipfile.testzip()` and `unzip -t`
  - Internal APK integrity (`AndroidManifest.xml`, `classes.dex`, uncompressed `resources.arsc`, all 4 ABIs `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`), `aapt2`, and `apksigner`
- Updated [`.github/workflows/android.yml`](../.github/workflows/android.yml) so `publish-draft-release` attaches **both** the raw `.apk` (direct Android install with zero ZIP extraction) and the conservative `ZIP_STORED` `.zip` + `.sha256` checksums, and added `verify-debug-artifact` and `verify-release-artifact` round-trip jobs.

---

## Bug B — Debug APK Google Play Protect Investigation

### 1. Signing Certificate Inspection
- **Debug APK (`STAR-Launcher-v3.0.1-debug.apk` & `app_pojavlauncher-debug.apk`):**
  - Signer DN: `CN=Android Debug, O=Android, C=US`
  - Keystore: `app_pojavlauncher/debug.keystore` (committed publicly in git, created `2020-09-05`, valid through `2060-08-26`, serial `1`)
  - Algorithm: `SHA256withRSA`, **1024-bit RSA key** (`keytool` reports `weak`)
  - Schemes: v1 (`JAR`) + v2 (`APK Signature Scheme v2`), no v3/v3.1
  - SHA-256: `d0d0886a0d7e3486e6627f9b8011027fe3c6b0fb09424530b6d7be14f8c2cc33`
- **Historical `v3.0.0` note:** `app_pojavlauncher-release.apk` built for `v3.0.0` also fell back to this same `debug.keystore` SHA-256; `v3.0.1` removed that silent fallback (`app_pojavlauncher/build.gradle`) and enforces `STAR_*` production signing secrets in CI.

### 2. AndroidManifest & Permissions Audit
From `aapt2 dump badging` / `aapt2 dump xmltree`:
- `minSdkVersion=21`, `targetSdkVersion=34`, `compileSdkVersion=37`
- Permissions: `POST_NOTIFICATIONS`, `WRITE_EXTERNAL_STORAGE` (`maxSdkVersion=28`), `READ_EXTERNAL_STORAGE` (`maxSdkVersion=28`), `INTERNET`, `ACCESS_NETWORK_STATE`, `RECORD_AUDIO` (used for mic/voice features), `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `FOREGROUND_SERVICE_SPECIAL_USE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `VIBRATE`.
- Sensitive components:
  - `StarMediaNotificationListener` (`BIND_NOTIFICATION_LISTENER_SERVICE`, `exported=true` — Live Lyrics media session reader)
  - `FolderProvider` (`MANAGE_DOCUMENTS`, `exported=true`)
  - `ImportControlActivity` (`exported=true`, JSON/text `VIEW`/`SEND`)
  - Debug-only: `androidx.compose.ui.tooling.PreviewActivity` (`exported=true`, merged from `debugImplementation 'androidx.compose.ui:ui-tooling'`)

### 3. Native Libraries Inventory (110 `.so` entries)
- Core & hooks: `libpojavexec.so`, `libpojavexec_awt.so`, `libexithook.so`, `liblinkerhook.so`, `libandroidnsbypass.so`, `libbytehook.so`, `libglxshim.so`, `libawt_headless.so`, `libawt_xawt.so`
- Renderers & multimedia (`app_pojavlauncher/libs/*.aar` & `jniLibs/`): `libEGL_angle.so`, `libGLESv2_angle.so`, `libEGL_mesa.so`, `libglapi.so`, `libzink_dri.so`, `libvulkan_freedreno.so` (`arm64-v8a`), `libVkLayer_khronos_timeline_semaphore.so` (`arm64-v8a`), `libmobileglues.so`, `libSimpleFPEWrapper.so`, `libspirv-cross-c-shared.so`, `libSDL2.so`, `libSDL3.so`, `libopenal.so`, `libimgui-java.so`, `libzstd-jni-1.5.7-6-dhcompat.so`, `libunpack200.so`, `libc++_shared.so`, `libandroidx.graphics.path.so`
- Legacy/extra ABIs: `libtwitchsdk.so` (`armeabi-v7a` only, 5,096 B), `libjnidispatch.so` (`net.java.dev.jna:jna:5.14.0@aar` — includes `armeabi`, `mips`, `mips64` in addition to the 4 primary ABIs, causing `aapt2` to advertise 7 ABIs).

### 4. Dynamic Code Loading & Java Runtime Handling
- `androidnsbypass/src/main/cpp/nsbypass.c`: resolves unexported Bionic linker symbols (`__loader_*`), inspects ARM64 `BL` instructions (`find_branch_label`), and calls `mprotect(..., PROT_WRITE | PROT_READ | PROT_EXEC)` to strip BTI protections when creating isolated Vulkan namespaces.
- `native_hooks/` + `jvm_hooks/`: uses `bytehook` (`libbytehook.so`) to hook `dlopen`, `dlclose`, `dlsym`, `chmod`, `fchmod`, `exit`, and `RegisterNatives` to intercept `java.lang.UNIXProcess.forkAndExec` / `ProcessImpl.forkAndExec`.
- `MultiRTUtils.java` & `JREUtils.java`: extracts bundled OpenJDK JRE (`tar.xz`) into app-private `runtimes/`, `dlopen()`s `libjli.so` / `libjvm.so` and runtime `.so`s, and launches JVM with `-javaagent` JARs (`MioLibPatcher.jar`, `arc_dns_injector.jar`, `forge_installer.jar`).

### 5. Debug vs Release APK Comparison
| Property | Debug (`STAR-Launcher-v3.0.1-debug.apk`) | Release (`v3.0.0` local / `v3.0.1` CI) |
|---|---|---|
| Package ID | `com.star.launcher.debug` | `com.star.launcher` |
| `android:debuggable` | `true` | `false` (omitted) |
| Signing Key | `debug.keystore` (`CN=Android Debug`, 1024-bit RSA, public) | `STAR_*` private keystore (`v3.0.1+`) |
| `PreviewActivity` (`exported=true`) | Present (`ui-tooling`) | Absent |
| DEX layout | 21 unmerged `classes*.dex` + `DebugProbesKt.bin` | 3 merged `classes*.dex` + `baseline.prof` |
| Native `.so` stripping | Unstripped (`libpojavexec.so` larger) | Stripped |

### Conclusion for Bug B
Google Play Protect blocks the sideloaded Debug APK because it combines a **publicly leaked, weak 1024-bit `CN=Android Debug` certificate**, **`android:debuggable="true"`**, an **unrecognized `.debug` package ID**, and **high-sensitivity native hooking / `mprotect(RWX)` / runtime binary extraction heuristics**. End users must only install **production-signed Release APKs** (`com.star.launcher`), never Debug builds.

---

## Verification Scope & Environment Notes
- **Physical device note:** No Android device (including Nubia Neo) was attached over ADB in this workstation environment (`adb devices` returned an empty list); Android-side extraction compatibility was verified via `java.util.zip.ZipInputStream` + `java.util.zip.ZipFile` + `apksigner` + `aapt2` + binary ZIP header/CD validation. Physical Nubia Neo extraction and Play Protect behavior on the production-signed `v3.0.1` release artifact should be confirmed on-device before publishing the GitHub Release out of draft.
