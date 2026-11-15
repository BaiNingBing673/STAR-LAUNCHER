package com.star.launcher.hud;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import net.kdt.pojavlaunch.BuildConfig;
import net.kdt.pojavlaunch.LwjglGlfwKeycode;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Development-only live input telemetry overlay (Phase 4).
 * Only active when {@link BuildConfig#DEBUG} is true and the user enables the dev panel toggle.
 * Displays active pointer IDs, button IDs, ref-counted keys/mouse states, bridge status,
 * mismatch/error counts, and provides a one-tap "Reset All" recovery trigger.
 */
public final class StarInputDebugOverlayView extends LinearLayout implements StarHudInputManager.TelemetryListener {

    private final TextView mHeaderText;
    private final TextView mBodyText;
    private final Button mResetButton;
    private boolean mDevEnabled = false;
    private boolean mListening = false;

    public StarInputDebugOverlayView(@NonNull Context context) {
        super(context);
        setOrientation(VERTICAL);
        float d = getResources().getDisplayMetrics().density;
        int pad = Math.round(8f * d);
        setPadding(pad, pad, pad, pad);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC0D131C);
        bg.setCornerRadius(10f * d);
        bg.setStroke(Math.max(1, Math.round(d)), 0x664DD0E1);
        setBackground(bg);

        LinearLayout headerRow = new LinearLayout(context);
        headerRow.setOrientation(HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        mHeaderText = new TextView(context);
        mHeaderText.setText("DEV INPUT MONITOR");
        mHeaderText.setTextColor(0xFF4DD0E1);
        mHeaderText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        mHeaderText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        LayoutParams headerLp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        headerRow.addView(mHeaderText, headerLp);

        mResetButton = new Button(context);
        mResetButton.setText("Reset All");
        mResetButton.setAllCaps(false);
        mResetButton.setTextColor(Color.WHITE);
        mResetButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        mResetButton.setMinHeight(0);
        mResetButton.setMinimumHeight(0);
        mResetButton.setMinWidth(0);
        mResetButton.setMinimumWidth(0);
        int btnPadH = Math.round(8f * d);
        int btnPadV = Math.round(3f * d);
        mResetButton.setPadding(btnPadH, btnPadV, btnPadH, btnPadV);
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(0xCCCF4444);
        btnBg.setCornerRadius(6f * d);
        mResetButton.setBackground(btnBg);
        mResetButton.setOnClickListener(v ->
                StarHudInputManager.getInstance().releaseAllInputs("dev_debug_reset_button")
        );
        LayoutParams btnLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        btnLp.setMarginStart(Math.round(8f * d));
        headerRow.addView(mResetButton, btnLp);

        addView(headerRow, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        mBodyText = new TextView(context);
        mBodyText.setTextColor(0xFFEAF2FA);
        mBodyText.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        mBodyText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        mBodyText.setLineSpacing(2f * d, 1f);
        LayoutParams bodyLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        bodyLp.topMargin = Math.round(4f * d);
        addView(mBodyText, bodyLp);

        setVisibility(View.GONE);
    }

    public void setDevPanelEnabled(boolean enabled) {
        boolean allow = BuildConfig.DEBUG && enabled;
        if (mDevEnabled == allow) {
            if (allow) {
                refreshSnapshot();
            }
            return;
        }
        mDevEnabled = allow;
        setVisibility(allow ? View.VISIBLE : View.GONE);
        syncListenerRegistration();
        if (allow) {
            refreshSnapshot();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        syncListenerRegistration();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mListening) {
            StarHudInputManager.getInstance().removeTelemetryListener(this);
            mListening = false;
        }
    }

    private void syncListenerRegistration() {
        boolean shouldListen = mDevEnabled && isAttachedToWindow();
        if (shouldListen && !mListening) {
            StarHudInputManager.getInstance().addTelemetryListener(this);
            mListening = true;
        } else if (!shouldListen && mListening) {
            StarHudInputManager.getInstance().removeTelemetryListener(this);
            mListening = false;
        }
    }

    @Override
    public void onInputStateChanged() {
        if (!mDevEnabled) return;
        refreshSnapshot();
    }

    public void refreshSnapshot() {
        if (!BuildConfig.DEBUG || !mDevEnabled) return;
        StarHudInputManager.DebugSnapshot snap = StarHudInputManager.getInstance().getDebugSnapshot();

        Set<Integer> pointerIds = new LinkedHashSet<>();
        Set<String> buttonIds = new LinkedHashSet<>();
        StringBuilder activeActions = new StringBuilder();
        for (StarHudInputManager.LeaseInfo lease : snap.activeLeases) {
            if (lease.pointerId >= 0) {
                pointerIds.add(lease.pointerId);
            }
            if (lease.buttonId != null && !lease.buttonId.isEmpty()) {
                buttonIds.add(lease.buttonId);
            }
            if (activeActions.length() > 0) activeActions.append(", ");
            activeActions.append(lease.actionLabel);
        }

        StringBuilder keyRefs = new StringBuilder();
        for (Map.Entry<Integer, Integer> entry : snap.pressedKeyRefCounts.entrySet()) {
            if (keyRefs.length() > 0) keyRefs.append(", ");
            String keyName = StarHudActionRegistry.keyShortLabel(entry.getKey());
            if ("?".equals(keyName)) {
                keyName = "K" + entry.getKey();
            }
            keyRefs.append(keyName)
                    .append('(')
                    .append(entry.getValue())
                    .append(')');
        }

        int lmb = getMouseRef(snap.pressedMouseRefCounts, LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT);
        int rmb = getMouseRef(snap.pressedMouseRefCounts, LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT);
        int mmb = getMouseRef(snap.pressedMouseRefCounts, LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_MIDDLE);

        StringBuilder sb = new StringBuilder();
        sb.append("Bridge: grab=").append(snap.isGrabbing)
                .append("  err=").append(snap.bridgeErrorCount)
                .append("  mismatch=").append(snap.mismatchCount).append('\n');
        sb.append("Ptrs: ").append(pointerIds.isEmpty() ? "none" : pointerIds.toString())
                .append(" | Btns: ").append(buttonIds.isEmpty() ? "none" : buttonIds.toString()).append('\n');
        sb.append("Mouse: L=").append(lmb)
                .append(" R=").append(rmb)
                .append(" M=").append(mmb).append('\n');
        sb.append("Keys: ").append(keyRefs.length() == 0 ? "none" : keyRefs.toString()).append('\n');
        sb.append("Active: ").append(activeActions.length() == 0 ? "idle" : activeActions.toString()).append('\n');
        sb.append("Last: ").append(snap.lastEvent == null ? "-" : snap.lastEvent);
        mBodyText.setText(sb.toString());
    }

    private static int getMouseRef(@NonNull Map<Integer, Integer> counts, int btn) {
        Integer v = counts.get(btn);
        return v == null ? 0 : v;
    }
}
