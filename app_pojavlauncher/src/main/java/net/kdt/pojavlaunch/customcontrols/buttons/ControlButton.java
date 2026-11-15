package net.kdt.pojavlaunch.customcontrols.buttons;

import static net.kdt.pojavlaunch.LwjglGlfwKeycode.GLFW_KEY_UNKNOWN;
import static org.lwjgl.glfw.CallbackBridge.sendKeyPress;
import static org.lwjgl.glfw.CallbackBridge.sendMouseButton;

import android.annotation.SuppressLint;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.star.launcher.hud.StarHudInputManager;

import net.kdt.pojavlaunch.EfficientAndroidLWJGLKeycode;
import net.kdt.pojavlaunch.LwjglGlfwKeycode;
import net.kdt.pojavlaunch.MainActivity;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.customcontrols.ControlData;
import net.kdt.pojavlaunch.customcontrols.ControlLayout;
import net.kdt.pojavlaunch.customcontrols.handleview.EditControlSideDialog;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import org.lwjgl.glfw.CallbackBridge;

@SuppressLint({"ViewConstructor", "AppCompatCustomView"})
public class ControlButton extends TextView implements ControlInterface, StarHudInputManager.ResetParticipant {
    private final Paint mRectPaint = new Paint();
    protected ControlData mProperties;
    private final ControlLayout mControlLayout;

    /* Cache value from the ControlData radius for drawing purposes */
    private float mComputedRadius;

    protected boolean mIsToggled = false;
    protected boolean mIsPointerOutOfBounds = false;
    private int mActivePointerId = MotionEvent.INVALID_POINTER_ID;
    private boolean mPointerHeld = false;
    private final String mMouseLeasePrefix = "ctrl_btn:" + System.identityHashCode(this) + ":";

    public ControlButton(ControlLayout layout, ControlData properties) {
        super(layout.getContext());
        mControlLayout = layout;
        setGravity(Gravity.CENTER);
        setAllCaps(LauncherPreferences.PREF_BUTTON_ALL_CAPS);
        setTextColor(Color.WHITE);
        setPadding(4, 4, 4, 4);
        setTextSize(14); // Nullify the default size setting
        setOutlineProvider(null); // Disable shadow casting, removing one drawing pass

        //setOnLongClickListener(this);

        //When a button is created, the width/height has yet to be processed to fit the scaling.
        setProperties(preProcessProperties(properties, layout));

        injectBehaviors();
    }

    @Override
    public View getControlView() {return this;}

    public ControlData getProperties() {
        return mProperties;
    }

