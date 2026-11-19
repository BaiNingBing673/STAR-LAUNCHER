package net.kdt.pojavlaunch.customcontrols.mouse;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.star.launcher.hud.StarHudConfig;
import com.star.launcher.hud.StarHudElementId;
import com.star.launcher.hud.StarHudElementLayout;
import com.star.launcher.hud.StarHudInputBridge;
import com.star.launcher.hud.StarHudInputManager;

import net.kdt.pojavlaunch.GrabListener;
import net.kdt.pojavlaunch.LwjglGlfwKeycode;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.utils.MCOptionUtils;
import net.kdt.pojavlaunch.utils.MathUtils;

import org.lwjgl.glfw.CallbackBridge;

/**
 * 9-slot in-game hotbar touch overlay & STAR-styled visual frame.
 *
 * Preserves transparent slot interiors so Minecraft's rendered item icons and stack counts
 * remain completely visible while providing large tap targets, horizontal swipe slot switching,
 * active-slot cyan highlight, double-tap offhand swap (F), and stationary long-press drop (Q).
 */
public class HotbarView extends View implements
        MCOptionUtils.MCOptionListener,
        View.OnLayoutChangeListener,
        Runnable,
        CallbackBridge.HotbarSyncListener,
        StarHudInputManager.ResetParticipant,
        StarHudInputManager.PointerReconciliationParticipant {

    private static final int SLOT_COUNT = 9;
    private static final float DEFAULT_WIDTH_DP = 396f; // 44dp * 9
    private static final float MIN_HEIGHT_DP = 48f;

    private static final int COLOR_FRAME_BG = 0xFF10141F;
    private static final int COLOR_BORDER_IDLE = 0xFF33415C;
    private static final int COLOR_DIVIDER = 0xFF2D364D;
    private static final int COLOR_ACTIVE_BORDER = 0xFF5EE2FF;
    private static final int COLOR_ACTIVE_GLOW = 0xFF5FA8FF;
    private static final int COLOR_CUSTOMIZE_BORDER = 0xFFF59E0B;

    private static final int[] HOTBAR_KEYS = {
            LwjglGlfwKeycode.GLFW_KEY_1, LwjglGlfwKeycode.GLFW_KEY_2, LwjglGlfwKeycode.GLFW_KEY_3,
            LwjglGlfwKeycode.GLFW_KEY_4, LwjglGlfwKeycode.GLFW_KEY_5, LwjglGlfwKeycode.GLFW_KEY_6,
            LwjglGlfwKeycode.GLFW_KEY_7, LwjglGlfwKeycode.GLFW_KEY_8, LwjglGlfwKeycode.GLFW_KEY_9
    };

    private final TapDetector mDoubleTapDetector = new TapDetector(2, TapDetector.DETECTION_METHOD_DOWN);
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final DropGesture mDropGesture = new DropGesture(mMainHandler);

    private final Paint mTrayBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mActiveSlotBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mActiveSlotBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mActiveBarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mOuterRect = new RectF();
    private final RectF mActiveRect = new RectF();

    private final GrabListener mGrabListener = new GrabListener() {
        @Override
        public void onGrabState(boolean isGrabbing) {
            mLastIndex = -1;
            mActivePointerId = MotionEvent.INVALID_POINTER_ID;
            mDropGesture.cancel();
            post(() -> {
                updateVisibilityForGrabState(isGrabbing);
                invalidate();
            });
        }
    };

    private View mParentView;
    private int mWidth;
    private int mHeight;
    private int mVisualHeight = 0;
    private int mLastIndex = -1;
    private int mSelectedSlot = 0;
    private int mGuiScale;
    private int mTouchSlop;

    // Multi-touch & swipe tracking
    private int mActivePointerId = MotionEvent.INVALID_POINTER_ID;
    private float mDownX = 0f;
    private boolean mSwipingSlots = false;

    // STAR HUD styling & safe-area layout
    private boolean mStarHudMode = true;
    private boolean mHudElementEnabled = true;
    private boolean mCustomizeHighlight = false;
    private float mHotbarScale = 1.0f;
    private float mHudOpacity = 0.78f;
    private float mXFraction = 0.50f;
    private float mYFraction = 1.00f;
    private int mSafeLeft = 0;
    private int mSafeTop = 0;
    private int mSafeRight = 0;
    private int mSafeBottom = 0;
    private StarHudInputBridge mInputBridge = StarHudInputBridge.DEFAULT;

    public HotbarView(Context context) {
        super(context);
        initialize(context);
    }

    public HotbarView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        initialize(context);
    }

    public HotbarView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initialize(context);
    }

    @SuppressWarnings("unused")
    public HotbarView(Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        initialize(context);
    }

    private void initialize(Context context) {
        MCOptionUtils.addMCOptionListener(this);
        mTouchSlop = Math.max(8, ViewConfiguration.get(context).getScaledTouchSlop());

        mTrayBgPaint.setStyle(Paint.Style.FILL);
        mBorderPaint.setStyle(Paint.Style.STROKE);
        mDividerPaint.setStyle(Paint.Style.STROKE);
        mActiveSlotBgPaint.setStyle(Paint.Style.FILL);
        mActiveSlotBorderPaint.setStyle(Paint.Style.STROKE);
        mActiveBarPaint.setStyle(Paint.Style.FILL);

        setWillNotDraw(false);
        setOutlineProvider(null);
        setTranslationZ(4f * context.getResources().getDisplayMetrics().density);
    }

    private boolean mRegisteredWithManager = false;

    private void ensureRegisteredWithManager() {
        if (!mRegisteredWithManager) {
            StarHudInputManager manager = StarHudInputManager.getInstance();
            manager.registerResetParticipant(this);
            manager.registerPointerParticipant(this);
            mRegisteredWithManager = true;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ViewParent parent = getParent();
        if (parent instanceof View) {
            mParentView = (View) parent;
            mParentView.addOnLayoutChangeListener(this);
        }
        mGuiScale = MCOptionUtils.getMcScale();
        repositionView();
        CallbackBridge.addGrabListener(mGrabListener);
        CallbackBridge.setHotbarSyncListener(this);
        ensureRegisteredWithManager();
        updateVisibilityForGrabState(CallbackBridge.isGrabbing());
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        finishTouchGesture();
        CallbackBridge.removeGrabListener(mGrabListener);
        CallbackBridge.setHotbarSyncListener(null);
        if (mRegisteredWithManager) {
            StarHudInputManager manager = StarHudInputManager.getInstance();
            manager.unregisterResetParticipant(this);
            manager.unregisterPointerParticipant(this);
            mRegisteredWithManager = false;
        }
    }

    @Override
    public void onGlobalInputReset(@NonNull String reason) {
        finishTouchGesture();
    }

    @Override
    public void onReconcileActivePointers(int activePointerIdBitmask, @NonNull String reason) {
        if (mActivePointerId != MotionEvent.INVALID_POINTER_ID) {
            boolean present = mActivePointerId >= 0
                    && mActivePointerId < 32
                    && (activePointerIdBitmask & (1 << mActivePointerId)) != 0;
            if (!present) {
                finishTouchGesture();
            }
        }
    }

    public void setInputBridge(@NonNull StarHudInputBridge inputBridge) {
        mInputBridge = inputBridge;
        StarHudInputManager.getInstance().setInputBridge(inputBridge);
    }

    public void applyStarHudConfig(
            @NonNull StarHudConfig config,
            int safeLeft,
            int safeTop,
            int safeRight,
            int safeBottom
    ) {
        mStarHudMode = config.getUseStarHud();
        StarHudElementLayout layout = config.elementLayout(StarHudElementId.HOTBAR);
        mHudElementEnabled = layout.getEnabled();
        mXFraction = layout.getXFraction();
        mYFraction = layout.getYFraction();
        mHotbarScale = config.getHotbarScale();
        mHudOpacity = config.getHudOpacity();
        mSafeLeft = Math.max(0, safeLeft);
        mSafeTop = Math.max(0, safeTop);
        mSafeRight = Math.max(0, safeRight);
        mSafeBottom = Math.max(0, safeBottom);

        updateVisibilityForGrabState(mInputBridge.isGrabbing());
        post(this::repositionView);
    }

    public void setCustomizeHighlight(boolean highlight) {
        if (mCustomizeHighlight != highlight) {
            mCustomizeHighlight = highlight;
            if (highlight) {
                mDropGesture.cancel();
                mActivePointerId = MotionEvent.INVALID_POINTER_ID;
                setVisibility(VISIBLE);
            } else {
                updateVisibilityForGrabState(mInputBridge.isGrabbing());
            }
            invalidate();
        }
    }

    public boolean isStarHudMode() {
        return mStarHudMode;
    }

    public int getSelectedSlot() {
        return mSelectedSlot;
    }

    private void updateVisibilityForGrabState(boolean isGrabbing) {
        if (!mStarHudMode) {
            setVisibility(VISIBLE);
            return;
        }
        if (mCustomizeHighlight) {
            setVisibility(VISIBLE);
            return;
        }
        setVisibility((mHudElementEnabled && isGrabbing) ? VISIBLE : GONE);
    }

    private void repositionView() {
        ViewGroup.LayoutParams layoutParams = getLayoutParams();
        if (!(layoutParams instanceof ViewGroup.MarginLayoutParams)) {
            return;
        }
        ViewGroup.MarginLayoutParams marginLayoutParams = (ViewGroup.MarginLayoutParams) layoutParams;
        float density = getResources().getDisplayMetrics().density;

        int parentWidth = mParentView != null && mParentView.getWidth() > 0
                ? mParentView.getWidth()
                : CallbackBridge.physicalWidth;
        int parentHeight = mParentView != null && mParentView.getHeight() > 0
                ? mParentView.getHeight()
                : CallbackBridge.physicalHeight;

        if (!mStarHudMode) {
            int height;
            marginLayoutParams.width = mWidth = mcScale(180);
            marginLayoutParams.height = mHeight = height = mcScale(20);
            marginLayoutParams.leftMargin = (CallbackBridge.physicalWidth / 2) - (mWidth / 2);
            marginLayoutParams.topMargin = CallbackBridge.physicalHeight - height;
            setLayoutParams(marginLayoutParams);
            invalidate();
            return;
        }

        // Align width cleanly with Minecraft's bottom-center 182 GUI px hotbar when available
        // so visual slot dividers and highlight never drift away from GL/Vulkan item icons.
        int mcBarWidth = (CallbackBridge.physicalWidth > 0 && mGuiScale > 0) ? mcScale(182) : 0;
        int fallbackWidth = Math.round(DEFAULT_WIDTH_DP * density * mHotbarScale);
        int desiredWidth = mcBarWidth > 0 ? mcBarWidth : fallbackWidth;
        if (parentWidth > 0) {
            int maxWidth = Math.max(Math.round(240f * density), parentWidth - mSafeLeft - mSafeRight - Math.round(16f * density));
            desiredWidth = Math.min(desiredWidth, maxWidth);
        }
        desiredWidth = Math.max(SLOT_COUNT * 18, desiredWidth);

        int mcBarHeight = (CallbackBridge.physicalHeight > 0 && mGuiScale > 0) ? mcScale(22) : 0;
        int minTouchHeight = Math.round(MIN_HEIGHT_DP * density * mHotbarScale);
        int desiredHeight = Math.max(minTouchHeight, mcBarHeight);

        marginLayoutParams.width = mWidth = desiredWidth;
        marginLayoutParams.height = mHeight = desiredHeight;
        mVisualHeight = (mcBarHeight > 0 && mcBarHeight < desiredHeight) ? mcBarHeight : desiredHeight;

        if (parentWidth > 0 && parentHeight > 0) {
            if (Math.abs(mXFraction - 0.50f) < 0.01f) {
                marginLayoutParams.leftMargin = Math.max(0, (parentWidth - desiredWidth) / 2);
            } else {
                int usableLeft = mSafeLeft;
                int usableRight = Math.max(usableLeft + desiredWidth, parentWidth - mSafeRight);
                int availX = Math.max(0, (usableRight - usableLeft) - desiredWidth);
                marginLayoutParams.leftMargin = usableLeft + Math.round(availX * mXFraction);
            }

            if (Math.abs(mYFraction - 1.00f) < 0.01f) {
                marginLayoutParams.topMargin = Math.max(0, parentHeight - desiredHeight);
            } else {
                int usableTop = mSafeTop;
                int usableBottom = Math.max(usableTop + desiredHeight, parentHeight - mSafeBottom);
                int availY = Math.max(0, (usableBottom - usableTop) - desiredHeight);
                marginLayoutParams.topMargin = usableTop + Math.round(availY * mYFraction);
            }
        } else {
            marginLayoutParams.leftMargin = 0;
            marginLayoutParams.topMargin = 0;
        }
        setLayoutParams(marginLayoutParams);
        invalidate();
    }

    public void setmLastIndex(int lastIndex) {
        this.mLastIndex = lastIndex;
        if (lastIndex >= 0 && lastIndex < SLOT_COUNT) {
            mSelectedSlot = lastIndex;
            invalidate();
        }
    }

    @Override
    public void onHotbarSlotSelected(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= SLOT_COUNT) return;
        Runnable update = () -> {
            mSelectedSlot = slotIndex;
            mLastIndex = slotIndex;
            invalidate();
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            update.run();
        } else {
            mMainHandler.post(update);
        }
    }

    @Override
    public void onHotbarScroll(float yOffset) {
        if (Math.abs(yOffset) < 0.01f) return;
        final int step = yOffset > 0f ? -1 : 1;
        Runnable update = () -> {
            mSelectedSlot = (mSelectedSlot + step + SLOT_COUNT) % SLOT_COUNT;
            mLastIndex = mSelectedSlot;
            invalidate();
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            update.run();
        } else {
            mMainHandler.post(update);
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (!mStarHudMode && !mCustomizeHighlight) {
            return;
        }
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float density = getResources().getDisplayMetrics().density;
        float stroke = Math.max(1.25f * density, 1.5f);
        float pad = stroke * 0.6f + 1f;

        // Keep upper touch-target extension transparent above Minecraft's bottom-aligned 22 GUI px bar
        // so visual dividers and cyan selection align pixel-for-pixel without overlapping Hearts/Hunger.
        float frameTop = (mVisualHeight > 0 && mVisualHeight < h) ? (h - mVisualHeight + pad) : pad;
        mOuterRect.set(pad, frameTop, w - pad, h - pad);
        float radius = 8f * density;
        float slotWidth = mOuterRect.width() / SLOT_COUNT;

        int masterAlpha = Math.round(255f * mHudOpacity);

        // Very subtle translucent dark rim so slot interiors keep Minecraft icons completely clear
        mTrayBgPaint.setColor(COLOR_FRAME_BG);
        mTrayBgPaint.setAlpha(Math.round(masterAlpha * 0.24f));
        canvas.drawRoundRect(mOuterRect, radius, radius, mTrayBgPaint);

        // Outer frame border
        mBorderPaint.setStrokeWidth(stroke);
        if (mCustomizeHighlight) {
            mBorderPaint.setColor(COLOR_CUSTOMIZE_BORDER);
            mBorderPaint.setAlpha(240);
        } else {
            mBorderPaint.setColor(COLOR_BORDER_IDLE);
            mBorderPaint.setAlpha(Math.round(masterAlpha * 0.90f));
        }
        canvas.drawRoundRect(mOuterRect, radius, radius, mBorderPaint);

        // Clean slot dividers
        mDividerPaint.setColor(COLOR_DIVIDER);
        mDividerPaint.setAlpha(Math.round(masterAlpha * 0.65f));
        mDividerPaint.setStrokeWidth(Math.max(1f, density));
        for (int i = 1; i < SLOT_COUNT; i++) {
            float sx = mOuterRect.left + slotWidth * i;
            canvas.drawLine(sx, mOuterRect.top + 3f * density, sx, mOuterRect.bottom - 3f * density, mDividerPaint);
        }

        // Highlight active slot without obscuring item icon/stack count
        int clampedSlot = Math.max(0, Math.min(SLOT_COUNT - 1, mSelectedSlot));
        float slotLeft = mOuterRect.left + clampedSlot * slotWidth;
        float slotRight = slotLeft + slotWidth;
        float inset = Math.max(1.5f, density);
        mActiveRect.set(slotLeft + inset, mOuterRect.top + inset, slotRight - inset, mOuterRect.bottom - inset);

        mActiveSlotBgPaint.setColor(COLOR_ACTIVE_GLOW);
        mActiveSlotBgPaint.setAlpha(Math.round(masterAlpha * 0.14f));
        canvas.drawRoundRect(mActiveRect, radius * 0.65f, radius * 0.65f, mActiveSlotBgPaint);

        mActiveSlotBorderPaint.setColor(COLOR_ACTIVE_BORDER);
        mActiveSlotBorderPaint.setAlpha(Math.min(255, Math.round(masterAlpha * 1.25f)));
        mActiveSlotBorderPaint.setStrokeWidth(Math.max(2f * density, stroke * 1.45f));
        canvas.drawRoundRect(mActiveRect, radius * 0.65f, radius * 0.65f, mActiveSlotBorderPaint);

        // Top indicator pill strip over the selected slot (away from bottom-right item stack count!)
        float barW = Math.min(slotWidth * 0.46f, 18f * density);
        float barH = Math.max(2.5f * density, 3f);
        float barCx = mActiveRect.centerX();
        mActiveBarPaint.setColor(COLOR_ACTIVE_BORDER);
        mActiveBarPaint.setAlpha(245);
        canvas.drawRoundRect(
                barCx - barW * 0.5f,
                mActiveRect.top + density,
                barCx + barW * 0.5f,
                mActiveRect.top + density + barH,
                barH * 0.5f,
                barH * 0.5f,
                mActiveBarPaint
        );
    }

    @SuppressWarnings("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mCustomizeHighlight) return false;
        if (!mInputBridge.isGrabbing()) return false;
        if (mStarHudMode && !mHudElementEnabled) return false;
        ensureRegisteredWithManager();

        int actionMasked = event.getActionMasked();
        switch (actionMasked) {
            case MotionEvent.ACTION_DOWN: {
                if (mActivePointerId != MotionEvent.INVALID_POINTER_ID) {
                    finishTouchGesture();
                }
                int idx = event.getActionIndex();
                if (idx < 0 || idx >= event.getPointerCount()) return false;
                mActivePointerId = event.getPointerId(idx);
                mDownX = event.getX(idx);
                mSwipingSlots = false;

                boolean hasDoubleTapped = mDoubleTapDetector.onTouchEvent(event);
                mDropGesture.submit();
                handleSlotAtX(mDownX, hasDoubleTapped, false);
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                if (mActivePointerId != MotionEvent.INVALID_POINTER_ID) {
                    if (event.getPointerCount() >= 2 && event.findPointerIndex(mActivePointerId) < 0) {
                        finishTouchGesture();
                    } else {
                        return true;
                    }
                }
                int idx = event.getActionIndex();
                if (idx < 0 || idx >= event.getPointerCount()) return false;
                mActivePointerId = event.getPointerId(idx);
                mDownX = event.getX(idx);
                mSwipingSlots = false;

                boolean hasDoubleTapped = mDoubleTapDetector.onTouchEvent(event);
                mDropGesture.submit();
                handleSlotAtX(mDownX, hasDoubleTapped, false);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mActivePointerId == MotionEvent.INVALID_POINTER_ID) return false;
                int idx = event.findPointerIndex(mActivePointerId);
                if (idx < 0) {
                    finishTouchGesture();
                    return true;
                }

                float x = event.getX(idx);
                if (!mSwipingSlots && Math.abs(x - mDownX) > mTouchSlop) {
                    mSwipingSlots = true;
                    mDropGesture.cancel();
                }
                handleSlotAtX(x, false, false);
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                if (mActivePointerId == MotionEvent.INVALID_POINTER_ID) return false;
                int idx = event.getActionIndex();
                if ((idx >= 0 && idx < event.getPointerCount() && event.getPointerId(idx) == mActivePointerId)
                        || (event.getPointerCount() >= 2 && event.findPointerIndex(mActivePointerId) < 0)) {
                    finishTouchGesture();
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                finishTouchGesture();
                return true;
            default:
                return false;
        }
    }

    private void handleSlotAtX(float x, boolean hasDoubleTapped, boolean isLastEvent) {
        int effectiveWidth = Math.max(mWidth, getWidth());
        if (effectiveWidth <= 0) return;

        if (x < 0 || x >= effectiveWidth) {
            if (!mSwipingSlots) {
                mDropGesture.cancel();
                return;
            }
            x = Math.max(0f, Math.min(effectiveWidth - 1f, x));
        }

        int hotbarIndex = (int) MathUtils.map(x, 0, effectiveWidth, 0, HOTBAR_KEYS.length);
        hotbarIndex = Math.max(0, Math.min(SLOT_COUNT - 1, hotbarIndex));

        if (hotbarIndex == mLastIndex) {
            if (hasDoubleTapped && !mSwipingSlots && !LauncherPreferences.PREF_DISABLE_SWAP_HAND) {
                mInputBridge.sendKeyTap(LwjglGlfwKeycode.GLFW_KEY_F);
            }
            return;
        }

        mLastIndex = hotbarIndex;
        mSelectedSlot = hotbarIndex;
        mInputBridge.sendKeyTap(HOTBAR_KEYS[hotbarIndex]);
        mDropGesture.cancel();
        if (!isLastEvent && !mSwipingSlots) {
            mDropGesture.submit();
        }
        invalidate();
    }

    private void finishTouchGesture() {
        mActivePointerId = MotionEvent.INVALID_POINTER_ID;
        mSwipingSlots = false;
        mDropGesture.cancel();
    }

    private int mcScale(int input) {
        float denom = Math.max(0.1f, LauncherPreferences.PREF_SCALE_FACTOR);
        return (int) ((mGuiScale * input) / denom);
    }

    /** Forces the view to reposition itself. */
    public void onResolutionChanged() {
        if (getParent() == null) return;
        mGuiScale = MCOptionUtils.getMcScale();
        post(this::repositionView);
    }

    @Override
    public void onOptionChanged() {
        post(this);
    }

    @Override
    public void run() {
        if (getParent() == null) return;
        int scale = MCOptionUtils.getMcScale();
        if (scale == mGuiScale) return;
        mGuiScale = scale;
        repositionView();
    }

    @Override
    public void onLayoutChange(
            View v, int left, int top, int right, int bottom,
            int oldLeft, int oldTop, int oldRight, int oldBottom
    ) {
        if (v.equals(mParentView) && (left != oldLeft || right != oldRight || top != oldTop || bottom != oldBottom)) {
            post(this::repositionView);
        }
    }
}
