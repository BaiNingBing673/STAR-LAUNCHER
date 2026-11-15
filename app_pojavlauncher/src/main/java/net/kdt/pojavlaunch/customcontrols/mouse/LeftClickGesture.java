package net.kdt.pojavlaunch.customcontrols.mouse;

import android.os.Handler;

import com.star.launcher.hud.StarHudInputManager;

import net.kdt.pojavlaunch.LwjglGlfwKeycode;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.utils.MathUtils;

import org.lwjgl.glfw.CallbackBridge;

public class LeftClickGesture extends ValidatorGesture {
    public static final int FINGER_STILL_THRESHOLD = (int) Tools.dpToPx(9);
    private float mGestureStartX, mGestureStartY, mGestureEndX, mGestureEndY;
    private float mCumulativeDistance;
    private boolean mMouseActivated;

    public LeftClickGesture(Handler handler) {
        super(handler);
    }

    public final void inputEvent() {
        inputEventAt(CallbackBridge.mouseX, CallbackBridge.mouseY);
    }

    public final void inputEventAt(float startX, float startY) {
        if(submit()) {
            mGestureStartX = mGestureEndX = startX;
            mGestureStartY = mGestureEndY = startY;
            mCumulativeDistance = 0f;
        }
    }

    @Override
    protected int getGestureDelay() {
        return LauncherPreferences.PREF_LONGPRESS_TRIGGER;
    }

    @Override
    public boolean checkAndTrigger() {
        if (!StarHudInputManager.getInstance().getInputBridge().isGrabbing()) {
            return true;
        }
        boolean fingerStill = mCumulativeDistance <= FINGER_STILL_THRESHOLD
                && LeftClickGesture.isFingerStill(mGestureStartX, mGestureStartY, mGestureEndX, mGestureEndY, FINGER_STILL_THRESHOLD);
        // If the finger is still, fire the gesture using reference-counted lease.
        if(fingerStill) {
            mMouseActivated = StarHudInputManager.getInstance().acquireMouseLease(
                    "gesture:left_click",
                    "gesture_lmb",
                    -1,
                    "GESTURE_LMB",
                    LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT
            );
        }
        // Otherwise, don't click but still keep it active
        return true;
    }

    @Override
    public void onGestureCancelled(boolean isSwitching) {
        mCumulativeDistance = 0f;
        if(mMouseActivated) {
            mMouseActivated = false;
            StarHudInputManager.getInstance().releaseLeaseIfPresent("gesture:left_click", isSwitching ? "gesture_switch" : "gesture_cancel");
        }
    }

    public void setMotion(float deltaX, float deltaY) {
        mGestureEndX += deltaX;
        mGestureEndY += deltaY;
        mCumulativeDistance += (float) Math.hypot(deltaX, deltaY);
        if (mCumulativeDistance > FINGER_STILL_THRESHOLD) {
            // Once total drag distance exceeds the stillness threshold, cancel immediately so
            // slow or oscillating drags cannot become a left-click long press later.
            cancel(false);
        }
    }

    /**
     * Check if the finger is still when compared to mouseX/mouseY in CallbackBridge.
     * @param startX the starting X of the gesture
     * @param startY the starting Y of the gesture
     * @return whether the finger's position counts as "still" or not
     */
    public static boolean isFingerStill(float startX, float startY, float threshold) {
        return MathUtils.dist(
                CallbackBridge.mouseX,
                CallbackBridge.mouseY,
                startX,
                startY
        ) <= threshold;
    }

    public static boolean isFingerStill(float startX, float startY, float endX, float endY, float threshold) {
        return MathUtils.dist(
                endX,
                endY,
                startX,
                startY
        ) <= threshold;
    }
}
