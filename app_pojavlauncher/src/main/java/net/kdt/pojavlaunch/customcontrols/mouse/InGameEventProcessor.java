package net.kdt.pojavlaunch.customcontrols.mouse;

import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;

import com.star.launcher.hud.StarHudInputManager;

import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import org.lwjgl.glfw.CallbackBridge;

public class InGameEventProcessor implements TouchEventProcessor, StarHudInputManager.ResetParticipant {
    private final Handler mGestureHandler = new Handler(Looper.getMainLooper());
    private final double mSensitivity;
    private boolean mEventTransitioned = true;
    private final PointerTracker mTracker = new PointerTracker();
    private final LeftClickGesture mLeftClickGesture = new LeftClickGesture(mGestureHandler);
    private final RightClickGesture mRightClickGesture = new RightClickGesture(mGestureHandler);

    public InGameEventProcessor(double sensitivity) {
        mSensitivity = sensitivity;
    }

    @Override
    public boolean processTouchEvent(MotionEvent motionEvent) {
        switch (motionEvent.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mTracker.startTracking(motionEvent);
                if (LauncherPreferences.PREF_DISABLE_GESTURES) break;
                mEventTransitioned = false;
                checkGestures();
                break;

            case MotionEvent.ACTION_POINTER_DOWN:
                mTracker.handlePointerDown(motionEvent);
                break;

            case MotionEvent.ACTION_MOVE:
                mTracker.trackEvent(motionEvent);
                float[] motionVector = mTracker.getMotionVector();
                double effectiveSensitivity = mSensitivity * Math.max(0.1f, LauncherPreferences.PREF_CAMERA_SENSITIVITY);
                float deltaX = (float) (motionVector[0] * effectiveSensitivity);
                float deltaY = (float) (motionVector[1] * effectiveSensitivity);
                mLeftClickGesture.setMotion(deltaX, deltaY);
                mRightClickGesture.setMotion(deltaX, deltaY);
                CallbackBridge.mouseX += deltaX;
                CallbackBridge.mouseY += deltaY;
                CallbackBridge.deltaX = deltaX;
                CallbackBridge.deltaY = deltaY;
                CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);
                if (LauncherPreferences.PREF_DISABLE_GESTURES) break;
                checkGestures();
                break;

            case MotionEvent.ACTION_POINTER_UP:
                mTracker.handlePointerUp(motionEvent);
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mTracker.cancelTracking();
                cancelGestures(false);
                break;
        }
        return true;
    }

    @Override
    public void cancelPendingActions() {
        mTracker.cancelTracking();
        cancelGestures(true);
    }

    @Override
    public void onGlobalInputReset(String reason) {
        if (Looper.myLooper() == mGestureHandler.getLooper()) {
            cancelPendingActions();
        } else {
            mGestureHandler.post(this::cancelPendingActions);
        }
    }

    private void checkGestures() {
        mLeftClickGesture.inputEvent();
        // Only register right click events if it's a fresh event stream, not one after a transition.
        // This is done to avoid problems when people hold the button for just a bit too long after
        // exiting a menu for example.
        if (!mEventTransitioned) mRightClickGesture.inputEvent();
    }

    private void cancelGestures(boolean isSwitching) {
        mEventTransitioned = true;
        mLeftClickGesture.cancel(isSwitching);
        mRightClickGesture.cancel(isSwitching);
    }
}
