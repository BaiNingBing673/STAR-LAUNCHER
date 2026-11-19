package com.star.launcher.hud

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import net.kdt.pojavlaunch.LwjglGlfwKeycode
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.customcontrols.ControlData
import net.kdt.pojavlaunch.customcontrols.ControlLayout
import net.kdt.pojavlaunch.customcontrols.CustomControls
import net.kdt.pojavlaunch.customcontrols.buttons.ControlButton
import net.kdt.pojavlaunch.customcontrols.mouse.InGameEventProcessor
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

/**
 * Comprehensive regression suite for the critical Stuck Break / Place input fix.
 *
 * Covers Section 8 Tests A through L as well as root-cause regressions:
 * - Android ViewGroup split-touch vanished pointer reconciliation in [ControlLayout.dispatchTouchEvent]
 * - Break/Place swipe isolation and out-of-bounds release in [ControlButton]
 * - Stale pointer and orphaned lease recovery on [MotionEvent.ACTION_DOWN] in [StarHudButtonView]
 * - Persistent toggle pointer isolation in [StarHudInputManager]
 * - Diagnostic logging and post-reset state verification
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StarStuckBreakPlaceInputRegressionTest {

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
        val mgr = StarHudInputManager.getInstance()
        mgr.resetForTesting()
        mgr.setInputBridge(bridge)
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

    // =========================================================================
    // Test A: Tap and release mining quickly
    // =========================================================================
    @Test
    fun `Test A - tap and release mining quickly on both StarHudButtonView and classic ControlButton`() {
        val lmb = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT.toInt()
        val mgr = StarHudInputManager.getInstance()
        mgr.setDiagnosticLoggingEnabled(true)

        val starAttack = StarHudButtonView(context, StarHudElementId.ATTACK).apply {
            layout(0, 0, 120, 120)
        }
        var t = 1000L
        repeat(20) {
            starAttack.onTouchEvent(singlePointerEvent(t, t, MotionEvent.ACTION_DOWN, 0, 60f, 60f))
            assertTrue(mgr.isMouseButtonPressed(lmb))
            starAttack.onTouchEvent(singlePointerEvent(t, t + 4, MotionEvent.ACTION_UP, 0, 60f, 60f))
            assertFalse(mgr.isMouseButtonPressed(lmb))
            assertFalse(starAttack.isInputDown)
            t += 10L
        }
        assertEquals(20, bridge.countMouseDown(lmb))
        assertEquals(20, bridge.countMouseUp(lmb))
        assertEquals(0, mgr.activeLeaseCount)
        assertTrue(mgr.diagnosticLogs.isNotEmpty())
    }

    // =========================================================================
    // Test B: Hold mining for several seconds and release
    // =========================================================================
    @Test
    fun `Test B - hold mining for several seconds and release`() {
        val lmb = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT.toInt()
        val mgr = StarHudInputManager.getInstance()
        val attackBtn = StarHudButtonView(context, StarHudElementId.ATTACK).apply {
            layout(0, 0, 120, 120)
        }
        val downTime = 2000L
        attackBtn.onTouchEvent(singlePointerEvent(downTime, downTime, MotionEvent.ACTION_DOWN, 0, 60f, 60f))
        assertTrue(mgr.isMouseButtonPressed(lmb))

        // Simulate 4 seconds of hold with micro-movements at 120Hz (480 frames)
        var now = downTime
        repeat(480) { step ->
            now += 8L
            val jitterX = 60f + (step % 5) - 2f
            val jitterY = 60f + ((step / 5) % 5) - 2f
            attackBtn.onTouchEvent(singlePointerEvent(downTime, now, MotionEvent.ACTION_MOVE, 0, jitterX, jitterY))
            assertTrue(mgr.isMouseButtonPressed(lmb))
            assertEquals(1, bridge.countMouseDown(lmb))
            assertEquals(0, bridge.countMouseUp(lmb))
        }

        // Release after 4 seconds
        attackBtn.onTouchEvent(singlePointerEvent(downTime, now + 8L, MotionEvent.ACTION_UP, 0, 60f, 60f))
        assertFalse(mgr.isMouseButtonPressed(lmb))
        assertFalse(attackBtn.isInputDown)
        assertEquals(1, bridge.countMouseDown(lmb))
        assertEquals(1, bridge.countMouseUp(lmb))
        assertEquals(0, mgr.activeLeaseCount)
    }

    // =========================================================================
    // Test C: Hold mining while rotating the camera
    // =========================================================================
    @Test
    fun `Test C - hold mining while rotating the camera simultaneously`() {
        val lmb = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT.toInt()
        val mgr = StarHudInputManager.getInstance()
        val attackBtn = StarHudButtonView(context, StarHudElementId.ATTACK).apply {
            layout(0, 0, 120, 120)
        }
        var totalCameraDx = 0f
        var totalCameraDy = 0f
        val cameraProcessor = InGameEventProcessor(1.0, object : InGameEventProcessor.CameraCursorSink {
            override fun onCameraDelta(deltaX: Float, deltaY: Float) {
                totalCameraDx += deltaX
                totalCameraDy += deltaY
            }
            override fun onCameraReset() = Unit
        })

        val t = 5000L
        // Pointer 0 holds mining
        attackBtn.onTouchEvent(singlePointerEvent(t, t, MotionEvent.ACTION_DOWN, 0, 60f, 60f))
        assertTrue(mgr.isMouseButtonPressed(lmb))

        // Pointer 1 touches world canvas and rotates camera across 30 move events
        cameraProcessor.processTouchEvent(
            multiPointerEvent(t, t + 5, MotionEvent.ACTION_POINTER_DOWN, 1, listOf(Triple(0, 60f, 60f), Triple(1, 500f, 300f)))
        )
        for (i in 1..30) {
            val moveEv = multiPointerEvent(
                t, t + 5 + i * 8L, MotionEvent.ACTION_MOVE, 0,
                listOf(Triple(0, 60f, 60f), Triple(1, 500f + i * 6f, 300f - i * 3f))
            )
            attackBtn.onTouchEvent(moveEv)
            cameraProcessor.processTouchEvent(moveEv)
        }
        assertTrue("Camera must have rotated while mining was held", totalCameraDx > 50f && totalCameraDy < -20f)
        assertTrue("Mining must remain active while camera rotates", mgr.isMouseButtonPressed(lmb))
        assertEquals(1, bridge.countMouseDown(lmb))
        assertEquals(0, bridge.countMouseUp(lmb))

        // Release mining (pointer 0) while camera (pointer 1) is still held
        attackBtn.onTouchEvent(
            multiPointerEvent(t, t + 300, MotionEvent.ACTION_POINTER_UP, 0, listOf(Triple(0, 60f, 60f), Triple(1, 680f, 210f)))
        )
        assertFalse("Mining must stop immediately when mining finger lifts", mgr.isMouseButtonPressed(lmb))
        assertEquals(1, bridge.countMouseUp(lmb))
        assertEquals(1, cameraProcessor.trackedCameraPointerId)

        // Release camera pointer 1
        cameraProcessor.processTouchEvent(singlePointerEvent(t, t + 320, MotionEvent.ACTION_UP, 1, 680f, 210f))
        assertEquals(MotionEvent.INVALID_POINTER_ID, cameraProcessor.trackedCameraPointerId)
    }

    // =========================================================================
    // Test D: Hold placement and release normally
    // =========================================================================
    @Test
    fun `Test D - hold placement and release normally without activating break`() {
        val lmb = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT.toInt()
        val rmb = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT.toInt()
        val mgr = StarHudInputManager.getInstance()
        val placeBtn = StarHudButtonView(context, StarHudElementId.INTERACT).apply {
            layout(0, 0, 120, 120)
        }

        val t = 8000L
        placeBtn.onTouchEvent(singlePointerEvent(t, t, MotionEvent.ACTION_DOWN, 2, 60f, 60f))
        assertTrue(mgr.isMouseButtonPressed(rmb))
        assertFalse(mgr.isMouseButtonPressed(lmb))
        assertEquals(setOf(2), mgr.getMouseButtonOwnerPointers(rmb))

        placeBtn.onTouchEvent(singlePointerEvent(t, t + 250, MotionEvent.ACTION_MOVE, 2, 65f, 55f))
        assertTrue(mgr.isMouseButtonPressed(rmb))

        placeBtn.onTouchEvent(singlePointerEvent(t, t + 500, MotionEvent.ACTION_UP, 2, 65f, 55f))
        assertFalse(mgr.isMouseButtonPressed(rmb))
        assertFalse(mgr.isMouseButtonPressed(lmb))
        assertEquals(1, bridge.countMouseDown(rmb))
        assertEquals(1, bridge.countMouseUp(rmb))
        assertEquals(0, bridge.countMouseDown(lmb))
        assertEquals(0, mgr.activeLeaseCount)
    }

    // =========================================================================
    // Test E: Rapidly alternate mining and placement
    // =========================================================================
    @Test
    fun `Test E - rapidly alternate mining and placement without cross-activation or stuck state`() {
        val lmb = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT.toInt()
        val rmb = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT.toInt()
        val mgr = StarHudInputManager.getInstance()
        val attackBtn = StarHudButtonView(context, StarHudElementId.ATTACK).apply { layout(0, 0, 120, 120) }
        val placeBtn = StarHudButtonView(context, StarHudElementId.INTERACT).apply { layout(0, 0, 120, 120) }

        var t = 10_000L
        repeat(40) { i ->
            val pidA = i % 3
            val pidB = (i + 1) % 3
            // Press mining
            attackBtn.onTouchEvent(singlePointerEvent(t, t, MotionEvent.ACTION_DOWN, pidA, 50f, 50f))
            assertTrue(mgr.isMouseButtonPressed(lmb))
            assertFalse(mgr.isMouseButtonPressed(rmb))

            // Overlap placement press before mining releases on even iterations
            if (i % 2 == 0) {
                placeBtn.onTouchEvent(singlePointerEvent(t, t + 2, MotionEvent.ACTION_DOWN, pidB, 50f, 50f))
                assertTrue(mgr.isMouseButtonPressed(lmb))
                assertTrue(mgr.isMouseButtonPressed(rmb))

                // Release mining -> placement must remain active
                attackBtn.onTouchEvent(singlePointerEvent(t, t + 4, MotionEvent.ACTION_UP, pidA, 50f, 50f))
                assertFalse(mgr.isMouseButtonPressed(lmb))
                assertTrue(mgr.isMouseButtonPressed(rmb))

                // Release placement
                placeBtn.onTouchEvent(singlePointerEvent(t, t + 6, MotionEvent.ACTION_UP, pidB, 50f, 50f))
                assertFalse(mgr.isMouseButtonPressed(rmb))
            } else {
                attackBtn.onTouchEvent(singlePointerEvent(t, t + 3, MotionEvent.ACTION_UP, pidA, 50f, 50f))
                assertFalse(mgr.isMouseButtonPressed(lmb))

                placeBtn.onTouchEvent(singlePointerEvent(t, t + 4, MotionEvent.ACTION_DOWN, pidB, 50f, 50f))
                assertTrue(mgr.isMouseButtonPressed(rmb))
                assertFalse(mgr.isMouseButtonPressed(lmb))

                placeBtn.onTouchEvent(singlePointerEvent(t, t + 7, MotionEvent.ACTION_UP, pidB, 50f, 50f))
                assertFalse(mgr.isMouseButtonPressed(rmb))
            }
            t += 12L
        }

        assertEquals(40, bridge.countMouseDown(lmb))
        assertEquals(40, bridge.countMouseUp(lmb))
        assertEquals(40, bridge.countMouseDown(rmb))
        assertEquals(40, bridge.countMouseUp(rmb))
        assertFalse(mgr.isMouseButtonPressed(lmb))
        assertFalse(mgr.isMouseButtonPressed(rmb))
        assertEquals(0, mgr.activeLeaseCount)
    }

    // =========================================================================
    // Test F: Hold mining, then move the finger outside the button
    // (Tests both StarHudButtonView AND classic ControlButton with adjacent Place button)
    // =========================================================================
    @Test
    fun `Test F - hold mining then move finger outside button releases mining and never swipe-activates adjacent placement`() {
        val lmb = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT.toInt()
        val rmb = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT.toInt()
        val mgr = StarHudInputManager.getInstance()

        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val controlLayout = ControlLayout(activity)
        activity.setContentView(controlLayout)
        controlLayout.loadLayout(CustomControls())

        // Create adjacent swipeable Left Click (Break) and Right Click (Place) buttons matching default.json
        val breakData = ControlData("Left Click", intArrayOf(ControlData.SPECIALBTN_MOUSEPRI), "0", "0", 100f, 100f, false).apply {
            isSwipeable = true
        }
        val placeData = ControlData("Right Click", intArrayOf(ControlData.SPECIALBTN_MOUSESEC), "105", "0", 100f, 100f, false).apply {
            isSwipeable = true
        }
        // Also test a non-swipeable mining button
        val nonSwipeBreakData = ControlData("Mine Fixed", intArrayOf(ControlData.SPECIALBTN_MOUSEPRI), "0", "120", 100f, 100f, false).apply {
            isSwipeable = false
        }

        val breakBtn = ControlButton(controlLayout, breakData)
        val placeBtn = ControlButton(controlLayout, placeData)
        val nonSwipeBreakBtn = ControlButton(controlLayout, nonSwipeBreakData)
        controlLayout.addView(breakBtn)
        controlLayout.addView(placeBtn)
        controlLayout.addView(nonSwipeBreakBtn)
        breakBtn.x = 0f
        breakBtn.y = 0f
        breakBtn.layout(0, 0, 100, 100)
        placeBtn.x = 105f
        placeBtn.y = 0f
        placeBtn.layout(105, 0, 205, 100)
        nonSwipeBreakBtn.x = 0f
        nonSwipeBreakBtn.y = 120f
        nonSwipeBreakBtn.layout(0, 120, 100, 220)

        val t = 15_000L
        // 1. Press swipeable Left Click (Mining) and drag finger outside directly over Right Click (Place)
        breakBtn.onTouchEvent(singlePointerEvent(t, t, MotionEvent.ACTION_DOWN, 0, 50f, 50f))
        assertTrue("Mining must be active on press", mgr.isMouseButtonPressed(lmb))

        // Drag finger to x = 150 (outside breakBtn and inside placeBtn!)
        breakBtn.onTouchEvent(singlePointerEvent(t, t + 16, MotionEvent.ACTION_MOVE, 0, 150f, 50f))
        assertFalse("Mining must stop immediately when finger leaves button bounds", mgr.isMouseButtonPressed(lmb))
        assertFalse("Sliding out of mining must NEVER accidentally activate placement", mgr.isMouseButtonPressed(rmb))
        assertEquals(0, bridge.countMouseDown(rmb))

        // Lift finger outside
        breakBtn.onTouchEvent(singlePointerEvent(t, t + 32, MotionEvent.ACTION_UP, 0, 150f, 50f))
        assertFalse(mgr.isMouseButtonPressed(lmb))
        assertFalse(mgr.isMouseButtonPressed(rmb))

        // 2. Press non-swipeable mining button and move finger outside -> must ALSO release immediately!
        val t2 = t + 100L
        nonSwipeBreakBtn.onTouchEvent(singlePointerEvent(t2, t2, MotionEvent.ACTION_DOWN, 0, 50f, 50f))
        assertTrue(mgr.isMouseButtonPressed(lmb))
        nonSwipeBreakBtn.onTouchEvent(singlePointerEvent(t2, t2 + 16, MotionEvent.ACTION_MOVE, 0, -20f, 50f))
        assertFalse("Non-swipeable ControlButton must also release when finger leaves button", mgr.isMouseButtonPressed(lmb))
        nonSwipeBreakBtn.onTouchEvent(singlePointerEvent(t2, t2 + 32, MotionEvent.ACTION_UP, 0, -20f, 50f))
        assertFalse(mgr.isMouseButtonPressed(lmb))
        assertEquals(0, mgr.activeLeaseCount)
    }

    // =========================================================================
    // Test G: Hold mining and trigger Android ACTION_CANCEL
    // =========================================================================
    @Test
    fun `Test G - hold mining and trigger ACTION_CANCEL on both hold and toggle ControlButton and StarHudButtonView`() {
        val lmb = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT.toInt()
        val mgr = StarHudInputManager.getInstance()

        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val controlLayout = ControlLayout(activity)
        controlLayout.loadLayout(CustomControls())

        val holdBreakBtn = ControlButton(
            controlLayout,
            ControlData("Mine Hold", intArrayOf(ControlData.SPECIALBTN_MOUSEPRI), "0", "0", 100f, 100f, false)
        )
        val toggleBreakBtn = ControlButton(
            controlLayout,
            ControlData("Mine Toggle", intArrayOf(ControlData.SPECIALBTN_MOUSEPRI), "0", "0", 100f, 100f, true)
        )
        controlLayout.addView(holdBreakBtn)
        controlLayout.addView(toggleBreakBtn)
        holdBreakBtn.layout(0, 0, 100, 100)
        toggleBreakBtn.layout(0, 0, 100, 100)

        val t = 20_000L
        holdBreakBtn.onTouchEvent(singlePointerEvent(t, t, MotionEvent.ACTION_DOWN, 0, 50f, 50f))
        assertTrue(mgr.isMouseButtonPressed(lmb))
        holdBreakBtn.onTouchEvent(singlePointerEvent(t, t + 20, MotionEvent.ACTION_CANCEL, 0, 50f, 50f))
        assertFalse("ACTION_CANCEL must immediately release held ControlButton mining", mgr.isMouseButtonPressed(lmb))

        // ACTION_CANCEL on a toggle ControlButton must NOT toggle it on!
        toggleBreakBtn.onTouchEvent(singlePointerEvent(t + 50, t + 50, MotionEvent.ACTION_DOWN, 0, 50f, 50f))
        toggleBreakBtn.onTouchEvent(singlePointerEvent(t + 50, t + 70, MotionEvent.ACTION_CANCEL, 0, 50f, 50f))
        assertFalse("ACTION_CANCEL on toggle ControlButton must not latch toggle on", mgr.isMouseButtonPressed(lmb))
    }

    // =========================================================================
    // Test H: Multiple fingers on movement, camera, and mining controls
    // + Split-touch vanished pointer reconciliation (Nubia Neo / OPPO root cause)
    // =========================================================================
    @Test
    fun `Test H - multitouch movement camera and mining plus OEM vanished pointer reconciliation`() {
        val lmb = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT.toInt()
        val keyW = LwjglGlfwKeycode.GLFW_KEY_W.toInt()
        val mgr = StarHudInputManager.getInstance()

        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val controlLayout = ControlLayout(activity)
        activity.setContentView(controlLayout)
        controlLayout.loadLayout(CustomControls())

        val joystick = StarJoystickView(activity).apply {
            setInputBridge(bridge)
        }
        val attackBtn = StarHudButtonView(activity, StarHudElementId.ATTACK)
        controlLayout.addView(joystick)
        controlLayout.addView(attackBtn)
        controlLayout.layout(0, 0, 1280, 720)
        joystick.layout(0, 400, 300, 700)
        attackBtn.layout(900, 400, 1040, 540)

        val t = 30_000L
        // Pointer 0 presses Joystick forward at (150, 440)
        controlLayout.dispatchTouchEvent(singlePointerEvent(t, t, MotionEvent.ACTION_DOWN, 0, 150f, 440f))
        assertTrue(joystick.isForwardPressed)
        assertEquals(true, bridge.pressedKeys[keyW])

        // Pointer 1 presses Mining at (960, 460)
        controlLayout.dispatchTouchEvent(
            multiPointerEvent(
                t, t + 10, MotionEvent.ACTION_POINTER_DOWN, 1,
                listOf(Triple(0, 150f, 440f), Triple(1, 960f, 460f))
            )
        )
        assertTrue(attackBtn.isInputDown)
        assertTrue(mgr.isMouseButtonPressed(lmb))
        assertTrue(joystick.isForwardPressed)

        // Simulate OEM driver/gesture hook (Nubia Neo / OPPO) dropping pointer 1's ACTION_POINTER_UP:
        // The next ACTION_MOVE arriving at ControlLayout only contains pointer 0!
        // Because Android ViewGroup split-touch never dispatches this ACTION_MOVE to attackBtn,
        // ControlLayout.dispatchTouchEvent's pointer reconciliation must catch and release pointer 1!
        controlLayout.dispatchTouchEvent(
            multiPointerEvent(
                t, t + 30, MotionEvent.ACTION_MOVE, 0,
                listOf(Triple(0, 150f, 435f))
            )
        )

        assertFalse("Vanished mining pointer 1 must be reconciled and released immediately", mgr.isMouseButtonPressed(lmb))
        assertFalse("attackBtn view state must be reset when pointer 1 vanishes", attackBtn.isInputDown)
        assertEquals(MotionEvent.INVALID_POINTER_ID, attackBtn.activePointerId)
        assertTrue("Joystick pointer 0 must remain active and unaffected", joystick.isForwardPressed)
        assertEquals(true, bridge.pressedKeys[keyW])

        // Now lift pointer 0
        controlLayout.dispatchTouchEvent(singlePointerEvent(t, t + 50, MotionEvent.ACTION_UP, 0, 150f, 435f))
        assertFalse(joystick.isForwardPressed)
        assertEquals(false, bridge.pressedKeys[keyW])
        assertEquals(0, mgr.activeLeaseCount)
    }

    // =========================================================================
    // Test I: Hold one control while releasing another (4 simultaneous controls)
    // =========================================================================
    @Test
    fun `Test I - hold movement jump mining and placement simultaneously and release independently`() {
        val lmb = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT.toInt()
        val rmb = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT.toInt()
        val keyW = LwjglGlfwKeycode.GLFW_KEY_W.toInt()
        val keySpace = LwjglGlfwKeycode.GLFW_KEY_SPACE.toInt()
        val mgr = StarHudInputManager.getInstance()

        val joystick = StarJoystickView(context).apply {
            setInputBridge(bridge)
            layout(0, 0, 300, 300)
        }
        val attackBtn = StarHudButtonView(context, StarHudElementId.ATTACK).apply { layout(0, 0, 120, 120) }
        val placeBtn = StarHudButtonView(context, StarHudElementId.INTERACT).apply { layout(0, 0, 120, 120) }
        val jumpBtn = StarHudButtonView(context, StarHudElementId.JUMP).apply { layout(0, 0, 120, 120) }

        val t = 40_000L
        joystick.onTouchEvent(singlePointerEvent(t, t, MotionEvent.ACTION_DOWN, 0, 150f, 40f))
        attackBtn.onTouchEvent(singlePointerEvent(t, t + 5, MotionEvent.ACTION_POINTER_DOWN, 1, 60f, 60f))
        placeBtn.onTouchEvent(singlePointerEvent(t, t + 10, MotionEvent.ACTION_POINTER_DOWN, 2, 60f, 60f))
        jumpBtn.onTouchEvent(singlePointerEvent(t, t + 15, MotionEvent.ACTION_POINTER_DOWN, 3, 60f, 60f))

        assertTrue(mgr.isKeyHeld(keyW))
        assertTrue(mgr.isMouseButtonPressed(lmb))
        assertTrue(mgr.isMouseButtonPressed(rmb))
        assertTrue(mgr.isKeyHeld(keySpace))

        // 1. Release Mining (pointer 1) -> Placement, Movement, and Jump must remain active
        attackBtn.onTouchEvent(singlePointerEvent(t, t + 30, MotionEvent.ACTION_POINTER_UP, 1, 60f, 60f))
        assertFalse(mgr.isMouseButtonPressed(lmb))
        assertTrue(mgr.isMouseButtonPressed(rmb))
        assertTrue(mgr.isKeyHeld(keyW))
        assertTrue(mgr.isKeyHeld(keySpace))

        // 2. Release Placement (pointer 2) -> Movement and Jump must remain active
        placeBtn.onTouchEvent(singlePointerEvent(t, t + 45, MotionEvent.ACTION_POINTER_UP, 2, 60f, 60f))
        assertFalse(mgr.isMouseButtonPressed(lmb))
        assertFalse(mgr.isMouseButtonPressed(rmb))
        assertTrue(mgr.isKeyHeld(keyW))
        assertTrue(mgr.isKeyHeld(keySpace))

        // 3. Release Jump (pointer 3) -> Movement remains active
        jumpBtn.onTouchEvent(singlePointerEvent(t, t + 60, MotionEvent.ACTION_POINTER_UP, 3, 60f, 60f))
        assertFalse(mgr.isKeyHeld(keySpace))
        assertTrue(mgr.isKeyHeld(keyW))

        // 4. Release Movement (pointer 0)
        joystick.onTouchEvent(singlePointerEvent(t, t + 75, MotionEvent.ACTION_UP, 0, 150f, 40f))
        assertFalse(mgr.isKeyHeld(keyW))
        assertEquals(0, mgr.activeLeaseCount)
    }

    // =========================================================================
    // Test J & K: Switch apps / focus loss and pause/resume while control is active
    // + Stale pointer recovery on ACTION_DOWN
    // =========================================================================
    @Test
    fun `Test J and K - app switch focus loss pause resume and stale pointer recovery on ACTION_DOWN`() {
        val lmb = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT.toInt()
        val rmb = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT.toInt()
        val mgr = StarHudInputManager.getInstance()

        val attackBtn = StarHudButtonView(context, StarHudElementId.ATTACK).apply { layout(0, 0, 120, 120) }
        val placeBtn = StarHudButtonView(context, StarHudElementId.INTERACT).apply { layout(0, 0, 120, 120) }

        val t = 50_000L
        attackBtn.onTouchEvent(singlePointerEvent(t, t, MotionEvent.ACTION_DOWN, 0, 60f, 60f))
        placeBtn.onTouchEvent(singlePointerEvent(t, t + 5, MotionEvent.ACTION_POINTER_DOWN, 1, 60f, 60f))
        assertTrue(mgr.isMouseButtonPressed(lmb))
        assertTrue(mgr.isMouseButtonPressed(rmb))

        // Test J: Window focus lost (notification shade / app switch)
        attackBtn.onWindowFocusChanged(false)
        placeBtn.onWindowFocusChanged(false)
        assertFalse(mgr.isMouseButtonPressed(lmb))
        assertFalse(mgr.isMouseButtonPressed(rmb))
        assertEquals(0, mgr.activeLeaseCount)

        // Return focus and press again, then Test K: Activity pause / global input reset
        attackBtn.onWindowFocusChanged(true)
        placeBtn.onWindowFocusChanged(true)
        attackBtn.onTouchEvent(singlePointerEvent(t + 100, t + 100, MotionEvent.ACTION_DOWN, 2, 60f, 60f))
        assertTrue(mgr.isMouseButtonPressed(lmb))

        mgr.releaseAllInputs("activity_onPause")
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertFalse(mgr.isMouseButtonPressed(lmb))
        assertFalse(attackBtn.isInputDown)
        assertEquals(0, mgr.activeLeaseCount)
        assertEquals(0, mgr.debugSnapshot.mismatchCount)

        // Also test stale pointer recovery on ACTION_DOWN:
        // If pointer 0 was held and its ACTION_UP was swallowed without focus loss,
        // the next ACTION_DOWN with pointer 1 must clean up pointer 0 and acquire pointer 1 cleanly
        // so that releasing pointer 1 releases LMB!
        attackBtn.onTouchEvent(singlePointerEvent(t + 200, t + 200, MotionEvent.ACTION_DOWN, 0, 60f, 60f))
        assertTrue(mgr.isMouseButtonPressed(lmb))
        // Next ACTION_DOWN arrives with pointerId = 1 (without prior ACTION_UP for pointer 0)
        attackBtn.onTouchEvent(singlePointerEvent(t + 300, t + 300, MotionEvent.ACTION_DOWN, 1, 60f, 60f))
        assertTrue(mgr.isMouseButtonPressed(lmb))
        assertEquals(1, attackBtn.activePointerId)
        // Releasing pointer 1 MUST release LMB (no wedged lease from pointer 0!)
        attackBtn.onTouchEvent(singlePointerEvent(t + 350, t + 350, MotionEvent.ACTION_UP, 1, 60f, 60f))
        assertFalse("LMB must release after stale-pointer recovery on ACTION_DOWN", mgr.isMouseButtonPressed(lmb))
        assertEquals(0, mgr.activeLeaseCount)

        // Verify GUI-to-gameplay grab transition releases pending putMouseEventWithCoords tap
        org.lwjgl.glfw.CallbackBridge.onGrabStateChanged(false)
        org.lwjgl.glfw.CallbackBridge.putMouseEventWithCoords(lmb, 200f, 200f)
        assertTrue(org.lwjgl.glfw.CallbackBridge.isMouseButtonDispatched(lmb))
        // Game enters grab mode (e.g. "Back to Game" / "Respawn") before 33ms Choreographer callback
        org.lwjgl.glfw.CallbackBridge.onGrabStateChanged(true)
        assertFalse(
            "GUI tap LMB press must be released when transitioning into gameplay grab",
            org.lwjgl.glfw.CallbackBridge.isMouseButtonDispatched(lmb)
        )
    }

    // =========================================================================
    // Test L: Repeat across different resolutions, densities, and refresh rates
    // =========================================================================
    @Test
    fun `Test L - multi-resolution density and refresh-rate matrix verification`() {
        val lmb = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT.toInt()
        val rmb = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT.toInt()
        val mgr = StarHudInputManager.getInstance()

        data class DeviceProfile(val name: String, val width: Int, val height: Int, val density: Float, val frameIntervalMs: Long)
        val profiles = listOf(
            DeviceProfile("720p_60Hz", 1280, 720, 2.0f, 16L),
            DeviceProfile("Nubia_Neo_1080p_120Hz", 2400, 1080, 2.75f, 8L),
            DeviceProfile("OPPO_1440p_120Hz", 3216, 1440, 3.5f, 8L),
            DeviceProfile("Ultra_240Hz_Touch", 2400, 1080, 3.0f, 4L)
        )

        var clock = 100_000L
        for (profile in profiles) {
            Tools.currentDisplayMetrics = DisplayMetrics().apply {
                widthPixels = profile.width
                heightPixels = profile.height
                density = profile.density
                densityDpi = (profile.density * 160).toInt()
            }

            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            val host = FrameLayout(activity)
            activity.setContentView(host)

            val overlay = StarTouchHudOverlay(activity).apply {
                setHostContainer(host)
                setConfig(StarHudConfig.defaults())
                setHudActive(true)
            }
            host.addView(overlay, FrameLayout.LayoutParams(profile.width, profile.height))
            host.measure(
                View.MeasureSpec.makeMeasureSpec(profile.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(profile.height, View.MeasureSpec.EXACTLY)
            )
            host.layout(0, 0, profile.width, profile.height)
            overlay.relayoutElements()

            val attackBtn = overlay.getButtonView(StarHudElementId.ATTACK)!!
            val placeBtn = overlay.getButtonView(StarHudElementId.INTERACT)!!

            // Hold mining and placement across 20 high-frequency frames, then release independently
            val ax = attackBtn.width * 0.5f
            val ay = attackBtn.height * 0.5f
            val px = placeBtn.width * 0.5f
            val py = placeBtn.height * 0.5f

            attackBtn.onTouchEvent(singlePointerEvent(clock, clock, MotionEvent.ACTION_DOWN, 0, ax, ay))
            placeBtn.onTouchEvent(singlePointerEvent(clock, clock + 1, MotionEvent.ACTION_POINTER_DOWN, 1, px, py))
            assertTrue("${profile.name}: LMB should be pressed", mgr.isMouseButtonPressed(lmb))
            assertTrue("${profile.name}: RMB should be pressed", mgr.isMouseButtonPressed(rmb))

            repeat(20) {
                clock += profile.frameIntervalMs
                attackBtn.onTouchEvent(singlePointerEvent(clock, clock, MotionEvent.ACTION_MOVE, 0, ax, ay))
                placeBtn.onTouchEvent(singlePointerEvent(clock, clock, MotionEvent.ACTION_MOVE, 1, px, py))
            }

            attackBtn.onTouchEvent(singlePointerEvent(clock, clock + 2, MotionEvent.ACTION_POINTER_UP, 0, ax, ay))
            assertFalse("${profile.name}: LMB must release immediately", mgr.isMouseButtonPressed(lmb))
            assertTrue("${profile.name}: RMB must stay pressed while LMB releases", mgr.isMouseButtonPressed(rmb))

            placeBtn.onTouchEvent(singlePointerEvent(clock, clock + 4, MotionEvent.ACTION_UP, 1, px, py))
            assertFalse("${profile.name}: RMB must release immediately", mgr.isMouseButtonPressed(rmb))
            assertEquals("${profile.name}: zero active leases must remain", 0, mgr.activeLeaseCount)
            assertTrue("${profile.name}: consistency check must pass", mgr.validateConsistencyAndRecover(profile.name))
            clock += 100L
        }

        assertEquals(profiles.size, bridge.countMouseDown(lmb))
        assertEquals(profiles.size, bridge.countMouseUp(lmb))
        assertEquals(profiles.size, bridge.countMouseDown(rmb))
        assertEquals(profiles.size, bridge.countMouseUp(rmb))
    }
}
