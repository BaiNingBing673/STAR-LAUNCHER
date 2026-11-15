# License & Dependency Audit (`M0.1` / `M0.2`)

> **Scope**: Root repository, Git submodules, Gradle dependencies, prebuilt `.aar`/`.jar`/`.so` binaries, and reference repository boundary.

---

## 1. Root & Reference Licenses

| Project | License | Notes |
|---|---|---|
| **Amethyst-Android** (`LICENSE`) | **GNU Lesser General Public License v3.0 (LGPLv3)** | Upstream fork of PojavLauncher (`v3_openjdk`) |
| **PojavLauncher** (Upstream ancestor, `about_en.txt` L4) | **GNU GPLv3** / LGPLv3 | Note: `about_en.txt` still states GNU GPLv3 while root `LICENSE` is LGPLv3 |
| **ZalithLauncher2** (External Reference Only) | **GNU GPLv3** | **Do NOT copy source code** into STAR Launcher |

---

## 2. Submodules & `.gitmodules` Discrepancy

`git ls-files --stage` shows **only 2 active Git submodules** (`160000` gitlinks):
1. **`MioLibPatcher`** (`https://github.com/AngelAuraMC/MioLibPatcher.git`, commit `9a1f807f`): **NO `LICENSE` file** in the submodule repository. Runtime dependency `org.javassist:javassist:3.29.2-GA` (triple-licensed MPL 1.1 / LGPL 2.1 / Apache 2.0).
2. **`androidnsbypass`** (`https://github.com/alexytomi/androidnsbypass.git`, commit `9f57982a`): Dual-notice **`MIT`** (`LICENSE`, Copyright 2026 alexytomi) + **`BSD-2-Clause`** (`LICENSE-BSD-2-Clause`, Copyright 2021 Billy Laws).

**Orphaned `.gitmodules` Entries**:
`.gitmodules` (L1–L9) declares `MobileGlues`, `SDL`, and `sdl2-compat`, **none of which have gitlink entries in the Git index**. Instead, these are consumed as prebuilt `.aar` binaries inside `app_pojavlauncher/libs/`.

---

## 3. Gradle Maven / JitPack Dependencies (`app_pojavlauncher/build.gradle` & `forge_installer/build.gradle`)

| Coordinate | Version | License | Notes |
|---|---|---|---|
| `javax.annotation:javax.annotation-api` | `1.3.2` | CDDL-1.0 / GPLv2+CE | Compile/runtime annotations |
| `commons-codec:commons-codec` | `1.15` | Apache-2.0 | Hashing & encoding |
| `androidx.preference:preference` | `1.2.0` | Apache-2.0 | XML preference UI |
| `androidx.drawerlayout:drawerlayout` | `1.2.0` | Apache-2.0 | In-game drawer |
| `androidx.viewpager2:viewpager2` | `1.1.0-beta01` | Apache-2.0 | Pager UI |
| `androidx.annotation:annotation` | `1.5.0` | Apache-2.0 | AndroidX annotations |
| `androidx.constraintlayout:constraintlayout` | `2.1.4` | Apache-2.0 | XML layout constraints |
| `com.github.duanhong169:checkerboarddrawable` | `1.0.2` | Apache-2.0 | Color picker UI |
| `com.github.PojavLauncherTeam:portrait-sdp` | `ed33e89cbc` | MIT / Apache-2.0 (verify) | JitPack commit pin |
| `com.github.PojavLauncherTeam:portrait-ssp` | `6c02fd739b` | MIT / Apache-2.0 (verify) | JitPack commit pin |
| `com.github.Mathias-Boulay:ExtendedView` | `1.0.0` | Apache-2.0 / MIT (verify) | Custom control views |
| `com.github.Mathias-Boulay:android_gamepad_remapper` | `2.0.3` | LGPLv3 / Apache-2.0 (verify) | Gamepad remapper |
| `com.github.Mathias-Boulay:virtual-joystick-android` | `1.14` | Apache-2.0 / MIT (verify) | On-screen joystick |
| `org.tukaani:xz` | `1.8` | Public Domain (0BSD/Unlicense) | `.tar.xz` JRE decompression |
| `net.sourceforge.htmlcleaner:htmlcleaner` | `2.6.1` | BSD-3-Clause | HTML parsing |
| `com.bytedance:bytehook` | `1.0.10` | **MIT** | Native PLT hooking (`libbytehook.so`); lacks x86/x86_64 prefab |
| `top.fifthlight.touchcontroller:proxy-client-android` | `0.0.4` | LGPLv3 / MIT (verify) | TouchController proxy client |
| `net.java.dev.jna:jna` | `5.14.0@aar` | LGPL-2.1+ / Apache-2.0 | JNA native bridge |
| `org.json:json` (`forge_installer`) | `20230618` | JSON License ("Good, not Evil") | **Note**: JSON License is FSF-classified non-free; consider replacing in future cleanup if strict FSF compliance is needed |

---

## 4. Bundled Binaries (`app_pojavlauncher/libs/`, `jniLibs/`, `assets/`)

