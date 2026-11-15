package com.star.launcher.hud

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import net.kdt.pojavlaunch.LwjglGlfwKeycode
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.customcontrols.mouse.InGameEventProcessor
import org.junit.After
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
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StarMouseStateAuthorityRegressionTest {

    private lateinit var context: Context
    private lateinit var bridge: RecordingBridge

    private val lmb = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT.toInt()
    private val rmb = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT.toInt()

    private class RecordingBridge : StarHudInputBridge {
        data class MouseEventRecord(val button: Int, val isDown: Boolean, val seq: Int)
        data class KeyEventRecord(val keycode: Int, val isDown: Boolean)

        val mouseEvents = mutableListOf<MouseEventRecord>()
        val keyEvents = mutableListOf<KeyEventRecord>()
        val pressedMouse = mutableMapOf<Int, Boolean>()
        var grabbing: Boolean = true
        private var seqCounter = 0

        override fun sendKey(keycode: Int, isDown: Boolean, applyModifiers: Boolean) {
            keyEvents.add(KeyEventRecord(keycode, isDown))
        }

        override fun sendMouseButton(button: Int, isDown: Boolean) {
            mouseEvents.add(MouseEventRecord(button, isDown, ++seqCounter))
            pressedMouse[button] = isDown
        }

        override fun sendKeyTap(keycode: Int) {
            keyEvents.add(KeyEventRecord(keycode, true))
            keyEvents.add(KeyEventRecord(keycode, false))
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
        bridge = RecordingBridge()
        StarHudInputManager.getInstance().resetForTesting()
        StarHudInputManager.getInstance().setInputBridge(bridge)
    }

    @After
    fun tearDown() {
        StarHudInputManager.getInstance().resetForTesting()
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

    private fun makeOverlayHost(config: StarHudConfig = StarHudConfig.defaults()): Triple<Activity, StarTouchHudOverlay, FrameLayout> {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val host = FrameLayout(activity)
        activity.setContentView(host)

        val overlay = StarTouchHudOverlay(activity).apply {
            setHostContainer(host)
            setConfig(config)
            setHudActive(true)
        }
        host.addView(overlay, FrameLayout.LayoutParams(1280, 720))
        host.measure(
            View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY)
        )
        host.layout(0, 0, 1280, 720)
        overlay.relayoutElements()
        return Triple(activity, overlay, host)
    }

    @Test
    fun `scenario 1 - hold Attack 30 seconds emits exactly 1 DOWN and 1 UP with zero click spam`() {
        val attackBtn = StarHudButtonView(context, StarHudElementId.ATTACK).apply { layout(0, 0, 120, 120) }
        val mgr = StarHudInputManager.getInstance()
        val start = SystemClock.uptimeMillis()

        attackBtn.onTouchEvent(singlePointerEvent(start, start, MotionEvent.ACTION_DOWN, 0, 60f, 60f))
        assertTrue(mgr.isMouseButtonPressed(lmb))
        assertEquals(setOf(0), mgr.getMouseButtonOwnerPointers(lmb))

        var elapsed = 500L
        while (elapsed <= 30_000L) {
            attackBtn.onTouchEvent(singlePointerEvent(start, start + elapsed, MotionEvent.ACTION_MOVE, 0, 62f, 62f))
            ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS)
            assertTrue("LMB must remain pressed at ${elapsed}ms", mgr.isMouseButtonPressed(lmb))
            assertEquals("Must not emit duplicate LMB DOWN during 30s hold", 1, bridge.countMouseDown(lmb))
            assertEquals("Must not emit premature LMB UP during 30s hold", 0, bridge.countMouseUp(lmb))
            elapsed += 500L
        }

        attackBtn.onTouchEvent(singlePointerEvent(start, start + 30_050L, MotionEvent.ACTION_UP, 0, 62f, 62f))
        assertFalse(mgr.isMouseButtonPressed(lmb))
        assertEquals(1, bridge.countMouseDown(lmb))
        assertEquals(1, bridge.countMouseUp(lmb))
        assertEquals(0, bridge.countMouseDown(rmb))
        assertEquals(0, bridge.countMouseUp(rmb))
        assertEquals(0, mgr.debugSnapshot.mismatchCount)
    }

    @Test
    fun `scenario 2 - hold Place 30 seconds emits exactly 1 DOWN and 1 UP with zero click spam`() {
        val placeBtn = StarHudButtonView(context, StarHudElementId.INTERACT).apply { layout(0, 0, 120, 120) }
        val mgr = StarHudInputManager.getInstance()
        val start = SystemClock.uptimeMillis()

        placeBtn.onTouchEvent(singlePointerEvent(start, start, MotionEvent.ACTION_DOWN, 1, 60f, 60f))
        assertTrue(mgr.isMouseButtonPressed(rmb))
        assertEquals(setOf(1), mgr.getMouseButtonOwnerPointers(rmb))

        var elapsed = 500L
        while (elapsed <= 30_000L) {
            placeBtn.onTouchEvent(singlePointerEvent(start, start + elapsed, MotionEvent.ACTION_MOVE, 1, 62f, 62f))
            ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS)
            assertTrue("RMB must remain pressed at ${elapsed}ms", mgr.isMouseButtonPressed(rmb))
            assertEquals("Must not emit duplicate RMB DOWN during 30s hold", 1, bridge.countMouseDown(rmb))
            assertEquals("Must not emit premature RMB UP during 30s hold", 0, bridge.countMouseUp(rmb))
            elapsed += 500L
        }

        placeBtn.onTouchEvent(singlePointerEvent(start, start + 30_050L, MotionEvent.ACTION_UP, 1, 62f, 62f))
        assertFalse(mgr.isMouseButtonPressed(rmb))
        assertEquals(1, bridge.countMouseDown(rmb))
        assertEquals(1, bridge.countMouseUp(rmb))
        assertEquals(0, bridge.countMouseDown(lmb))
        assertEquals(0, bridge.countMouseUp(lmb))
        assertEquals(0, mgr.debugSnapshot.mismatchCount)
    }

    @Test
    fun `scenario 3 - rapidly tap both Attack and Place maintains strict 1-to-1 DOWN-UP transitions`() {
        val attackTap = StarHudButtonView(
            context,
            StarHudConfig.createButtonForAction("tap_atk", StarHudActionType.ATTACK).copy(interactionMode = StarHudInteractionMode.TAP)
        ).apply { layout(0, 0, 120, 120) }
        val placeTap = StarHudButtonView(
            context,
            StarHudConfig.createButtonForAction("tap_plc", StarHudActionType.INTERACT).copy(interactionMode = StarHudInteractionMode.TAP)
        ).apply { layout(0, 0, 120, 120) }

        val mgr = StarHudInputManager.getInstance()
        var t = SystemClock.uptimeMillis()

        repeat(30) {
            attackTap.onTouchEvent(singlePointerEvent(t, t, MotionEvent.ACTION_DOWN, 0, 60f, 60f))
            attackTap.onTouchEvent(singlePointerEvent(t, t + 5, MotionEvent.ACTION_UP, 0, 60f, 60f))
            placeTap.onTouchEvent(singlePointerEvent(t + 10, t + 10, MotionEvent.ACTION_DOWN, 1, 60f, 60f))
            placeTap.onTouchEvent(singlePointerEvent(t + 10, t + 15, MotionEvent.ACTION_UP, 1, 60f, 60f))
            t += 20
        }

        assertEquals(30, bridge.countMouseDown(lmb))
        assertEquals(30, bridge.countMouseUp(lmb))
        assertEquals(30, bridge.countMouseDown(rmb))
        assertEquals(30, bridge.countMouseUp(rmb))
        assertFalse(mgr.isMouseButtonPressed(lmb))
        assertFalse(mgr.isMouseButtonPressed(rmb))
        assertEquals(0, mgr.debugSnapshot.mismatchCount)

        // Verify strict alternating DOWN -> UP ordering per button.
        val lmbEvents = bridge.mouseEvents.filter { it.button == lmb }
        val rmbEvents = bridge.mouseEvents.filter { it.button == rmb }
        for (i in lmbEvents.indices) assertEquals(i % 2 == 0, lmbEvents[i].isDown)
        for (i in rmbEvents.indices) assertEquals(i % 2 == 0, rmbEvents[i].isDown)
    }

    @Test
    fun `scenarios 4, 5, and 6 - hold Attack and Place simultaneously then release independently in both orders`() {
        val attackBtn = StarHudButtonView(context, StarHudElementId.ATTACK).apply { layout(0, 0, 120, 120) }
        val placeBtn = StarHudButtonView(context, StarHudElementId.INTERACT).apply { layout(0, 0, 120, 120) }
        val mgr = StarHudInputManager.getInstance()
        val t = SystemClock.uptimeMillis()

        // Scenario 4: Simultaneous Attack (ptr 0) and Place (ptr 1).
        attackBtn.onTouchEvent(singlePointerEvent(t, t, MotionEvent.ACTION_DOWN, 0, 60f, 60f))
        placeBtn.onTouchEvent(singlePointerEvent(t, t + 5, MotionEvent.ACTION_POINTER_DOWN, 1, 60f, 60f))
        assertTrue(mgr.isMouseButtonPressed(lmb))
        assertTrue(mgr.isMouseButtonPressed(rmb))
        assertEquals(setOf(0), mgr.getMouseButtonOwnerPointers(lmb))
        assertEquals(setOf(1), mgr.getMouseButtonOwnerPointers(rmb))

        // Scenario 5: Release Attack (ptr 0) while keeping Place (ptr 1) pressed.
        attackBtn.onTouchEvent(singlePointerEvent(t, t + 50, MotionEvent.ACTION_POINTER_UP, 0, 60f, 60f))
        assertFalse(mgr.isMouseButtonPressed(lmb))
        assertTrue("Releasing Attack must NEVER release Place", mgr.isMouseButtonPressed(rmb))
        assertEquals(1, bridge.countMouseUp(lmb))
        assertEquals(0, bridge.countMouseUp(rmb))

        // Finish releasing Place (ptr 1).
        placeBtn.onTouchEvent(singlePointerEvent(t, t + 100, MotionEvent.ACTION_UP, 1, 60f, 60f))
        assertFalse(mgr.isMouseButtonPressed(rmb))
        assertEquals(1, bridge.countMouseUp(rmb))

        // Scenario 6: Hold both again, release Place (ptr 3) while keeping Attack (ptr 2) pressed.
        val t2 = t + 200
        attackBtn.onTouchEvent(singlePointerEvent(t2, t2, MotionEvent.ACTION_DOWN, 2, 60f, 60f))
        placeBtn.onTouchEvent(singlePointerEvent(t2, t2 + 5, MotionEvent.ACTION_POINTER_DOWN, 3, 60f, 60f))
        assertEquals(2, bridge.countMouseDown(lmb))
        assertEquals(2, bridge.countMouseDown(rmb))

        placeBtn.onTouchEvent(singlePointerEvent(t2, t2 + 40, MotionEvent.ACTION_POINTER_UP, 3, 60f, 60f))
        assertFalse(mgr.isMouseButtonPressed(rmb))
        assertTrue("Releasing Place must NEVER release Attack", mgr.isMouseButtonPressed(lmb))
        assertEquals(2, bridge.countMouseUp(rmb))
        assertEquals(1, bridge.countMouseUp(lmb))

        // Finish releasing Attack (ptr 2).
        attackBtn.onTouchEvent(singlePointerEvent(t2, t2 + 90, MotionEvent.ACTION_UP, 2, 60f, 60f))
        assertFalse(mgr.isMouseButtonPressed(lmb))
        assertEquals(2, bridge.countMouseUp(lmb))
        assertEquals(0, mgr.activeLeaseCount)
        assertEquals(0, mgr.debugSnapshot.mismatchCount)
    }

    @Test
    fun `scenario 7 - rotate camera continuously while holding both Attack and Place buttons`() {
        val attackBtn = StarHudButtonView(context, StarHudElementId.ATTACK).apply { layout(0, 0, 120, 120) }
        val placeBtn = StarHudButtonView(context, StarHudElementId.INTERACT).apply { layout(0, 0, 120, 120) }
        val mgr = StarHudInputManager.getInstance()
        val cursorUpdates = mutableListOf<Pair<Float, Float>>()
        val processor = InGameEventProcessor(1.0, object : InGameEventProcessor.CameraCursorSink {
            override fun onCameraDelta(deltaX: Float, deltaY: Float) {
                cursorUpdates.add(deltaX to deltaY)
            }

            override fun onCameraReset() = Unit
        })
        mgr.registerResetParticipant(processor)

        val t = SystemClock.uptimeMillis()
        attackBtn.onTouchEvent(singlePointerEvent(t, t, MotionEvent.ACTION_DOWN, 0, 60f, 60f))
        placeBtn.onTouchEvent(singlePointerEvent(t, t + 10, MotionEvent.ACTION_POINTER_DOWN, 1, 60f, 60f))
        processor.processTouchEvent(singlePointerEvent(t, t + 20, MotionEvent.ACTION_DOWN, 2, 400f, 300f))

        for (i in 1..40) {
            processor.processTouchEvent(singlePointerEvent(t, t + 20 + i * 50L, MotionEvent.ACTION_MOVE, 2, 400f + i * 7f, 300f - i * 3f))
            ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
        }

        assertTrue(cursorUpdates.size == 40)
        assertTrue(mgr.isMouseButtonPressed(lmb))
        assertTrue(mgr.isMouseButtonPressed(rmb))
        assertEquals(1, bridge.countMouseDown(lmb))
        assertEquals(1, bridge.countMouseDown(rmb))
        assertEquals(0, bridge.countMouseUp(lmb))
        assertEquals(0, bridge.countMouseUp(rmb))

        // Lifting camera pointer keeps both buttons pressed; releasing each button releases only its own.
        processor.processTouchEvent(singlePointerEvent(t, t + 2100, MotionEvent.ACTION_UP, 2, 680f, 180f))
        assertTrue(mgr.isMouseButtonPressed(lmb))
        assertTrue(mgr.isMouseButtonPressed(rmb))

        attackBtn.onTouchEvent(singlePointerEvent(t, t + 2150, MotionEvent.ACTION_UP, 0, 60f, 60f))
        assertFalse(mgr.isMouseButtonPressed(lmb))
        assertTrue(mgr.isMouseButtonPressed(rmb))

        placeBtn.onTouchEvent(singlePointerEvent(t, t + 2200, MotionEvent.ACTION_UP, 1, 60f, 60f))
        assertFalse(mgr.isMouseButtonPressed(rmb))
        assertEquals(0, mgr.debugSnapshot.mismatchCount)
    }

    @Test
    fun `scenario 8 - minimize and reopen releases both Attack and Place cleanly and resumes working`() {
        val (_, overlay, _) = makeOverlayHost()
        val attackBtn = overlay.getButtonView(StarHudElementId.ATTACK)!!
        val placeBtn = overlay.getButtonView(StarHudElementId.INTERACT)!!
        val mgr = StarHudInputManager.getInstance()
        val t = SystemClock.uptimeMillis()

        attackBtn.onTouchEvent(singlePointerEvent(t, t, MotionEvent.ACTION_DOWN, 0, attackBtn.width * 0.5f, attackBtn.height * 0.5f))
        placeBtn.onTouchEvent(singlePointerEvent(t, t + 5, MotionEvent.ACTION_POINTER_DOWN, 1, placeBtn.width * 0.5f, placeBtn.height * 0.5f))
        assertTrue(mgr.isMouseButtonPressed(lmb))
        assertTrue(mgr.isMouseButtonPressed(rmb))

        // Simulate minimize (focus loss + pause + grab lost + resume reset)
        bridge.grabbing = false
        overlay.onWindowFocusChanged(false)
        mgr.releaseAllInputs("activity_onPause")
        overlay.onGrabState(false)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertFalse(mgr.isMouseButtonPressed(lmb))
        assertFalse(mgr.isMouseButtonPressed(rmb))
        assertEquals(1, bridge.countMouseUp(lmb))
        assertEquals(1, bridge.countMouseUp(rmb))
        assertEquals(0, mgr.activeLeaseCount)

        // Reopen (resume + focus restored + grab restored) -> must re-arm cleanly
        mgr.releaseAllInputs("activity_onResume")
        bridge.grabbing = true
        overlay.onWindowFocusChanged(true)
        overlay.onGrabState(true)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        // No spurious UP when resuming cleanly
        assertEquals(1, bridge.countMouseUp(lmb))
        assertEquals(1, bridge.countMouseUp(rmb))

        val t2 = t + 300
        attackBtn.onTouchEvent(singlePointerEvent(t2, t2, MotionEvent.ACTION_DOWN, 0, attackBtn.width * 0.5f, attackBtn.height * 0.5f))
        placeBtn.onTouchEvent(singlePointerEvent(t2, t2 + 5, MotionEvent.ACTION_POINTER_DOWN, 1, placeBtn.width * 0.5f, placeBtn.height * 0.5f))
        assertTrue(mgr.isMouseButtonPressed(lmb))
        assertTrue(mgr.isMouseButtonPressed(rmb))
        attackBtn.onTouchEvent(singlePointerEvent(t2, t2 + 40, MotionEvent.ACTION_UP, 0, attackBtn.width * 0.5f, attackBtn.height * 0.5f))
        placeBtn.onTouchEvent(singlePointerEvent(t2, t2 + 50, MotionEvent.ACTION_UP, 1, placeBtn.width * 0.5f, placeBtn.height * 0.5f))
        assertFalse(mgr.isMouseButtonPressed(lmb))
        assertFalse(mgr.isMouseButtonPressed(rmb))
        assertEquals(2, bridge.countMouseDown(lmb))
        assertEquals(2, bridge.countMouseUp(lmb))
        assertEquals(2, bridge.countMouseDown(rmb))
        assertEquals(2, bridge.countMouseUp(rmb))
    }

    @Test
    fun `scenario 9 - switch layouts disable controller and ACTION_CANCEL protect other active pointers`() {
        val layoutA = StarHudConfig.defaults()
        val layoutB = layoutA
            .withElementPosition(StarHudElementId.ATTACK, 0.72f, 0.42f)
            .withElementPosition(StarHudElementId.INTERACT, 0.85f, 0.48f)
            .copy(layoutId = "layout_b", layoutName = "Combat B")

        val (_, overlay, _) = makeOverlayHost(layoutA)
        val attackBtn = overlay.getButtonView(StarHudElementId.ATTACK)!!
        val placeBtn = overlay.getButtonView(StarHudElementId.INTERACT)!!
        val mgr = StarHudInputManager.getInstance()
        val t = SystemClock.uptimeMillis()

        // 1) Switch layouts while holding both Attack and Place -> releases both immediately
        attackBtn.onTouchEvent(singlePointerEvent(t, t, MotionEvent.ACTION_DOWN, 0, attackBtn.width * 0.5f, attackBtn.height * 0.5f))
        placeBtn.onTouchEvent(singlePointerEvent(t, t + 5, MotionEvent.ACTION_POINTER_DOWN, 1, placeBtn.width * 0.5f, placeBtn.height * 0.5f))
        overlay.setConfig(layoutB)

        assertFalse(mgr.isMouseButtonPressed(lmb))
        assertFalse(mgr.isMouseButtonPressed(rmb))
        assertEquals(0, mgr.activeLeaseCount)

        // 2) Controller disabled while holding both -> releases both immediately
        val t2 = t + 100
        attackBtn.onTouchEvent(singlePointerEvent(t2, t2, MotionEvent.ACTION_DOWN, 0, attackBtn.width * 0.5f, attackBtn.height * 0.5f))
        placeBtn.onTouchEvent(singlePointerEvent(t2, t2 + 5, MotionEvent.ACTION_POINTER_DOWN, 1, placeBtn.width * 0.5f, placeBtn.height * 0.5f))
        assertTrue(mgr.isMouseButtonPressed(lmb))
        assertTrue(mgr.isMouseButtonPressed(rmb))
        overlay.setHudActive(false)
        assertFalse(mgr.isMouseButtonPressed(lmb))
        assertFalse(mgr.isMouseButtonPressed(rmb))

        // Re-enable controller and verify ACTION_CANCEL on one Attack button never releases another pointer's active LMB lease
        overlay.setHudActive(true)
        val secondAttackBtn = StarHudButtonView(
            context,
            StarHudConfig.createButtonForAction("custom_atk_2", StarHudActionType.ATTACK)
        ).apply { layout(0, 0, 120, 120) }

        val t3 = t + 200
        attackBtn.onTouchEvent(singlePointerEvent(t3, t3, MotionEvent.ACTION_DOWN, 2, attackBtn.width * 0.5f, attackBtn.height * 0.5f))
        secondAttackBtn.onTouchEvent(singlePointerEvent(t3, t3 + 5, MotionEvent.ACTION_DOWN, 3, 60f, 60f))
        assertEquals(setOf(2, 3), mgr.getMouseButtonOwnerPointers(lmb))

        // ACTION_CANCEL cancels only pointer 2's button -> pointer 3 MUST keep LMB pressed!
        attackBtn.onTouchEvent(singlePointerEvent(t3, t3 + 20, MotionEvent.ACTION_CANCEL, 2, 0f, 0f))
        assertTrue("Cleaning up pointer 2 must NOT release LMB owned by pointer 3", mgr.isMouseButtonPressed(lmb))
        assertEquals(setOf(3), mgr.getMouseButtonOwnerPointers(lmb))

        secondAttackBtn.onTouchEvent(singlePointerEvent(t3, t3 + 40, MotionEvent.ACTION_UP, 3, 60f, 60f))
        assertFalse(mgr.isMouseButtonPressed(lmb))
        assertEquals(0, mgr.activeLeaseCount)
        assertEquals(0, mgr.debugSnapshot.mismatchCount)
    }

    @Test
    fun `authoritative state suppresses repeated DOWNs spurious UPs and concurrent TAP collisions`() {
        val mgr = StarHudInputManager.getInstance()

        // Acquire Attack -> 1 DOWN
        assertTrue(mgr.acquireMouseLease("atk:1", "btn1", 10, "ATTACK", lmb))
        // Repeated acquire on different lease or tap while held -> ZERO duplicate DOWNs and ZERO premature UPs
        assertTrue(mgr.acquireMouseLease("atk:2", "btn2", 11, "ATTACK", lmb))
        mgr.tapMouseButton("tap_atk", 12, "ATTACK", lmb)
        assertEquals(1, bridge.countMouseDown(lmb))
        assertEquals(0, bridge.countMouseUp(lmb))

        // Releasing unknown/already-released RMB via emergency recovery must NOT emit fake RMB UP
        mgr.emergencyReleaseAllInputs("test_cleanup")
        assertEquals(1, bridge.countMouseUp(lmb))
        assertEquals(0, bridge.countMouseUp(rmb))
        assertFalse(mgr.isMouseButtonPressed(lmb))
        assertFalse(mgr.isMouseButtonPressed(rmb))
    }
}
