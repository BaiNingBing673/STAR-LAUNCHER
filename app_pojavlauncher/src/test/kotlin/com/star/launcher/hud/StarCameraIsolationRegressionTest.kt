package com.star.launcher.hud

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import net.kdt.pojavlaunch.LwjglGlfwKeycode
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.customcontrols.mouse.InGameEventProcessor
import net.kdt.pojavlaunch.customcontrols.mouse.LeftClickGesture
import net.kdt.pojavlaunch.prefs.LauncherPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StarCameraIsolationRegressionTest {

    private lateinit var context: Context
    private lateinit var bridge: RecordingBridge
    private lateinit var cursorUpdates: MutableList<Pair<Float, Float>>
    private lateinit var processor: InGameEventProcessor
    private var virtualMouseX = 0f
    private var virtualMouseY = 0f

    private val lmb = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT.toInt()
    private val rmb = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT.toInt()

    private var savedDisableGestures = false
    private var savedLongPressTrigger = 300
    private var savedCameraSensitivity = 1.0f

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
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Tools.currentDisplayMetrics = context.resources.displayMetrics
        savedDisableGestures = LauncherPreferences.PREF_DISABLE_GESTURES
        savedLongPressTrigger = LauncherPreferences.PREF_LONGPRESS_TRIGGER
        savedCameraSensitivity = LauncherPreferences.PREF_CAMERA_SENSITIVITY

        // Even with gestures enabled globally (as in legacy saved configs), camera zone must stay isolated.
        LauncherPreferences.PREF_DISABLE_GESTURES = false
        LauncherPreferences.PREF_LONGPRESS_TRIGGER = 300
        LauncherPreferences.PREF_CAMERA_SENSITIVITY = 1.0f
        virtualMouseX = 0f
        virtualMouseY = 0f

        bridge = RecordingBridge()
        cursorUpdates = mutableListOf()
        StarHudInputManager.getInstance().resetForTesting()
        StarHudInputManager.getInstance().setInputBridge(bridge)

        processor = InGameEventProcessor(1.0, object : InGameEventProcessor.CameraCursorSink {
            override fun onCameraDelta(deltaX: Float, deltaY: Float) {
                virtualMouseX += deltaX
                virtualMouseY += deltaY
                cursorUpdates.add(virtualMouseX to virtualMouseY)
            }

            override fun onCameraReset() = Unit
        })
        StarHudInputManager.getInstance().registerResetParticipant(processor)
    }

    @After
    fun tearDown() {
        StarHudInputManager.getInstance().unregisterResetParticipant(processor)
        StarHudInputManager.getInstance().resetForTesting()
        LauncherPreferences.PREF_DISABLE_GESTURES = savedDisableGestures
        LauncherPreferences.PREF_LONGPRESS_TRIGGER = savedLongPressTrigger
        LauncherPreferences.PREF_CAMERA_SENSITIVITY = savedCameraSensitivity
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
        val fullAction = if (actionMasked == MotionEvent.ACTION_POINTER_DOWN || actionMasked == MotionEvent.ACTION_POINTER_UP) {
            actionMasked or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        } else {
            actionMasked
        }
        return MotionEvent.obtain(downTime, eventTime, fullAction, pointers.size, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
    }

    private fun makeBuiltinButton(elementId: StarHudElementId): StarHudButtonView {
        return StarHudButtonView(context, elementId).apply {
            layout(0, 0, 140, 140)
        }
    }

    private fun runContinuousCameraDrag(durationMs: Long, stepMs: Long = 50L, sensitivity: Float = 1.0f) {
        LauncherPreferences.PREF_CAMERA_SENSITIVITY = sensitivity
        val downTime = SystemClock.uptimeMillis()
        var currentX = 300f
        var currentY = 300f

        processor.processTouchEvent(singlePointerEvent(downTime, downTime, MotionEvent.ACTION_DOWN, 0, currentX, currentY))

        var elapsed = stepMs
        while (elapsed <= durationMs) {
            // Mix slow starts (<9dp during first 300ms), steady pans, and reversals.
            val dx = if (elapsed <= 350L) 0.5f else (6.0 * sin(elapsed / 400.0)).toFloat() + 3.0f
            val dy = if (elapsed <= 350L) 0.25f else 1.5f
            currentX += dx
            currentY += dy
            processor.processTouchEvent(singlePointerEvent(downTime, downTime + elapsed, MotionEvent.ACTION_MOVE, 0, currentX, currentY))
            ShadowLooper.idleMainLooper(stepMs, TimeUnit.MILLISECONDS)
            elapsed += stepMs
        }

        processor.processTouchEvent(singlePointerEvent(downTime, downTime + durationMs + stepMs, MotionEvent.ACTION_UP, 0, currentX, currentY))
        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS)
    }

    @Test
    fun scenario1_continuousCameraDrag5Seconds_neverTriggersMouseButtons() {
        runContinuousCameraDrag(durationMs = 5_000L, stepMs = 50L, sensitivity = 0.35f)

        val mgr = StarHudInputManager.getInstance()
        assertTrue("Camera drag must produce cursor movement updates", cursorUpdates.isNotEmpty())
        assertEquals("5s camera drag must never send any mouse events", 0, bridge.mouseEvents.size)
        assertEquals(0, bridge.keyEvents.size)
        assertEquals(0, mgr.activeLeaseCount)
        assertEquals(0, mgr.debugSnapshot.mismatchCount)
    }

    @Test
    fun scenario2_continuousCameraDrag15Seconds_neverTriggersMouseButtons() {
        runContinuousCameraDrag(durationMs = 15_000L, stepMs = 100L, sensitivity = 0.5f)

        val mgr = StarHudInputManager.getInstance()
        assertTrue("15s camera drag must produce cursor movement updates", cursorUpdates.size >= 100)
        assertEquals("15s camera drag must never send any mouse events", 0, bridge.mouseEvents.size)
        assertEquals(0, bridge.keyEvents.size)
        assertEquals(0, mgr.activeLeaseCount)
        assertEquals(0, mgr.debugSnapshot.mismatchCount)
    }

    @Test
    fun scenario3_continuousCameraDrag30Seconds_neverTriggersMouseButtons() {
        runContinuousCameraDrag(durationMs = 30_000L, stepMs = 150L, sensitivity = 0.25f)

        val mgr = StarHudInputManager.getInstance()
        assertTrue("30s camera drag must produce cursor movement updates", cursorUpdates.size >= 150)
        assertEquals("30s camera drag must never send any mouse events", 0, bridge.mouseEvents.size)
        assertEquals(0, bridge.keyEvents.size)
        assertEquals(0, mgr.activeLeaseCount)
        assertEquals(0, mgr.debugSnapshot.mismatchCount)
    }

    @Test
    fun scenario4_rapidCameraSwipesAndOscillation_neverTriggersMouseButtons() {
        val base = SystemClock.uptimeMillis()
        for (swipe in 0 until 25) {
            val downTime = base + swipe * 400L
            val startX = 400f
            val startY = 300f
            processor.processTouchEvent(singlePointerEvent(downTime, downTime, MotionEvent.ACTION_DOWN, 0, startX, startY))
            // Swipe out rapidly and oscillate back near the origin right around 300ms.
            processor.processTouchEvent(singlePointerEvent(downTime, downTime + 80L, MotionEvent.ACTION_MOVE, 0, startX + 260f, startY - 90f))
            processor.processTouchEvent(singlePointerEvent(downTime, downTime + 180L, MotionEvent.ACTION_MOVE, 0, startX - 180f, startY + 75f))
            processor.processTouchEvent(singlePointerEvent(downTime, downTime + 300L, MotionEvent.ACTION_MOVE, 0, startX + 2f, startY - 1f))
            ShadowLooper.idleMainLooper(320, TimeUnit.MILLISECONDS)
            processor.processTouchEvent(singlePointerEvent(downTime, downTime + 340L, MotionEvent.ACTION_UP, 0, startX + 2f, startY - 1f))
            ShadowLooper.idleMainLooper(80, TimeUnit.MILLISECONDS)
        }

        val mgr = StarHudInputManager.getInstance()
        assertTrue("Rapid swipes must produce cursor updates", cursorUpdates.size >= 75)
        assertEquals("Rapid swipes must never click LMB or RMB", 0, bridge.mouseEvents.size)
        assertEquals(0, bridge.keyEvents.size)
        assertEquals(0, mgr.debugSnapshot.mismatchCount)
    }

    @Test
    fun scenario5_rotateCameraWhileHoldingMineAttackAndPlaceButtons_strictPointerOwnership() {
        val mineBtn = makeBuiltinButton(StarHudElementId.ATTACK)
        val placeBtn = makeBuiltinButton(StarHudElementId.INTERACT)
        val mgr = StarHudInputManager.getInstance()
        val now = SystemClock.uptimeMillis()

        // Pointer 1 presses MINE (ATTACK) button -> exact 1 LMB DOWN.
        assertTrue(mineBtn.onTouchEvent(singlePointerEvent(now, now, MotionEvent.ACTION_DOWN, 1, 70f, 70f)))
        assertEquals(1, bridge.countMouseDown(lmb))
        assertEquals(0, bridge.countMouseUp(lmb))

        // Pointer 2 claims camera zone and drags for 3 seconds while MINE is held.
        processor.processTouchEvent(singlePointerEvent(now + 20, now + 20, MotionEvent.ACTION_DOWN, 2, 500f, 400f))
        assertEquals(2, processor.trackedCameraPointerId)
        for (i in 1..60) {
            processor.processTouchEvent(singlePointerEvent(now + 20, now + 20 + i * 50L, MotionEvent.ACTION_MOVE, 2, 500f + i * 5f, 400f - i * 2f))
            ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
        }

        // Camera drag must NOT duplicate LMB DOWN or release MINE button's LMB lease.
        assertEquals(1, bridge.countMouseDown(lmb))
        assertEquals(0, bridge.countMouseUp(lmb))
        assertTrue(bridge.pressedMouse[lmb] == true)

        // Pointer 3 presses PLACE (INTERACT) button -> exact 1 RMB DOWN.
        assertTrue(placeBtn.onTouchEvent(singlePointerEvent(now + 3100, now + 3100, MotionEvent.ACTION_DOWN, 3, 70f, 70f)))
        assertEquals(1, bridge.countMouseDown(rmb))
        assertTrue(bridge.pressedMouse[rmb] == true)

        // Camera pointer 2 lifts -> camera stops, BOTH MINE & PLACE remain held with zero spurious UP/DOWN.
        processor.processTouchEvent(singlePointerEvent(now + 20, now + 3200, MotionEvent.ACTION_UP, 2, 800f, 280f))
        assertEquals(MotionEvent.INVALID_POINTER_ID, processor.trackedCameraPointerId)
        assertEquals(0, bridge.countMouseUp(lmb))
        assertEquals(0, bridge.countMouseUp(rmb))

        // Lifting MINE pointer 1 releases ONLY LMB; PLACE remains held.
        assertTrue(mineBtn.onTouchEvent(singlePointerEvent(now, now + 3300, MotionEvent.ACTION_UP, 1, 70f, 70f)))
        assertEquals(1, bridge.countMouseUp(lmb))
        assertEquals(0, bridge.countMouseUp(rmb))
        assertFalse(mineBtn.isInputDown)
        assertTrue(placeBtn.isInputDown)

        // Lifting PLACE pointer 3 releases RMB.
        assertTrue(placeBtn.onTouchEvent(singlePointerEvent(now + 3100, now + 3400, MotionEvent.ACTION_UP, 3, 70f, 70f)))
        assertEquals(1, bridge.countMouseUp(rmb))
        assertFalse(placeBtn.isInputDown)
        assertEquals(0, mgr.activeLeaseCount)
        assertEquals(0, mgr.debugSnapshot.mismatchCount)
    }

    @Test
    fun scenario6_simultaneousFingerReleaseAndCancel_cleanZeroStuckInputs() {
        val mineBtn = makeBuiltinButton(StarHudElementId.ATTACK)
        val placeBtn = makeBuiltinButton(StarHudElementId.INTERACT)
        val mgr = StarHudInputManager.getInstance()
        val now = SystemClock.uptimeMillis()

        assertTrue(mineBtn.onTouchEvent(singlePointerEvent(now, now, MotionEvent.ACTION_DOWN, 1, 70f, 70f)))
        assertTrue(placeBtn.onTouchEvent(singlePointerEvent(now + 10, now + 10, MotionEvent.ACTION_DOWN, 2, 70f, 70f)))
        processor.processTouchEvent(singlePointerEvent(now + 20, now + 20, MotionEvent.ACTION_DOWN, 0, 500f, 400f))
        processor.processTouchEvent(singlePointerEvent(now + 20, now + 500, MotionEvent.ACTION_MOVE, 0, 560f, 430f))
        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS)

        // Release all pointers simultaneously (UP + CANCEL).
        processor.processTouchEvent(singlePointerEvent(now + 20, now + 520, MotionEvent.ACTION_UP, 0, 560f, 430f))
        mineBtn.onTouchEvent(singlePointerEvent(now, now + 520, MotionEvent.ACTION_UP, 1, 70f, 70f))
        placeBtn.onTouchEvent(singlePointerEvent(now + 10, now + 520, MotionEvent.ACTION_CANCEL, 2, 70f, 70f))

        assertEquals(MotionEvent.INVALID_POINTER_ID, processor.trackedCameraPointerId)
        assertEquals(1, bridge.countMouseDown(lmb))
        assertEquals(1, bridge.countMouseUp(lmb))
        assertEquals(1, bridge.countMouseDown(rmb))
        assertEquals(1, bridge.countMouseUp(rmb))
        assertEquals(0, mgr.activeLeaseCount)
        assertEquals(0, mgr.debugSnapshot.mismatchCount)
    }

    @Test
    fun scenario7_focusSwitchDuringCameraGesture_cancelsAndPreventsPhantomMoveOrClick() {
        val mineBtn = makeBuiltinButton(StarHudElementId.ATTACK)
        val mgr = StarHudInputManager.getInstance()
        val now = SystemClock.uptimeMillis()

        // Start holding MINE and dragging camera.
        assertTrue(mineBtn.onTouchEvent(singlePointerEvent(now, now, MotionEvent.ACTION_DOWN, 1, 70f, 70f)))
        processor.processTouchEvent(singlePointerEvent(now, now + 10, MotionEvent.ACTION_DOWN, 0, 300f, 300f))
        processor.processTouchEvent(singlePointerEvent(now, now + 320, MotionEvent.ACTION_MOVE, 0, 310f, 305f))
        val updatesBeforeFocusLoss = cursorUpdates.size

        // Simulate window focus loss / pause emergency cleanup.
        mgr.releaseAllInputs("window_focus_lost")
        ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)

        assertEquals(MotionEvent.INVALID_POINTER_ID, processor.trackedCameraPointerId)
        assertEquals(1, bridge.countMouseUp(lmb))
        assertEquals(0, mgr.activeLeaseCount)

        // Stray ACTION_MOVE from the cancelled gesture must be ignored (no cursor jump, no mouse down).
        processor.processTouchEvent(singlePointerEvent(now, now + 450, MotionEvent.ACTION_MOVE, 0, 700f, 700f))
        assertEquals("Cancelled camera pointer must ignore stray ACTION_MOVE until fresh ACTION_DOWN", updatesBeforeFocusLoss, cursorUpdates.size)
        assertEquals(1, bridge.countMouseDown(lmb))

        // Fresh camera touch after focus returns works normally with zero clicks.
        processor.processTouchEvent(singlePointerEvent(now + 600, now + 600, MotionEvent.ACTION_DOWN, 0, 400f, 400f))
        processor.processTouchEvent(singlePointerEvent(now + 600, now + 650, MotionEvent.ACTION_MOVE, 0, 440f, 420f))
        assertEquals(updatesBeforeFocusLoss + 1, cursorUpdates.size)
        assertEquals(1, bridge.countMouseDown(lmb))
        assertEquals(0, mgr.debugSnapshot.mismatchCount)
    }

    @Test
    fun strictCameraPointerOwnership_secondPointerDoesNotHijackOrTransferOnLift() {
        val now = SystemClock.uptimeMillis()

        // Pointer 10 starts owning camera.
        processor.processTouchEvent(singlePointerEvent(now, now, MotionEvent.ACTION_DOWN, 10, 200f, 200f))
        assertEquals(10, processor.trackedCameraPointerId)

        // Pointer 20 lands simultaneously on camera zone -> must NOT steal ownership.
        processor.processTouchEvent(
            multiPointerEvent(
                now, now + 20, MotionEvent.ACTION_POINTER_DOWN, 1,
                listOf(Triple(10, 200f, 200f), Triple(20, 600f, 600f))
            )
        )
        assertEquals(10, processor.trackedCameraPointerId)

        // Only pointer 20 moves -> camera cursor delta stays 0!
        processor.processTouchEvent(
            multiPointerEvent(
                now, now + 40, MotionEvent.ACTION_MOVE, 0,
                listOf(Triple(10, 200f, 200f), Triple(20, 800f, 800f))
            )
        )
        assertEquals(0f, virtualMouseX, 0.01f)
        assertEquals(0f, virtualMouseY, 0.01f)

        // Pointer 10 lifts (ACTION_POINTER_UP) -> ownership releases cleanly instead of transferring to pointer 20.
        processor.processTouchEvent(
            multiPointerEvent(
                now, now + 60, MotionEvent.ACTION_POINTER_UP, 0,
                listOf(Triple(10, 200f, 200f), Triple(20, 800f, 800f))
            )
        )
        assertEquals(MotionEvent.INVALID_POINTER_ID, processor.trackedCameraPointerId)

        // Subsequent move from remaining pointer 20 cannot steal camera or jump cursor.
        processor.processTouchEvent(singlePointerEvent(now, now + 80, MotionEvent.ACTION_MOVE, 20, 900f, 900f))
        assertEquals(0f, virtualMouseX, 0.01f)
        assertEquals(0, bridge.mouseEvents.size)
    }

    @Test
    fun leftClickGestureHardening_oscillatingOrSlowDragCancelsLongPressTrigger() {
        val lcg = LeftClickGesture(Handler(Looper.getMainLooper()))
        lcg.inputEventAt(0f, 0f)
        // Move past FINGER_STILL_THRESHOLD then return near start point so net displacement is ~0.
        val excursion = (LeftClickGesture.FINGER_STILL_THRESHOLD + 8).toFloat()
        lcg.setMotion(excursion, 0f)
        lcg.setMotion(-excursion, 0f)

        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS)
        assertEquals(
            "Oscillating drag returning near start must never acquire LMB",
            0,
            bridge.countMouseDown(lmb)
        )
    }
}
