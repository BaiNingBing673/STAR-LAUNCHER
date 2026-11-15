package net.kdt.pojavlaunch.customcontrols.mouse;

import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;

import com.star.launcher.hud.StarHudInputManager;

import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import org.lwjgl.glfw.CallbackBridge;

/**
 * Handles touch input on the in-game world canvas strictly as camera look rotation.
 *
 * <p>Camera touches are permanently isolated from mouse button actions: they never schedule
 * long-press or tap click gestures regardless of hold duration or preferences, and bind
 * exclusively to a single owned pointer ID until that pointer is released or cancelled.
 */
public class InGameEventProcessor implements TouchEventProcessor, StarHudInputManager.ResetParticipant {

    public interface CameraCursorSink {
        void onCameraDelta(float deltaX, float deltaY);
        void onCameraReset();
    }

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final double mSensitivity;
    private final CameraCursorSink mCursorSink;

    private int mCameraPointerId = MotionEvent.INVALID_POINTER_ID;
    private float mLastX;
    private float mLastY;

    public InGameEventProcessor(double sensitivity) {
        this(sensitivity, new CameraCursorSink() {
            @Override
            public void onCameraDelta(float deltaX, float deltaY) {
                CallbackBridge.mouseX += deltaX;
                CallbackBridge.mouseY += deltaY;
                CallbackBridge.deltaX = deltaX;
                CallbackBridge.deltaY = deltaY;
                CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);
            }

            @Override
            public void onCameraReset() {
                CallbackBridge.deltaX = 0f;
                CallbackBridge.deltaY = 0f;
            }
        });
    }

    public InGameEventProcessor(double sensitivity, CameraCursorSink cursorSink) {
        mSensitivity = sensitivity;
        mCursorSink = cursorSink;
    }

    @Override
    public boolean processTouchEvent(MotionEvent motionEvent) {
        if (motionEvent == null) return false;
        switch (motionEvent.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                claimCameraPointer(motionEvent, 0);
                break;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (mCameraPointerId == MotionEvent.INVALID_POINTER_ID) {
                    claimCameraPointer(motionEvent, motionEvent.getActionIndex());
                }
                break;

            case MotionEvent.ACTION_MOVE: {
                if (mCameraPointerId == MotionEvent.INVALID_POINTER_ID) {
                    break;
                }
                int trackedIndex = motionEvent.findPointerIndex(mCameraPointerId);
                if (trackedIndex < 0) {
                    // Owned pointer vanished unexpectedly; release cleanly without hijacking another pointer.
                    releaseCameraPointer();
                    break;
                }
                float currentX = motionEvent.getX(trackedIndex);
                float currentY = motionEvent.getY(trackedIndex);
                float rawDeltaX = currentX - mLastX;
                float rawDeltaY = currentY - mLastY;
                mLastX = currentX;
                mLastY = currentY;

                double effectiveSensitivity = mSensitivity * Math.max(0.1f, LauncherPreferences.PREF_CAMERA_SENSITIVITY);
                float deltaX = (float) (rawDeltaX * effectiveSensitivity);
                float deltaY = (float) (rawDeltaY * effectiveSensitivity);
                if (mCursorSink != null) {
                    mCursorSink.onCameraDelta(deltaX, deltaY);
                }
                break;
            }

            case MotionEvent.ACTION_POINTER_UP: {
                int liftingIndex = motionEvent.getActionIndex();
                if (liftingIndex >= 0 && liftingIndex < motionEvent.getPointerCount()
                        && motionEvent.getPointerId(liftingIndex) == mCameraPointerId) {
                    releaseCameraPointer();
                }
                break;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                releaseCameraPointer();
                break;
        }
        return true;
    }

    public int getTrackedCameraPointerId() {
        return mCameraPointerId;
    }

    private void claimCameraPointer(MotionEvent motionEvent, int pointerIndex) {
        if (pointerIndex < 0 || pointerIndex >= motionEvent.getPointerCount()) {
            releaseCameraPointer();
            return;
        }
        mCameraPointerId = motionEvent.getPointerId(pointerIndex);
        mLastX = motionEvent.getX(pointerIndex);
        mLastY = motionEvent.getY(pointerIndex);
    }

    private void releaseCameraPointer() {
        mCameraPointerId = MotionEvent.INVALID_POINTER_ID;
        mLastX = 0f;
        mLastY = 0f;
        if (mCursorSink != null) {
            mCursorSink.onCameraReset();
        }
    }

    @Override
    public void cancelPendingActions() {
        releaseCameraPointer();
        StarHudInputManager.getInstance().releaseLeaseIfPresent("gesture:left_click", "camera_cancel");
    }

    @Override
    public void onGlobalInputReset(String reason) {
        if (Looper.myLooper() == mMainHandler.getLooper()) {
            cancelPendingActions();
        } else {
            mMainHandler.post(this::cancelPendingActions);
        }
    }
}
