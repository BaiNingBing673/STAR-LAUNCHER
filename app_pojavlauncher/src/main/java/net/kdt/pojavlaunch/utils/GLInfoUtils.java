package net.kdt.pojavlaunch.utils;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.util.Log;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GLInfoUtils {
    private static final String TAG = "GLInfoUtils";
    public static final String GLES_VERSION_PREFIX = "OpenGL ES ";
    private static final Pattern MAJOR_VERSION_PATTERN = Pattern.compile("(\\d+)\\.(\\d+)");
    private static final int EGL_OPENGL_ES3_BIT_KHR = 0x0040;

    private static volatile GLInfo info;
    private static volatile boolean probeSucceeded = false;

    /**
     * Parses the major OpenGL ES version safely from vendor version strings such as
     * "OpenGL ES 3.2 v1.r38p1-01eac0..." or "OpenGL ES-CM 1.1".
     */
    public static int parseMajorGLVersion(String versionString) {
        if (versionString == null || versionString.trim().isEmpty()) {
            return 2;
        }
        String candidate = versionString.trim();
        if (candidate.startsWith(GLES_VERSION_PREFIX)) {
            candidate = candidate.substring(GLES_VERSION_PREFIX.length()).trim();
        }
        Matcher matcher = MAJOR_VERSION_PATTERN.matcher(candidate);
        if (matcher.find()) {
            try {
                int parsed = Integer.parseInt(matcher.group(1));
                if (parsed >= 1 && parsed <= 10) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // Fall through to safe default
            }
        }
        return 2;
    }

    private static GLInfo queryInfo(int contextGLVersion) {
        String vendor = GLES20.glGetString(GLES20.GL_VENDOR);
        String renderer = GLES20.glGetString(GLES20.GL_RENDERER);
        String versionString = GLES20.glGetString(GLES30.GL_VERSION);
        if (vendor == null || vendor.trim().isEmpty()) vendor = "<Unknown>";
        if (renderer == null || renderer.trim().isEmpty()) renderer = "<Unknown>";
        int version = parseMajorGLVersion(versionString);
        // LTW and MobileGlues depend on the ability to create a context with a major version of 3;
        // even if the string reports 3 while EGL can only create 2, clamp to the actual context version.
        version = Math.min(version, contextGLVersion);
        return new GLInfo(vendor, renderer, version);
    }

    private static GLInfo createDummyInfo() {
        Log.e(TAG, "An error happened during EGL/GL info query. Using conservative GLES 2 fallback info.");
        return new GLInfo("<Unknown>", "<Unknown>", 2);
    }

    private static EGLContext tryCreateContext(EGLDisplay eglDisplay, EGLConfig config, int majorVersion) {
        int[] eglContextAttributes = new int[] { EGL14.EGL_CONTEXT_CLIENT_VERSION, majorVersion, EGL14.EGL_NONE };
        EGLContext context = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, eglContextAttributes, 0);
        if (context == EGL14.EGL_NO_CONTEXT || context == null) {
            Log.i(TAG, "Failed to create EGL context with major version " + majorVersion);
            return null;
        }
        return context;
    }

    private static EGLContext tryMakeCurrent(EGLDisplay eglDisplay, EGLConfig config, EGLSurface surface, int majorVersion) {
        EGLContext context = tryCreateContext(eglDisplay, config, majorVersion);
        if (context == null) return null;
        // Some Mali / OEM drivers let us create a GLES 3 context but reject eglMakeCurrent.
        boolean makeCurrentResult = EGL14.eglMakeCurrent(eglDisplay, surface, surface, context);
        if (!makeCurrentResult) {
            Log.i(TAG, "Failed to make context GL version " + majorVersion + " current");
            EGL14.eglDestroyContext(eglDisplay, context);
            return null;
        }
        return context;
    }

    /**
     * Tries progressively more permissive EGL pbuffer configs so Mali / UNISOC / PowerVR / OEM drivers
     * that do not expose 24-bit depth pbuffer configs still probe accurately.
     */
    private static EGLConfig choosePbufferConfig(EGLDisplay eglDisplay) {
        int[][] candidateAttributes = new int[][] {
                // Tier 1: Preferred RGBA8 + 24-bit depth + ES3/ES2
                new int[] {
                        EGL14.EGL_BLUE_SIZE, 8,
                        EGL14.EGL_GREEN_SIZE, 8,
                        EGL14.EGL_RED_SIZE, 8,
                        EGL14.EGL_ALPHA_SIZE, 8,
                        EGL14.EGL_DEPTH_SIZE, 24,
                        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                        EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                        EGL14.EGL_NONE
                },
                // Tier 2: RGBA8 + 16-bit depth (common on Mali-G57 / UNISOC pbuffers)
                new int[] {
                        EGL14.EGL_BLUE_SIZE, 8,
                        EGL14.EGL_GREEN_SIZE, 8,
                        EGL14.EGL_RED_SIZE, 8,
                        EGL14.EGL_ALPHA_SIZE, 8,
                        EGL14.EGL_DEPTH_SIZE, 16,
                        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                        EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                        EGL14.EGL_NONE
                },
                // Tier 3: RGBA8 without depth requirement (ES3 KHR bit)
                new int[] {
                        EGL14.EGL_BLUE_SIZE, 8,
                        EGL14.EGL_GREEN_SIZE, 8,
                        EGL14.EGL_RED_SIZE, 8,
                        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                        EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
                        EGL14.EGL_NONE
                },
                // Tier 4: Minimal pbuffer config compatible with any GLES 2+ driver
                new int[] {
                        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                        EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                        EGL14.EGL_NONE
                }
        };

        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[] { 0 };
        for (int[] attrs : candidateAttributes) {
            numConfigs[0] = 0;
            configs[0] = null;
            if (EGL14.eglChooseConfig(eglDisplay, attrs, 0, configs, 0, 1, numConfigs, 0)
                    && numConfigs[0] > 0 && configs[0] != null) {
                return configs[0];
            }
        }
        return null;
    }

    private static GLInfo initAndQueryInfo() {
        int[] eglVersion = new int[2];
        EGLDisplay eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == null || eglDisplay == EGL14.EGL_NO_DISPLAY
                || !EGL14.eglInitialize(eglDisplay, eglVersion, 0, eglVersion, 1)) {
            Log.e(TAG, "Failed to initialize EGL default display");
            return null;
        }

        EGLSurface surface = EGL14.EGL_NO_SURFACE;
        EGLContext context = EGL14.EGL_NO_CONTEXT;
        try {
            EGLConfig config = choosePbufferConfig(eglDisplay);
            if (config == null) {
                Log.e(TAG, "Failed to choose an EGL pbuffer config across all fallback tiers");
                return null;
            }

            int[] pbufferAttributes = new int[] {
                    EGL14.EGL_WIDTH, 16,
                    EGL14.EGL_HEIGHT, 16,
                    EGL14.EGL_NONE
            };
            surface = EGL14.eglCreatePbufferSurface(eglDisplay, config, pbufferAttributes, 0);
            if (surface == null || surface == EGL14.EGL_NO_SURFACE) {
                Log.e(TAG, "Failed to create EGL pbuffer surface");
                return null;
            }

            int contextGLVersion = 3;
            context = tryMakeCurrent(eglDisplay, config, surface, contextGLVersion);
            if (context == null) {
                contextGLVersion = 2;
                context = tryMakeCurrent(eglDisplay, config, surface, contextGLVersion);
            }
            if (context == null) {
                Log.e(TAG, "Failed to create and make GLES 3/2 context current");
                return null;
            }

            return queryInfo(contextGLVersion);
        } finally {
            try {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            } catch (Throwable ignored) {}
            if (context != null && context != EGL14.EGL_NO_CONTEXT) {
                try {
                    EGL14.eglDestroyContext(eglDisplay, context);
                } catch (Throwable ignored) {}
            }
            if (surface != null && surface != EGL14.EGL_NO_SURFACE) {
                try {
                    EGL14.eglDestroySurface(eglDisplay, surface);
                } catch (Throwable ignored) {}
            }
            try {
                EGL14.eglTerminate(eglDisplay);
            } catch (Throwable ignored) {}
        }
    }

    /**
     * Get the information about the current OpenGL ES device, which consists of the vendor,
     * the renderer and the major GLES version.
     */
    public static GLInfo getGlInfo() {
        GLInfo cached = info;
        if (cached != null && probeSucceeded) return cached;
        synchronized (GLInfoUtils.class) {
            if (info != null && probeSucceeded) return info;
            Log.i(TAG, "Querying graphics device info...");
            GLInfo queried = null;
            try {
                queried = initAndQueryInfo();
            } catch (Throwable e) {
                Log.e(TAG, "Throwable when trying to initialize GL info", e);
            }
            if (queried != null) {
                info = queried;
                probeSucceeded = true;
                return queried;
            }
            GLInfo fallback = createDummyInfo();
            info = fallback;
            return fallback;
        }
    }

    public static synchronized void resetCacheForTesting() {
        info = null;
        probeSucceeded = false;
    }

    public static class GLInfo {
        public final String vendor;
        public final String renderer;
        public final int glesMajorVersion;

        public GLInfo(String vendor, String renderer, int glesMajorVersion) {
            this.vendor = vendor != null ? vendor : "<Unknown>";
            this.renderer = renderer != null ? renderer : "<Unknown>";
            this.glesMajorVersion = glesMajorVersion;
        }

        /**
         * Check if this GLInfo belongs to a Qualcomm Adreno graphics adapter.
         */
        public boolean isAdreno() {
            return renderer != null && vendor != null
                    && renderer.contains("Adreno")
                    && vendor.equalsIgnoreCase("Qualcomm");
        }

        /**
         * Check if this GLInfo belongs to an ARM Mali / Immortalis graphics adapter.
         */
        public boolean isMali() {
            String r = renderer != null ? renderer.toLowerCase(java.util.Locale.ROOT) : "";
            String v = vendor != null ? vendor.toLowerCase(java.util.Locale.ROOT) : "";
            return r.contains("mali") || v.contains("arm");
        }
    }
}