| Artifact | Embedded License File / Origin | License | Risk / Action |
|---|---|---|---|
| `MobileGlues-release.aar` | `mobileglues-lgplv2.1-license.txt` | **LGPL-2.1** | Compatible; retain notice & source link |
| `SDL-release.aar` | `sdl2-compat-zlib-license.txt`, `sdl3-zlib-license.txt` | **zlib** | Compatible; retain notices |
| `angle-release.aar` | `angle-arr-license.txt` & `assets/licenses/ANGLE_LICENSE` | **BSD-3-Clause** (labeled ARR in `README.md` due to line 2 wording) | Retain ANGLE copyright & BSD-3 notice |
| `imgui-java-release.aar` | `imgui-java-mit-license.txt` | **MIT** | Retain notice |
| `kopper-zink-release.aar` | `mesa-licenses.rst` | **MIT / X11 / Khronos (Mesa)** | Retain Mesa license notice |
| `lwjgl-3.3.3-natives-release.aar`, `lwjgl-3.4.1-natives-release.aar` | `blendish_license.txt`, `freetype_license.txt`, `glfw_license.txt` (+ LWJGL BSD-3) | **BSD-3 / MIT / FTL / zlib** | Retain notices |
| `openal-soft-release.aar` | `OPENAL-SOFT_GPL2` *(actually **LGPL v2**, mislabeled GPLv2 in `README.md`)*, `OBOE_APACHE2`, `PFFFT_LICENSE` | **LGPL-2.0 + Apache-2.0 + BSD-like (FFTPACK)** | Fix mislabeling in STAR notices; LGPLv2 is compatible |
| `zstd-jni-release.aar` | `zstd-gplv2-license.txt` + `zstd-jni-bsd2-license.txt` | **BSD-2-Clause** (JNI) + **BSD-3 / GPLv2 dual** (zstd) | Document explicit election of BSD-3 for zstd under dual license |
| `SimpleFPEWrapper-release.aar` | **NONE in AAR** | **UNRESOLVED** | **Flagged**: Identify upstream repo/license before public distribution |
| `spirv-cross-natives.aar` | **NONE in AAR** | Apache-2.0 / MIT (KhronosGroup/SPIRV-Cross upstream), but **missing in AAR** | Bundle Apache-2.0/MIT notice explicitly |
| `ExagearApacheCommons.jar` | **NONE in JAR** | Apache-2.0 (expected), **unverified binary provenance** | Audit contents & bundle Apache-2.0 `NOTICE`/`LICENSE` or replace with Maven Commons |
| `exp4j-0.4.9-SNAPSHOT.jar` | Fork at `PojavLauncherTeam/exp4j` | Apache-2.0 | Retain notice |
| `gson-2.8.6.jar` | Google Gson | Apache-2.0 | Retain notice |
| `libvulkan_freedreno.so` + `libVkLayer_khronos_timeline_semaphore.so` | Mesa Turnip / Khronos | MIT / Apache-2.0 | Retain Mesa & Khronos notices |
| `libunpack200.so` / OpenJDK JREs | OpenJDK | **GPLv2 + Classpath Exception** | Source offer / upstream repo link required on redistribution |
| `libtwitchsdk.so` | Legacy stub (`armeabi-v7a`) | **UNRESOLVED** | Audit origin or remove if obsolete |
| `caciocavallo` / `caciocavallo17` | OpenJDK Caciocavallo fork | **GPLv2 + Classpath Exception** | Retain attribution & source pointer |
| `lwjgl-lwjglx.jar` | `AngelAuraMC/lwjglx` (`README.md` L142: *"unknown license"*) | **UNRESOLVED** | **Flagged**: Must audit upstream `lwjglx` license before release |

---

## 5. Critical License Concerns Requiring Action Before Public Distribution

1. **`bigcoreaffinity.c` GPLv3 Conflict (`app_pojavlauncher/src/main/jni/bigcoreaffinity.c:5`)**:
   - Line 5 states `#define _GNU_SOURCE // we are GNU GPLv3`, and this file is compiled directly into `libpojavexec.so`, while the repository root `LICENSE` is **GNU LGPLv3**.
   - **Impact**: Linking GPLv3 code directly into `libpojavexec.so` may obligate the combined binary/app to **GPLv3** unless upstream relicenses or separates it. Must be verified with legal/upstream review before deciding STAR's distribution license (LGPLv3 vs GPLv3).
2. **Missing Provenance & Unresolved Binaries**:
   - `SimpleFPEWrapper-release.aar`, `ExagearApacheCommons.jar`, `lwjgl-lwjglx.jar`, `MioLibPatcher`, `forge_installer`, `arc_dns_injector`, and `libtwitchsdk.so` lack clear in-repo license texts.
3. **`org.json:json` License (`forge_installer`)**:
   - Uses the JSON License ("The Software shall be used for Good, not Evil"), which is incompatible with strict GPL/FSF distributions if packaged into the APK without review.
4. **Source-Offer & Attribution Requirements**:
   - Distributing an APK built from LGPLv3 / LGPLv2.1 (`MobileGlues`) / LGPLv2 (`OpenAL-Soft`) / GPLv2+CE (`OpenJDK`, `Caciocavallo`) requires preserving all upstream copyright notices, including full license texts in-app (About/Licenses screen), and providing matching source-code access for all copyleft components.
5. **Preservation Rule**: Keep all existing copyright headers, `about_*.txt`, and `assets/licenses/*` intact.