    public void setProperties(ControlData properties, boolean changePos) {
        mProperties = properties;
        ControlInterface.super.setProperties(properties, changePos);
        mComputedRadius = ControlInterface.super.computeCornerRadius(mProperties.cornerRadius);

        if (mProperties.isToggle) {
            //For the toggle layer
            final TypedValue value = new TypedValue();
            getContext().getTheme().resolveAttribute(R.attr.colorAccent, value, true);
            mRectPaint.setColor(value.data);
            mRectPaint.setAlpha(128);
        } else {
            mRectPaint.setColor(Color.WHITE);
            mRectPaint.setAlpha(60);
        }

        setText(properties.name);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mIsToggled || (!mProperties.isToggle && isActivated()))
            canvas.drawRoundRect(0, 0, getWidth(), getHeight(), mComputedRadius, mComputedRadius, mRectPaint);
    }


    public void loadEditValues(EditControlSideDialog editControlPopup){
        editControlPopup.loadValues(getProperties());
    }

    /** Add another instance of the ControlButton to the parent layout */
    public void cloneButton(){
        ControlData cloneData = new ControlData(getProperties());
        cloneData.dynamicX = "0.5 * ${screen_width}";
        cloneData.dynamicY = "0.5 * ${screen_height}";
        ((ControlLayout) getParent()).addControlButton(cloneData);
    }

    /** Remove any trace of this button from the layout */
    public void removeButton() {
        getControlLayoutParent().getLayout().mControlDataList.remove(getProperties());
        getControlLayoutParent().removeView(this);
    }


    private void forceReleaseHeldControl(String reason) {
        mActivePointerId = MotionEvent.INVALID_POINTER_ID;
        mIsPointerOutOfBounds = false;
        if (mPointerHeld) {
            mPointerHeld = false;
            sendKeyPresses(false);
        }
        if (mIsToggled) {
            mIsToggled = false;
            sendKeyPresses(false);
            invalidate();
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != VISIBLE) {
            forceReleaseHeldControl("visibility_hidden");
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus) {
            forceReleaseHeldControl("window_focus_lost");
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        StarHudInputManager.getInstance().registerResetParticipant(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        forceReleaseHeldControl("detached");
        StarHudInputManager.getInstance().unregisterResetParticipant(this);
        super.onDetachedFromWindow();
    }

    @Override
    public void onGlobalInputReset(@NonNull String reason) {
        mActivePointerId = MotionEvent.INVALID_POINTER_ID;
        mIsPointerOutOfBounds = false;
        mPointerHeld = false;
        if (mIsToggled) {
            mIsToggled = false;
        }
        setActivated(false);
        invalidate();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()){
            case MotionEvent.ACTION_MOVE: {
                if (mActivePointerId == MotionEvent.INVALID_POINTER_ID) {
                    return false;
                }
                int idx = event.findPointerIndex(mActivePointerId);
                if (idx < 0) {
                    if (!mProperties.isToggle && mPointerHeld) {
                        mPointerHeld = false;
                        sendKeyPresses(false);
                    }
                    mActivePointerId = MotionEvent.INVALID_POINTER_ID;
                    mIsPointerOutOfBounds = false;
                    return true;
                }

                //Send the event to be taken as a mouse action
                if(getProperties().passThruEnabled && CallbackBridge.isGrabbing()){
                    View gameSurface = getControlLayoutParent().getGameSurface();
                    if(gameSurface != null) gameSurface.dispatchTouchEvent(event);
                }

                float pointerX = event.getX(idx);
                float pointerY = event.getY(idx);

                //If out of bounds
                if(pointerX < 0 || pointerX > getWidth() ||
                        pointerY < 0 || pointerY > getHeight()){
                    if(getProperties().isSwipeable && !mIsPointerOutOfBounds){
                        //Remove keys
                        if(!triggerToggle() && mPointerHeld) {
                            mPointerHeld = false;
                            sendKeyPresses(false);
                        }
                    }
                    mIsPointerOutOfBounds = true;
                    getControlLayoutParent().onTouch(this, event);
                    break;
                }

                //Else if we now are in bounds
                if(mIsPointerOutOfBounds) {
                    getControlLayoutParent().onTouch(this, event);
                    //RE-press the button
                    if(getProperties().isSwipeable && !getProperties().isToggle && !mPointerHeld){
                        mPointerHeld = true;
                        sendKeyPresses(true);
                    }
                }
                mIsPointerOutOfBounds = false;
                break;
            }

            case MotionEvent.ACTION_DOWN: // 0
            case MotionEvent.ACTION_POINTER_DOWN: { // 5
                if (mActivePointerId != MotionEvent.INVALID_POINTER_ID) {
                    return true;
                }
                int idx = event.getActionIndex();
                if (idx < 0 || idx >= event.getPointerCount()) {
                    return false;
                }
                mActivePointerId = event.getPointerId(idx);
                if(!getProperties().isToggle && !mPointerHeld){
                    mPointerHeld = true;
                    sendKeyPresses(true);
                }
                break;
            }

            case MotionEvent.ACTION_POINTER_UP: { // 6
                int idx = event.getActionIndex();
                int pointerId = (idx >= 0 && idx < event.getPointerCount())
                        ? event.getPointerId(idx)
                        : MotionEvent.INVALID_POINTER_ID;
                if (pointerId != mActivePointerId) {
                    return false;
                }
                if(getProperties().passThruEnabled){
                    View gameSurface = getControlLayoutParent().getGameSurface();
                    if(gameSurface != null) gameSurface.dispatchTouchEvent(event);
                }
                if(mIsPointerOutOfBounds) getControlLayoutParent().onTouch(this, event);
                mIsPointerOutOfBounds = false;
                mActivePointerId = MotionEvent.INVALID_POINTER_ID;

                if(!triggerToggle() && mPointerHeld) {
                    mPointerHeld = false;
                    sendKeyPresses(false);
                }
                break;
            }

            case MotionEvent.ACTION_UP: // 1
            case MotionEvent.ACTION_CANCEL: { // 3
                if(getProperties().passThruEnabled){
                    View gameSurface = getControlLayoutParent().getGameSurface();
                    if(gameSurface != null) gameSurface.dispatchTouchEvent(event);
                }
                if(mIsPointerOutOfBounds) getControlLayoutParent().onTouch(this, event);
                mIsPointerOutOfBounds = false;
                mActivePointerId = MotionEvent.INVALID_POINTER_ID;

                if(!triggerToggle() && mPointerHeld) {
                    mPointerHeld = false;
                    sendKeyPresses(false);
                }
                break;
            }

            default:
                return false;
        }

        return super.onTouchEvent(event);
    }



    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean triggerToggle(){
        //returns true a the toggle system is triggered
        if(mProperties.isToggle){
            mIsToggled = !mIsToggled;
            invalidate();
            sendKeyPresses(mIsToggled);
            return true;
        }
        return false;
    }

    public void sendKeyPresses(boolean isDown){
        setActivated(isDown);
        for(int keycode : mProperties.keycodes){
            if(keycode >= GLFW_KEY_UNKNOWN){
                sendKeyPress(keycode, EfficientAndroidLWJGLKeycode.getLwjglChar(keycode), CallbackBridge.getCurrentMods(), isDown);
                CallbackBridge.setModifiers(keycode, isDown);
            }else{
                Log.i("punjabilauncher", "sendSpecialKey("+keycode+","+isDown+")");
                sendSpecialKey(keycode, isDown);
            }
        }
    }

    private void sendSpecialKey(int keycode, boolean isDown){
        switch (keycode) {
            case ControlData.SPECIALBTN_KEYBOARD:
                if(isDown) MainActivity.switchKeyboardState();
                break;

            case ControlData.SPECIALBTN_TOGGLECTRL:
                if(isDown)getControlLayoutParent().toggleControlVisible();
                break;

            case ControlData.SPECIALBTN_VIRTUALMOUSE:
                if(isDown) MainActivity.toggleMouse(getContext());
                break;

            case ControlData.SPECIALBTN_MOUSEPRI:
                dispatchManagedMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, isDown);
                break;

            case ControlData.SPECIALBTN_MOUSEMID:
                dispatchManagedMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_MIDDLE, isDown);
                break;

            case ControlData.SPECIALBTN_MOUSESEC:
                dispatchManagedMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT, isDown);
                break;

            case ControlData.SPECIALBTN_SCROLLDOWN:
                if (!isDown) CallbackBridge.sendScroll(0, 1f);
                break;

            case ControlData.SPECIALBTN_SCROLLUP:
                if (!isDown) CallbackBridge.sendScroll(0, -1f);
                break;

            case ControlData.SPECIALBTN_MENU:
                mControlLayout.notifyAppMenu();
                break;

            case ControlData.SPECIALBTN_MOUSEBCK:
                sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_4, isDown);
                break;

            case ControlData.SPECIALBTN_MOUSEFWD:
                sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_5, isDown);
                break;

            default:
                Toast.makeText(getContext(), R.string.error_key_unsupported, Toast.LENGTH_LONG).show();
                break;
        }
    }

    private void dispatchManagedMouseButton(int mouseButton, boolean isDown) {
        String leaseId = mMouseLeasePrefix + mouseButton;
        String buttonId = "ctrl:" + System.identityHashCode(this);
        String label = (mProperties != null && mProperties.name != null && !mProperties.name.isEmpty())
                ? mProperties.name
                : ("CTRL_MB_" + mouseButton);
        StarHudInputManager manager = StarHudInputManager.getInstance();
        if (isDown) {
            manager.acquireMouseLease(leaseId, buttonId, mActivePointerId, label, mouseButton);
        } else {
            manager.releaseLeaseIfPresent(leaseId, "ctrl_release");
        }
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }
}
