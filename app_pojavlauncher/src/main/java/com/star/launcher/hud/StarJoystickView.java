package com.star.launcher.hud;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.LwjglGlfwKeycode;

/**
 * Translucent circular STAR virtual joystick providing 360-degree visual tracking
 * and clean multi-touch-safe WASD input for Minecraft Java Edition.
 */
public class StarJoystickView extends View implements StarHudInputManager.ResetParticipant {
    public static final float DEFAULT_DIAMETER_DP = 126f;

    private static final float RADIAL_DEADZONE = 0.20f;
    private static final float AXIS_PRESS_THRESHOLD = 0.32f;
    private static final float AXIS_RELEASE_THRESHOLD = 0.24f;
    private static final float KNOB_TRAVEL_RATIO = 0.68f;
    private static final float KNOB_RADIUS_RATIO = 0.28f;

    // STAR palette colors
    private static final int COLOR_RING_BG = 0xFF141926;
    private static final int COLOR_INNER_WELL = 0xFF0E131D;
    private static final int COLOR_BORDER_IDLE = 0xFF33415C;
    private static final int COLOR_BORDER_ACTIVE = 0xFF5FA8FF;
    private static final int COLOR_KNOB_FILL = 0xFF1E2638;
    private static final int COLOR_KNOB_ACTIVE = 0xFF2563EB;
    private static final int COLOR_CYAN = 0xFF5EE2FF;
    private static final int COLOR_CUSTOMIZE_BORDER = 0xFFF59E0B;

    private final Paint mBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mInnerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mVectorLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mKnobFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mKnobBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mKnobDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mBounds = new RectF();

    private float mOpacity = 0.78f;
    private float mSizeScale = 1.0f;
    private boolean mDynamicMode = true;
    private boolean mCustomizeHighlight = false;

    private int mActivePointerId = MotionEvent.INVALID_POINTER_ID;
    private boolean mTracking = false;
    private float mKnobNormX = 0f;
    private float mKnobNormY = 0f;

    // Key output states
    private boolean mKeyForward = false;
    private boolean mKeyBackward = false;
    private boolean mKeyLeft = false;
    private boolean mKeyRight = false;

    // Dynamic reposition offset relative to view rest frame
    private float mDynamicOffsetX = 0f;
    private float mDynamicOffsetY = 0f;
    private float mRestTranslationX = 0f;
    private float mRestTranslationY = 0f;
    private final float mDensity;
    private StarHudInputBridge mInputBridge = StarHudInputBridge.DEFAULT;

    public StarJoystickView(@NonNull Context context) {
        this(context, null);
    }

