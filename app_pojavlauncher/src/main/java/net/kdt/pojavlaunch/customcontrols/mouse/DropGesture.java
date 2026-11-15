package net.kdt.pojavlaunch.customcontrols.mouse;

import android.os.Handler;

import com.star.launcher.hud.StarHudInputManager;

import net.kdt.pojavlaunch.LwjglGlfwKeycode;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

public class DropGesture implements Runnable{
    private final Handler mHandler;
    private boolean mActive;

    public DropGesture(Handler mHandler) {
        this.mHandler = mHandler;
    }

    public void submit() {
        if(!mActive) {
            mActive = true;
            mHandler.postDelayed(this, LauncherPreferences.PREF_LONGPRESS_TRIGGER);
        }
    }

    public void cancel() {
        mActive = false;
        mHandler.removeCallbacks(this);
    }

    @Override
    public void run() {
        if(!mActive) return;
        StarHudInputManager.getInstance().tapKey(
                "hotbar_drop_gesture",
                -1,
                "DROP_ITEM",
                LwjglGlfwKeycode.GLFW_KEY_Q,
                false
        );
        mHandler.postDelayed(this, 250);
    }
}
