package net.kdt.pojavlaunch.utils;

import android.content.Context;
import android.os.Vibrator;

import top.fifthlight.touchcontroller.proxy.client.LauncherProxyClient;
import top.fifthlight.touchcontroller.proxy.client.MessageTransport;
import top.fifthlight.touchcontroller.proxy.client.PlatformCapability;
import top.fifthlight.touchcontroller.proxy.client.android.transport.UnixSocketTransportKt;
import top.fifthlight.touchcontroller.proxy.message.VibrateMessage;

import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import java.util.Set;

public class TouchControllerUtils {
    private TouchControllerUtils() {
    }

    public static LauncherProxyClient proxyClient;
    private static final String socketName = "Amethyst";

    private static class VibrationHandler implements LauncherProxyClient.VibrationHandler {
        private final Vibrator vibrator;

        public VibrationHandler(Vibrator vibrator) {
            this.vibrator = vibrator;
        }

        @Override
        @SuppressWarnings("DEPRECATION")
        public void vibrate(@NonNull VibrateMessage.Kind kind) {
            vibrator.vibrate(LauncherPreferences.PREF_TOUCHCONTROLLER_VIBRATE_LENGTH);
        }
    }

    private static final SparseIntArray pointerIdMap = new SparseIntArray();
    private static int nextPointerId = 1;
    private static boolean resetRegistered = false;

    public static void cancelAllPointers() {
        pointerIdMap.clear();
        if (proxyClient != null) {
            proxyClient.clearPointer();
        }
    }

    public static void processTouchEvent(MotionEvent motionEvent, View view) {
        if (proxyClient == null || motionEvent == null || view == null) {
            return;
        }
        int width = view.getWidth();
        int height = view.getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        int pointerId;
        switch (motionEvent.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // A new ACTION_DOWN starts a fresh touch sequence; clear any stale pointer mappings.
                proxyClient.clearPointer();
                pointerIdMap.clear();
                pointerId = nextPointerId++;
                pointerIdMap.put(motionEvent.getPointerId(0), pointerId);
                proxyClient.addPointer(pointerId, motionEvent.getX(0) / width, motionEvent.getY(0) / height);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                pointerId = nextPointerId++;
                int actionIndex = motionEvent.getActionIndex();
                if (actionIndex >= 0 && actionIndex < motionEvent.getPointerCount()) {
                    int rawPointerId = motionEvent.getPointerId(actionIndex);
                    int existingMapped = pointerIdMap.get(rawPointerId, 0);
                    if (existingMapped != 0) {
                        proxyClient.removePointer(existingMapped);
                    }
                    pointerIdMap.put(rawPointerId, pointerId);
                    proxyClient.addPointer(pointerId, motionEvent.getX(actionIndex) / width, motionEvent.getY(actionIndex) / height);
                }
                break;
            case MotionEvent.ACTION_MOVE:
                for (int idx = pointerIdMap.size() - 1; idx >= 0; idx--) {
                    int trackedRawId = pointerIdMap.keyAt(idx);
                    if (motionEvent.findPointerIndex(trackedRawId) < 0) {
                        int staleMappedId = pointerIdMap.valueAt(idx);
                        pointerIdMap.removeAt(idx);
                        if (staleMappedId != 0) {
                            proxyClient.removePointer(staleMappedId);
                        }
                    }
                }
                for (int i = 0; i < motionEvent.getPointerCount(); i++) {
                    pointerId = pointerIdMap.get(motionEvent.getPointerId(i));
                    if (pointerId == 0) {
                        Log.d("TouchController", "Move pointerId is 0");
                        continue;
                    }
                    proxyClient.addPointer(pointerId, motionEvent.getX(i) / width, motionEvent.getY(i) / height);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                proxyClient.clearPointer();
                pointerIdMap.clear();
                break;
            case MotionEvent.ACTION_POINTER_UP:
                int i = motionEvent.getActionIndex();
                if (i >= 0 && i < motionEvent.getPointerCount()) {
                    int rawPointerId = motionEvent.getPointerId(i);
                    pointerId = pointerIdMap.get(rawPointerId);
                    if (pointerId == 0) {
                        Log.d("TouchController", "Pointer up pointerId is 0");
                        break;
                    }
                    pointerIdMap.delete(rawPointerId);
                    proxyClient.removePointer(pointerId);
                }
                break;
        }
    }

    public static void initialize(Context context, TouchControllerInputView touchControllerInputView) {
        if (!resetRegistered) {
            resetRegistered = true;
            com.star.launcher.hud.StarHudInputManager.getInstance().registerResetParticipant(reason -> cancelAllPointers());
        }
        if (proxyClient != null) {
            return;
        }
        try {
            Os.setenv("TOUCH_CONTROLLER_PROXY_SOCKET", socketName, true);
        } catch (ErrnoException e) {
            Log.w("TouchController", "Failed to set TouchController environment variable", e);
        }
        MessageTransport transport = UnixSocketTransportKt.UnixSocketTransport(socketName);
        proxyClient = new LauncherProxyClient(transport, Set.of(PlatformCapability.TEXT_STATUS, PlatformCapability.KEYBOARD_SHOW));
        proxyClient.run();
        touchControllerInputView.setClient(proxyClient);
        Vibrator vibrator = ContextCompat.getSystemService(context, Vibrator.class);
        if (vibrator != null) {
            LauncherProxyClient.VibrationHandler vibrationHandler = new VibrationHandler(vibrator);
            proxyClient.setVibrationHandler(vibrationHandler);
        }
    }
}
