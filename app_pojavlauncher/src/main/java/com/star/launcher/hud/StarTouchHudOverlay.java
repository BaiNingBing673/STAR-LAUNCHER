package com.star.launcher.hud;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.text.InputFilter;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import kotlin.Pair;

import net.kdt.pojavlaunch.BuildConfig;
import net.kdt.pojavlaunch.GrabListener;
import net.kdt.pojavlaunch.customcontrols.mouse.HotbarView;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import org.lwjgl.glfw.CallbackBridge;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Controller and in-game Edit Controller overlay for STAR Launcher's customizable touch HUD.
 *
 * Interactive HUD views (joystick, dynamic left zone, and dynamic user-configured buttons) are
 * attached as tightly-bounded siblings inside the host ViewGroup (ControlLayout) so Android's
 * multi-touch target splitting handles simultaneous joystick + camera + multi-button input without
 * stealing open-screen camera swipes.
 *
 * In Edit Controller mode, edits are staged inside {@code mDraftConfig} and only become active
 * in gameplay once explicitly saved via "Save".
 */
public class StarTouchHudOverlay extends FrameLayout implements GrabListener {
    public interface Listener {
        void onHudConfigUpdated(@NonNull StarHudConfig config);
        void onCustomizeModeChanged(boolean active);
    }

    private final Map<String, StarHudButtonView> mDynamicButtons = new LinkedHashMap<>();
    private final Map<StarHudElementId, StarHudElementRect> mCurrentRects = new EnumMap<>(StarHudElementId.class);
    private final RectF mDynamicZoneBounds = new RectF();

    private StarJoystickView mJoystickView;
    private DynamicJoystickZoneView mDynamicZoneView;
    private StarHudButtonView mMenuKeyboardButton;
    private StarHudButtonView mMenuVirtualMouseButton;
    @Nullable private StarInputDebugOverlayView mDebugOverlayView;

    @Nullable private ViewGroup mHostContainer;
    @Nullable private HotbarView mHotbarView;
    @Nullable private Listener mListener;

    private StarHudConfig mConfig = StarHudConfig.defaults();
    @Nullable private StarHudConfig mDraftConfig = null;
    private StarHudSafeInsets mSafeInsets = new StarHudSafeInsets(0, 0, 0, 0);

    private boolean mHudActive = false;
    private boolean mCustomizeMode = false;
    private boolean mChildrenAttached = false;
    private boolean mGrabAttached = false;

    // Edit Controller toolbar & selection/drag/resize state
    private HorizontalScrollView mCustomizeBarScroll;
    private LinearLayout mCustomizeBar;
    private TextView mEditButtonChip;
    private TextView mSnapToggleChip;
    private TextView mSelectedLabelText;

    private final Paint mScrimPaint = new Paint();
    private final Paint mGuidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSnapGuidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mResizeHandlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mResizeHandleStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mResizeHandleRect = new RectF();

    @Nullable private String mSelectedButtonId = null;
    @Nullable private StarHudElementId mSelectedStructuralElement = null;
    private boolean mIsResizing = false;
    private boolean mIsDragging = false;
    private float mDragStartRawX;
    private float mDragStartRawY;
    private int mDragInitialLeft;
    private int mDragInitialTop;
    private float mResizeInitialWidthDp;
    private float mResizeInitialHeightDp;
    @Nullable private Integer mActiveSnapX = null;
    @Nullable private Integer mActiveSnapY = null;

    public StarTouchHudOverlay(@NonNull Context context) {
        this(context, null);
    }

