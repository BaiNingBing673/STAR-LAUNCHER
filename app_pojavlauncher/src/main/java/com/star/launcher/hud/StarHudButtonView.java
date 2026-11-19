package com.star.launcher.hud;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.MainActivity;

/**
 * Modular STAR Launcher controller button backed by {@link StarHudButtonConfig},
 * {@link StarHudActionRegistry}, and {@link StarHudInputManager}.
 *
 * Actions are resolved centrally (never hardcoded in the view) and every hold/toggle
 * lease is reference-counted per pointer ID and guaranteed to release on UP, CANCEL,
 * missing pointer index, visibility loss, detach, or lifecycle transitions.
 */
public class StarHudButtonView extends View implements
        StarHudInputManager.ResetParticipant,
        StarHudInputManager.PointerReconciliationParticipant {
    public static final int ACTION_ELEMENT = 0;
    public static final int ACTION_KEYBOARD = 1;
    public static final int ACTION_VIRTUAL_MOUSE = 2;

    private static final int COLOR_BG_IDLE = 0xFF141926;
    private static final int COLOR_BG_PRESSED = 0xFF213152;
    private static final int COLOR_BG_TOGGLED = 0xFF1D3A6B;
    private static final int COLOR_BORDER_IDLE = 0xFF33415C;
    private static final int COLOR_BORDER_ACTIVE = 0xFF5FA8FF;
    private static final int COLOR_BORDER_CUSTOMIZE = 0xFFF59E0B;
    private static final int COLOR_BORDER_SELECTED = 0xFF5EE2FF;
    private static final int COLOR_ICON_IDLE = 0xFFE2E8F0;
    private static final int COLOR_ICON_ACTIVE = 0xFF5EE2FF;
    private static final int COLOR_SUBLABEL = 0xFF94A3B8;

    private final int mSpecialAction;
    @Nullable private final StarHudElementId mLegacyElementId;

    private final Paint mBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mIconStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mIconFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private final RectF mIconBounds = new RectF();
    private final Path mScratchPath = new Path();

    @NonNull
    private StarHudButtonConfig mButtonConfig;
    private float mGlobalScale = 1.0f;
    private float mGlobalOpacity = 0.78f;
    private boolean mCustomizeHighlight = false;
    private boolean mEditSelected = false;
    private boolean mHiddenInPlayPreview = false;

    private int mActivePointerId = MotionEvent.INVALID_POINTER_ID;
    @Nullable private String mActiveLeaseId = null;
    private boolean mIsPressed = false;
    private boolean mIsToggled = false;
    private boolean mToggledOnDuringCurrentTouch = false;
    private boolean mAttachedToManager = false;

    public StarHudButtonView(@NonNull Context context, @NonNull StarHudElementId elementId) {
        this(context, elementId, ACTION_ELEMENT);
    }

    public StarHudButtonView(@NonNull Context context, @Nullable StarHudElementId elementId, int specialAction) {
        super(context);
        mLegacyElementId = elementId;
        mSpecialAction = specialAction;
        if (specialAction == ACTION_KEYBOARD) {
            mButtonConfig = StarHudConfig.createButtonForAction(
                    "special_keyboard", StarHudActionType.CUSTOM_KEY, 0, 0f, 0f
            ).copy(
                    "special_keyboard", StarHudActionType.CUSTOM_KEY, 0, 0f, 0f,
                    46f, 40f, 1.0f, 0.80f, StarHudIconId.KEY_CAP, "KB", true, StarHudInteractionMode.TAP, false
            );
        } else if (specialAction == ACTION_VIRTUAL_MOUSE) {
            mButtonConfig = StarHudConfig.createButtonForAction(
                    "special_vmouse", StarHudActionType.CUSTOM_KEY, 0, 0f, 0f
            ).copy(
                    "special_vmouse", StarHudActionType.CUSTOM_KEY, 0, 0f, 0f,
                    46f, 40f, 1.0f, 0.80f, StarHudIconId.PICK_BLOCK, "PTR", true, StarHudInteractionMode.TAP, false
            );
        } else if (elementId != null) {
            StarHudActionType act = elementId.toActionType();
            String id = elementId.toBuiltInButtonId() != null ? elementId.toBuiltInButtonId() : elementId.name().toLowerCase();
            mButtonConfig = new StarHudButtonConfig(
                    id,
                    act,
                    0,
                    elementId.getDefaultXFraction(),
                    elementId.getDefaultYFraction(),
                    act.getDefaultWidthDp(),
                    act.getDefaultHeightDp(),
                    1.0f,
                    0.80f,
                    act.getDefaultIcon(),
                    act.getDefaultShortLabel(),
                    elementId.getDefaultEnabled(),
                    act.getDefaultInteractionMode(),
                    true
            );
        } else {
            mButtonConfig = StarHudConfig.createButtonForAction(
                    "custom_btn", StarHudActionType.CUSTOM_KEY, 0, 0.5f, 0.5f
            );
        }
        initViewCommon(context);
    }

    public StarHudButtonView(@NonNull Context context, @NonNull StarHudButtonConfig buttonConfig) {
        super(context);
        mSpecialAction = ACTION_ELEMENT;
        mButtonConfig = buttonConfig.sanitized();
        mLegacyElementId = StarHudElementId.fromBuiltInButtonId(mButtonConfig.getId());
        initViewCommon(context);
    }

    private void initViewCommon(Context context) {
        initPaints();
        setClickable(false);
        setFocusable(false);
        setOutlineProvider(null);
        setTranslationZ(4f * context.getResources().getDisplayMetrics().density);
    }

    private void initPaints() {
        mBgPaint.setStyle(Paint.Style.FILL);
        mBorderPaint.setStyle(Paint.Style.STROKE);

        mIconStrokePaint.setStyle(Paint.Style.STROKE);
        mIconStrokePaint.setStrokeCap(Paint.Cap.ROUND);
        mIconStrokePaint.setStrokeJoin(Paint.Join.ROUND);

        mIconFillPaint.setStyle(Paint.Style.FILL);

        mLabelPaint.setTextAlign(Paint.Align.CENTER);
        mLabelPaint.setFakeBoldText(true);

        mDotPaint.setStyle(Paint.Style.FILL);
    }

    @NonNull
    public String getButtonId() {
        return mButtonConfig.getId();
    }

    @NonNull
    public StarHudButtonConfig getButtonConfig() {
        return mButtonConfig;
    }

    @Nullable
    public StarHudElementId getElementId() {
        return mLegacyElementId;
    }

    public int getSpecialAction() {
        return mSpecialAction;
    }

    public void setInputBridge(@NonNull StarHudInputBridge inputBridge) {
        StarHudInputManager.getInstance().setInputBridge(inputBridge);
    }

    public void applyButtonConfig(@NonNull StarHudButtonConfig buttonConfig, float globalScale, float globalOpacity) {
        StarHudButtonConfig next = buttonConfig.sanitized();
        boolean bindingChanged = next.getAction() != mButtonConfig.getAction()
                || next.getCustomKeycode() != mButtonConfig.getCustomKeycode()
                || next.getInteractionMode() != mButtonConfig.getInteractionMode()
                || !next.getVisible();
        float clampedScale = Math.max(0.65f, Math.min(1.50f, globalScale));
        float clampedOpacity = Math.max(0.20f, Math.min(1.0f, globalOpacity));
        boolean sizeChanged = Math.abs(next.getWidthDp() - mButtonConfig.getWidthDp()) > 0.01f
                || Math.abs(next.getHeightDp() - mButtonConfig.getHeightDp()) > 0.01f
                || Math.abs(clampedScale - mGlobalScale) > 0.005f;
        boolean positionChanged = Math.abs(next.getXFraction() - mButtonConfig.getXFraction()) > 0.0005f
                || Math.abs(next.getYFraction() - mButtonConfig.getYFraction()) > 0.0005f;
        if (bindingChanged || sizeChanged || positionChanged) {
            releaseActiveLease("config_changed");
        }
        mButtonConfig = next;
        mGlobalScale = clampedScale;
        mGlobalOpacity = clampedOpacity;
        if (sizeChanged) {
            requestLayout();
        }
        invalidate();
    }

    public void configure(float scale, float opacity, boolean toggleMode) {
        float clampedScale = Math.max(0.65f, Math.min(1.50f, scale));
        boolean sizeChanged = Math.abs(clampedScale - mGlobalScale) > 0.005f;
        mGlobalScale = clampedScale;
        mGlobalOpacity = Math.max(0.20f, Math.min(1.0f, opacity));
        if (mSpecialAction == ACTION_ELEMENT && supportsToggle()) {
            StarHudInteractionMode targetMode = toggleMode ? StarHudInteractionMode.TOGGLE : StarHudInteractionMode.HOLD;
            if (mButtonConfig.getInteractionMode() != targetMode) {
                if (!toggleMode && mIsToggled) {
                    releaseActiveLease("toggle_mode_disabled");
                }
                mButtonConfig = mButtonConfig.copy(
                        mButtonConfig.getId(),
                        mButtonConfig.getAction(),
                        mButtonConfig.getCustomKeycode(),
                        mButtonConfig.getXFraction(),
                        mButtonConfig.getYFraction(),
                        mButtonConfig.getWidthDp(),
                        mButtonConfig.getHeightDp(),
                        mButtonConfig.getOpacity(),
                        mButtonConfig.getBackgroundAlpha(),
                        mButtonConfig.getIcon(),
                        mButtonConfig.getLabel(),
                        mButtonConfig.getVisible(),
                        targetMode,
                        mButtonConfig.isBuiltIn()
                );
            }
        }
        if (sizeChanged) {
            requestLayout();
        }
        invalidate();
    }

    public boolean supportsToggle() {
        return mButtonConfig.getAction().getAllowHoldOrToggle();
    }

    public void setCustomizeHighlight(boolean highlight) {
        if (mCustomizeHighlight != highlight) {
            mCustomizeHighlight = highlight;
            if (highlight) {
                forceReleaseAll();
            } else {
                mEditSelected = false;
            }
            invalidate();
        }
    }

    public void setEditSelected(boolean selected) {
        if (mEditSelected != selected) {
            mEditSelected = selected;
            invalidate();
        }
    }

    public void setHiddenInPlayPreview(boolean hidden) {
        if (mHiddenInPlayPreview != hidden) {
            mHiddenInPlayPreview = hidden;
            invalidate();
        }
    }

    public boolean isButtonPressed() {
        return mIsPressed;
    }

    public boolean isToggledOn() {
        return mIsToggled;
    }

    public boolean isInputDown() {
        return mActiveLeaseId != null && (mIsPressed || mIsToggled);
    }

    public int getActivePointerId() {
        return mActivePointerId;
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ensureRegisteredWithManager();
    }

    private void ensureRegisteredWithManager() {
        if (!mAttachedToManager) {
            StarHudInputManager manager = StarHudInputManager.getInstance();
            manager.registerResetParticipant(this);
            manager.registerPointerParticipant(this);
            mAttachedToManager = true;
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (!enabled) {
            forceReleaseWithReason("disabled");
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus) {
            forceReleaseWithReason("window_focus_lost");
        }
    }

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility != VISIBLE) {
            forceReleaseWithReason("visibility_hidden");
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        forceReleaseWithReason("detached");
        if (mAttachedToManager) {
            StarHudInputManager manager = StarHudInputManager.getInstance();
            manager.unregisterResetParticipant(this);
            manager.unregisterPointerParticipant(this);
            mAttachedToManager = false;
        }
        super.onDetachedFromWindow();
    }

    @Override
    public void onGlobalInputReset(@NonNull String reason) {
        mActivePointerId = MotionEvent.INVALID_POINTER_ID;
        mActiveLeaseId = null;
        mIsPressed = false;
        mIsToggled = false;
        mToggledOnDuringCurrentTouch = false;
        invalidate();
    }

    @Override
    public void onReconcileActivePointers(int activePointerIdBitmask, @NonNull String reason) {
        if (mActivePointerId != MotionEvent.INVALID_POINTER_ID) {
            boolean present = mActivePointerId >= 0
                    && mActivePointerId < 32
                    && (activePointerIdBitmask & (1 << mActivePointerId)) != 0;
            if (!present) {
                onPointerReleased(true, "reconcile_pointer:" + reason);
            }
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        float density = getResources().getDisplayMetrics().density;
        float baseWidthDp = mSpecialAction != ACTION_ELEMENT ? 46f : mButtonConfig.getWidthDp();
        float baseHeightDp = mSpecialAction != ACTION_ELEMENT ? 40f : mButtonConfig.getHeightDp();

        int w = resolveSize(Math.round(baseWidthDp * mGlobalScale * density), widthMeasureSpec);
        int h = resolveSize(Math.round(baseHeightDp * mGlobalScale * density), heightMeasureSpec);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float density = getResources().getDisplayMetrics().density;
        float minDim = Math.min(w, h);
        float borderStroke = Math.max(1.25f * density, minDim * 0.025f);
        float pad = borderStroke * 0.6f + 1f;
        mRect.set(pad, pad, w - pad, h - pad);

        boolean isActive = mIsPressed || mIsToggled;
        boolean isCircularPrimary = (mSpecialAction == ACTION_ELEMENT
                && mButtonConfig.getAction() == StarHudActionType.JUMP
                && Math.abs(w - h) <= Math.max(4, Math.round(6f * density)));
        float cornerRadius = isCircularPrimary ? minDim * 0.5f : minDim * 0.28f;

        float combinedOpacity = mGlobalOpacity * mButtonConfig.getOpacity();
        if (mCustomizeHighlight && mHiddenInPlayPreview) {
            combinedOpacity *= 0.42f;
        }
        int masterAlpha = Math.max(20, Math.min(255, Math.round(255f * combinedOpacity)));

        float bgFactor = mButtonConfig.getBackgroundAlpha();
        int bgAlpha = isActive
                ? Math.min(255, Math.round(masterAlpha * Math.max(0.35f, bgFactor) * 1.15f))
                : Math.round(masterAlpha * bgFactor);
        int borderAlpha = (isActive || mCustomizeHighlight)
                ? Math.min(255, Math.round(masterAlpha * 1.25f))
                : Math.round(masterAlpha * 0.85f);

        mBgPaint.setColor(mIsPressed ? COLOR_BG_PRESSED : (mIsToggled ? COLOR_BG_TOGGLED : COLOR_BG_IDLE));
        mBgPaint.setAlpha(bgAlpha);
        canvas.drawRoundRect(mRect, cornerRadius, cornerRadius, mBgPaint);

        if (mEditSelected && mCustomizeHighlight) {
            mBorderPaint.setStrokeWidth(borderStroke * 1.55f);
            mBorderPaint.setColor(COLOR_BORDER_SELECTED);
            mBorderPaint.setAlpha(255);
        } else if (mCustomizeHighlight) {
            mBorderPaint.setStrokeWidth(borderStroke);
            mBorderPaint.setColor(COLOR_BORDER_CUSTOMIZE);
            mBorderPaint.setAlpha(235);
        } else {
            mBorderPaint.setStrokeWidth(borderStroke);
            mBorderPaint.setColor(isActive ? COLOR_BORDER_ACTIVE : COLOR_BORDER_IDLE);
            mBorderPaint.setAlpha(borderAlpha);
        }
        canvas.drawRoundRect(mRect, cornerRadius, cornerRadius, mBorderPaint);

        String shortLabel = resolveShortLabel();
        StarHudIconId iconId = mButtonConfig.getIcon();
        boolean hasIcon = (mSpecialAction != ACTION_ELEMENT) || (iconId != null && iconId != StarHudIconId.NONE);
        boolean hasLabel = (shortLabel != null && !shortLabel.isEmpty() && (minDim >= 30f * density || !hasIcon));

        int iconColor = isActive ? COLOR_ICON_ACTIVE : COLOR_ICON_IDLE;
        int iconAlpha = Math.min(255, Math.round(masterAlpha * (isActive ? 1.25f : 1.05f)));
        mIconStrokePaint.setColor(iconColor);
        mIconStrokePaint.setAlpha(iconAlpha);
        mIconStrokePaint.setStrokeWidth(Math.max(1.8f * density, minDim * 0.044f));
        mIconFillPaint.setColor(iconColor);
        mIconFillPaint.setAlpha(iconAlpha);

        if (hasIcon) {
            float iconScale = hasLabel ? 0.36f : 0.44f;
            float iconR = minDim * iconScale * 0.5f;
            float iconCenterY = hasLabel ? h * 0.42f : h * 0.5f;
            float cx = w * 0.5f;
            mIconBounds.set(cx - iconR, iconCenterY - iconR, cx + iconR, iconCenterY + iconR);
            if (mSpecialAction == ACTION_KEYBOARD) {
                StarHudIcons.drawSpecialIcon(canvas, StarHudIcons.SPECIAL_KEYBOARD, mIconBounds, mIconStrokePaint, mIconFillPaint);
            } else if (mSpecialAction == ACTION_VIRTUAL_MOUSE) {
                StarHudIcons.drawSpecialIcon(canvas, StarHudIcons.SPECIAL_VIRTUAL_MOUSE, mIconBounds, mIconStrokePaint, mIconFillPaint);
            } else {
                StarHudIcons.drawIcon(canvas, iconId, mIconBounds, mIconStrokePaint, mIconFillPaint, mScratchPath);
            }
        }

        if (hasLabel) {
            mLabelPaint.setColor(isActive ? COLOR_ICON_ACTIVE : (hasIcon ? COLOR_SUBLABEL : COLOR_ICON_IDLE));
            mLabelPaint.setAlpha(Math.min(255, Math.round(masterAlpha * 1.1f)));
            float textY = hasIcon ? (h * 0.84f) : (h * 0.5f + minDim * 0.11f);
            float textSize = hasIcon ? Math.max(8f * density, minDim * 0.165f) : Math.max(10f * density, minDim * 0.25f);
            mLabelPaint.setTextSize(textSize);
            canvas.drawText(shortLabel, w * 0.5f, textY, mLabelPaint);
        }

        // Toggle active indicator dot
        if (mSpecialAction == ACTION_ELEMENT && mButtonConfig.getInteractionMode() == StarHudInteractionMode.TOGGLE && mIsToggled) {
            mDotPaint.setColor(COLOR_ICON_ACTIVE);
            mDotPaint.setAlpha(245);
            canvas.drawCircle(w - pad - minDim * 0.16f, pad + minDim * 0.16f, Math.max(2.5f * density, minDim * 0.055f), mDotPaint);
        }

        // Edit-mode corner resize handle indicator
        if (mCustomizeHighlight && mEditSelected) {
            float handleR = Math.max(4f * density, minDim * 0.09f);
            mDotPaint.setColor(COLOR_BORDER_SELECTED);
            mDotPaint.setAlpha(255);
            canvas.drawCircle(w - pad - handleR * 0.4f, h - pad - handleR * 0.4f, handleR * 0.7f, mDotPaint);
        }
    }

    @Nullable
    private String resolveShortLabel() {
        if (mSpecialAction == ACTION_KEYBOARD) return "KB";
        if (mSpecialAction == ACTION_VIRTUAL_MOUSE) return "PTR";
        String lbl = mButtonConfig.effectiveShortLabel();
        return lbl.isEmpty() ? null : lbl;
    }

    private boolean isPointInHitbox(float localX, float localY) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return true;
        }
        return localX >= 0f && localX <= w && localY >= 0f && localY <= h;
    }

    @Override
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        if (mCustomizeHighlight || !isEnabled() || getVisibility() != VISIBLE) {
            if (mActivePointerId != MotionEvent.INVALID_POINTER_ID
                    || (mActiveLeaseId != null && mButtonConfig.getInteractionMode() == StarHudInteractionMode.HOLD)) {
                onPointerReleased(true, "touch_while_inactive");
            }
            return false;
        }
        ensureRegisteredWithManager();
        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                int idx = event.getActionIndex();
                if (idx < 0 || idx >= event.getPointerCount()) {
                    return false;
                }
                int pointerId = event.getPointerId(idx);
                if (pointerId == MotionEvent.INVALID_POINTER_ID) {
                    return false;
                }
                if (mActivePointerId != MotionEvent.INVALID_POINTER_ID
                        || (mActiveLeaseId != null && mButtonConfig.getInteractionMode() == StarHudInteractionMode.HOLD)) {
                    // ACTION_DOWN always begins a new touch sequence on this view; any previous active pointer is stale.
                    onPointerReleased(true, "stale_pointer_on_action_down");
                }
                if (!isPointInHitbox(event.getX(idx), event.getY(idx))) {
                    return false;
                }
                mActivePointerId = pointerId;
                onPointerPressed(pointerId);
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                int idx = event.getActionIndex();
                if (idx < 0 || idx >= event.getPointerCount()) {
                    return false;
                }
                int pointerId = event.getPointerId(idx);
                if (pointerId == MotionEvent.INVALID_POINTER_ID) {
                    return false;
                }
                if (!isPointInHitbox(event.getX(idx), event.getY(idx))) {
                    return false;
                }
                if (mActivePointerId != MotionEvent.INVALID_POINTER_ID) {
                    // Already held by an active finger: never duplicate DOWN or transfer ownership mid-hold.
                    return true;
                }
                if (mActiveLeaseId != null && mButtonConfig.getInteractionMode() == StarHudInteractionMode.HOLD) {
                    releaseActiveLease("stale_lease_cleanup");
                }
                mActivePointerId = pointerId;
                onPointerPressed(pointerId);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mActivePointerId == MotionEvent.INVALID_POINTER_ID) return false;
                int idx = event.findPointerIndex(mActivePointerId);
                if (idx < 0) {
                    onPointerReleased(true, "pointer_missing");
                    return true;
                }
                if (mSpecialAction == ACTION_ELEMENT
                        && mButtonConfig.getInteractionMode() == StarHudInteractionMode.HOLD
                        && !isPointInHitbox(event.getX(idx), event.getY(idx))) {
                    onPointerReleased(true, "pointer_left_hitbox");
                    return true;
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                if (mActivePointerId == MotionEvent.INVALID_POINTER_ID) return false;
                int idx = event.getActionIndex();
                if (idx >= 0 && idx < event.getPointerCount() && event.getPointerId(idx) == mActivePointerId) {
                    onPointerReleased(false, "action_pointer_up");
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_UP: {
                if (mActivePointerId == MotionEvent.INVALID_POINTER_ID) {
                    if (mActiveLeaseId != null && mButtonConfig.getInteractionMode() == StarHudInteractionMode.HOLD) {
                        releaseActiveLease("up_orphan_cleanup");
                        invalidate();
                    }
                    return false;
                }
                int idx = event.getActionIndex();
                int pointerId = (idx >= 0 && idx < event.getPointerCount())
                        ? event.getPointerId(idx)
                        : MotionEvent.INVALID_POINTER_ID;
                if (pointerId == mActivePointerId) {
                    onPointerReleased(false, "action_up");
                    return true;
                }
                // ACTION_UP signals all pointers in this view's stream have lifted; ensure cleanup even on ID mismatch.
                onPointerReleased(true, "action_up_mismatch");
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                if (mActivePointerId == MotionEvent.INVALID_POINTER_ID) {
                    if (mActiveLeaseId != null && mButtonConfig.getInteractionMode() == StarHudInteractionMode.HOLD) {
                        releaseActiveLease("cancel_orphan_cleanup");
                        invalidate();
                    }
                    return false;
                }
                onPointerReleased(true, "action_cancel");
                return true;
            default:
                return false;
        }
    }

    private void onPointerPressed(int pointerId) {
        mIsPressed = true;
        mToggledOnDuringCurrentTouch = false;
        try {
            if (mSpecialAction == ACTION_KEYBOARD) {
                MainActivity.switchKeyboardState();
                invalidate();
                return;
            }
            if (mSpecialAction == ACTION_VIRTUAL_MOUSE) {
                MainActivity.toggleMouse(getContext());
                invalidate();
                return;
            }

            StarHudActionType action = mButtonConfig.getAction();
            int customKeycode = mButtonConfig.getCustomKeycode();
            StarHudInteractionMode mode = mButtonConfig.getInteractionMode();
            StarHudInputManager manager = StarHudInputManager.getInstance();

            if (mode == StarHudInteractionMode.TAP) {
                dispatchOneShotTap(manager, action, customKeycode, pointerId);
            } else if (mode == StarHudInteractionMode.TOGGLE) {
                mIsToggled = !mIsToggled;
                if (mIsToggled) {
                    mToggledOnDuringCurrentTouch = true;
                    acquireButtonLease(manager, action, customKeycode, pointerId);
                    if (!mIsToggled) {
                        mToggledOnDuringCurrentTouch = false;
                    }
                } else {
                    mIsToggled = false;
                    releaseLeaseOnly("toggle_off");
                }
            } else {
                // HOLD mode
                acquireButtonLease(manager, action, customKeycode, pointerId);
            }
        } catch (Throwable t) {
            mIsPressed = false;
            mIsToggled = false;
            mToggledOnDuringCurrentTouch = false;
            releaseActiveLease("press_exception");
        }
        invalidate();
    }

    private void onPointerReleased(boolean cancelled, @NonNull String reason) {
        mActivePointerId = MotionEvent.INVALID_POINTER_ID;
        mIsPressed = false;
        boolean toggledThisTouch = mToggledOnDuringCurrentTouch;
        mToggledOnDuringCurrentTouch = false;
        try {
            if (mSpecialAction == ACTION_ELEMENT) {
                StarHudInteractionMode mode = mButtonConfig.getInteractionMode();
                if (mode == StarHudInteractionMode.HOLD) {
                    releaseActiveLease(reason);
                } else if (mode == StarHudInteractionMode.TOGGLE && cancelled && toggledThisTouch) {
                    mIsToggled = false;
                    releaseActiveLease(reason + "_toggle");
                }
            }
        } catch (Throwable t) {
            mIsToggled = false;
            releaseActiveLease("release_exception");
        }
        invalidate();
    }

    private void acquireButtonLease(
            StarHudInputManager manager,
            StarHudActionType action,
            int customKeycode,
            int pointerId
    ) {
        String leaseId = "btn:" + mButtonConfig.getId();
        boolean persistentToggle = (mButtonConfig.getInteractionMode() == StarHudInteractionMode.TOGGLE);
        int mouseButton = StarHudActionRegistry.mouseButtonForJava(action);
        if (mouseButton >= 0) {
            mActiveLeaseId = leaseId;
            boolean ok = manager.acquireMouseLease(
                    leaseId, mButtonConfig.getId(), pointerId, action.name(), mouseButton, persistentToggle
            );
            if (!ok) {
                mActiveLeaseId = null;
                mIsPressed = false;
                mIsToggled = false;
            }
            return;
        }
        int keycode = StarHudActionRegistry.keyCodeForJava(action, customKeycode);
        if (keycode > 0) {
            boolean applyMods = StarHudActionRegistry.applyModifiersForJava(action, customKeycode);
            mActiveLeaseId = leaseId;
            boolean ok = manager.acquireKeyLease(
                    leaseId, mButtonConfig.getId(), pointerId, action.name(), keycode, applyMods, persistentToggle
            );
            if (!ok) {
                mActiveLeaseId = null;
                mIsPressed = false;
                mIsToggled = false;
            }
        }
    }

    private void dispatchOneShotTap(
            StarHudInputManager manager,
            StarHudActionType action,
            int customKeycode,
            int pointerId
    ) {
        float scrollY = StarHudActionRegistry.scrollYForJava(action);
        if (Math.abs(scrollY) > 0.01f) {
            manager.dispatchScroll(mButtonConfig.getId(), pointerId, scrollY);
            return;
        }
        int mouseButton = StarHudActionRegistry.mouseButtonForJava(action);
        if (mouseButton >= 0) {
            manager.tapMouseButton(mButtonConfig.getId(), pointerId, action.name(), mouseButton);
            return;
        }
        int keycode = StarHudActionRegistry.keyCodeForJava(action, customKeycode);
        if (keycode > 0) {
            boolean applyMods = StarHudActionRegistry.applyModifiersForJava(action, customKeycode);
            manager.tapKey(mButtonConfig.getId(), pointerId, action.name(), keycode, applyMods);
        }
    }

    private void releaseLeaseOnly(String reason) {
        String leaseId = mActiveLeaseId;
        mActiveLeaseId = null;
        if (leaseId != null) {
            StarHudInputManager.getInstance().releaseLease(leaseId, reason);
        } else if (mSpecialAction == ACTION_ELEMENT) {
            StarHudInputManager.getInstance().releaseButton(mButtonConfig.getId(), reason);
        }
    }

    private void releaseActiveLease(String reason) {
        mActivePointerId = MotionEvent.INVALID_POINTER_ID;
        mIsPressed = false;
        mToggledOnDuringCurrentTouch = false;
        if (mButtonConfig.getInteractionMode() != StarHudInteractionMode.TOGGLE
                || "toggle_off".equals(reason)
                || "visibility_hidden".equals(reason)
                || "detached".equals(reason)
                || "config_changed".equals(reason)
                || "force_release".equals(reason)
                || "disabled".equals(reason)
                || "window_focus_lost".equals(reason)
                || "toggle_mode_disabled".equals(reason)
                || reason.endsWith("_toggle")) {
            mIsToggled = false;
        }
        releaseLeaseOnly(reason);
    }

    private void forceReleaseWithReason(@NonNull String reason) {
        mIsToggled = false;
        releaseActiveLease(reason);
        invalidate();
    }

    public void releaseOnGuiTransition() {
        forceReleaseAll();
    }

    public void forceReleaseAll() {
        forceReleaseWithReason("force_release");
    }
}
