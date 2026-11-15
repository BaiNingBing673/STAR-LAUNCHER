package com.star.launcher.hud

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import net.kdt.pojavlaunch.LwjglGlfwKeycode
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.customcontrols.mouse.LeftClickGesture
import net.kdt.pojavlaunch.customcontrols.mouse.RightClickGesture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StarHudStuckInputRegressionTest {

    private lateinit var context: Context
    private lateinit var bridge: RecordingBridge

    private class RecordingBridge : StarHudInputBridge {
        data class KeyEventRecord(val keycode: Int, val isDown: Boolean, val applyModifiers: Boolean)
        data class MouseEventRecord(val button: Int, val isDown: Boolean)

        val keyEvents = mutableListOf<KeyEventRecord>()
        val mouseEvents = mutableListOf<MouseEventRecord>()
        val pressedKeys = mutableMapOf<Int, Boolean>()
        val pressedMouse = mutableMapOf<Int, Boolean>()
        var grabbing: Boolean = true

        override fun sendKey(keycode: Int, isDown: Boolean, applyModifiers: Boolean) {
            keyEvents.add(KeyEventRecord(keycode, isDown, applyModifiers))
            pressedKeys[keycode] = isDown
        }

        override fun sendMouseButton(button: Int, isDown: Boolean) {
            mouseEvents.add(MouseEventRecord(button, isDown))
            pressedMouse[button] = isDown
        }

        override fun sendKeyTap(keycode: Int) {
            keyEvents.add(KeyEventRecord(keycode, true, false))
            keyEvents.add(KeyEventRecord(keycode, false, false))
        }

        override fun sendScroll(xOffset: Float, yOffset: Float) = Unit

        override fun isGrabbing(): Boolean = grabbing

        fun countMouseDown(button: Int): Int = mouseEvents.count { it.button == button && it.isDown }
        fun countMouseUp(button: Int): Int = mouseEvents.count { it.button == button && !it.isDown }
        fun countKeyDown(keycode: Int): Int = keyEvents.count { it.keycode == keycode && it.isDown }
        fun countKeyUp(keycode: Int): Int = keyEvents.count { it.keycode == keycode && !it.isDown }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Tools.currentDisplayMetrics = context.resources.displayMetrics
        bridge = RecordingBridge()
        StarHudInputManager.getInstance().resetForTesting()
        StarHudInputManager.getInstance().setInputBridge(bridge)
    }

    private fun singlePointerEvent(
        downTime: Long,
        eventTime: Long,
        actionMasked: Int,
        pointerId: Int,
        x: Float,
        y: Float
    ): MotionEvent {
        val props = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = pointerId
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
            }
        )
        return MotionEvent.obtain(downTime, eventTime, actionMasked, 1, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
    }

    private fun multiPointerEvent(
        downTime: Long,
        eventTime: Long,
        actionMasked: Int,
        actionIndex: Int,
        pointers: List<Triple<Int, Float, Float>>
    ): MotionEvent {
        val props = Array(pointers.size) { i ->
            MotionEvent.PointerProperties().apply {
                id = pointers[i].first
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(pointers.size) { i ->
            MotionEvent.PointerCoords().apply {
                x = pointers[i].second
                y = pointers[i].third
            }
        }
        val action = if (actionMasked == MotionEvent.ACTION_POINTER_DOWN || actionMasked == MotionEvent.ACTION_POINTER_UP) {
            actionMasked or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        } else {
            actionMasked
        }
        return MotionEvent.obtain(
            downTime, eventTime, action, pointers.size, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0
        )
    }

    @Test
    fun `scenario 1 - hold mining then release normally and on cancel`() {
        val attackBtn = StarHudButtonView(context, StarHudElementId.ATTACK).apply {
            layout(0, 0, 120, 120)
        }
        val lmb = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT.toInt()
        val mgr = StarHudInputManager.getInstance()
        val t = SystemClock.uptimeMillis()

        attackBtn.onTouchEvent(singlePointerEvent(t, t, MotionEvent.ACTION_DOWN, 0, 60f, 60f))
        assertTrue(attackBtn.isInputDown)
        assertTrue(mgr.isButtonPressed("builtin_attack"))
        assertEquals(0, mgr.getPressedPointerId("builtin_attack"))
        assertEquals(true, bridge.pressedMouse[lmb])
        assertEquals(1, bridge.countMouseDown(lmb))
        assertEquals(0, bridge.countMouseUp(lmb))

        attackBtn.onTouchEvent(singlePointerEvent(t, t + 150, MotionEvent.ACTION_UP, 0, 60f, 60f))
        assertFalse(attackBtn.isInputDown)
        assertFalse(mgr.isButtonPressed("builtin_attack"))
        assertEquals(0, mgr.activeLeaseCount)
        assertEquals(false, bridge.pressedMouse[lmb])
        assertEquals(1, bridge.countMouseDown(lmb))
        assertEquals(1, bridge.countMouseUp(lmb))

        // Also verify ACTION_CANCEL releases immediately
        val t2 = t + 300
        attackBtn.onTouchEvent(singlePointerEvent(t2, t2, MotionEvent.ACTION_DOWN, 1, 60f, 60f))
        assertTrue(mgr.isButtonPressed("builtin_attack"))
        assertEquals(true, bridge.pressedMouse[lmb])
        attackBtn.onTouchEvent(singlePointerEvent(t2, t2 + 40, MotionEvent.ACTION_CANCEL, 1, 60f, 60f))
        assertFalse(attackBtn.isInputDown)
        assertFalse(mgr.isButtonPressed("builtin_attack"))
        assertEquals(false, bridge.pressedMouse[lmb])
        assertEquals(2, bridge.countMouseDown(lmb))
        assertEquals(2, bridge.countMouseUp(lmb))
    }

    @Test
    fun `scenario 2 - rapid mining taps maintain strict 1-to-1 down and up symmetry without duplicates`() {
        val attackBtn = StarHudButtonView(context, StarHudElementId.ATTACK).apply {
            layout(0, 0, 120, 120)
        }
        val lmb = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT.toInt()
        val mgr = StarHudInputManager.getInstance()
        var t = SystemClock.uptimeMillis()

        repeat(25) {
            attackBtn.onTouchEvent(singlePointerEvent(t, t, MotionEvent.ACTION_DOWN, 0, 50f, 50f))
            // Second finger hitting the same held button must NOT emit duplicate DOWN or hijack ownership
            attackBtn.onTouchEvent(
                multiPointerEvent(
                    t, t + 2, MotionEvent.ACTION_POINTER_DOWN, 1,
                    listOf(Triple(0, 50f, 50f), Triple(1, 70f, 70f))
                )
            )
            attackBtn.onTouchEvent(
                multiPointerEvent(
                    t, t + 4, MotionEvent.ACTION_POINTER_UP, 1,
                    listOf(Triple(0, 50f, 50f), Triple(1, 70f, 70f))
                )
            )
            assertTrue(attackBtn.isInputDown)
            assertEquals(true, bridge.pressedMouse[lmb])

            attackBtn.onTouchEvent(singlePointerEvent(t, t + 8, MotionEvent.ACTION_UP, 0, 50f, 50f))
            assertFalse(attackBtn.isInputDown)
            assertEquals(false, bridge.pressedMouse[lmb])
            t += 20
        }

        assertEquals(25, bridge.countMouseDown(lmb))
        assertEquals(25, bridge.countMouseUp(lmb))
        assertEquals(0, mgr.activeLeaseCount)
        assertTrue(mgr.pressedButtonIds.isEmpty())
    }

    @Test
    fun `scenario 3 - hold mining while moving joystick simultaneously works cleanly in both directions`() {
        val joystick = StarJoystickView(context).apply {
            setInputBridge(bridge)
            layout(0, 0, 300, 300)
        }
        val attackBtn = StarHudButtonView(context, StarHudElementId.ATTACK).apply {
            layout(0, 0, 120, 120)
        }
        val lmb = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT.toInt()
        val keyW = LwjglGlfwKeycode.GLFW_KEY_W.toInt()
        val keyD = LwjglGlfwKeycode.GLFW_KEY_D.toInt()
        val mgr = StarHudInputManager.getInstance()
        val t = SystemClock.uptimeMillis()

        // Pointer 0 holds mining, Pointer 1 moves joystick
        attackBtn.onTouchEvent(singlePointerEvent(t, t, MotionEvent.ACTION_DOWN, 0, 60f, 60f))
        joystick.onTouchEvent(singlePointerEvent(t, t + 5, MotionEvent.ACTION_DOWN, 1, 150f, 50f))

        assertTrue(attackBtn.isInputDown)
        assertTrue(joystick.isForwardPressed)
        assertEquals(true, bridge.pressedMouse[lmb])
        assertEquals(true, bridge.pressedKeys[keyW])
        assertTrue(mgr.pressedButtonIds.containsAll(listOf("builtin_attack", "joystick")))

        // Diagonal joystick movement keeps mining held
        joystick.onTouchEvent(singlePointerEvent(t, t + 20, MotionEvent.ACTION_MOVE, 1, 240f, 60f))
        assertTrue(joystick.isForwardPressed && joystick.isRightPressed)
        assertEquals(true, bridge.pressedKeys[keyD])
        assertEquals(1, bridge.countMouseDown(lmb))
        assertEquals(0, bridge.countMouseUp(lmb))

        // Lifting joystick finger releases WASD only; mining remains held
        joystick.onTouchEvent(singlePointerEvent(t, t + 40, MotionEvent.ACTION_UP, 1, 240f, 60f))
        assertFalse(joystick.isForwardPressed || joystick.isRightPressed)
        assertEquals(false, bridge.pressedKeys[keyW])
        assertEquals(false, bridge.pressedKeys[keyD])
        assertTrue(attackBtn.isInputDown)
        assertEquals(true, bridge.pressedMouse[lmb])

        // Releasing mining finger stops mining
        attackBtn.onTouchEvent(singlePointerEvent(t, t + 60, MotionEvent.ACTION_UP, 0, 60f, 60f))
        assertFalse(attackBtn.isInputDown)
        assertEquals(false, bridge.pressedMouse[lmb])
        assertEquals(0, mgr.activeLeaseCount)
    }

    @Test
    fun `scenario 4 - press mining and jump simultaneously and release independently`() {
        val attackBtn = StarHudButtonView(context, StarHudElementId.ATTACK).apply {
            layout(0, 0, 120, 120)
        }
        val jumpBtn = StarHudButtonView(context, StarHudElementId.JUMP).apply {
            layout(0, 0, 120, 120)
        }
        val lmb = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT.toInt()
        val space = LwjglGlfwKeycode.GLFW_KEY_SPACE.toInt()
        val mgr = StarHudInputManager.getInstance()
        val t = SystemClock.uptimeMillis()

        attackBtn.onTouchEvent(singlePointerEvent(t, t, MotionEvent.ACTION_DOWN, 0, 60f, 60f))
        jumpBtn.onTouchEvent(singlePointerEvent(t, t + 10, MotionEvent.ACTION_POINTER_DOWN, 1, 60f, 60f))

        assertEquals(0, attackBtn.activePointerId)
        assertEquals(1, jumpBtn.activePointerId)
        assertEquals(true, bridge.pressedMouse[lmb])
        assertEquals(true, bridge.pressedKeys[space])

        // Pointer 1 lifts from jump -> jump stops, mining continues
        jumpBtn.onTouchEvent(singlePointerEvent(t, t + 30, MotionEvent.ACTION_POINTER_UP, 1, 60f, 60f))
        assertFalse(jumpBtn.isInputDown)
        assertEquals(false, bridge.pressedKeys[space])
        assertTrue(attackBtn.isInputDown)
        assertEquals(true, bridge.pressedMouse[lmb])
        assertEquals(1, mgr.activeLeaseCount)

        // Releasing pointer 0 lifts mining
        attackBtn.onTouchEvent(singlePointerEvent(t, t + 60, MotionEvent.ACTION_UP, 0, 60f, 60f))
        assertFalse(attackBtn.isInputDown)
        assertEquals(false, bridge.pressedMouse[lmb])
        assertEquals(0, mgr.activeLeaseCount)
    }

    @Test
    fun `scenario 5 - moving finger outside mining button hitbox releases immediately before up`() {
        val attackBtn = StarHudButtonView(context, StarHudElementId.ATTACK).apply {
            layout(0, 0, 120, 120)
        }
        val lmb = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT.toInt()
        val mgr = StarHudInputManager.getInstance()
        val t = SystemClock.uptimeMillis()

        attackBtn.onTouchEvent(singlePointerEvent(t, t, MotionEvent.ACTION_DOWN, 0, 60f, 60f))
        assertTrue(attackBtn.isInputDown)
        assertEquals(true, bridge.pressedMouse[lmb])

        // Inside edge still holds
        attackBtn.onTouchEvent(singlePointerEvent(t, t + 10, MotionEvent.ACTION_MOVE, 0, 115f, 60f))
        assertTrue(attackBtn.isInputDown)
        assertEquals(true, bridge.pressedMouse[lmb])

        // Dragging outside hitbox releases LMB immediately without waiting for ACTION_UP
        attackBtn.onTouchEvent(singlePointerEvent(t, t + 20, MotionEvent.ACTION_MOVE, 0, 135f, 60f))
        assertFalse(attackBtn.isInputDown)
        assertFalse(mgr.isButtonPressed("builtin_attack"))
        assertEquals(false, bridge.pressedMouse[lmb])
        assertEquals(1, bridge.countMouseDown(lmb))
        assertEquals(1, bridge.countMouseUp(lmb))
        assertEquals(0, mgr.activeLeaseCount)

        // Subsequent UP outside hitbox stays clean and does not generate spurious mismatch/UP
        attackBtn.onTouchEvent(singlePointerEvent(t, t + 40, MotionEvent.ACTION_UP, 0, 145f, 60f))
        assertEquals(1, bridge.countMouseUp(lmb))
        assertEquals(0, mgr.debugSnapshot.mismatchCount)
    }

    @Test
    fun `scenario 6 - multi-button touch and out-of-order release preserves independent pointer ownership`() {
        val attackBtn = StarHudButtonView(context, StarHudElementId.ATTACK).apply { layout(0, 0, 120, 120) }
        val jumpBtn = StarHudButtonView(context, StarHudElementId.JUMP).apply { layout(0, 0, 120, 120) }
        val useBtn = StarHudButtonView(context, StarHudElementId.INTERACT).apply { layout(0, 0, 120, 120) }
        val lmb = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT.toInt()
        val rmb = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT.toInt()
        val space = LwjglGlfwKeycode.GLFW_KEY_SPACE.toInt()
        val mgr = StarHudInputManager.getInstance()
        val t = SystemClock.uptimeMillis()

        // Press ptr 0 = attack, ptr 1 = jump, ptr 2 = use
        attackBtn.onTouchEvent(singlePointerEvent(t, t, MotionEvent.ACTION_DOWN, 0, 50f, 50f))
        jumpBtn.onTouchEvent(singlePointerEvent(t, t + 5, MotionEvent.ACTION_POINTER_DOWN, 1, 50f, 50f))
        useBtn.onTouchEvent(singlePointerEvent(t, t + 10, MotionEvent.ACTION_POINTER_DOWN, 2, 50f, 50f))
        assertEquals(3, mgr.activeLeaseCount)

        // Wrong pointer event sent to attack (ptr 1 lifting) must NOT release attack
        assertFalse(attackBtn.onTouchEvent(singlePointerEvent(t, t + 15, MotionEvent.ACTION_POINTER_UP, 1, 50f, 50f)))
        assertTrue(attackBtn.isInputDown)
        assertEquals(true, bridge.pressedMouse[lmb])

        // Release Attack (ptr 0) FIRST out of order via ACTION_POINTER_UP -> Jump (ptr 1) and Use (ptr 2) stay active!
        assertTrue(attackBtn.onTouchEvent(singlePointerEvent(t, t + 20, MotionEvent.ACTION_POINTER_UP, 0, 50f, 50f)))
        assertFalse(attackBtn.isInputDown)
        assertEquals(false, bridge.pressedMouse[lmb])
        assertTrue(jumpBtn.isInputDown)
        assertTrue(useBtn.isInputDown)
        assertEquals(true, bridge.pressedKeys[space])
        assertEquals(true, bridge.pressedMouse[rmb])

        // Release Use (ptr 2) SECOND -> Jump (ptr 1) still held
        assertTrue(useBtn.onTouchEvent(singlePointerEvent(t, t + 30, MotionEvent.ACTION_POINTER_UP, 2, 50f, 50f)))
        assertFalse(useBtn.isInputDown)
        assertEquals(false, bridge.pressedMouse[rmb])
        assertTrue(jumpBtn.isInputDown)
        assertEquals(true, bridge.pressedKeys[space])

        // Release Jump (ptr 1) LAST
        assertTrue(jumpBtn.onTouchEvent(singlePointerEvent(t, t + 40, MotionEvent.ACTION_UP, 1, 50f, 50f)))
        assertFalse(jumpBtn.isInputDown)
        assertEquals(false, bridge.pressedKeys[space])
        assertEquals(0, mgr.activeLeaseCount)
        assertTrue(mgr.pressedButtonIds.isEmpty())
    }

    @Test
    fun `scenario 7 - minimize pause focus loss and reopen releases pressed buttons and recovers cleanly`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val host = FrameLayout(activity)
        activity.setContentView(host)

        val overlay = StarTouchHudOverlay(activity).apply {
            setHostContainer(host)
            setConfig(StarHudConfig.defaults())
            setHudActive(true)
        }
        host.addView(overlay, FrameLayout.LayoutParams(1280, 720))
        host.measure(
            View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY)
        )
        host.layout(0, 0, 1280, 720)
        overlay.relayoutElements()

        val attackBtn = overlay.getButtonView(StarHudElementId.ATTACK)!!
        val joystick = overlay.joystickView!!
        val lmb = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT.toInt()
        val keyW = LwjglGlfwKeycode.GLFW_KEY_W.toInt()
        val mgr = StarHudInputManager.getInstance()
        val t = SystemClock.uptimeMillis()

        // Press Attack and move dynamic/fixed joystick forward while in-game
        attackBtn.onTouchEvent(singlePointerEvent(t, t, MotionEvent.ACTION_DOWN, 0, attackBtn.width * 0.5f, attackBtn.height * 0.5f))
        joystick.beginDynamicSession(1, 200f, 400f, android.graphics.RectF(0f, 0f, 600f, 720f))
        joystick.moveDynamicSession(200f, 280f)
        assertTrue(attackBtn.isInputDown)
        assertTrue(joystick.isForwardPressed)
        assertEquals(true, bridge.pressedMouse[lmb])
        assertEquals(true, bridge.pressedKeys[keyW])

        // Simulate minimize / focus loss / grab lost / pause
        bridge.grabbing = false
        overlay.onWindowFocusChanged(false)
        mgr.releaseAllInputs("activity_onPause")
        overlay.onGrabState(false)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertFalse(attackBtn.isInputDown)
        assertFalse(joystick.isForwardPressed)
        assertFalse(joystick.isTracking)
        assertEquals(false, bridge.pressedMouse[lmb])
        assertEquals(false, bridge.pressedKeys[keyW])
        assertEquals(0, mgr.activeLeaseCount)
        assertTrue(mgr.pressedButtonIds.isEmpty())

        // Reopen game (focus restored + grab restored) -> next mining press & joystick touch work immediately
        bridge.grabbing = true
        overlay.onWindowFocusChanged(true)
        overlay.onGrabState(true)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val t2 = t + 500
        attackBtn.onTouchEvent(singlePointerEvent(t2, t2, MotionEvent.ACTION_DOWN, 0, attackBtn.width * 0.5f, attackBtn.height * 0.5f))
        assertTrue(attackBtn.isInputDown)
        assertEquals(true, bridge.pressedMouse[lmb])
        attackBtn.onTouchEvent(singlePointerEvent(t2, t2 + 25, MotionEvent.ACTION_UP, 0, attackBtn.width * 0.5f, attackBtn.height * 0.5f))
        assertFalse(attackBtn.isInputDown)
        assertEquals(false, bridge.pressedMouse[lmb])
    }

    @Test
    fun `scenario 8 - switching controller layouts mid-gameplay clears held inputs and binds new layout cleanly`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val host = FrameLayout(activity)
        activity.setContentView(host)

        val layoutA = StarHudConfig.defaults()
        val layoutB = layoutA
            .withElementPosition(StarHudElementId.ATTACK, 0.72f, 0.42f)
            .withElementEnabled(StarHudElementId.JUMP, false)
            .copy(layoutId = "layout_b", layoutName = "Combat B")

        val overlay = StarTouchHudOverlay(activity).apply {
            setHostContainer(host)
            setConfig(layoutA)
            setHudActive(true)
        }
        host.addView(overlay, FrameLayout.LayoutParams(1280, 720))
        host.measure(
            View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY)
        )
        host.layout(0, 0, 1280, 720)
        overlay.relayoutElements()

        val attackBtn = overlay.getButtonView(StarHudElementId.ATTACK)!!
        val jumpBtn = overlay.getButtonView(StarHudElementId.JUMP)!!
        val lmb = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT.toInt()
        val space = LwjglGlfwKeycode.GLFW_KEY_SPACE.toInt()
        val mgr = StarHudInputManager.getInstance()
        val t = SystemClock.uptimeMillis()

        attackBtn.onTouchEvent(singlePointerEvent(t, t, MotionEvent.ACTION_DOWN, 0, attackBtn.width * 0.5f, attackBtn.height * 0.5f))
        jumpBtn.onTouchEvent(singlePointerEvent(t, t + 5, MotionEvent.ACTION_POINTER_DOWN, 1, jumpBtn.width * 0.5f, jumpBtn.height * 0.5f))
        assertEquals(true, bridge.pressedMouse[lmb])
        assertEquals(true, bridge.pressedKeys[space])

        // Switch layouts during gameplay without lifting fingers first
        overlay.setConfig(layoutB)

        assertFalse(attackBtn.isInputDown)
        assertFalse(jumpBtn.isInputDown)
        assertEquals(false, bridge.pressedMouse[lmb])
        assertEquals(false, bridge.pressedKeys[space])
        assertEquals(0, mgr.activeLeaseCount)
        assertTrue(mgr.pressedButtonIds.isEmpty())

        // Verify attack button in new layout still presses & releases properly
        val t2 = t + 100
        attackBtn.onTouchEvent(singlePointerEvent(t2, t2, MotionEvent.ACTION_DOWN, 2, attackBtn.width * 0.5f, attackBtn.height * 0.5f))
        assertEquals(true, bridge.pressedMouse[lmb])
        attackBtn.onTouchEvent(singlePointerEvent(t2, t2 + 20, MotionEvent.ACTION_UP, 2, attackBtn.width * 0.5f, attackBtn.height * 0.5f))
        assertEquals(false, bridge.pressedMouse[lmb])
    }

    @Test
    fun `emergency consistency recovery clears stuck leases when grab lost and protects held RMB from right-click gesture`() {
        val mgr = StarHudInputManager.getInstance()
        val rmb = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT.toInt()
        val lmb = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT.toInt()

        // Holding Use (RMB) via HUD button
        assertTrue(mgr.acquireMouseLease("btn:use", "use", 0, "USE_ITEM", rmb))
        assertEquals(true, bridge.pressedMouse[rmb])

        // Right-click world gesture finishes -> must NOT drop held RMB
        val rcg = RightClickGesture(Handler(Looper.getMainLooper()))
        rcg.onGestureCancelled(false)
        assertEquals(true, bridge.pressedMouse[rmb])
        assertEquals(0, bridge.countMouseUp(rmb))

        // LeftClickGesture releaseIfPresent during global reset must NOT increment mismatchCount
        val lcg = LeftClickGesture(Handler(Looper.getMainLooper()))
        lcg.checkAndTrigger()
        assertEquals(true, bridge.pressedMouse[lmb])
        mgr.releaseAllInputs("reset_test")
        lcg.onGestureCancelled(true)
        assertEquals(0, mgr.debugSnapshot.mismatchCount)

        // Simulate inconsistent state: LMB lease still active while game is NOT grabbing anymore
        mgr.acquireMouseLease("btn:attack", "attack", 0, "ATTACK", lmb)
        bridge.grabbing = false
        val consistent = mgr.validateConsistencyAndRecover("tick_audit")
        assertFalse(consistent)
        assertEquals(false, bridge.pressedMouse[lmb])
        assertEquals(false, bridge.pressedMouse[rmb])
        assertEquals(0, mgr.activeLeaseCount)
        assertTrue(mgr.pressedButtonIds.isEmpty())
    }
}