    public StarJoystickView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public StarJoystickView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mDensity = context.getResources().getDisplayMetrics().density;
        initPaints();
        setClickable(false);
        setFocusable(false);
        setOutlineProvider(null);
        setTranslationZ(4f * mDensity);
    }

    private void initPaints() {
        mBgPaint.setStyle(Paint.Style.FILL);
        mInnerPaint.setStyle(Paint.Style.FILL);

        mBorderPaint.setStyle(Paint.Style.STROKE);
        mTickPaint.setStyle(Paint.Style.STROKE);
        mTickPaint.setStrokeCap(Paint.Cap.ROUND);

        mVectorLinePaint.setStyle(Paint.Style.STROKE);
        mVectorLinePaint.setStrokeCap(Paint.Cap.ROUND);

        mKnobFillPaint.setStyle(Paint.Style.FILL);
        mKnobBorderPaint.setStyle(Paint.Style.STROKE);
        mKnobDotPaint.setStyle(Paint.Style.FILL);
    }

    public void setInputBridge(@NonNull StarHudInputBridge inputBridge) {
        mInputBridge = inputBridge;
        StarHudInputManager.getInstance().setInputBridge(inputBridge);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        StarHudInputManager.getInstance().registerResetParticipant(this);
    }

    @Override
    public void onGlobalInputReset(@NonNull String reason) {
        mActivePointerId = MotionEvent.INVALID_POINTER_ID;
        mTracking = false;
        mKnobNormX = 0f;
        mKnobNormY = 0f;
        mDynamicOffsetX = 0f;
        mDynamicOffsetY = 0f;
        mKeyForward = false;
        mKeyBackward = false;
        mKeyLeft = false;
        mKeyRight = false;
        setTranslationX(mRestTranslationX);
        setTranslationY(mRestTranslationY);
        invalidate();
    }

    public void configure(float opacity, float sizeScale, boolean dynamicMode) {
        mOpacity = Math.max(0.20f, Math.min(1.0f, opacity));
        mSizeScale = Math.max(0.65f, Math.min(1.50f, sizeScale));
        mDynamicMode = dynamicMode;
        invalidate();
    }

    public void setCustomizeHighlight(boolean highlight) {
        if (mCustomizeHighlight != highlight) {
            mCustomizeHighlight = highlight;
            if (highlight) {
                releaseAllKeys();
            }
            invalidate();
        }
    }

    public void setRestTranslation(float tx, float ty) {
        mRestTranslationX = tx;
        mRestTranslationY = ty;
        if (!mTracking) {
            setTranslationX(tx);
            setTranslationY(ty);
        }
    }

    public int getActivePointerId() {
        return mActivePointerId;
    }

    public boolean isTracking() {
        return mTracking;
    }

    public boolean isForwardPressed() {
        return mKeyForward;
    }

    public boolean isBackwardPressed() {
        return mKeyBackward;
    }

    public boolean isLeftPressed() {
        return mKeyLeft;
    }

    public boolean isRightPressed() {
        return mKeyRight;
    }

    public float getKnobNormalizedX() {
        return mKnobNormX;
    }

    public float getKnobNormalizedY() {
        return mKnobNormY;
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility != VISIBLE) {
            releaseAllKeys();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        releaseAllKeys();
        StarHudInputManager.getInstance().unregisterResetParticipant(this);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float density = mDensity;
        float cx = w * 0.5f;
        float cy = h * 0.5f;
        float borderStroke = Math.max(1.5f * density, Math.min(w, h) * 0.015f);
        float outerRadius = Math.min(w, h) * 0.5f - borderStroke - 1f;
        if (outerRadius <= 4f) return;

        int masterAlpha = Math.round(255f * mOpacity);
        int bgAlpha = Math.round(masterAlpha * 0.80f);
        int innerAlpha = Math.round(masterAlpha * 0.48f);
        int borderAlpha = mTracking ? Math.min(255, Math.round(masterAlpha * 1.15f)) : Math.round(masterAlpha * 0.85f);

        // Outer translucent disc
        mBgPaint.setColor(COLOR_RING_BG);
        mBgPaint.setAlpha(bgAlpha);
        canvas.drawCircle(cx, cy, outerRadius, mBgPaint);

        // Subtle inner guide ring
        mInnerPaint.setColor(COLOR_INNER_WELL);
        mInnerPaint.setAlpha(innerAlpha);
        canvas.drawCircle(cx, cy, outerRadius * 0.62f, mInnerPaint);

        // Outer border
        mBorderPaint.setStrokeWidth(borderStroke);
        if (mCustomizeHighlight) {
            mBorderPaint.setColor(COLOR_CUSTOMIZE_BORDER);
            mBorderPaint.setAlpha(240);
        } else {
            mBorderPaint.setColor(mTracking ? COLOR_BORDER_ACTIVE : COLOR_BORDER_IDLE);
            mBorderPaint.setAlpha(borderAlpha);
        }
        canvas.drawCircle(cx, cy, outerRadius, mBorderPaint);

        // Clean cardinal orientation ticks (N / E / S / W) inside rim
        mTickPaint.setColor(mTracking ? COLOR_CYAN : 0xFF94A3B8);
        mTickPaint.setAlpha(Math.round(masterAlpha * (mTracking ? 0.72f : 0.42f)));
        mTickPaint.setStrokeWidth(Math.max(1.5f, borderStroke * 0.95f));
        float tickOuter = outerRadius * 0.88f;
        float tickInner = outerRadius * 0.76f;
        canvas.drawLine(cx, cy - tickOuter, cx, cy - tickInner, mTickPaint);
        canvas.drawLine(cx, cy + tickInner, cx, cy + tickOuter, mTickPaint);
        canvas.drawLine(cx - tickOuter, cy, cx - tickInner, cy, mTickPaint);
        canvas.drawLine(cx + tickInner, cy, cx + tickOuter, cy, mTickPaint);

        // Knob geometry
        float maxTravel = outerRadius * KNOB_TRAVEL_RATIO;
        float knobX = cx + mKnobNormX * maxTravel;
        float knobY = cy + mKnobNormY * maxTravel;
        float knobRadius = outerRadius * KNOB_RADIUS_RATIO;

        // Subtle directional tether line when displaced
        if (mTracking && (Math.abs(mKnobNormX) > 0.06f || Math.abs(mKnobNormY) > 0.06f)) {
            mVectorLinePaint.setColor(COLOR_CYAN);
            mVectorLinePaint.setAlpha(Math.round(masterAlpha * 0.45f));
            mVectorLinePaint.setStrokeWidth(Math.max(2f, borderStroke * 1.15f));
            canvas.drawLine(cx, cy, knobX, knobY, mVectorLinePaint);
        }

        // Thumb knob
        mKnobFillPaint.setColor(mTracking ? COLOR_KNOB_ACTIVE : COLOR_KNOB_FILL);
        mKnobFillPaint.setAlpha(mTracking ? Math.min(255, Math.round(masterAlpha * 1.18f)) : Math.round(masterAlpha * 0.92f));
        canvas.drawCircle(knobX, knobY, knobRadius, mKnobFillPaint);

        mKnobBorderPaint.setColor(mTracking ? COLOR_CYAN : COLOR_BORDER_ACTIVE);
        mKnobBorderPaint.setAlpha(Math.min(255, Math.round(masterAlpha * (mTracking ? 1.25f : 0.72f))));
        mKnobBorderPaint.setStrokeWidth(borderStroke * 1.1f);
        canvas.drawCircle(knobX, knobY, knobRadius, mKnobBorderPaint);

        mKnobDotPaint.setColor(mTracking ? 0xFFFFFFFF : COLOR_CYAN);
        mKnobDotPaint.setAlpha(Math.min(255, Math.round(masterAlpha * (mTracking ? 0.95f : 0.65f))));
        canvas.drawCircle(knobX, knobY, knobRadius * 0.28f, mKnobDotPaint);
    }

    @Override
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        if (mCustomizeHighlight || !isEnabled() || getVisibility() != VISIBLE) {
            return false;
        }
        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                if (mActivePointerId != MotionEvent.INVALID_POINTER_ID) {
                    return true;
                }
                int idx = event.getActionIndex();
                mActivePointerId = event.getPointerId(idx);
                mTracking = true;
                updateVectorFromLocalCoords(event.getX(idx), event.getY(idx));
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mActivePointerId == MotionEvent.INVALID_POINTER_ID) return false;
                int idx = event.findPointerIndex(mActivePointerId);
                if (idx >= 0) {
                    updateVectorFromLocalCoords(event.getX(idx), event.getY(idx));
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                int idx = event.getActionIndex();
                if (event.getPointerId(idx) == mActivePointerId) {
                    releaseAllKeys();
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                releaseAllKeys();
                return true;
            default:
                return false;
        }
    }

    /**
     * Begins a dynamic joystick session from overlay-space coordinates.
     * Centers the floating joystick pad at (overlayX, overlayY) clamped inside parent bounds.
     */
    public void beginDynamicSession(int pointerId, float overlayX, float overlayY, RectF zoneBounds) {
        if (mCustomizeHighlight) return;
        mActivePointerId = pointerId;
        mTracking = true;

        float restCenterX = getLeft() + mRestTranslationX + getWidth() * 0.5f;
        float restCenterY = getTop() + mRestTranslationY + getHeight() * 0.5f;
        float halfW = getWidth() * 0.5f;
        float halfH = getHeight() * 0.5f;

        float targetCenterX = overlayX;
        float targetCenterY = overlayY;
        if (zoneBounds != null && zoneBounds.width() > getWidth() && zoneBounds.height() > getHeight()) {
            targetCenterX = Math.max(zoneBounds.left + halfW, Math.min(zoneBounds.right - halfW, overlayX));
            targetCenterY = Math.max(zoneBounds.top + halfH, Math.min(zoneBounds.bottom - halfH, overlayY));
        }

        mDynamicOffsetX = targetCenterX - restCenterX;
        mDynamicOffsetY = targetCenterY - restCenterY;
        setTranslationX(mRestTranslationX + mDynamicOffsetX);
        setTranslationY(mRestTranslationY + mDynamicOffsetY);

        updateVectorFromLocalCoords(overlayX - (getLeft() + getTranslationX()), overlayY - (getTop() + getTranslationY()));
        invalidate();
    }

    public void moveDynamicSession(float overlayX, float overlayY) {
        if (!mTracking) return;
        float localX = overlayX - (getLeft() + getTranslationX());
        float localY = overlayY - (getTop() + getTranslationY());
        updateVectorFromLocalCoords(localX, localY);
    }

    public void endDynamicSession() {
        releaseAllKeys();
    }

    /**
     * Updates knob coordinates and WASD keys from view-local coordinates.
     */
    public void updateVectorFromLocalCoords(float localX, float localY) {
        float cx = getWidth() * 0.5f;
        float cy = getHeight() * 0.5f;
        float outerRadius = Math.max(16f, Math.min(getWidth(), getHeight()) * 0.5f - 4f);
        float maxTravel = outerRadius * KNOB_TRAVEL_RATIO;

        float dx = localX - cx;
        float dy = localY - cy;
        float dist = (float) Math.hypot(dx, dy);

        float normX = dx / maxTravel;
        float normY = dy / maxTravel;
        float normMag = dist / maxTravel;
        if (normMag > 1.0f && normMag > 0.0001f) {
            normX /= normMag;
            normY /= normMag;
            normMag = 1.0f;
        }

        float prevKnobX = mKnobNormX;
        float prevKnobY = mKnobNormY;
        boolean prevF = mKeyForward;
        boolean prevB = mKeyBackward;
        boolean prevL = mKeyLeft;
        boolean prevR = mKeyRight;

        mKnobNormX = normX;
        mKnobNormY = normY;

        applyDirectionalInputs(normX, normY, normMag);
        boolean keysChanged = (prevF != mKeyForward) || (prevB != mKeyBackward)
                || (prevL != mKeyLeft) || (prevR != mKeyRight);
        if (keysChanged || Math.abs(normX - prevKnobX) >= 0.005f || Math.abs(normY - prevKnobY) >= 0.005f) {
            invalidate();
        }
    }

    /**
     * Converts normalized vector [-1, 1] into smooth WASD inputs with hysteresis.
     */
    void applyDirectionalInputs(float normX, float normY, float normMag) {
        if (normMag < RADIAL_DEADZONE) {
            setDirectionStates(false, false, false, false);
            return;
        }

        boolean nextForward = mKeyForward
                ? (-normY) >= AXIS_RELEASE_THRESHOLD
                : (-normY) >= AXIS_PRESS_THRESHOLD;
        boolean nextBackward = mKeyBackward
                ? normY >= AXIS_RELEASE_THRESHOLD
                : normY >= AXIS_PRESS_THRESHOLD;
        boolean nextLeft = mKeyLeft
                ? (-normX) >= AXIS_RELEASE_THRESHOLD
                : (-normX) >= AXIS_PRESS_THRESHOLD;
        boolean nextRight = mKeyRight
                ? normX >= AXIS_RELEASE_THRESHOLD
                : normX >= AXIS_PRESS_THRESHOLD;

        if (nextForward && nextBackward) {
            if (-normY >= normY) nextBackward = false;
            else nextForward = false;
        }
        if (nextLeft && nextRight) {
            if (-normX >= normX) nextRight = false;
            else nextLeft = false;
        }

        setDirectionStates(nextForward, nextBackward, nextLeft, nextRight);
    }

    private void setDirectionStates(boolean forward, boolean backward, boolean left, boolean right) {
        if (mKeyForward != forward) {
            mKeyForward = forward;
            dispatchKey(LwjglGlfwKeycode.GLFW_KEY_W, forward);
        }
        if (mKeyBackward != backward) {
            mKeyBackward = backward;
            dispatchKey(LwjglGlfwKeycode.GLFW_KEY_S, backward);
        }
        if (mKeyLeft != left) {
            mKeyLeft = left;
            dispatchKey(LwjglGlfwKeycode.GLFW_KEY_A, left);
        }
        if (mKeyRight != right) {
            mKeyRight = right;
            dispatchKey(LwjglGlfwKeycode.GLFW_KEY_D, right);
        }
    }

    public void releaseAllKeys() {
        mActivePointerId = MotionEvent.INVALID_POINTER_ID;
        mTracking = false;
        mKnobNormX = 0f;
        mKnobNormY = 0f;
        mDynamicOffsetX = 0f;
        mDynamicOffsetY = 0f;
        setTranslationX(mRestTranslationX);
        setTranslationY(mRestTranslationY);
        setDirectionStates(false, false, false, false);
        invalidate();
    }

    private void dispatchKey(int keycode, boolean isDown) {
        String leaseId = "joystick:" + keycode;
        if (isDown) {
            StarHudInputManager.getInstance().acquireKeyLease(
                    leaseId,
                    "joystick",
                    mActivePointerId,
                    "JOYSTICK_" + StarHudInputManager.formatKeycode(keycode),
                    keycode,
                    false
            );
        } else {
            StarHudInputManager.getInstance().releaseLease(leaseId, "joystick_release");
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int targetPx = Math.round(DEFAULT_DIAMETER_DP * mSizeScale * mDensity);
        int width = resolveSize(targetPx, widthMeasureSpec);
        int height = resolveSize(targetPx, heightMeasureSpec);
        int square = Math.min(width, height);
        setMeasuredDimension(square, square);
    }
}
