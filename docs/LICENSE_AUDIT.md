# Third-Party Licenses, Prebuilt Binaries & Attribution Audit

**Date:** `2026-11-15`  
**Repository:** `STAR-LAUNCHER` (`com.star.launcher`, branch `v3_openjdk`)  
**Root License:** GNU Lesser General Public License v3.0 ([`LICENSE`](../LICENSE))

> **Compliance Notice:** STAR Launcher retains upstream attribution and notice files intact. Because several inherited binaries and dependencies below lack complete source provenance or contain non-standard/field-of-use license terms, **full downstream license compliance cannot be claimed until the unresolved items in Section 3 are resolved.**

---

## 1. Upstream Launcher Lineage & Core Submodules

| Component | Path / Origin | License / Notice Status |
|---|---|---|
| Root Launcher Application | `app_pojavlauncher/` (derived from PojavLauncher & AngelAuraMC Amethyst `v3_openjdk`) | **LGPL-3.0** (`LICENSE`); retains upstream notices and headers. |
| `androidnsbypass` | `androidnsbypass/` submodule | **BSD-2-Clause** (`androidnsbypass/LICENSE`, `androidnsbypass/LICENSE-BSD-2-Clause`). |
| `arc_dns_injector` | `arc_dns_injector/` | Java agent source present in repo under root project license unless otherwise noted. |
| `forge_installer` | `forge_installer/` (`git.artdeell.installer_agent`) | Java agent source present in repo; **flags `org.json:json:20230618`** (see Section 3). |
| `jre_lwjgl3glfw` | `jre_lwjgl3glfw/` (`lwjgl-3.3.3`, `lwjgl-3.4.1`) | Java GLFW compatibility bridge; references prebuilt `lwjgl-lwjglx.jar` (see Section 3). |
| `ANGLE` notice | `app_pojavlauncher/src/main/assets/licenses/ANGLE_LICENSE` | Preserved intact in assets. |
| Font notice | `app_pojavlauncher/src/main/assets/font/LICENSE_OFL.txt` | **SIL Open Font License** preserved intact. |

---

## 2. Maven / Gradle Dependencies Reviewed

- **AndroidX, Jetpack Compose BOM (`2025.08.00`), Room (`2.7.2`), Coroutines (`1.10.2`), Material 3:** Apache-2.0.
- **ByteHook (`1.0.10`), XZ (`1.8`), Commons Codec (`1.15`), JNA (`5.14.0`), Exp4j (`0.4.9-SNAPSHOT`):** Permissive (MIT / Apache-2.0 / Public Domain / LGPL-2.1).
- **Javassist (`3.29.2-GA` in `MioLibPatcher`):** MPL-1.1 / LGPL-2.1 / Apache-2.0 tri-license.
- **`org.json:json:20230618` (`forge_installer/build.gradle:11`):** **JSON License** — contains the field-of-use restriction *"The Software shall be used for Good, not Evil"*, which is non-FSF/non-OSI compatible and conflicts with copyleft redistribution unless replaced cleanly (e.g., with `gson` or Android `org.json` / Apache-2.0 clean-room JSON).

---

## 3. Unresolved Items Requiring Maintainer Action Before Broad Distribution

The following inherited C files, prebuilt archives, submodules, and dependencies require provenance verification, source restoration, or replacement:

| Item | Location | Finding / Risk | Recommended Action |
|---|---|---|---|
| **`bigcoreaffinity.c`** | `app_pojavlauncher/src/main/jni/bigcoreaffinity.c` | C source sets big-core affinity (`sys/syscall.h` / `sched_setaffinity`), inherited from upstream PojavLauncher/forks with no explicit SPDX/license header inside the file. | Verify upstream commit attribution and add an accurate copyright/license header or rewrite cleanly. |
| **`SimpleFPEWrapper-release.aar`** | `app_pojavlauncher/libs/SimpleFPEWrapper-release.aar` (8.45 MB) | Prebuilt binary AAR with no accompanying source tree or `LICENSE` file in `app_pojavlauncher/libs/` or `assets/licenses/`. | Obtain upstream source link + license text and bundle its required notice in `assets/licenses/`, or replace from source. |
| **`ExagearApacheCommons.jar`** | `app_pojavlauncher/libs/ExagearApacheCommons.jar` (474 KB) | Prebuilt repackaged JAR (`org.apache.commons` / ExaGear naming) with no standalone `NOTICE`/`LICENSE` or build source in repo. | Replace with standard Maven Central Apache Commons coordinates (`Apache-2.0`) or document exact source + Apache-2.0 `NOTICE`. |
| **`lwjgl-lwjglx.jar`** | Referenced in `jre_lwjgl3glfw/lwjgl-3.3.3/build.gradle` & `lwjgl-3.4.1/build.gradle` | Prebuilt compatibility bridge archive without accompanying source repository commit or license file in `STAR-LAUNCHER`. | Add upstream repository URL, commit pin, and BSD-3-Clause/MIT notice, or build from source. |
| **`libtwitchsdk.so`** | `app_pojavlauncher/src/main/jniLibs/armeabi-v7a/libtwitchsdk.so` (5,096 B) | Legacy stub shared library inherited from older PojavLauncher 32-bit compatibility paths without an accompanying license/notice file in `assets/licenses/`. | Confirm stub origin / MIT-or-LGPL reimplementation status and add notice file (or remove only after verifying older MC 1.7.10/1.8 compatibility does not `dlopen` it). |
| **`MioLibPatcher` & STAR overlay** | Submodule `MioLibPatcher/` (`AngelAuraMC/MioLibPatcher@9a1f807`) + `patches/miolibpatcher-star.patch` | Upstream `MioLibPatcher` repository root does not include a `LICENSE` file (`README.md` / `README_EN.md` only), and STAR adds 5 graphics/lifecycle transformers via `patches/miolibpatcher-star.patch`. | Confirm upstream license with `AngelAuraMC/MioLibPatcher` authors and mirror/fork or keep patch attribution explicit. |
| **`org.json:json:20230618`** | `forge_installer/build.gradle:11` | Field-of-use restriction (*"Good, not Evil"*). | Migrate `forge_installer` JSON parsing away from `org.json:json` (e.g. `com.google.code.gson:gson` or minimal clean-room parser). |

Other prebuilt `.aar` files in `app_pojavlauncher/libs/` (`angle-release.aar`, `imgui-java-release.aar`, `kopper-zink-release.aar`, `lwjgl-3.3.3-natives-release.aar`, `lwjgl-3.4.1-natives-release.aar`, `MobileGlues-release.aar`, `openal-soft-release.aar`, `SDL-release.aar`, `spirv-cross-natives.aar`, `zstd-jni-release.aar`) and `arm64-v8a/libvulkan_freedreno.so` / `libVkLayer_khronos_timeline_semaphore.so` / `libunpack200.so` likewise require keeping pinned upstream source tags & license notices synced under `assets/licenses/`.

---

## 4. Repository Preservation Rules

1. Do **not** strip upstream copyright notices, headers, `ANGLE_LICENSE`, or `LICENSE_OFL.txt`.
2. Keep STAR Launcher's original Kotlin/Compose UI, HUD (`StarTouchHudOverlay`), and Live Lyrics assets clearly distinct from upstream modules.
3. Exclude legacy upstream `aamc_upload.jks` from STAR production signing (`STAR_*` credentials only).