    public StarTouchHudOverlay(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public StarTouchHudOverlay(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        setWillNotDraw(!mCustomizeMode);
        setClickable(false);
        setFocusable(false);
        setOutlineProvider(null);
        float d = getResources().getDisplayMetrics().density;
        setTranslationZ(8f * d);

        mScrimPaint.setColor(0x5A060A14);
        mScrimPaint.setStyle(Paint.Style.FILL);

        mGuidePaint.setColor(0x555EE2FF);
        mGuidePaint.setStyle(Paint.Style.STROKE);
        mGuidePaint.setStrokeWidth(1.5f * d);
        mGuidePaint.setPathEffect(new DashPathEffect(new float[]{12f, 10f}, 0f));

        mSnapGuidePaint.setColor(0xFF4DD0E1);
        mSnapGuidePaint.setStyle(Paint.Style.STROKE);
        mSnapGuidePaint.setStrokeWidth(2f * d);

        mResizeHandlePaint.setColor(0xFF5EE2FF);
        mResizeHandlePaint.setStyle(Paint.Style.FILL);
        mResizeHandleStrokePaint.setColor(0xFF0A101B);
        mResizeHandleStrokePaint.setStyle(Paint.Style.STROKE);
        mResizeHandleStrokePaint.setStrokeWidth(1.8f * d);

        mDynamicZoneView = new DynamicJoystickZoneView(context);
        mJoystickView = new StarJoystickView(context);
        mMenuKeyboardButton = new StarHudButtonView(context, null, StarHudButtonView.ACTION_KEYBOARD);
        mMenuVirtualMouseButton = new StarHudButtonView(context, null, StarHudButtonView.ACTION_VIRTUAL_MOUSE);

        if (BuildConfig.DEBUG) {
            mDebugOverlayView = new StarInputDebugOverlayView(context);
        }

        syncDynamicButtonViews(mConfig);
        buildCustomizeToolbar(context);
    }

    @NonNull
    private StarHudConfig activeConfig() {
        if (mCustomizeMode && mDraftConfig != null) {
            return mDraftConfig;
        }
        return mConfig;
    }

    private void buildCustomizeToolbar(Context context) {
        float d = getResources().getDisplayMetrics().density;
        mCustomizeBarScroll = new HorizontalScrollView(context);
        mCustomizeBarScroll.setHorizontalScrollBarEnabled(false);
        mCustomizeBarScroll.setVisibility(GONE);

        mCustomizeBar = new LinearLayout(context);
        mCustomizeBar.setOrientation(LinearLayout.HORIZONTAL);
        mCustomizeBar.setGravity(Gravity.CENTER_VERTICAL);
        mCustomizeBar.setPadding(Math.round(10 * d), Math.round(6 * d), Math.round(10 * d), Math.round(6 * d));
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(0xEB0E1420);
        barBg.setCornerRadius(12f * d);
        barBg.setStroke(Math.max(1, Math.round(d)), 0x554DD0E1);
        mCustomizeBar.setBackground(barBg);

        mSelectedLabelText = new TextView(context);
        mSelectedLabelText.setText("Edit Controller");
        mSelectedLabelText.setTextColor(0xFF9EDCF0);
        mSelectedLabelText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.setMarginEnd(Math.round(8 * d));
        mCustomizeBar.addView(mSelectedLabelText, titleLp);

        mCustomizeBar.addView(createToolbarButton(context, "+ Add", 0xFF1D4ED8, v -> showAddButtonDialog()));
        mEditButtonChip = createToolbarButton(context, "Edit", 0xFF1E293B, v -> showEditSelectedButtonDialog());
        mCustomizeBar.addView(mEditButtonChip);
        mCustomizeBar.addView(createToolbarButton(context, "Dup", 0xFF1E293B, v -> duplicateSelectedButton()));
        mCustomizeBar.addView(createToolbarButton(context, "Del", 0xFF4C1D24, v -> deleteSelectedButton()));
        mSnapToggleChip = createToolbarButton(context, "Snap: On", 0xFF1E293B, v -> toggleSnapInDraft());
        mCustomizeBar.addView(mSnapToggleChip);
        mCustomizeBar.addView(createToolbarButton(context, "Layouts", 0xFF1E293B, v -> showLayoutManagerDialog()));
        mCustomizeBar.addView(createToolbarButton(context, "Reset", 0xFF3F1D2B, v -> confirmResetDraftLayout()));
        mCustomizeBar.addView(createToolbarButton(context, "Cancel", 0xFF334155, v -> cancelCustomizeChanges()));
        mCustomizeBar.addView(createToolbarButton(context, "Save", 0xFF15803D, v -> saveCustomizeChanges()));

        mCustomizeBarScroll.addView(mCustomizeBar);

        LayoutParams barLp = new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL
        );
        barLp.topMargin = Math.round(10 * d);
        addView(mCustomizeBarScroll, barLp);

        if (mDebugOverlayView != null) {
            LayoutParams dbgLp = new LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL
            );
            dbgLp.topMargin = Math.round(30 * d);
            addView(mDebugOverlayView, dbgLp);
        }
    }

    private TextView createToolbarButton(Context context, String label, int bgColor, OnClickListener listener) {
        float d = getResources().getDisplayMetrics().density;
        TextView btn = new TextView(context);
        btn.setText(label);
        btn.setTextColor(0xFFFFFFFF);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgColor);
        gd.setCornerRadius(8f * d);
        btn.setBackground(gd);
        btn.setPadding(Math.round(10 * d), Math.round(6 * d), Math.round(10 * d), Math.round(6 * d));
        btn.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(Math.round(5 * d));
        btn.setLayoutParams(lp);
        return btn;
    }

    public void setHostContainer(@Nullable ViewGroup hostContainer) {
        if (mHostContainer == hostContainer) return;
        detachHudChildren();
        mHostContainer = hostContainer;
        attachHudChildrenIfReady();
        relayoutElements();
    }

    public void setHotbarView(@Nullable HotbarView hotbarView) {
        mHotbarView = hotbarView;
        applyHotbarConfig(activeConfig());
    }

    public void setListener(@Nullable Listener listener) {
        mListener = listener;
    }

    public void setHudActive(boolean active) {
        if (mHudActive == active) return;
        mHudActive = active;
        if (!active) {
            setCustomizeMode(false);
            releaseAllInputsWithReason("hud_disabled");
        }
        updateChildVisibilities();
        relayoutElements();
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (!enabled) {
            releaseAllInputsWithReason("overlay_disabled");
        }
        if (mJoystickView != null) {
            mJoystickView.setEnabled(enabled);
        }
        if (mDynamicZoneView != null) {
            mDynamicZoneView.setEnabled(enabled);
        }
        for (StarHudButtonView btn : mDynamicButtons.values()) {
            btn.setEnabled(enabled);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus) {
            releaseAllInputsWithReason("overlay_focus_lost");
        }
    }

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility != VISIBLE) {
            releaseAllInputsWithReason("overlay_visibility_hidden");
        }
    }

    public boolean isHudActive() {
        return mHudActive;
    }

    public boolean isCustomizeMode() {
        return mCustomizeMode;
    }

    public void setCustomizeMode(boolean customizeMode) {
        if (mCustomizeMode == customizeMode) return;
        StarHudInputManager.getInstance().releaseAllInputs(
                customizeMode ? "enter_edit_controller" : "exit_edit_controller"
        );
        mCustomizeMode = customizeMode;
        if (customizeMode) {
            mDraftConfig = mConfig.sanitized();
            if (mSelectedButtonId == null || mDraftConfig.findButton(mSelectedButtonId) == null) {
                List<StarHudButtonConfig> btns = mDraftConfig.getButtons();
                mSelectedButtonId = btns.isEmpty() ? null : btns.get(0).getId();
                mSelectedStructuralElement = null;
            }
        } else {
            mDraftConfig = null;
            mIsDragging = false;
            mIsResizing = false;
            mActiveSnapX = null;
            mActiveSnapY = null;
        }

        mJoystickView.setCustomizeHighlight(customizeMode);
        if (mHotbarView != null) {
            mHotbarView.setCustomizeHighlight(customizeMode);
        }
        mCustomizeBarScroll.setVisibility(customizeMode ? VISIBLE : GONE);
        setWillNotDraw(!customizeMode);
        setClickable(customizeMode);

        applyActiveConfigToViews();
        refreshToolbarSelectionState();
        invalidate();

        if (mListener != null) {
            mListener.onCustomizeModeChanged(customizeMode);
        }
    }

    private void saveCustomizeChanges() {
        if (mDraftConfig != null) {
            mConfig = StarHudConfigStore.save(getContext(), mDraftConfig.sanitized());
        } else {
            mConfig = StarHudConfigStore.save(getContext(), mConfig.sanitized());
        }
        Toast.makeText(getContext(), "Controller layout saved", Toast.LENGTH_SHORT).show();
        setCustomizeMode(false);
        notifyConfigUpdated();
    }

    private void cancelCustomizeChanges() {
        mDraftConfig = null;
        Toast.makeText(getContext(), "Changes discarded", Toast.LENGTH_SHORT).show();
        setCustomizeMode(false);
    }

    private void confirmResetDraftLayout() {
        new AlertDialog.Builder(getContext())
                .setTitle("Reset Controller Layout")
                .setMessage("Restore default STAR Controller buttons and positions? You can review before tapping Save.")
                .setPositiveButton("Reset", (dialog, which) -> {
                    StarHudConfig cur = activeConfig();
                    StarHudConfig reset = StarHudConfig.defaults().copy(
                            cur.getLayoutId(),
                            cur.getLayoutName(),
                            cur.getUseStarHud(),
                            cur.getHudScale(),
                            cur.getHudOpacity(),
                            cur.getJoystickSize(),
                            cur.getJoystickDynamic(),
                            cur.getHotbarScale(),
                            cur.getCameraSensitivity(),
                            cur.getSneakToggle(),
                            cur.getSprintToggle(),
                            cur.getScreenGesturesEnabled(),
                            cur.getSnapToGuides(),
                            cur.getShowAlignmentGuides(),
                            cur.getShowInputDebugPanel(),
                            StarHudConfig.defaultElements(),
                            StarHudConfig.defaultButtons()
                    );
                    updateActiveDraft(reset);
                    List<StarHudButtonConfig> btns = activeConfig().getButtons();
                    mSelectedButtonId = btns.isEmpty() ? null : btns.get(0).getId();
                    mSelectedStructuralElement = null;
                    refreshToolbarSelectionState();
                    Toast.makeText(getContext(), "Reset staged — tap Save to apply", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Keep Editing", null)
                .show();
    }

    private void toggleSnapInDraft() {
        StarHudConfig cur = activeConfig();
        boolean nextSnap = !cur.getSnapToGuides();
        StarHudConfig updated = cur.copy(
                cur.getLayoutId(),
                cur.getLayoutName(),
                cur.getUseStarHud(),
                cur.getHudScale(),
                cur.getHudOpacity(),
                cur.getJoystickSize(),
                cur.getJoystickDynamic(),
                cur.getHotbarScale(),
                cur.getCameraSensitivity(),
                cur.getSneakToggle(),
                cur.getSprintToggle(),
                cur.getScreenGesturesEnabled(),
                nextSnap,
                nextSnap,
                cur.getShowInputDebugPanel(),
                cur.getElements(),
                cur.getButtons()
        );
        updateActiveDraft(updated);
        refreshToolbarSelectionState();
    }

    private void duplicateSelectedButton() {
        if (mSelectedButtonId == null) {
            Toast.makeText(getContext(), "Select a button first", Toast.LENGTH_SHORT).show();
            return;
        }
        StarHudConfig cur = activeConfig();
        StarHudButtonConfig source = cur.findButton(mSelectedButtonId);
        if (source == null) return;
        StarHudConfig next = cur.withDuplicatedButton(mSelectedButtonId);
        List<StarHudButtonConfig> btns = next.getButtons();
        if (!btns.isEmpty()) {
            mSelectedButtonId = btns.get(btns.size() - 1).getId();
        }
        updateActiveDraft(next);
        refreshToolbarSelectionState();
        Toast.makeText(getContext(), "Button duplicated", Toast.LENGTH_SHORT).show();
    }

    private void deleteSelectedButton() {
        if (mSelectedButtonId == null) {
            Toast.makeText(getContext(), "Select a button first", Toast.LENGTH_SHORT).show();
            return;
        }
        StarHudConfig cur = activeConfig();
        StarHudButtonConfig target = cur.findButton(mSelectedButtonId);
        if (target == null) return;
        if (target.isBuiltIn()) {
            updateActiveDraft(cur.withRemovedButton(mSelectedButtonId));
            Toast.makeText(getContext(), "Built-in button hidden (toggle Visible in Edit to restore)", Toast.LENGTH_SHORT).show();
        } else {
            StarHudConfig next = cur.withRemovedButton(mSelectedButtonId);
            List<StarHudButtonConfig> remaining = next.getButtons();
            mSelectedButtonId = remaining.isEmpty() ? null : remaining.get(0).getId();
            updateActiveDraft(next);
            Toast.makeText(getContext(), "Custom button deleted", Toast.LENGTH_SHORT).show();
        }
        refreshToolbarSelectionState();
    }

    private void updateActiveDraft(@NonNull StarHudConfig next) {
        StarHudConfig clean = next.sanitized();
        if (mCustomizeMode) {
            mDraftConfig = clean;
            applyActiveConfigToViews();
        } else {
            setConfig(clean);
        }
    }

    private void refreshToolbarSelectionState() {
        StarHudConfig cfg = activeConfig();
        if (mSnapToggleChip != null) {
            mSnapToggleChip.setText(cfg.getSnapToGuides() ? "Snap: On" : "Snap: Off");
        }
        if (mSelectedLabelText != null) {
            if (mSelectedStructuralElement != null) {
                mSelectedLabelText.setText(mSelectedStructuralElement.getDisplayName());
            } else if (mSelectedButtonId != null) {
                StarHudButtonConfig btn = cfg.findButton(mSelectedButtonId);
                mSelectedLabelText.setText(btn != null ? btn.getAction().getDisplayName() : "Edit Controller");
            } else {
                mSelectedLabelText.setText("Edit Controller");
            }
        }
        if (mEditButtonChip != null) {
            boolean hasButton = mSelectedButtonId != null && cfg.findButton(mSelectedButtonId) != null;
            mEditButtonChip.setAlpha(hasButton ? 1.0f : 0.45f);
        }
        for (Map.Entry<String, StarHudButtonView> entry : mDynamicButtons.entrySet()) {
            entry.getValue().setEditSelected(mCustomizeMode && entry.getKey().equals(mSelectedButtonId));
        }
        invalidate();
    }

    @NonNull
    public StarHudConfig getConfig() {
        return mConfig;
    }

    public void setConfig(@NonNull StarHudConfig config) {
        StarHudConfig sanitized = config.sanitized();
        boolean changed = !sanitized.equals(mConfig);
        if (changed) {
            releaseAllInputsWithReason("layout_switched");
        }
        mConfig = sanitized;
        if (!mCustomizeMode) {
            applyActiveConfigToViews();
        }
    }

    private void applyActiveConfigToViews() {
        StarHudConfig cfg = activeConfig();
        LauncherPreferences.PREF_CAMERA_SENSITIVITY = cfg.getCameraSensitivity();
        LauncherPreferences.PREF_DISABLE_GESTURES = !cfg.getScreenGesturesEnabled();

        syncDynamicButtonViews(cfg);
        mJoystickView.configure(cfg.getHudOpacity(), cfg.getJoystickSize(), cfg.getJoystickDynamic());
        mMenuKeyboardButton.configure(cfg.getHudScale(), cfg.getHudOpacity(), false);
        mMenuVirtualMouseButton.configure(cfg.getHudScale(), cfg.getHudOpacity(), false);

        applyHotbarConfig(cfg);
        updateChildVisibilities();
        relayoutElements();
    }

    private static boolean isInputGrabbing() {
        try {
            return StarHudInputManager.getInstance().getInputBridge().isGrabbing();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void applyHotbarConfig(@NonNull StarHudConfig cfg) {
        if (mHotbarView != null) {
            mHotbarView.applyStarHudConfig(
                    cfg,
                    mSafeInsets.getLeft(),
                    mSafeInsets.getTop(),
                    mSafeInsets.getRight(),
                    mSafeInsets.getBottom()
            );
        }
    }

    private void syncDynamicButtonViews(@NonNull StarHudConfig cfg) {
        List<StarHudButtonConfig> desired = cfg.getButtons();
        Map<String, StarHudButtonConfig> desiredById = new LinkedHashMap<>();
        for (StarHudButtonConfig b : desired) {
            desiredById.put(b.getId(), b);
        }

        boolean hierarchyChanged = false;
        Iterator<Map.Entry<String, StarHudButtonView>> it = mDynamicButtons.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, StarHudButtonView> entry = it.next();
            if (!desiredById.containsKey(entry.getKey())) {
                entry.getValue().forceReleaseAll();
                removeFromParent(entry.getValue());
                it.remove();
                hierarchyChanged = true;
            }
        }

        ViewGroup target = mHostContainer != null ? mHostContainer : this;
        int insertIndex = findInsertIndex(target);

        for (StarHudButtonConfig btnCfg : desired) {
            StarHudButtonView view = mDynamicButtons.get(btnCfg.getId());
            if (view == null) {
                view = new StarHudButtonView(getContext(), btnCfg);
                mDynamicButtons.put(btnCfg.getId(), view);
                if (mChildrenAttached) {
                    target.addView(view, Math.min(insertIndex++, target.getChildCount()));
                }
                hierarchyChanged = true;
            }
            view.applyButtonConfig(btnCfg, cfg.getHudScale(), cfg.getHudOpacity());
            view.setCustomizeHighlight(mCustomizeMode);
            view.setEditSelected(mCustomizeMode && btnCfg.getId().equals(mSelectedButtonId));
            view.setHiddenInPlayPreview(mCustomizeMode && !btnCfg.getVisible());
        }

        if (hierarchyChanged && mChildrenAttached) {
            target.requestLayout();
        }
    }

    public void updateSafeInsets(int left, int top, int right, int bottom) {
        StarHudSafeInsets next = new StarHudSafeInsets(
                Math.max(0, left),
                Math.max(0, top),
                Math.max(0, right),
                Math.max(0, bottom)
        );
        if (next.equals(mSafeInsets)) return;
        mSafeInsets = next;
        applyHotbarConfig(activeConfig());
        relayoutElements();
    }

    public void releaseAllInputs() {
        releaseAllInputsWithReason("overlay_release_all");
    }

    private void releaseAllInputsWithReason(@NonNull String reason) {
        StarHudInputManager.getInstance().releaseAllInputs(reason);
        if (mDynamicZoneView != null) {
            mDynamicZoneView.resetPointerState();
        }
        if (mJoystickView != null) {
            mJoystickView.releaseAllKeys();
        }
        for (StarHudButtonView btn : mDynamicButtons.values()) {
            btn.forceReleaseAll();
        }
    }

    @Nullable
    public StarJoystickView getJoystickView() {
        return mJoystickView;
    }

    @Nullable
    public StarHudButtonView getButtonView(@NonNull StarHudElementId id) {
        String builtInId = id.toBuiltInButtonId();
        return builtInId != null ? mDynamicButtons.get(builtInId) : null;
    }

    @Nullable
    public StarHudButtonView getButtonViewById(@NonNull String buttonId) {
        return mDynamicButtons.get(buttonId);
    }

    @Nullable
    public Map<StarHudElementId, StarHudElementRect> getCurrentRects() {
        return mCurrentRects;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mHostContainer == null && getParent() instanceof ViewGroup) {
            mHostContainer = (ViewGroup) getParent();
        }
        attachHudChildrenIfReady();
        if (!mGrabAttached) {
            try {
                CallbackBridge.addGrabListener(this);
                mGrabAttached = true;
            } catch (UnsatisfiedLinkError | NoClassDefFoundError ignored) {
                // Native bridge library is unavailable in JVM unit tests.
            }
        }
        relayoutElements();
    }

    @Override
    protected void onDetachedFromWindow() {
        releaseAllInputs();
        if (mGrabAttached) {
            try {
                CallbackBridge.removeGrabListener(this);
            } catch (UnsatisfiedLinkError | NoClassDefFoundError ignored) {
            }
            mGrabAttached = false;
        }
        detachHudChildren();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw || h != oldh) {
            post(this::relayoutElements);
        }
    }

    private void attachHudChildrenIfReady() {
        if (mChildrenAttached) return;
        ViewGroup target = mHostContainer != null ? mHostContainer : this;
        int insertIndex = findInsertIndex(target);

        target.addView(mDynamicZoneView, Math.min(insertIndex++, target.getChildCount()));
        target.addView(mJoystickView, Math.min(insertIndex++, target.getChildCount()));
        for (StarHudButtonView btn : mDynamicButtons.values()) {
            target.addView(btn, Math.min(insertIndex++, target.getChildCount()));
        }
        target.addView(mMenuKeyboardButton, Math.min(insertIndex++, target.getChildCount()));
        target.addView(mMenuVirtualMouseButton, Math.min(insertIndex, target.getChildCount()));
        mChildrenAttached = true;
        updateChildVisibilities();
    }

    private int findInsertIndex(ViewGroup target) {
        if (target == this) return Math.max(0, getChildCount() - 1);
        int hotbarIdx = mHotbarView != null ? target.indexOfChild(mHotbarView) : -1;
        if (hotbarIdx >= 0) return hotbarIdx;
        int selfIdx = target.indexOfChild(this);
        return selfIdx >= 0 ? selfIdx : target.getChildCount();
    }

    private void detachHudChildren() {
        if (!mChildrenAttached) return;
        releaseAllInputsWithReason("detach_hud_children");
        removeFromParent(mDynamicZoneView);
        removeFromParent(mJoystickView);
        for (StarHudButtonView btn : mDynamicButtons.values()) {
            removeFromParent(btn);
        }
        removeFromParent(mMenuKeyboardButton);
        removeFromParent(mMenuVirtualMouseButton);
        mChildrenAttached = false;
    }

    private static void removeFromParent(View v) {
        if (v != null && v.getParent() instanceof ViewGroup) {
            ((ViewGroup) v.getParent()).removeView(v);
        }
    }

    @Override
    public void onGrabState(boolean isGrabbing) {
        Runnable task = () -> {
            if (!isGrabbing) {
                releaseAllInputsWithReason("grab_state_lost");
                for (StarHudButtonView btn : mDynamicButtons.values()) {
                    btn.releaseOnGuiTransition();
                }
            }
            updateChildVisibilities();
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
        } else {
            post(task);
        }
    }

    private void updateChildVisibilities() {
        StarHudConfig cfg = activeConfig();
        boolean showHud = mHudActive && (cfg.getUseStarHud() || mCustomizeMode);
        boolean grabbing = isInputGrabbing();
        boolean worldMode = showHud && (grabbing || mCustomizeMode);
        boolean menuMode = showHud && !grabbing && !mCustomizeMode;

        mJoystickView.setVisibility(
                (worldMode && (mCustomizeMode || cfg.isElementEnabled(StarHudElementId.JOYSTICK)))
                        ? VISIBLE : GONE
        );
        mDynamicZoneView.setVisibility(
                (mHudActive && grabbing && !mCustomizeMode
                        && cfg.getJoystickDynamic()
                        && cfg.isElementEnabled(StarHudElementId.JOYSTICK))
                        ? VISIBLE : GONE
        );

        for (StarHudButtonConfig btnCfg : cfg.getButtons()) {
            StarHudButtonView view = mDynamicButtons.get(btnCfg.getId());
            if (view == null) continue;
            if (mCustomizeMode) {
                view.setVisibility(VISIBLE);
                view.setHiddenInPlayPreview(!btnCfg.getVisible());
            } else {
                boolean isPause = btnCfg.getAction() == StarHudActionType.PAUSE_MENU;
                boolean visibleInState = isPause ? (worldMode || menuMode) : worldMode;
                view.setHiddenInPlayPreview(false);
                view.setVisibility((btnCfg.getVisible() && visibleInState) ? VISIBLE : GONE);
            }
        }

        mMenuKeyboardButton.setVisibility(menuMode ? VISIBLE : GONE);
        mMenuVirtualMouseButton.setVisibility(menuMode ? VISIBLE : GONE);

        if (mDebugOverlayView != null) {
            mDebugOverlayView.setDevPanelEnabled(
                    BuildConfig.DEBUG && mHudActive && !mCustomizeMode && cfg.getShowInputDebugPanel()
            );
        }
    }

    public void relayoutElements() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            ViewGroup parent = mHostContainer;
            if (parent != null) {
                w = parent.getWidth();
                h = parent.getHeight();
            }
        }
        if (w <= 0 || h <= 0) return;

        StarHudConfig cfg = activeConfig();
        float density = getResources().getDisplayMetrics().density;
        mCurrentRects.clear();

        // Joystick
        StarHudElementRect joyRect = StarHudLayoutSolver.solveElementRect(
                StarHudElementId.JOYSTICK, cfg, w, h, density, mSafeInsets, -1, -1);
        mCurrentRects.put(StarHudElementId.JOYSTICK, joyRect);
        applyMargins(mJoystickView, joyRect.getLeft(), joyRect.getTop(), joyRect.getWidth(), joyRect.getHeight());

        // Dynamic left activation zone (strictly left side so right/center camera touches never hit it)
        int usableLeft = Math.max(0, mSafeInsets.getLeft());
        int usableTop = Math.max(0, mSafeInsets.getTop());
        int usableRight = Math.max(usableLeft + 1, w - Math.max(0, mSafeInsets.getRight()));
        int usableBottom = Math.max(usableTop + 1, h - Math.max(0, mSafeInsets.getBottom()));
        int zoneW = Math.round((usableRight - usableLeft) * 0.38f);
        int zoneTop = usableTop + Math.round((usableBottom - usableTop) * 0.30f);
        int zoneH = Math.max(1, usableBottom - zoneTop);
        mDynamicZoneBounds.set(usableLeft, zoneTop, usableLeft + zoneW, usableBottom);
        applyMargins(mDynamicZoneView, usableLeft, zoneTop, zoneW, zoneH);

        // Dynamic custom & built-in buttons
        for (StarHudButtonConfig btnCfg : cfg.getButtons()) {
            StarHudButtonView view = mDynamicButtons.get(btnCfg.getId());
            if (view == null) continue;
            StarHudButtonRect rect = StarHudLayoutSolver.solveButtonRect(
                    btnCfg, cfg, w, h, density, mSafeInsets);
            applyMargins(view, rect.getLeft(), rect.getTop(), rect.getWidth(), rect.getHeight());
            StarHudElementId legacyId = StarHudElementId.fromBuiltInButtonId(btnCfg.getId());
            if (legacyId != null) {
                mCurrentRects.put(
                        legacyId,
                        new StarHudElementRect(legacyId, rect.getLeft(), rect.getTop(), rect.getWidth(), rect.getHeight())
                );
            }
        }

        // Menu-only utility pills at safe top-left
        int menuPad = Math.round(10f * density);
        int kbW = Math.round(46f * cfg.getHudScale() * density);
        int kbH = Math.round(40f * cfg.getHudScale() * density);
        applyMargins(mMenuKeyboardButton, usableLeft + menuPad, usableTop + menuPad, kbW, kbH);
        applyMargins(mMenuVirtualMouseButton, usableLeft + menuPad + kbW + Math.round(8f * density), usableTop + menuPad, kbW, kbH);
    }

    private static void applyMargins(View view, int left, int top, int width, int height) {
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) lp;
            if (flp.leftMargin == left && flp.topMargin == top && flp.width == width && flp.height == height) {
                return;
            }
            flp.gravity = Gravity.TOP | Gravity.START;
            flp.leftMargin = left;
            flp.topMargin = top;
            flp.width = width;
            flp.height = height;
            view.setLayoutParams(flp);
        } else if (lp instanceof MarginLayoutParams) {
            MarginLayoutParams mlp = (MarginLayoutParams) lp;
            mlp.leftMargin = left;
            mlp.topMargin = top;
            mlp.width = width;
            mlp.height = height;
            view.setLayoutParams(mlp);
        } else {
            FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(width, height, Gravity.TOP | Gravity.START);
            flp.leftMargin = left;
            flp.topMargin = top;
            view.setLayoutParams(flp);
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (!mCustomizeMode) return;
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        canvas.drawRect(0, 0, w, h, mScrimPaint);

        if (activeConfig().getShowAlignmentGuides()) {
            canvas.drawLine(w * 0.5f, 0f, w * 0.5f, h, mGuidePaint);
            canvas.drawLine(0f, h * 0.5f, w, h * 0.5f, mGuidePaint);
        }
        if (mActiveSnapX != null) {
            canvas.drawLine(mActiveSnapX, 0f, mActiveSnapX, h, mSnapGuidePaint);
        }
        if (mActiveSnapY != null) {
            canvas.drawLine(0f, mActiveSnapY, w, mActiveSnapY, mSnapGuidePaint);
        }

        // Draw bottom-right resize knob on the selected button
        if (mSelectedButtonId != null) {
            StarHudButtonView selectedView = mDynamicButtons.get(mSelectedButtonId);
            if (selectedView != null && selectedView.getVisibility() == VISIBLE) {
                float d = getResources().getDisplayMetrics().density;
                float r = 7f * d;
                float cx = selectedView.getRight();
                float cy = selectedView.getBottom();
                mResizeHandleRect.set(cx - r, cy - r, cx + r, cy + r);
                canvas.drawCircle(cx, cy, r, mResizeHandlePaint);
                canvas.drawCircle(cx, cy, r, mResizeHandleStrokePaint);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!mCustomizeMode) {
            return false;
        }
        int action = event.getActionMasked();
        float x = event.getX();
        float y = event.getY();

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                mActiveSnapX = null;
                mActiveSnapY = null;
                mIsResizing = false;
                mIsDragging = false;

                if (isPointInSelectedResizeHandle(x, y)) {
                    StarHudButtonConfig btn = activeConfig().findButton(mSelectedButtonId);
                    if (btn != null) {
                        mIsResizing = true;
                        mDragStartRawX = event.getRawX();
                        mDragStartRawY = event.getRawY();
                        mResizeInitialWidthDp = btn.getWidthDp();
                        mResizeInitialHeightDp = btn.getHeightDp();
                        return true;
                    }
                }

                String hitBtnId = hitTestDynamicButton(x, y);
                if (hitBtnId != null) {
                    mSelectedButtonId = hitBtnId;
                    mSelectedStructuralElement = null;
                    startDragForView(mDynamicButtons.get(hitBtnId), event.getRawX(), event.getRawY());
                    refreshToolbarSelectionState();
                    return true;
                }

                StarHudElementId structHit = hitTestStructuralElement(x, y);
                if (structHit != null) {
                    mSelectedStructuralElement = structHit;
                    mSelectedButtonId = null;
                    startDragForView(
                            structHit == StarHudElementId.JOYSTICK ? mJoystickView : mHotbarView,
                            event.getRawX(),
                            event.getRawY()
                    );
                    refreshToolbarSelectionState();
                    return true;
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                float density = getResources().getDisplayMetrics().density;
                if (mIsResizing && mSelectedButtonId != null) {
                    StarHudConfig cur = activeConfig();
                    StarHudButtonConfig btn = cur.findButton(mSelectedButtonId);
                    if (btn != null) {
                        float scale = Math.max(0.65f, cur.getHudScale());
                        float dxDp = (event.getRawX() - mDragStartRawX) / (density * scale);
                        float dyDp = (event.getRawY() - mDragStartRawY) / (density * scale);
                        float newW = mResizeInitialWidthDp + dxDp;
                        float newH = mResizeInitialHeightDp + dyDp;
                        updateActiveDraft(cur.withButtonSize(mSelectedButtonId, newW, newH));
                    }
                    return true;
                }
                if (!mIsDragging) return true;

                int containerW = getWidth();
                int containerH = getHeight();
                if (containerW <= 0 || containerH <= 0) return true;

                View target = mSelectedButtonId != null
                        ? mDynamicButtons.get(mSelectedButtonId)
                        : (mSelectedStructuralElement == StarHudElementId.JOYSTICK ? mJoystickView : mHotbarView);
                if (target == null) return true;

                float dx = event.getRawX() - mDragStartRawX;
                float dy = event.getRawY() - mDragStartRawY;
                int ew = Math.max(1, target.getWidth());
                int eh = Math.max(1, target.getHeight());
                int rawLeft = Math.round(mDragInitialLeft + dx);
                int rawTop = Math.round(mDragInitialTop + dy);

                StarHudConfig cur = activeConfig();
                if (cur.getSnapToGuides()) {
                    StarHudSnapResult snapped = StarHudLayoutSolver.snapDragPosition(
                            rawLeft, rawTop, ew, eh, containerW, containerH, density, collectPeerCenters(target)
                    );
                    rawLeft = snapped.getLeftPx();
                    rawTop = snapped.getTopPx();
                    mActiveSnapX = snapped.getGuideX();
                    mActiveSnapY = snapped.getGuideY();
                } else {
                    mActiveSnapX = null;
                    mActiveSnapY = null;
                }

                if (mSelectedButtonId != null) {
                    Pair<Float, Float> fractions = StarHudLayoutSolver.fractionsFromTopLeftForButton(
                            rawLeft, rawTop, ew, eh, containerW, containerH, density, mSafeInsets
                    );
                    updateActiveDraft(cur.withButtonPosition(mSelectedButtonId, fractions.getFirst(), fractions.getSecond()));
                } else if (mSelectedStructuralElement != null) {
                    Pair<Float, Float> fractions = StarHudLayoutSolver.fractionsFromTopLeft(
                            mSelectedStructuralElement, rawLeft, rawTop, ew, eh, containerW, containerH, density, mSafeInsets
                    );
                    StarHudConfig next = cur.withElementPosition(
                            mSelectedStructuralElement, fractions.getFirst(), fractions.getSecond()
                    );
                    if (mCustomizeMode) {
                        mDraftConfig = next.sanitized();
                        if (mSelectedStructuralElement == StarHudElementId.HOTBAR) {
                            applyHotbarConfig(mDraftConfig);
                        } else {
                            relayoutElements();
                        }
                    } else {
                        setConfig(next);
                    }
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mIsDragging = false;
                mIsResizing = false;
                mActiveSnapX = null;
                mActiveSnapY = null;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private void startDragForView(@Nullable View v, float rawX, float rawY) {
        mIsDragging = true;
        if (v != null && v.getLayoutParams() instanceof MarginLayoutParams) {
            MarginLayoutParams mlp = (MarginLayoutParams) v.getLayoutParams();
            mDragInitialLeft = mlp.leftMargin;
            mDragInitialTop = mlp.topMargin;
        } else {
            mDragInitialLeft = v != null ? v.getLeft() : 0;
            mDragInitialTop = v != null ? v.getTop() : 0;
        }
        mDragStartRawX = rawX;
        mDragStartRawY = rawY;
    }

    private List<Pair<Integer, Integer>> collectPeerCenters(@NonNull View exclude) {
        List<Pair<Integer, Integer>> centers = new ArrayList<>();
        if (mJoystickView != null && mJoystickView != exclude && mJoystickView.getVisibility() == VISIBLE) {
            centers.add(new Pair<>(
                    (mJoystickView.getLeft() + mJoystickView.getRight()) / 2,
                    (mJoystickView.getTop() + mJoystickView.getBottom()) / 2
            ));
        }
        if (mHotbarView != null && mHotbarView != exclude && mHotbarView.getVisibility() == VISIBLE) {
            centers.add(new Pair<>(
                    (mHotbarView.getLeft() + mHotbarView.getRight()) / 2,
                    (mHotbarView.getTop() + mHotbarView.getBottom()) / 2
            ));
        }
        for (StarHudButtonView btn : mDynamicButtons.values()) {
            if (btn != exclude && btn.getVisibility() == VISIBLE) {
                centers.add(new Pair<>(
                        (btn.getLeft() + btn.getRight()) / 2,
                        (btn.getTop() + btn.getBottom()) / 2
                ));
            }
        }
        return centers;
    }

    private boolean isPointInSelectedResizeHandle(float x, float y) {
        if (mSelectedButtonId == null) return false;
        StarHudButtonView btn = mDynamicButtons.get(mSelectedButtonId);
        if (btn == null || btn.getVisibility() != VISIBLE) return false;
        float hitRadius = 18f * getResources().getDisplayMetrics().density;
        float cx = btn.getRight();
        float cy = btn.getBottom();
        return Math.abs(x - cx) <= hitRadius && Math.abs(y - cy) <= hitRadius;
    }

    @Nullable
    private String hitTestDynamicButton(float x, float y) {
        List<String> orderedIds = new ArrayList<>(mDynamicButtons.keySet());
        for (int i = orderedIds.size() - 1; i >= 0; i--) {
            String id = orderedIds.get(i);
            StarHudButtonView btn = mDynamicButtons.get(id);
            if (isPointInView(btn, x, y)) {
                return id;
            }
        }
        return null;
    }

    @Nullable
    private StarHudElementId hitTestStructuralElement(float x, float y) {
        if (isPointInView(mJoystickView, x, y)) return StarHudElementId.JOYSTICK;
        if (isPointInView(mHotbarView, x, y)) return StarHudElementId.HOTBAR;
        return null;
    }

    private static boolean isPointInView(@Nullable View view, float x, float y) {
        if (view == null || view.getVisibility() != VISIBLE) return false;
        return x >= view.getLeft() && x <= view.getRight() && y >= view.getTop() && y <= view.getBottom();
    }

    private void notifyConfigUpdated() {
        if (mListener != null) {
            mListener.onHudConfigUpdated(mConfig);
        }
    }

    // -------------------------------------------------------------------------
    // Phase 1 & Phase 2 In-Game Dialogs (Add Button, Configure Button, Layouts)
    // -------------------------------------------------------------------------

    private void showAddButtonDialog() {
        Context ctx = getContext();
        float d = getResources().getDisplayMetrics().density;
        List<StarHudActionType> actions = StarHudActionType.assignableButtonActions();
        List<StarHudKeyBindingOption> keyOptions = StarHudActionRegistry.getSupportedKeys();

        LinearLayout container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(Math.round(16 * d), Math.round(10 * d), Math.round(16 * d), Math.round(6 * d));

        TextView actionLabel = new TextView(ctx);
        actionLabel.setText("Button Action:");
        container.addView(actionLabel);

        Spinner actionSpinner = new Spinner(ctx);
        List<String> actionNames = new ArrayList<>();
        for (StarHudActionType a : actions) {
            actionNames.add(a.getCategory() + " • " + a.getDisplayName());
        }
        actionSpinner.setAdapter(new ArrayAdapter<>(ctx, android.R.layout.simple_spinner_dropdown_item, actionNames));
        container.addView(actionSpinner);

        TextView keyLabel = new TextView(ctx);
        keyLabel.setText("Minecraft Key (for Custom Key Binding):");
        keyLabel.setVisibility(GONE);
        container.addView(keyLabel);

        Spinner keySpinner = new Spinner(ctx);
        List<String> keyNames = new ArrayList<>();
        for (StarHudKeyBindingOption k : keyOptions) {
            keyNames.add(k.getDisplayName() + " (" + k.getCategory() + ")");
        }
        keySpinner.setAdapter(new ArrayAdapter<>(ctx, android.R.layout.simple_spinner_dropdown_item, keyNames));
        keySpinner.setVisibility(GONE);
        container.addView(keySpinner);

        TextView labelTitle = new TextView(ctx);
        labelTitle.setText("Custom Button Label (optional):");
        container.addView(labelTitle);

        EditText labelInput = new EditText(ctx);
        labelInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(12)});
        labelInput.setHint("Leave blank for default");
        container.addView(labelInput);

        TextView warningText = new TextView(ctx);
        warningText.setTextColor(0xFFEF4444);
        warningText.setVisibility(GONE);
        container.addView(warningText);

        actionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                StarHudActionType selected = actions.get(position);
                boolean isCustom = selected == StarHudActionType.CUSTOM_KEY;
                keyLabel.setVisibility(isCustom ? VISIBLE : GONE);
                keySpinner.setVisibility(isCustom ? VISIBLE : GONE);
                int customKey = isCustom ? keyOptions.get(keySpinner.getSelectedItemPosition()).getKeycode() : 0;
                StarHudActionValidation val = StarHudActionRegistry.validateBinding(selected, customKey);
                if (!val.isSupported()) {
                    warningText.setText(val.getWarningMessage());
                    warningText.setVisibility(VISIBLE);
                } else {
                    warningText.setVisibility(GONE);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        ScrollView scroll = new ScrollView(ctx);
        scroll.addView(container);

        new AlertDialog.Builder(ctx)
                .setTitle("Add Controller Button")
                .setView(scroll)
                .setPositiveButton("Create Button", (dialog, which) -> {
                    StarHudActionType selected = actions.get(actionSpinner.getSelectedItemPosition());
                    int customKey = selected == StarHudActionType.CUSTOM_KEY
                            ? keyOptions.get(keySpinner.getSelectedItemPosition()).getKeycode()
                            : 0;
                    StarHudActionValidation validation = StarHudActionRegistry.validateBinding(selected, customKey);
                    if (!validation.isSupported()) {
                        new AlertDialog.Builder(ctx)
                                .setTitle("Unsupported Action")
                                .setMessage(validation.getWarningMessage() != null
                                        ? validation.getWarningMessage()
                                        : "This action cannot be mapped safely.")
                                .setPositiveButton("OK", null)
                                .show();
                        return;
                    }
                    StarHudConfig cur = activeConfig();
                    String newId = cur.nextUniqueCustomButtonId(selected);
                    StarHudButtonConfig created = StarHudConfig.createButtonForAction(
                            newId, selected, customKey, 0.50f, 0.42f
                    );
                    String customLabel = labelInput.getText().toString().trim();
                    if (!customLabel.isEmpty()) {
                        created = created.copy(
                                created.getId(), created.getAction(), created.getCustomKeycode(),
                                created.getXFraction(), created.getYFraction(),
                                created.getWidthDp(), created.getHeightDp(),
                                created.getOpacity(), created.getBackgroundAlpha(),
                                created.getIcon(), customLabel, created.getVisible(),
                                created.getInteractionMode(), false
                        );
                    }
                    mSelectedButtonId = newId;
                    mSelectedStructuralElement = null;
                    updateActiveDraft(cur.withAddedButton(created));
                    refreshToolbarSelectionState();
                    Toast.makeText(ctx, "Added " + created.getAction().getDisplayName(), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showEditSelectedButtonDialog() {
        StarHudConfig cur = activeConfig();
        if (mSelectedButtonId == null || cur.findButton(mSelectedButtonId) == null) {
            Toast.makeText(getContext(), "Tap a button on screen first to select it", Toast.LENGTH_SHORT).show();
            return;
        }
        StarHudButtonConfig btn = cur.findButton(mSelectedButtonId);
        if (btn == null) return;

        Context ctx = getContext();
        float d = getResources().getDisplayMetrics().density;
        LinearLayout container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(Math.round(16 * d), Math.round(10 * d), Math.round(16 * d), Math.round(8 * d));

        // Action picker
        List<StarHudActionType> actions = StarHudActionType.assignableButtonActions();
        TextView actionLabel = new TextView(ctx);
        actionLabel.setText("Action:");
        container.addView(actionLabel);

        Spinner actionSpinner = new Spinner(ctx);
        List<String> actionNames = new ArrayList<>();
        int initActionIdx = 0;
        for (int i = 0; i < actions.size(); i++) {
            actionNames.add(actions.get(i).getDisplayName());
            if (actions.get(i) == btn.getAction()) initActionIdx = i;
        }
        actionSpinner.setAdapter(new ArrayAdapter<>(ctx, android.R.layout.simple_spinner_dropdown_item, actionNames));
        actionSpinner.setSelection(initActionIdx);
        container.addView(actionSpinner);

        // Custom key picker
        List<StarHudKeyBindingOption> keys = StarHudActionRegistry.getSupportedKeys();
        TextView keyTitle = new TextView(ctx);
        keyTitle.setText("Custom Key Binding:");
        container.addView(keyTitle);

        Spinner keySpinner = new Spinner(ctx);
        List<String> keyNames = new ArrayList<>();
        int initKeyIdx = 0;
        for (int i = 0; i < keys.size(); i++) {
            keyNames.add(keys.get(i).getDisplayName());
            if (keys.get(i).getKeycode() == btn.getCustomKeycode()) initKeyIdx = i;
        }
        keySpinner.setAdapter(new ArrayAdapter<>(ctx, android.R.layout.simple_spinner_dropdown_item, keyNames));
        keySpinner.setSelection(initKeyIdx);
        container.addView(keySpinner);

        boolean initCustom = btn.getAction() == StarHudActionType.CUSTOM_KEY;
        keyTitle.setVisibility(initCustom ? VISIBLE : GONE);
        keySpinner.setVisibility(initCustom ? VISIBLE : GONE);

        // Icon picker
        StarHudIconId[] icons = StarHudIconId.values();
        TextView iconTitle = new TextView(ctx);
        iconTitle.setText("Icon:");
        container.addView(iconTitle);

        Spinner iconSpinner = new Spinner(ctx);
        List<String> iconNames = new ArrayList<>();
        int initIconIdx = 0;
        for (int i = 0; i < icons.length; i++) {
            iconNames.add(icons[i].name());
            if (icons[i] == btn.getIcon()) initIconIdx = i;
        }
        iconSpinner.setAdapter(new ArrayAdapter<>(ctx, android.R.layout.simple_spinner_dropdown_item, iconNames));
        iconSpinner.setSelection(initIconIdx);
        container.addView(iconSpinner);

        // Interaction mode picker
        TextView modeTitle = new TextView(ctx);
        modeTitle.setText("Activation Behavior:");
        container.addView(modeTitle);

        StarHudInteractionMode[] modes = StarHudInteractionMode.values();
        Spinner modeSpinner = new Spinner(ctx);
        List<String> modeNames = new ArrayList<>();
        int initModeIdx = 0;
        for (int i = 0; i < modes.length; i++) {
            modeNames.add(modes[i].getDisplayName());
            if (modes[i] == btn.getInteractionMode()) initModeIdx = i;
        }
        modeSpinner.setAdapter(new ArrayAdapter<>(ctx, android.R.layout.simple_spinner_dropdown_item, modeNames));
        modeSpinner.setSelection(initModeIdx);
        container.addView(modeSpinner);

        actionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                boolean isCustom = actions.get(pos) == StarHudActionType.CUSTOM_KEY;
                keyTitle.setVisibility(isCustom ? VISIBLE : GONE);
                keySpinner.setVisibility(isCustom ? VISIBLE : GONE);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        // Label
        TextView labelTitle = new TextView(ctx);
        labelTitle.setText("Label (max 12 chars):");
        container.addView(labelTitle);

        EditText labelEdit = new EditText(ctx);
        labelEdit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(12)});
        labelEdit.setText(btn.getLabel());
        container.addView(labelEdit);

        // Width slider
        int minDp = Math.round(StarHudButtonConfig.MIN_BUTTON_DP);
        int maxDp = Math.round(StarHudButtonConfig.MAX_BUTTON_DP);
        TextView widthLabel = new TextView(ctx);
        widthLabel.setText("Width: " + Math.round(btn.getWidthDp()) + " dp");
        container.addView(widthLabel);
        SeekBar widthSeek = new SeekBar(ctx);
        widthSeek.setMax(maxDp - minDp);
        widthSeek.setProgress(Math.round(btn.getWidthDp()) - minDp);
        widthSeek.setOnSeekBarChangeListener(simpleSeekListener(p ->
                widthLabel.setText("Width: " + (minDp + p) + " dp")
        ));
        container.addView(widthSeek);

        // Height slider
        TextView heightLabel = new TextView(ctx);
        heightLabel.setText("Height: " + Math.round(btn.getHeightDp()) + " dp");
        container.addView(heightLabel);
        SeekBar heightSeek = new SeekBar(ctx);
        heightSeek.setMax(maxDp - minDp);
        heightSeek.setProgress(Math.round(btn.getHeightDp()) - minDp);
        heightSeek.setOnSeekBarChangeListener(simpleSeekListener(p ->
                heightLabel.setText("Height: " + (minDp + p) + " dp")
        ));
        container.addView(heightSeek);

        // Opacity slider
        TextView opacityLabel = new TextView(ctx);
        opacityLabel.setText("Opacity: " + Math.round(btn.getOpacity() * 100f) + "%");
        container.addView(opacityLabel);
        SeekBar opacitySeek = new SeekBar(ctx);
        opacitySeek.setMax(85);
        opacitySeek.setProgress(Math.max(0, Math.round(btn.getOpacity() * 100f) - 15));
        opacitySeek.setOnSeekBarChangeListener(simpleSeekListener(p ->
                opacityLabel.setText("Opacity: " + (15 + p) + "%")
        ));
        container.addView(opacitySeek);

        // Background alpha slider
        TextView bgAlphaLabel = new TextView(ctx);
        bgAlphaLabel.setText("Background Fill Alpha: " + Math.round(btn.getBackgroundAlpha() * 100f) + "%");
        container.addView(bgAlphaLabel);
        SeekBar bgAlphaSeek = new SeekBar(ctx);
        bgAlphaSeek.setMax(100);
        bgAlphaSeek.setProgress(Math.round(btn.getBackgroundAlpha() * 100f));
        bgAlphaSeek.setOnSeekBarChangeListener(simpleSeekListener(p ->
                bgAlphaLabel.setText("Background Fill Alpha: " + p + "%")
        ));
        container.addView(bgAlphaSeek);

        CheckBox visibleCheck = new CheckBox(ctx);
        visibleCheck.setText("Visible during gameplay");
        visibleCheck.setChecked(btn.getVisible());
        container.addView(visibleCheck);

        ScrollView scroll = new ScrollView(ctx);
        scroll.addView(container);

        new AlertDialog.Builder(ctx)
                .setTitle("Configure: " + btn.getAction().getDisplayName())
                .setView(scroll)
                .setPositiveButton("Apply", (dialog, which) -> {
                    StarHudActionType chosenAction = actions.get(actionSpinner.getSelectedItemPosition());
                    int chosenKey = chosenAction == StarHudActionType.CUSTOM_KEY
                            ? keys.get(keySpinner.getSelectedItemPosition()).getKeycode()
                            : 0;
                    StarHudActionValidation val = StarHudActionRegistry.validateBinding(chosenAction, chosenKey);
                    if (!val.isSupported()) {
                        Toast.makeText(ctx, val.getWarningMessage(), Toast.LENGTH_LONG).show();
                        return;
                    }
                    StarHudButtonConfig updated = btn.copy(
                            btn.getId(),
                            chosenAction,
                            chosenKey,
                            btn.getXFraction(),
                            btn.getYFraction(),
                            minDp + widthSeek.getProgress(),
                            minDp + heightSeek.getProgress(),
                            (15 + opacitySeek.getProgress()) / 100f,
                            bgAlphaSeek.getProgress() / 100f,
                            icons[iconSpinner.getSelectedItemPosition()],
                            labelEdit.getText().toString().trim(),
                            visibleCheck.isChecked(),
                            modes[modeSpinner.getSelectedItemPosition()],
                            btn.isBuiltIn()
                    );
                    updateActiveDraft(activeConfig().withButtonUpdated(updated));
                    refreshToolbarSelectionState();
                })
                .setNeutralButton("Reset Button", (dialog, which) -> {
                    updateActiveDraft(activeConfig().withResetButton(btn.getId()));
                    refreshToolbarSelectionState();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showLayoutManagerDialog() {
        Context ctx = getContext();
        List<StarHudLayoutSummary> layouts = StarHudConfigStore.listLayouts(ctx);
        String activeId = activeConfig().getLayoutId();
        String[] labels = new String[layouts.size()];
        for (int i = 0; i < layouts.size(); i++) {
            StarHudLayoutSummary sum = layouts.get(i);
            labels[i] = (sum.getLayoutId().equals(activeId) ? "● " : "  ")
                    + sum.getLayoutName() + " (" + sum.getButtonCount() + " btns)";
        }

        new AlertDialog.Builder(ctx)
                .setTitle("Controller Layouts")
                .setItems(labels, (dialog, which) -> {
                    StarHudLayoutSummary chosen = layouts.get(which);
                    showLayoutActionsDialog(chosen);
                })
                .setPositiveButton("+ New Layout", (dialog, which) -> promptNewLayoutName())
                .setNeutralButton("Restore Default", (dialog, which) -> {
                    releaseAllInputsWithReason("layout_restore_default");
                    StarHudConfig def = StarHudConfigStore.restoreDefaultLayout(ctx);
                    mDraftConfig = def.sanitized();
                    applyActiveConfigToViews();
                    refreshToolbarSelectionState();
                    Toast.makeText(ctx, "Default layout restored", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void showLayoutActionsDialog(@NonNull StarHudLayoutSummary summary) {
        Context ctx = getContext();
        List<String> actions = new ArrayList<>();
        actions.add("Switch to Layout");
        actions.add("Rename Layout");
        actions.add("Duplicate Layout");
        if (!summary.isDefault()) {
            actions.add("Delete Layout");
        }

        new AlertDialog.Builder(ctx)
                .setTitle(summary.getLayoutName())
                .setItems(actions.toArray(new String[0]), (d, idx) -> {
                    String choice = actions.get(idx);
                    if ("Switch to Layout".equals(choice)) {
                        StarHudConfig switched = StarHudConfigStore.switchLayout(ctx, summary.getLayoutId());
                        if (switched != null) {
                            releaseAllInputsWithReason("layout_switched");
                            mConfig = switched;
                            mDraftConfig = switched;
                            mSelectedButtonId = switched.getButtons().isEmpty()
                                    ? null : switched.getButtons().get(0).getId();
                            applyActiveConfigToViews();
                            refreshToolbarSelectionState();
                            notifyConfigUpdated();
                            Toast.makeText(ctx, "Switched to " + switched.getLayoutName(), Toast.LENGTH_SHORT).show();
                        }
                    } else if ("Rename Layout".equals(choice)) {
                        promptRenameLayout(summary);
                    } else if ("Duplicate Layout".equals(choice)) {
                        StarHudConfig dup = StarHudConfigStore.duplicateLayout(ctx, summary.getLayoutId(), null);
                        if (dup != null) {
                            Toast.makeText(ctx, "Created " + dup.getLayoutName(), Toast.LENGTH_SHORT).show();
                        }
                    } else if ("Delete Layout".equals(choice)) {
                        StarHudConfig after = StarHudConfigStore.deleteLayout(ctx, summary.getLayoutId());
                        if (after != null) {
                            releaseAllInputsWithReason("layout_deleted");
                            mConfig = after;
                            mDraftConfig = after;
                            applyActiveConfigToViews();
                            refreshToolbarSelectionState();
                            notifyConfigUpdated();
                            Toast.makeText(ctx, "Deleted " + summary.getLayoutName(), Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("Back", null)
                .show();
    }

    private void promptNewLayoutName() {
        Context ctx = getContext();
        EditText input = new EditText(ctx);
        input.setHint("e.g. Combat Layout");
        new AlertDialog.Builder(ctx)
                .setTitle("New Controller Layout")
                .setView(input)
                .setPositiveButton("Create", (d, w) -> {
                    String name = input.getText().toString().trim();
                    releaseAllInputsWithReason("layout_created");
                    StarHudConfig created = StarHudConfigStore.createLayout(ctx, name, activeConfig());
                    mConfig = created;
                    mDraftConfig = created;
                    applyActiveConfigToViews();
                    refreshToolbarSelectionState();
                    notifyConfigUpdated();
                    Toast.makeText(ctx, "Created " + created.getLayoutName(), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void promptRenameLayout(@NonNull StarHudLayoutSummary summary) {
        Context ctx = getContext();
        EditText input = new EditText(ctx);
        input.setText(summary.getLayoutName());
        new AlertDialog.Builder(ctx)
                .setTitle("Rename Layout")
                .setView(input)
                .setPositiveButton("Rename", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    StarHudConfig renamed = StarHudConfigStore.renameLayout(ctx, summary.getLayoutId(), name);
                    if (renamed != null && activeConfig().getLayoutId().equals(renamed.getLayoutId())) {
                        mConfig = renamed;
                        mDraftConfig = renamed;
                        refreshToolbarSelectionState();
                        notifyConfigUpdated();
                    }
                    Toast.makeText(ctx, "Renamed to " + name, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private interface IntConsumer {
        void accept(int val);
    }

    private static SeekBar.OnSeekBarChangeListener simpleSeekListener(@NonNull IntConsumer onProgress) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                onProgress.accept(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        };
    }

    /**
     * Bounded invisible activation zone on the left screen edge for dynamic joystick placement.
     * Kept separate from open center/right screen space so camera rotation stays completely unobstructed.
     */
    private final class DynamicJoystickZoneView extends View implements
            StarHudInputManager.ResetParticipant,
            StarHudInputManager.PointerReconciliationParticipant {
        private int mPointerId = MotionEvent.INVALID_POINTER_ID;
        private boolean mAttachedToManager = false;

        DynamicJoystickZoneView(Context context) {
            super(context);
            setClickable(false);
            setFocusable(false);
            setOutlineProvider(null);
            setTranslationZ(3f * context.getResources().getDisplayMetrics().density);
        }

        void resetPointerState() {
            mPointerId = MotionEvent.INVALID_POINTER_ID;
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
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            ensureRegisteredWithManager();
        }

        @Override
        protected void onDetachedFromWindow() {
            endSessionAndClearPointer();
            if (mAttachedToManager) {
                StarHudInputManager manager = StarHudInputManager.getInstance();
                manager.unregisterResetParticipant(this);
                manager.unregisterPointerParticipant(this);
                mAttachedToManager = false;
            }
            super.onDetachedFromWindow();
        }

        @Override
        public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            if (!enabled) {
                endSessionAndClearPointer();
            }
        }

        @Override
        public void onWindowFocusChanged(boolean hasWindowFocus) {
            super.onWindowFocusChanged(hasWindowFocus);
            if (!hasWindowFocus) {
                endSessionAndClearPointer();
            }
        }

        @Override
        protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
            super.onVisibilityChanged(changedView, visibility);
            if (visibility != VISIBLE) {
                endSessionAndClearPointer();
            }
        }

        @Override
        public void onGlobalInputReset(@NonNull String reason) {
            mPointerId = MotionEvent.INVALID_POINTER_ID;
        }

        @Override
        public void onReconcileActivePointers(int activePointerIdBitmask, @NonNull String reason) {
            if (mPointerId != MotionEvent.INVALID_POINTER_ID) {
                boolean present = mPointerId >= 0
                        && mPointerId < 32
                        && (activePointerIdBitmask & (1 << mPointerId)) != 0;
                if (!present) {
                    endSessionAndClearPointer();
                }
            }
        }

        private void endSessionAndClearPointer() {
            if (mPointerId != MotionEvent.INVALID_POINTER_ID) {
                mPointerId = MotionEvent.INVALID_POINTER_ID;
                mJoystickView.endDynamicSession();
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            StarHudConfig cfg = activeConfig();
            if (mCustomizeMode || !isEnabled() || getVisibility() != VISIBLE
                    || !isInputGrabbing()
                    || !cfg.getJoystickDynamic()
                    || !cfg.isElementEnabled(StarHudElementId.JOYSTICK)) {
                endSessionAndClearPointer();
                return false;
            }
            ensureRegisteredWithManager();
            int action = event.getActionMasked();
            switch (action) {
                case MotionEvent.ACTION_DOWN: {
                    if (mPointerId != MotionEvent.INVALID_POINTER_ID) {
                        endSessionAndClearPointer();
                    }
                    if (mJoystickView.isTracking()) {
                        return false;
                    }
                    int idx = event.getActionIndex();
                    if (idx < 0 || idx >= event.getPointerCount()) {
                        return false;
                    }
                    int pointerId = event.getPointerId(idx);
                    if (pointerId == MotionEvent.INVALID_POINTER_ID) {
                        return false;
                    }
                    mPointerId = pointerId;
                    float overlayX = getLeft() + event.getX(idx);
                    float overlayY = getTop() + event.getY(idx);
                    mJoystickView.beginDynamicSession(mPointerId, overlayX, overlayY, mDynamicZoneBounds);
                    return true;
                }
                case MotionEvent.ACTION_POINTER_DOWN: {
                    if (mPointerId != MotionEvent.INVALID_POINTER_ID) {
                        if (event.getPointerCount() >= 2 && event.findPointerIndex(mPointerId) < 0) {
                            endSessionAndClearPointer();
                        } else if (mJoystickView.isTracking()) {
                            return false;
                        } else {
                            mPointerId = MotionEvent.INVALID_POINTER_ID;
                        }
                    }
                    if (mJoystickView.isTracking()) {
                        return false;
                    }
                    int idx = event.getActionIndex();
                    if (idx < 0 || idx >= event.getPointerCount()) {
                        return false;
                    }
                    int pointerId = event.getPointerId(idx);
                    if (pointerId == MotionEvent.INVALID_POINTER_ID) {
                        return false;
                    }
                    mPointerId = pointerId;
                    float overlayX = getLeft() + event.getX(idx);
                    float overlayY = getTop() + event.getY(idx);
                    mJoystickView.beginDynamicSession(mPointerId, overlayX, overlayY, mDynamicZoneBounds);
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (mPointerId == MotionEvent.INVALID_POINTER_ID) return false;
                    int idx = event.findPointerIndex(mPointerId);
                    if (idx >= 0) {
                        float overlayX = getLeft() + event.getX(idx);
                        float overlayY = getTop() + event.getY(idx);
                        mJoystickView.moveDynamicSession(overlayX, overlayY);
                    } else {
                        endSessionAndClearPointer();
                    }
                    return true;
                }
                case MotionEvent.ACTION_POINTER_UP: {
                    if (mPointerId == MotionEvent.INVALID_POINTER_ID) return false;
                    int idx = event.getActionIndex();
                    if ((idx >= 0 && idx < event.getPointerCount() && event.getPointerId(idx) == mPointerId)
                            || (event.getPointerCount() >= 2 && event.findPointerIndex(mPointerId) < 0)) {
                        endSessionAndClearPointer();
                        return true;
                    }
                    return false;
                }
                case MotionEvent.ACTION_UP: {
                    if (mPointerId == MotionEvent.INVALID_POINTER_ID) return false;
                    endSessionAndClearPointer();
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    if (mPointerId == MotionEvent.INVALID_POINTER_ID) return false;
                    endSessionAndClearPointer();
                    return true;
                default:
                    return false;
            }
        }
    }
}
