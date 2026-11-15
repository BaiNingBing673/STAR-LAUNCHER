package com.star.launcher.hud;

import net.kdt.pojavlaunch.EfficientAndroidLWJGLKeycode;

import org.lwjgl.glfw.CallbackBridge;

/**
 * Lightweight seam over {@link CallbackBridge} used by {@link StarJoystickView} and
 * {@link StarHudButtonView}. In production, delegates directly to {@link CallbackBridge};
 * JVM unit tests may substitute a recording implementation.
 */
public interface StarHudInputBridge {
    void sendKey(int keycode, boolean isDown, boolean applyModifiers);
    void sendMouseButton(int button, boolean isDown);
    default void sendScroll(float xOffset, float yOffset) {
        CallbackBridge.sendScroll(xOffset, yOffset);
    }
    default void sendKeyTap(int keycode) {
        sendKey(keycode, true, false);
        sendKey(keycode, false, false);
    }
    default boolean isGrabbing() {
        return CallbackBridge.isGrabbing();
    }

    StarHudInputBridge DEFAULT = new StarHudInputBridge() {
        @Override
        public void sendKey(int keycode, boolean isDown, boolean applyModifiers) {
            CallbackBridge.sendKeyPress(
                    keycode,
                    EfficientAndroidLWJGLKeycode.getLwjglChar(keycode),
                    CallbackBridge.getCurrentMods(),
                    isDown
            );
            if (applyModifiers) {
                CallbackBridge.setModifiers(keycode, isDown);
            }
        }

        @Override
        public void sendMouseButton(int button, boolean isDown) {
            CallbackBridge.sendMouseButton(button, isDown);
        }

        @Override
        public void sendKeyTap(int keycode) {
            CallbackBridge.sendKeyPress(keycode);
        }

        @Override
        public boolean isGrabbing() {
            return CallbackBridge.isGrabbing();
        }
    };
}
