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
        public final List<String> pressedButtonIds;
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
            this(
                    activeLeases,
                    pressedKeyRefCounts,
                    pressedMouseRefCounts,
                    Collections.emptyList(),
                    recentEvents,
                    lastEvent,
                    mismatchCount,
                    bridgeErrorCount,
                    isGrabbing
            );
        }

        DebugSnapshot(
                List<LeaseInfo> activeLeases,
                Map<Integer, Integer> pressedKeyRefCounts,
                Map<Integer, Integer> pressedMouseRefCounts,
                List<String> pressedButtonIds,
                List<String> recentEvents,
                String lastEvent,
                int mismatchCount,
                int bridgeErrorCount,
                boolean isGrabbing
        ) {
            this.activeLeases = activeLeases;
            this.pressedKeyRefCounts = pressedKeyRefCounts;
            this.pressedMouseRefCounts = pressedMouseRefCounts;
            this.pressedButtonIds = pressedButtonIds;
            this.recentEvents = recentEvents;
            this.lastEvent = lastEvent;
            this.mismatchCount = mismatchCount;
            this.bridgeErrorCount = bridgeErrorCount;
            this.isGrabbing = isGrabbing;
        }
    }

    private static final int INVALID_POINTER_ID = -1;
    private static final StarHudInputManager INSTANCE = new StarHudInputManager();

    public static StarHudInputManager getInstance() {
        return INSTANCE;
    }

    private final Object mLock = new Object();
    private final Object mDispatchLock = new Object();
    private final Map<String, LeaseInfo> mLeasesById = new LinkedHashMap<>();
    private final Map<String, Set<String>> mButtonLeases = new LinkedHashMap<>();
    private final Map<Integer, Set<String>> mKeyOwners = new HashMap<>();
    private final Map<Integer, Boolean> mKeyApplyMods = new HashMap<>();
    private final Map<Integer, Set<String>> mMouseOwners = new HashMap<>();
    // Authoritative dispatched native state per GLFW mouse button (true = DOWN sent without matching UP yet).
    private final Map<Integer, Boolean> mDispatchedMouseStates = new HashMap<>();
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
        return acquireLeaseInternal(leaseId, buttonId, pointerId, actionLabel, false, keycode, applyModifiers);
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
        return acquireLeaseInternal(leaseId, buttonId, pointerId, actionLabel, true, mouseButton, false);
    }

    private boolean acquireLeaseInternal(
            @NonNull String leaseId,
            @NonNull String buttonId,
            int pointerId,
            @NonNull String actionLabel,
            boolean isMouse,
            int code,
            boolean applyModifiers
    ) {
        boolean shouldSendDown;
        boolean hasOldRelease = false;
        boolean oldIsMouse = false;
        int oldCode = 0;
        boolean oldApplyMods = false;
        boolean bridgeSuccess = true;

        synchronized (mDispatchLock) {
            synchronized (mLock) {
                LeaseInfo existing = mLeasesById.get(leaseId);
                if (existing != null) {
                    if (existing.isMouse == isMouse && existing.code == code) {
                        // Reject hijacking by a different active pointer; ignore duplicate DOWN from same pointer.
                        if (pointerId >= 0 && existing.pointerId >= 0 && existing.pointerId != pointerId) {
                            recordMismatch("Conflicting pointer DOWN on " + leaseId
                                    + " (active=" + existing.pointerId + ", incoming=" + pointerId + ")");
                            return false;
                        }
                        return true;
                    }
                    hasOldRelease = removeLeaseLocked(leaseId, "reacquire");
                    if (hasOldRelease) {
                        oldIsMouse = existing.isMouse;
                        oldCode = existing.code;
                        oldApplyMods = existing.applyModifiers || Boolean.TRUE.equals(mKeyApplyMods.get(oldCode));
                    }
                }

                Map<Integer, Set<String>> ownerMap = isMouse ? mMouseOwners : mKeyOwners;
                Set<String> owners = ownerMap.get(code);
                if (owners == null) {
                    owners = new LinkedHashSet<>();
                    ownerMap.put(code, owners);
                }
                shouldSendDown = isMouse ? !isMousePressedLocked(code) : owners.isEmpty();
                LeaseInfo info = new LeaseInfo(
                        leaseId, buttonId, pointerId, actionLabel, isMouse, code, applyModifiers, System.currentTimeMillis()
                );
                mLeasesById.put(leaseId, info);
                owners.add(leaseId);
                addButtonLeaseLocked(buttonId, leaseId);
                if (!isMouse && applyModifiers) {
                    mKeyApplyMods.put(code, Boolean.TRUE);
                }
                String formatted = isMouse ? ("MOUSE " + formatMouseButton(code)) : ("KEY " + formatKeycode(code));
                recordEventLocked("DOWN " + formatted + " [" + buttonId + " p=" + pointerId + "]");
            }

            if (hasOldRelease) {
                if (oldIsMouse) {
                    transitionMouseLocked(oldCode, false, leaseId + ":reacquire_old");
                } else {
                    dispatchKeySafe(oldCode, false, oldApplyMods, leaseId + ":reacquire_old");
                }
            }

            if (shouldSendDown) {
                bridgeSuccess = isMouse
                        ? transitionMouseLocked(code, true, leaseId)
                        : dispatchKeySafe(code, true, applyModifiers, leaseId);
                if (!bridgeSuccess) {
                    synchronized (mLock) {
                        removeLeaseLocked(leaseId, "bridge_failure_rollback");
                    }
                }
            }
        }
        notifyTelemetry();
        return bridgeSuccess;
    }

    public void releaseLease(@NonNull String leaseId, @NonNull String reason) {
        LeaseInfo removed;
        boolean shouldSendUp;
        boolean applyMods;

        synchronized (mDispatchLock) {
            synchronized (mLock) {
                removed = mLeasesById.get(leaseId);
                if (removed == null) {
                    if (!"idle_release".equals(reason) && !"up_no_lease".equals(reason)) {
                        recordMismatch("Release without active lease: " + leaseId + " (" + reason + ")");
                    }
                    return;
                }
                applyMods = removed.applyModifiers || Boolean.TRUE.equals(mKeyApplyMods.get(removed.code));
                shouldSendUp = removeLeaseLocked(leaseId, reason);
                if (removed.isMouse) {
                    recordEventLocked("UP MOUSE " + formatMouseButton(removed.code) + " [" + removed.buttonId + " " + reason + "]");
                } else {
                    recordEventLocked("UP KEY " + formatKeycode(removed.code) + " [" + removed.buttonId + " " + reason + "]");
                }
            }

            if (shouldSendUp) {
                if (removed.isMouse) {
                    transitionMouseLocked(removed.code, false, leaseId);
                } else {
                    dispatchKeySafe(removed.code, false, applyMods, leaseId);
                }
            }
        }
        notifyTelemetry();
    }

    /**
     * Releases a lease only if it is currently active, avoiding spurious mismatch logs during cascade resets.
     */
    public boolean releaseLeaseIfPresent(@NonNull String leaseId, @NonNull String reason) {
        synchronized (mLock) {
            if (!mLeasesById.containsKey(leaseId)) {
                return false;
            }
        }
        releaseLease(leaseId, reason);
        return true;
    }

    public boolean hasLease(@NonNull String leaseId) {
        synchronized (mLock) {
            return mLeasesById.containsKey(leaseId);
        }
    }

    /**
     * Releases all active leases owned by the specified pointer without affecting other pointers.
     */
    public void releaseLeasesForPointer(int pointerId, @NonNull String reason) {
        if (pointerId == INVALID_POINTER_ID) return;
        List<String> matchingLeases = new ArrayList<>();
        synchronized (mLock) {
            for (LeaseInfo info : mLeasesById.values()) {
                if (info.pointerId == pointerId) {
                    matchingLeases.add(info.leaseId);
                }
            }
        }
        for (String leaseId : matchingLeases) {
            releaseLease(leaseId, reason);
        }
    }

    /**
     * Releases all active leases belonging to the given virtual button ID.
     */
    public void releaseButton(@NonNull String buttonId, @NonNull String reason) {
        List<String> matchingLeases = new ArrayList<>();
        synchronized (mLock) {
            Set<String> ids = mButtonLeases.get(buttonId);
            if (ids != null) {
                matchingLeases.addAll(ids);
            }
        }
        for (String leaseId : matchingLeases) {
            releaseLease(leaseId, reason);
        }
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
        synchronized (mDispatchLock) {
            boolean alreadyHeld;
            synchronized (mLock) {
                alreadyHeld = isMousePressedLocked(mouseButton) || isDispatchedMousePressedLocked(mouseButton);
                recordEventLocked("TAP MOUSE " + formatMouseButton(mouseButton) + " [" + buttonId + " p=" + pointerId + "]");
            }
            if (!alreadyHeld) {
                if (transitionMouseLocked(mouseButton, true, "tapDown:" + buttonId)) {
                    transitionMouseLocked(mouseButton, false, "tapUp:" + buttonId);
                }
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
        releaseAllInputsInternal(reason, false);
    }

    /**
     * Emergency recovery path invoked when input state is inconsistent or native state may have
     * drifted outside tracked leases. Clears all tracked leases and forcefully releases standard
     * mouse buttons that were not already tracked.
     */
    public void emergencyReleaseAllInputs(@NonNull String reason) {
        recordMismatch("Emergency release-all triggered: " + reason);
        releaseAllInputsInternal("emergency:" + reason, true);
    }

    private void releaseAllInputsInternal(@NonNull String reason, boolean forceCommonMouseUp) {
        List<Integer> keysToRelease = new ArrayList<>();
        List<Boolean> keyMods = new ArrayList<>();
        Set<Integer> mouseToRelease = new LinkedHashSet<>();

        synchronized (mDispatchLock) {
            synchronized (mLock) {
                for (Integer keycode : mKeyOwners.keySet()) {
                    keysToRelease.add(keycode);
                    keyMods.add(Boolean.TRUE.equals(mKeyApplyMods.get(keycode)));
                }
                mouseToRelease.addAll(mMouseOwners.keySet());
                for (Map.Entry<Integer, Boolean> e : mDispatchedMouseStates.entrySet()) {
                    if (Boolean.TRUE.equals(e.getValue())) {
                        mouseToRelease.add(e.getKey());
                    }
                }
                mLeasesById.clear();
                mButtonLeases.clear();
                mKeyOwners.clear();
                mKeyApplyMods.clear();
                mMouseOwners.clear();
                recordEventLocked("RESET ALL (" + reason + ")");
            }

            for (int i = 0; i < keysToRelease.size(); i++) {
                dispatchKeySafe(keysToRelease.get(i), false, keyMods.get(i), "resetAll:" + reason);
            }
            for (Integer btn : mouseToRelease) {
                transitionMouseLocked(btn, false, "resetAll:" + reason);
            }
        }
        // Clear any stray modifier latches safely
        try {
            CallbackBridge.holdingShift = false;
            CallbackBridge.holdingCtrl = false;
            CallbackBridge.holdingAlt = false;
        } catch (Throwable ignored) {
        }

        notifyResetParticipants(reason);
        notifyTelemetry();
    }

    private void notifyResetParticipants(@NonNull String reason) {
        if (mResetParticipants.isEmpty()) return;
        Runnable notifyTask = () -> {
            for (ResetParticipant participant : mResetParticipants) {
                try {
                    participant.onGlobalInputReset(reason);
                } catch (Throwable t) {
                    Log.w(TAG, "ResetParticipant threw during releaseAllInputs(" + reason + ")", t);
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            notifyTask.run();
        } else {
            mMainHandler.post(notifyTask);
        }
    }

    /**
     * Verifies internal lease/ref-count/button/grab invariants and triggers emergency recovery
     * if any desynchronization is detected.
     *
     * @return {@code true} if state was consistent, {@code false} if recovery was triggered.
     */
    public boolean validateConsistencyAndRecover(@NonNull String context) {
        String inconsistency = null;
        synchronized (mLock) {
            for (Map.Entry<String, LeaseInfo> entry : mLeasesById.entrySet()) {
                LeaseInfo info = entry.getValue();
                if (!entry.getKey().equals(info.leaseId)) {
                    inconsistency = "leaseId key mismatch: " + entry.getKey();
                    break;
                }
                Map<Integer, Set<String>> ownerMap = info.isMouse ? mMouseOwners : mKeyOwners;
                Set<String> owners = ownerMap.get(info.code);
                if (owners == null || !owners.contains(info.leaseId)) {
                    inconsistency = "missing reverse owner for " + info.leaseId;
                    break;
                }
                Set<String> btnLeases = mButtonLeases.get(info.buttonId);
                if (btnLeases == null || !btnLeases.contains(info.leaseId)) {
                    inconsistency = "missing button mapping for " + info.buttonId;
                    break;
                }
            }
            if (inconsistency == null) {
                inconsistency = findOrphanedOwnerLocked(mKeyOwners, false);
            }
            if (inconsistency == null) {
                inconsistency = findOrphanedOwnerLocked(mMouseOwners, true);
            }
            if (inconsistency == null) {
                for (Map.Entry<String, Set<String>> e : mButtonLeases.entrySet()) {
                    if (e.getValue().isEmpty()) {
                        inconsistency = "empty button lease set for " + e.getKey();
                        break;
                    }
                    for (String id : e.getValue()) {
                        LeaseInfo info = mLeasesById.get(id);
                        if (info == null || !e.getKey().equals(info.buttonId)) {
                            inconsistency = "orphan button lease " + id + " on " + e.getKey();
                            break;
                        }
                    }
                    if (inconsistency != null) break;
                }
            }
            if (inconsistency == null) {
                for (Integer mouseBtn : mMouseOwners.keySet()) {
                    if (!isDispatchedMousePressedLocked(mouseBtn)) {
                        inconsistency = "mouse owners active without dispatched DOWN for " + formatMouseButton(mouseBtn);
                        break;
                    }
                }
            }
            if (inconsistency == null) {
                for (Map.Entry<Integer, Boolean> e : mDispatchedMouseStates.entrySet()) {
                    if (Boolean.TRUE.equals(e.getValue()) && !isMousePressedLocked(e.getKey())) {
                        inconsistency = "mouse button dispatched DOWN without active owner for " + formatMouseButton(e.getKey());
                        break;
                    }
                }
            }
            if (inconsistency == null && !mLeasesById.isEmpty() && !isBridgeGrabbingSafe()) {
                inconsistency = "active leases held while not grabbing (" + mLeasesById.size() + ")";
            }
        }

        if (inconsistency != null) {
            emergencyReleaseAllInputs(context + ":" + inconsistency);
            return false;
        }
        return true;
    }

    @Nullable
    private String findOrphanedOwnerLocked(Map<Integer, Set<String>> ownerMap, boolean isMouse) {
        for (Map.Entry<Integer, Set<String>> entry : ownerMap.entrySet()) {
            Set<String> owners = entry.getValue();
            if (owners.isEmpty()) {
                return "empty owner set for " + (isMouse ? "mouse " : "key ") + entry.getKey();
            }
            for (String leaseId : owners) {
                LeaseInfo info = mLeasesById.get(leaseId);
                if (info == null || info.isMouse != isMouse || info.code != entry.getKey()) {
                    return "orphan owner " + leaseId + " for " + (isMouse ? "mouse " : "key ") + entry.getKey();
                }
            }
        }
        return null;
    }

    private boolean isBridgeGrabbingSafe() {
        try {
            return mBridge.isGrabbing();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public boolean isButtonPressed(@NonNull String buttonId) {
        synchronized (mLock) {
            Set<String> ids = mButtonLeases.get(buttonId);
            return ids != null && !ids.isEmpty();
        }
    }

    public int getPressedPointerId(@NonNull String buttonId) {
        synchronized (mLock) {
            Set<String> ids = mButtonLeases.get(buttonId);
            if (ids == null || ids.isEmpty()) {
                return INVALID_POINTER_ID;
            }
            for (String id : ids) {
                LeaseInfo info = mLeasesById.get(id);
                if (info != null) {
                    return info.pointerId;
                }
            }
            return INVALID_POINTER_ID;
        }
    }

    @NonNull
    public Set<String> getPressedButtonIds() {
        synchronized (mLock) {
            return new LinkedHashSet<>(mButtonLeases.keySet());
        }
    }

    public boolean isKeyHeld(int keycode) {
        synchronized (mLock) {
            Set<String> owners = mKeyOwners.get(keycode);
            return owners != null && !owners.isEmpty();
        }
    }

    public boolean isMouseButtonHeld(int mouseButton) {
        synchronized (mLock) {
            return isMousePressedLocked(mouseButton) || isDispatchedMousePressedLocked(mouseButton);
        }
    }

    /**
     * Returns whether the given GLFW mouse button is currently in the authoritative pressed state.
     */
    public boolean isMouseButtonPressed(int mouseButton) {
        synchronized (mLock) {
            return isDispatchedMousePressedLocked(mouseButton);
        }
    }

    /**
     * Returns independent pointer IDs currently owning the specified GLFW mouse button.
     */
    @NonNull
    public Set<Integer> getMouseButtonOwnerPointers(int mouseButton) {
        synchronized (mLock) {
            Set<Integer> pointerIds = new LinkedHashSet<>();
            Set<String> owners = mMouseOwners.get(mouseButton);
            if (owners != null) {
                for (String leaseId : owners) {
                    LeaseInfo info = mLeasesById.get(leaseId);
                    if (info != null) {
                        pointerIds.add(info.pointerId);
                    }
                }
            }
            return pointerIds;
        }
    }

    private boolean isMousePressedLocked(int mouseButton) {
        Set<String> owners = mMouseOwners.get(mouseButton);
        return owners != null && !owners.isEmpty();
    }

    private boolean isDispatchedMousePressedLocked(int mouseButton) {
        return Boolean.TRUE.equals(mDispatchedMouseStates.get(mouseButton));
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
            List<String> pressedButtons = new ArrayList<>(mButtonLeases.keySet());
            List<String> recent = new ArrayList<>(mRecentEvents);
            boolean grabbing = isBridgeGrabbingSafe();
            return new DebugSnapshot(
                    leases,
                    keyCounts,
                    mouseCounts,
                    pressedButtons,
                    recent,
                    mLastEvent,
                    mMismatchCount,
                    mBridgeErrorCount,
                    grabbing
            );
        }
    }

    public void resetForTesting() {
        synchronized (mDispatchLock) {
            synchronized (mLock) {
                mLeasesById.clear();
                mButtonLeases.clear();
                mKeyOwners.clear();
                mKeyApplyMods.clear();
                mMouseOwners.clear();
                mDispatchedMouseStates.clear();
                mRecentEvents.clear();
                mLastEvent = "idle";
                mMismatchCount = 0;
                mBridgeErrorCount = 0;
            }
            mResetParticipants.clear();
            mTelemetryListeners.clear();
            mBridge = StarHudInputBridge.DEFAULT;
        }
    }

    private void addButtonLeaseLocked(@NonNull String buttonId, @NonNull String leaseId) {
        Set<String> ids = mButtonLeases.get(buttonId);
        if (ids == null) {
            ids = new LinkedHashSet<>();
            mButtonLeases.put(buttonId, ids);
        }
        ids.add(leaseId);
    }

    /**
     * Removes {@code leaseId} from all maps under {@link #mLock} and returns {@code true} if the
     * underlying key/mouse ref-count dropped to zero and needs a native UP dispatch.
     */
    private boolean removeLeaseLocked(@NonNull String leaseId, @NonNull String reason) {
        LeaseInfo removed = mLeasesById.remove(leaseId);
        if (removed == null) return false;
        Set<String> btnIds = mButtonLeases.get(removed.buttonId);
        if (btnIds != null) {
            btnIds.remove(leaseId);
            if (btnIds.isEmpty()) {
                mButtonLeases.remove(removed.buttonId);
            }
        }
        boolean becameEmpty = false;
        if (removed.isMouse) {
            Set<String> owners = mMouseOwners.get(removed.code);
            if (owners != null) {
                owners.remove(leaseId);
                if (owners.isEmpty()) {
                    mMouseOwners.remove(removed.code);
                    becameEmpty = true;
                }
            }
        } else {
            Set<String> owners = mKeyOwners.get(removed.code);
            if (owners != null) {
                owners.remove(leaseId);
                if (owners.isEmpty()) {
                    mKeyOwners.remove(removed.code);
                    mKeyApplyMods.remove(removed.code);
                    becameEmpty = true;
                }
            }
        }
        if ("reacquire".equals(reason)) {
            recordEventLocked("INTERNAL REPLACE " + leaseId + " (" + reason + ")");
        }
        return becameEmpty;
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

    /**
     * Transitions a GLFW mouse button state strictly on released <-> pressed edges under
     * {@link #mDispatchLock}, preventing duplicate DOWNs or spurious UPs across threads and callers.
     */
    private boolean transitionMouseLocked(int mouseButton, boolean wantPressed, String contextId) {
        synchronized (mLock) {
            boolean currentlyPressed = isDispatchedMousePressedLocked(mouseButton);
            if (currentlyPressed == wantPressed) {
                return true;
            }
            mDispatchedMouseStates.put(mouseButton, wantPressed);
        }
        boolean ok = dispatchMouseSafe(mouseButton, wantPressed, contextId);
        if (!ok) {
            synchronized (mLock) {
                mDispatchedMouseStates.put(mouseButton, !wantPressed);
            }
        }
        return ok;
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
