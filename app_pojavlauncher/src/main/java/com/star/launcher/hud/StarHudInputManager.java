package com.star.launcher.hud;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.LwjglGlfwKeycode;

import org.lwjgl.glfw.CallbackBridge;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Centralized reference-counted input state manager for STAR Launcher.
 *
 * Guarantees symmetric press/release lifecycles across multi-touch pointers, overlapping
 * controls, tap vs hold vs toggle buttons, world gestures, and Android lifecycle/surface events.
 * Logical keys and mouse buttons are released ONLY when the last active lease releases them,
 * or unconditionally when {@link #releaseAllInputs(String)} is triggered.
 */
public final class StarHudInputManager {
    private static final String TAG = "StarHudInputManager";
    private static final int MAX_RECENT_EVENTS = 16;
    private static final long MISMATCH_LOG_INTERVAL_MS = 1000L;

    public interface ResetParticipant {
        void onGlobalInputReset(@NonNull String reason);
    }

    public interface TelemetryListener {
        void onInputStateChanged();
    }

    public static final class LeaseInfo {
        public final String leaseId;
        public final String buttonId;
        public final int pointerId;
        public final String actionLabel;
        public final boolean isMouse;
        public final int code;
        public final boolean applyModifiers;
        public final long acquiredAtMs;

        LeaseInfo(
                String leaseId,
                String buttonId,
                int pointerId,
                String actionLabel,
                boolean isMouse,
                int code,
                boolean applyModifiers,
                long acquiredAtMs
        ) {
            this.leaseId = leaseId;
            this.buttonId = buttonId;
            this.pointerId = pointerId;
            this.actionLabel = actionLabel;
            this.isMouse = isMouse;
            this.code = code;
            this.applyModifiers = applyModifiers;
            this.acquiredAtMs = acquiredAtMs;
        }
    }

    public static final class DebugSnapshot {
        public final List<LeaseInfo> activeLeases;
        public final Map<Integer, Integer> pressedKeyRefCounts;
        public final Map<Integer, Integer> pressedMouseRefCounts;
        public final List<String> recentEvents;
        public final String lastEvent;
        public final int mismatchCount;
        public final int bridgeErrorCount;
        public final boolean isGrabbing;

        DebugSnapshot(
                List<LeaseInfo> activeLeases,
                Map<Integer, Integer> pressedKeyRefCounts,
                Map<Integer, Integer> pressedMouseRefCounts,
                List<String> recentEvents,
                String lastEvent,
                int mismatchCount,
                int bridgeErrorCount,
                boolean isGrabbing
        ) {
            this.activeLeases = activeLeases;
            this.pressedKeyRefCounts = pressedKeyRefCounts;
            this.pressedMouseRefCounts = pressedMouseRefCounts;
            this.recentEvents = recentEvents;
            this.lastEvent = lastEvent;
            this.mismatchCount = mismatchCount;
            this.bridgeErrorCount = bridgeErrorCount;
            this.isGrabbing = isGrabbing;
        }
    }

    private static final StarHudInputManager INSTANCE = new StarHudInputManager();

    public static StarHudInputManager getInstance() {
        return INSTANCE;
    }

    private final Object mLock = new Object();
    private final Map<String, LeaseInfo> mLeasesById = new LinkedHashMap<>();
    private final Map<Integer, Set<String>> mKeyOwners = new HashMap<>();
    private final Map<Integer, Boolean> mKeyApplyMods = new HashMap<>();
    private final Map<Integer, Set<String>> mMouseOwners = new HashMap<>();
    private final Deque<String> mRecentEvents = new ArrayDeque<>(MAX_RECENT_EVENTS);
    private final CopyOnWriteArrayList<ResetParticipant> mResetParticipants = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<TelemetryListener> mTelemetryListeners = new CopyOnWriteArrayList<>();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    @NonNull
    private volatile StarHudInputBridge mBridge = StarHudInputBridge.DEFAULT;
    private String mLastEvent = "idle";
    private int mMismatchCount = 0;
    private int mBridgeErrorCount = 0;
    private long mLastMismatchLogMs = 0L;

    private StarHudInputManager() {}

    public void setInputBridge(@NonNull StarHudInputBridge bridge) {
        synchronized (mLock) {
            mBridge = bridge;
        }
    }

    @NonNull
    public StarHudInputBridge getInputBridge() {
        return mBridge;
    }

    public void registerResetParticipant(@NonNull ResetParticipant participant) {
        if (!mResetParticipants.contains(participant)) {
            mResetParticipants.add(participant);
        }
    }

    public void unregisterResetParticipant(@NonNull ResetParticipant participant) {
        mResetParticipants.remove(participant);
    }

    public void addTelemetryListener(@NonNull TelemetryListener listener) {
        if (!mTelemetryListeners.contains(listener)) {
            mTelemetryListeners.add(listener);
        }
    }

    public void removeTelemetryListener(@NonNull TelemetryListener listener) {
        mTelemetryListeners.remove(listener);
    }

    public boolean acquireKeyLease(
            @NonNull String leaseId,
            @NonNull String buttonId,
            int pointerId,
            @NonNull String actionLabel,
            int keycode,
            boolean applyModifiers
    ) {
        if (keycode <= 0) {
            recordMismatch("Invalid keycode " + keycode + " from " + buttonId);
            return false;
        }
        boolean shouldSendDown = false;
        boolean bridgeSuccess = true;
        synchronized (mLock) {
            LeaseInfo existing = mLeasesById.get(leaseId);
            if (existing != null) {
                if (!existing.isMouse && existing.code == keycode) {
                    return true;
                }
                releaseLeaseLocked(leaseId, "reacquire");
            }

            Set<String> owners = mKeyOwners.get(keycode);
            if (owners == null) {
                owners = new LinkedHashSet<>();
                mKeyOwners.put(keycode, owners);
            }
            shouldSendDown = owners.isEmpty();
            LeaseInfo info = new LeaseInfo(
                    leaseId, buttonId, pointerId, actionLabel, false, keycode, applyModifiers, System.currentTimeMillis()
            );
            mLeasesById.put(leaseId, info);
            owners.add(leaseId);
            if (applyModifiers) {
                mKeyApplyMods.put(keycode, Boolean.TRUE);
            }
            recordEventLocked("DOWN KEY " + formatKeycode(keycode) + " [" + buttonId + " p=" + pointerId + "]");
        }

        if (shouldSendDown) {
            bridgeSuccess = dispatchKeySafe(keycode, true, applyModifiers, leaseId);
            if (!bridgeSuccess) {
                releaseLease(leaseId, "bridge_failure_rollback");
            }
        }
        notifyTelemetry();
        return bridgeSuccess;
    }

    public boolean acquireMouseLease(
            @NonNull String leaseId,
            @NonNull String buttonId,
            int pointerId,
            @NonNull String actionLabel,
            int mouseButton
    ) {
        if (mouseButton < 0) {
            recordMismatch("Invalid mouseButton " + mouseButton + " from " + buttonId);
            return false;
        }
        boolean shouldSendDown = false;
        boolean bridgeSuccess = true;
        synchronized (mLock) {
            LeaseInfo existing = mLeasesById.get(leaseId);
            if (existing != null) {
                if (existing.isMouse && existing.code == mouseButton) {
                    return true;
                }
                releaseLeaseLocked(leaseId, "reacquire");
            }

            Set<String> owners = mMouseOwners.get(mouseButton);
            if (owners == null) {
                owners = new LinkedHashSet<>();
                mMouseOwners.put(mouseButton, owners);
            }
            shouldSendDown = owners.isEmpty();
            LeaseInfo info = new LeaseInfo(
                    leaseId, buttonId, pointerId, actionLabel, true, mouseButton, false, System.currentTimeMillis()
            );
            mLeasesById.put(leaseId, info);
            owners.add(leaseId);
            recordEventLocked("DOWN MOUSE " + formatMouseButton(mouseButton) + " [" + buttonId + " p=" + pointerId + "]");
        }

        if (shouldSendDown) {
            bridgeSuccess = dispatchMouseSafe(mouseButton, true, leaseId);
            if (!bridgeSuccess) {
                releaseLease(leaseId, "bridge_failure_rollback");
            }
        }
        notifyTelemetry();
        return bridgeSuccess;
    }

    public void releaseLease(@NonNull String leaseId, @NonNull String reason) {
        LeaseInfo removed;
        boolean shouldSendUp = false;
        boolean isMouse = false;
        int code = 0;
        boolean applyMods = false;

        synchronized (mLock) {
            removed = mLeasesById.remove(leaseId);
            if (removed == null) {
                if (!"idle_release".equals(reason) && !"up_no_lease".equals(reason)) {
                    recordMismatch("Release without active lease: " + leaseId + " (" + reason + ")");
                }
                return;
            }
            isMouse = removed.isMouse;
            code = removed.code;
            applyMods = removed.applyModifiers || Boolean.TRUE.equals(mKeyApplyMods.get(code));
            if (isMouse) {
                Set<String> owners = mMouseOwners.get(code);
                if (owners != null) {
                    owners.remove(leaseId);
                    if (owners.isEmpty()) {
                        mMouseOwners.remove(code);
                        shouldSendUp = true;
                    }
                }
                recordEventLocked("UP MOUSE " + formatMouseButton(code) + " [" + removed.buttonId + " " + reason + "]");
            } else {
                Set<String> owners = mKeyOwners.get(code);
                if (owners != null) {
                    owners.remove(leaseId);
                    if (owners.isEmpty()) {
                        mKeyOwners.remove(code);
                        mKeyApplyMods.remove(code);
                        shouldSendUp = true;
                    }
                }
                recordEventLocked("UP KEY " + formatKeycode(code) + " [" + removed.buttonId + " " + reason + "]");
            }
        }

        if (shouldSendUp) {
            if (isMouse) {
                dispatchMouseSafe(code, false, leaseId);
            } else {
                dispatchKeySafe(code, false, applyMods, leaseId);
            }
        }
        notifyTelemetry();
    }

    public void tapKey(
            @NonNull String buttonId,
            int pointerId,
            @NonNull String actionLabel,
            int keycode,
            boolean applyModifiers
    ) {
        if (keycode <= 0) {
            recordMismatch("Tap with invalid keycode " + keycode + " from " + buttonId);
            return;
        }
        boolean alreadyHeld;
        synchronized (mLock) {
            Set<String> owners = mKeyOwners.get(keycode);
            alreadyHeld = owners != null && !owners.isEmpty();
            recordEventLocked("TAP KEY " + formatKeycode(keycode) + " [" + buttonId + " p=" + pointerId + "]");
        }
        if (alreadyHeld) {
            // Do not fight an active held lease; leave release responsibility with lease owner.
            notifyTelemetry();
            return;
        }
        try {
            mBridge.sendKey(keycode, true, applyModifiers);
        } catch (Throwable t) {
            handleBridgeException("tapKey DOWN " + keycode, t);
        } finally {
            try {
                mBridge.sendKey(keycode, false, applyModifiers);
            } catch (Throwable t) {
                handleBridgeException("tapKey UP " + keycode, t);
            }
        }
        notifyTelemetry();
    }

    public void tapMouseButton(
            @NonNull String buttonId,
            int pointerId,
            @NonNull String actionLabel,
            int mouseButton
    ) {
        if (mouseButton < 0) {
            recordMismatch("Tap with invalid mouseButton " + mouseButton + " from " + buttonId);
            return;
        }
        boolean alreadyHeld;
        synchronized (mLock) {
            Set<String> owners = mMouseOwners.get(mouseButton);
            alreadyHeld = owners != null && !owners.isEmpty();
            recordEventLocked("TAP MOUSE " + formatMouseButton(mouseButton) + " [" + buttonId + " p=" + pointerId + "]");
        }
        if (alreadyHeld) {
            notifyTelemetry();
            return;
        }
        try {
            mBridge.sendMouseButton(mouseButton, true);
        } catch (Throwable t) {
            handleBridgeException("tapMouse DOWN " + mouseButton, t);
        } finally {
            try {
                mBridge.sendMouseButton(mouseButton, false);
            } catch (Throwable t) {
                handleBridgeException("tapMouse UP " + mouseButton, t);
            }
        }
        notifyTelemetry();
    }

    public void dispatchScroll(@NonNull String buttonId, int pointerId, float yOffset) {
        synchronized (mLock) {
            recordEventLocked("SCROLL y=" + yOffset + " [" + buttonId + " p=" + pointerId + "]");
        }
        try {
            mBridge.sendScroll(0f, yOffset);
        } catch (Throwable t) {
            handleBridgeException("dispatchScroll y=" + yOffset, t);
        }
        notifyTelemetry();
    }

    /**
     * Force-releases all held keys, mouse buttons, pointer leases, and registered UI states.
     * Safe to invoke from any thread and re-entrant across lifecycle transitions.
     */
    public void releaseAllInputs(@NonNull String reason) {
        List<Integer> keysToRelease = new ArrayList<>();
        List<Boolean> keyMods = new ArrayList<>();
        List<Integer> mouseToRelease = new ArrayList<>();

        synchronized (mLock) {
            for (Integer keycode : mKeyOwners.keySet()) {
                keysToRelease.add(keycode);
                keyMods.add(Boolean.TRUE.equals(mKeyApplyMods.get(keycode)));
            }
            for (Integer btn : mMouseOwners.keySet()) {
                mouseToRelease.add(btn);
            }
            mLeasesById.clear();
            mKeyOwners.clear();
            mKeyApplyMods.clear();
            mMouseOwners.clear();
            recordEventLocked("RESET ALL (" + reason + ")");
        }

        for (int i = 0; i < keysToRelease.size(); i++) {
            dispatchKeySafe(keysToRelease.get(i), false, keyMods.get(i), "resetAll:" + reason);
        }
        for (int i = 0; i < mouseToRelease.size(); i++) {
            dispatchMouseSafe(mouseToRelease.get(i), false, "resetAll:" + reason);
        }
        // Clear any stray modifier latches safely
        try {
            CallbackBridge.holdingShift = false;
            CallbackBridge.holdingCtrl = false;
            CallbackBridge.holdingAlt = false;
        } catch (Throwable ignored) {
        }

        for (ResetParticipant participant : mResetParticipants) {
            try {
                participant.onGlobalInputReset(reason);
            } catch (Throwable t) {
                Log.w(TAG, "ResetParticipant threw during releaseAllInputs(" + reason + ")", t);
            }
        }
        notifyTelemetry();
    }

    public boolean isKeyHeld(int keycode) {
        synchronized (mLock) {
            Set<String> owners = mKeyOwners.get(keycode);
            return owners != null && !owners.isEmpty();
        }
    }

    public boolean isMouseButtonHeld(int mouseButton) {
        synchronized (mLock) {
            Set<String> owners = mMouseOwners.get(mouseButton);
            return owners != null && !owners.isEmpty();
        }
    }

    public int getActiveLeaseCount() {
        synchronized (mLock) {
            return mLeasesById.size();
        }
    }

    @NonNull
    public DebugSnapshot getDebugSnapshot() {
        synchronized (mLock) {
            List<LeaseInfo> leases = new ArrayList<>(mLeasesById.values());
            Map<Integer, Integer> keyCounts = new LinkedHashMap<>();
            for (Map.Entry<Integer, Set<String>> e : mKeyOwners.entrySet()) {
                keyCounts.put(e.getKey(), e.getValue().size());
            }
            Map<Integer, Integer> mouseCounts = new LinkedHashMap<>();
            for (Map.Entry<Integer, Set<String>> e : mMouseOwners.entrySet()) {
                mouseCounts.put(e.getKey(), e.getValue().size());
            }
            List<String> recent = new ArrayList<>(mRecentEvents);
            boolean grabbing = false;
            try {
                grabbing = mBridge.isGrabbing();
            } catch (Throwable ignored) {
            }
            return new DebugSnapshot(
                    leases,
                    keyCounts,
                    mouseCounts,
                    recent,
                    mLastEvent,
                    mMismatchCount,
                    mBridgeErrorCount,
                    grabbing
            );
        }
    }

    public void resetForTesting() {
        synchronized (mLock) {
            mLeasesById.clear();
            mKeyOwners.clear();
            mKeyApplyMods.clear();
            mMouseOwners.clear();
            mRecentEvents.clear();
            mLastEvent = "idle";
            mMismatchCount = 0;
            mBridgeErrorCount = 0;
        }
        mResetParticipants.clear();
        mTelemetryListeners.clear();
        mBridge = StarHudInputBridge.DEFAULT;
    }

    private void releaseLeaseLocked(String leaseId, String reason) {
        LeaseInfo removed = mLeasesById.remove(leaseId);
        if (removed == null) return;
        if (removed.isMouse) {
            Set<String> owners = mMouseOwners.get(removed.code);
            if (owners != null) {
                owners.remove(leaseId);
                if (owners.isEmpty()) mMouseOwners.remove(removed.code);
            }
        } else {
            Set<String> owners = mKeyOwners.get(removed.code);
            if (owners != null) {
                owners.remove(leaseId);
                if (owners.isEmpty()) {
                    mKeyOwners.remove(removed.code);
                    mKeyApplyMods.remove(removed.code);
                }
            }
        }
        recordEventLocked("INTERNAL REPLACE " + leaseId + " (" + reason + ")");
    }

    private boolean dispatchKeySafe(int keycode, boolean down, boolean applyMods, String contextId) {
        try {
            mBridge.sendKey(keycode, down, applyMods);
            return true;
        } catch (Throwable t) {
            handleBridgeException("sendKey(" + keycode + ", " + down + ") " + contextId, t);
            return false;
        }
    }

    private boolean dispatchMouseSafe(int mouseButton, boolean down, String contextId) {
        try {
            mBridge.sendMouseButton(mouseButton, down);
            return true;
        } catch (Throwable t) {
            handleBridgeException("sendMouse(" + mouseButton + ", " + down + ") " + contextId, t);
            return false;
        }
    }

    private void recordMismatch(String msg) {
        synchronized (mLock) {
            mMismatchCount++;
            mLastEvent = "WARN: " + msg;
        }
        long now = System.currentTimeMillis();
        if (now - mLastMismatchLogMs >= MISMATCH_LOG_INTERVAL_MS) {
            mLastMismatchLogMs = now;
            Log.w(TAG, msg);
        }
        notifyTelemetry();
    }

    private void handleBridgeException(String op, Throwable t) {
        synchronized (mLock) {
            mBridgeErrorCount++;
            mLastEvent = "ERR: " + op + " (" + t.getClass().getSimpleName() + ")";
        }
        long now = System.currentTimeMillis();
        if (now - mLastMismatchLogMs >= MISMATCH_LOG_INTERVAL_MS) {
            mLastMismatchLogMs = now;
            Log.e(TAG, "Input bridge exception on " + op, t);
        }
    }

    private void recordEventLocked(String text) {
        mLastEvent = text;
        if (mRecentEvents.size() >= MAX_RECENT_EVENTS) {
            mRecentEvents.pollFirst();
        }
        mRecentEvents.addLast(text);
    }

    private void notifyTelemetry() {
        if (mTelemetryListeners.isEmpty()) return;
        Runnable notifyTask = () -> {
            for (TelemetryListener listener : mTelemetryListeners) {
                try {
                    listener.onInputStateChanged();
                } catch (Throwable ignored) {
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            notifyTask.run();
        } else {
            mMainHandler.post(notifyTask);
        }
    }

    @NonNull
    public static String formatKeycode(int keycode) {
        if (keycode >= LwjglGlfwKeycode.GLFW_KEY_A && keycode <= LwjglGlfwKeycode.GLFW_KEY_Z) {
            return String.valueOf((char) keycode);
        }
        if (keycode >= LwjglGlfwKeycode.GLFW_KEY_0 && keycode <= LwjglGlfwKeycode.GLFW_KEY_9) {
            return String.valueOf((char) keycode);
        }
        switch (keycode) {
            case LwjglGlfwKeycode.GLFW_KEY_SPACE: return "SPACE";
            case LwjglGlfwKeycode.GLFW_KEY_LEFT_SHIFT: return "LSHIFT";
            case LwjglGlfwKeycode.GLFW_KEY_LEFT_CONTROL: return "LCTRL";
            case LwjglGlfwKeycode.GLFW_KEY_LEFT_ALT: return "LALT";
            case LwjglGlfwKeycode.GLFW_KEY_ESCAPE: return "ESC";
            case LwjglGlfwKeycode.GLFW_KEY_F5: return "F5";
            case LwjglGlfwKeycode.GLFW_KEY_TAB: return "TAB";
            default: return "K#" + keycode;
        }
    }

    @NonNull
    public static String formatMouseButton(int button) {
        switch (button) {
            case LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT: return "LMB";
            case LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT: return "RMB";
            case LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_MIDDLE: return "MMB";
            default: return "MB#" + button;
        }
    }
}
