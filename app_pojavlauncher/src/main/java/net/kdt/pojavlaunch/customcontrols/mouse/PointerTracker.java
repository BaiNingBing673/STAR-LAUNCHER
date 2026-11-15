package net.kdt.pojavlaunch.customcontrols.mouse;

import android.view.MotionEvent;

/**
 * Tracks a single active touch pointer across multi-touch MotionEvents without
 * jumping or resetting deltas when secondary pointers go down or up.
 */
public class PointerTracker {
    private boolean mColdStart = true;
    private int mTrackedPointerId = MotionEvent.INVALID_POINTER_ID;
    private float mLastX, mLastY;
    private final float[] mMotionVector = new float[2];

    public void startTracking(MotionEvent motionEvent) {
        startTracking(motionEvent, 0);
    }

    public void startTracking(MotionEvent motionEvent, int pointerIndex) {
        if (pointerIndex < 0 || pointerIndex >= motionEvent.getPointerCount()) {
            cancelTracking();
            return;
        }
        mColdStart = false;
        mTrackedPointerId = motionEvent.getPointerId(pointerIndex);
        mLastX = motionEvent.getX(pointerIndex);
        mLastY = motionEvent.getY(pointerIndex);
        mMotionVector[0] = 0f;
        mMotionVector[1] = 0f;
    }

    public void handlePointerDown(MotionEvent motionEvent) {
        if (mColdStart || motionEvent.findPointerIndex(mTrackedPointerId) == -1) {
            startTracking(motionEvent, motionEvent.getActionIndex());
        }
    }

    public void handlePointerUp(MotionEvent motionEvent) {
        int liftingIndex = motionEvent.getActionIndex();
        int liftingId = motionEvent.getPointerId(liftingIndex);
        if (liftingId != mTrackedPointerId) {
            return;
        }
        int count = motionEvent.getPointerCount();
        for (int i = 0; i < count; i++) {
            if (i != liftingIndex) {
                startTracking(motionEvent, i);
                return;
            }
        }
        cancelTracking();
    }

    public void cancelTracking() {
        mColdStart = true;
        mTrackedPointerId = MotionEvent.INVALID_POINTER_ID;
        mMotionVector[0] = 0f;
        mMotionVector[1] = 0f;
    }

    public int trackEvent(MotionEvent motionEvent) {
        int trackedPointerIndex = motionEvent.findPointerIndex(mTrackedPointerId);
        if (trackedPointerIndex == -1 || mColdStart) {
            startTracking(motionEvent, 0);
            return 0;
        }
        float trackedX = motionEvent.getX(trackedPointerIndex);
        float trackedY = motionEvent.getY(trackedPointerIndex);
        mMotionVector[0] = trackedX - mLastX;
        mMotionVector[1] = trackedY - mLastY;
        mLastX = trackedX;
        mLastY = trackedY;
        return trackedPointerIndex;
    }

    public float[] getMotionVector() {
        return mMotionVector;
    }
}
