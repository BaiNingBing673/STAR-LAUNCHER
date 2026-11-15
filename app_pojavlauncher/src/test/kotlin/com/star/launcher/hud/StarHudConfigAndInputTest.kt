package com.star.launcher.hud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import com.star.launcher.ui.dashboard.DashboardTab
import com.star.launcher.ui.navigation.AetherRoute
import net.kdt.pojavlaunch.LwjglGlfwKeycode
import net.kdt.pojavlaunch.customcontrols.mouse.HotbarView
import net.kdt.pojavlaunch.customcontrols.mouse.PointerTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StarHudConfigAndInputTest {

    private lateinit var context: Context

    private class RecordingInputBridge : StarHudInputBridge {
        data class KeyEventRecord(val keycode: Int, val isDown: Boolean, val applyModifiers: Boolean)
        data class MouseEventRecord(val button: Int, val isDown: Boolean)
        data class ScrollEventRecord(val xOffset: Float, val yOffset: Float)

        val keyEvents = mutableListOf<KeyEventRecord>()
        val mouseEvents = mutableListOf<MouseEventRecord>()
        val scrollEvents = mutableListOf<ScrollEventRecord>()
        val pressedKeys = mutableMapOf<Int, Boolean>()
        val pressedMouse = mutableMapOf<Int, Boolean>()
        var grabbing: Boolean = true
        var throwOnSend: Boolean = false

        override fun sendKey(keycode: Int, isDown: Boolean, applyModifiers: Boolean) {
            if (throwOnSend) throw IllegalStateException("Simulated bridge failure")
            keyEvents.add(KeyEventRecord(keycode, isDown, applyModifiers))
            pressedKeys[keycode] = isDown
        }

        override fun sendMouseButton(button: Int, isDown: Boolean) {
            if (throwOnSend) throw IllegalStateException("Simulated bridge failure")
            mouseEvents.add(MouseEventRecord(button, isDown))
            pressedMouse[button] = isDown
        }

        override fun sendKeyTap(keycode: Int) {
            if (throwOnSend) throw IllegalStateException("Simulated bridge failure")
            keyEvents.add(KeyEventRecord(keycode, true, false))
            keyEvents.add(KeyEventRecord(keycode, false, false))
        }

        override fun sendScroll(xOffset: Float, yOffset: Float) {
            if (throwOnSend) throw IllegalStateException("Simulated bridge failure")
            scrollEvents.add(ScrollEventRecord(xOffset, yOffset))
        }

        override fun isGrabbing(): Boolean = grabbing
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        net.kdt.pojavlaunch.Tools.currentDisplayMetrics = context.resources.displayMetrics
        StarHudInputManager.getInstance().resetForTesting()
        StarHudInputManager.getInstance().setInputBridge(RecordingInputBridge())
        StarHudConfigStore.resetToDefault(context)
    }

    @Test
    fun `config persistence saves restores presets clamps bounds and survives reload`() {
        val edited = StarHudConfig.defaults()
            .copy(
                hudScale = 1.32f,
                hudOpacity = 0.55f,
                joystickSize = 1.25f,
                joystickDynamic = false,
                hotbarScale = 1.15f,
                cameraSensitivity = 1.85f,
                sneakToggle = false,
                sprintToggle = false
            )
            .withElementEnabled(StarHudElementId.SPRINT, false)
            .withElementPosition(StarHudElementId.JUMP, 0.91f, 0.77f)

        StarHudConfigStore.savePreset(context, "combat_claw", edited)
        StarHudConfigStore.save(context, edited)

        val reloaded = StarHudConfigStore.load(context)
        assertEquals(1.32f, reloaded.hudScale, 0.001f)
        assertEquals(0.55f, reloaded.hudOpacity, 0.001f)
        assertEquals(1.25f, reloaded.joystickSize, 0.001f)
        assertFalse(reloaded.joystickDynamic)
        assertEquals(1.85f, reloaded.cameraSensitivity, 0.001f)
        assertFalse(reloaded.isElementEnabled(StarHudElementId.SPRINT))
        assertEquals(0.91f, reloaded.elementLayout(StarHudElementId.JUMP).xFraction, 0.001f)
        assertEquals(0.77f, reloaded.elementLayout(StarHudElementId.JUMP).yFraction, 0.001f)

        val reset = StarHudConfigStore.resetToDefault(context)
        assertEquals(1.0f, reset.hudScale, 0.001f)
        assertTrue(reset.isElementEnabled(StarHudElementId.SPRINT))
        assertTrue(StarHudConfigStore.hasSavedPreset(context, "combat_claw"))

        val restored = StarHudConfigStore.restorePreset(context, "combat_claw")
        assertNotNull(restored)
        assertEquals(1.32f, restored!!.hudScale, 0.001f)
        assertFalse(restored.isElementEnabled(StarHudElementId.SPRINT))

        val clamped = StarHudConfig(
            hudScale = 9.0f,
            hudOpacity = -1.0f,
            cameraSensitivity = 20.0f
        ).sanitized()
        assertEquals(StarHudConfig.MAX_HUD_SCALE, clamped.hudScale, 0.001f)
        assertEquals(StarHudConfig.MIN_HUD_OPACITY, clamped.hudOpacity, 0.001f)
        assertEquals(StarHudConfig.MAX_CAMERA_SENSITIVITY, clamped.cameraSensitivity, 0.001f)
    }

    @Test
    fun `default HUD elements never overlap and stay inside safe insets across aspect ratios`() {
        val configs = listOf(
            Triple(1280, 720, 2.0f) to StarHudSafeInsets(0, 0, 0, 0),
            Triple(1920, 1080, 3.0f) to StarHudSafeInsets(96, 0, 0, 0),
            Triple(2400, 1080, 2.75f) to StarHudSafeInsets(110, 0, 48, 0),
            Triple(2800, 1280, 3.25f) to StarHudSafeInsets(120, 0, 40, 24),
            Triple(2560, 1600, 2.0f) to StarHudSafeInsets(0, 48, 0, 48)
        )

        val defaultConfig = StarHudConfig.defaults()
        configs.forEach { (dim, insets) ->
            val (w, h, density) = dim
            val rects = StarHudLayoutSolver.solveAllEnabledRects(defaultConfig, w, h, density, insets)
            assertEquals(StarHudElementId.values().size, rects.size)

            rects.values.forEach { rect ->
                assertTrue("${rect.id} clipped left on ${w}x$h", rect.left >= insets.left)
                assertTrue("${rect.id} clipped top on ${w}x$h", rect.top >= insets.top)
                assertTrue("${rect.id} clipped right on ${w}x$h", rect.right <= w - insets.right)
                assertTrue("${rect.id} clipped bottom on ${w}x$h", rect.bottom <= h - insets.bottom)
            }

            val list = rects.values.toList()
            for (i in list.indices) {
                for (j in i + 1 until list.size) {
                    assertFalse(
                        "${list[i].id} overlaps ${list[j].id} on ${w}x$h (${list[i]} vs ${list[j]})",
                        list[i].overlaps(list[j])
                    )
                }
            }
        }
    }

    @Test
    fun `joystick moves in all 8 directions and cleanly releases keys on up or cancel`() {
        val bridge = RecordingInputBridge()
        StarHudInputManager.getInstance().setInputBridge(bridge)
        val joystick = StarJoystickView(context).apply {
            setInputBridge(bridge)
            configure(0.8f, 1.0f, true)
            measure(
                android.view.View.MeasureSpec.makeMeasureSpec(300, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(300, android.view.View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, 300, 300)
        }

        val cx = 150f
        val cy = 150f
        val t0 = SystemClock.uptimeMillis()

        joystick.onTouchEvent(MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, cx, cy - 95f, 0))
        assertTrue(joystick.isForwardPressed)
        assertFalse(joystick.isBackwardPressed)
        assertEquals(true, bridge.pressedKeys[LwjglGlfwKeycode.GLFW_KEY_W.toInt()])

        joystick.onTouchEvent(MotionEvent.obtain(t0, t0 + 16, MotionEvent.ACTION_MOVE, cx + 80f, cy - 80f, 0))
        assertTrue(joystick.isForwardPressed && joystick.isRightPressed)
        assertEquals(true, bridge.pressedKeys[LwjglGlfwKeycode.GLFW_KEY_D.toInt()])

        joystick.onTouchEvent(MotionEvent.obtain(t0, t0 + 32, MotionEvent.ACTION_MOVE, cx, cy + 95f, 0))
        assertTrue(joystick.isBackwardPressed)
        assertFalse(joystick.isForwardPressed)
        assertFalse(joystick.isRightPressed)
        assertEquals(false, bridge.pressedKeys[LwjglGlfwKeycode.GLFW_KEY_W.toInt()])
        assertEquals(true, bridge.pressedKeys[LwjglGlfwKeycode.GLFW_KEY_S.toInt()])

        joystick.onTouchEvent(MotionEvent.obtain(t0, t0 + 48, MotionEvent.ACTION_MOVE, cx - 95f, cy, 0))
        assertTrue(joystick.isLeftPressed)
        assertFalse(joystick.isBackwardPressed)
        assertEquals(true, bridge.pressedKeys[LwjglGlfwKeycode.GLFW_KEY_A.toInt()])

        joystick.onTouchEvent(MotionEvent.obtain(t0, t0 + 64, MotionEvent.ACTION_MOVE, cx + 4f, cy + 4f, 0))
        assertFalse(joystick.isLeftPressed || joystick.isRightPressed || joystick.isForwardPressed || joystick.isBackwardPressed)

        joystick.beginDynamicSession(0, 220f, 180f, RectF(0f, 0f, 500f, 500f))
        joystick.moveDynamicSession(220f, 60f)
        assertTrue(joystick.isForwardPressed)
        joystick.releaseAllKeys()
        assertFalse(joystick.isForwardPressed)
        assertEquals(false, bridge.pressedKeys[LwjglGlfwKeycode.GLFW_KEY_W.toInt()])
    }

    @Test
    fun `action buttons support simultaneous movement jump attack interact and GUI toggle reset`() {
        val bridge = RecordingInputBridge()
        StarHudInputManager.getInstance().setInputBridge(bridge)
        val joystick = StarJoystickView(context).apply {
            setInputBridge(bridge)
            layout(0, 0, 280, 280)
        }
        val jumpBtn = StarHudButtonView(context, StarHudElementId.JUMP).apply {
            setInputBridge(bridge)
            configure(1.0f, 0.8f, false)
            layout(0, 0, 120, 120)
        }
        val attackBtn = StarHudButtonView(context, StarHudElementId.ATTACK).apply {
            setInputBridge(bridge)
            configure(1.0f, 0.8f, false)
            layout(0, 0, 120, 120)
        }
        val sneakBtn = StarHudButtonView(context, StarHudElementId.SNEAK).apply {
            setInputBridge(bridge)
            configure(1.0f, 0.8f, true)
            layout(0, 0, 100, 100)
        }

        val now = SystemClock.uptimeMillis()
        joystick.onTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 140f, 30f, 0))
        jumpBtn.onTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 60f, 60f, 0))
        attackBtn.onTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 60f, 60f, 0))

        assertTrue(joystick.isForwardPressed)
        assertTrue(jumpBtn.isInputDown)
        assertTrue(attackBtn.isInputDown)
        assertEquals(true, bridge.pressedKeys[LwjglGlfwKeycode.GLFW_KEY_W.toInt()])
        assertEquals(true, bridge.pressedKeys[LwjglGlfwKeycode.GLFW_KEY_SPACE.toInt()])
        assertEquals(true, bridge.pressedMouse[LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT.toInt()])

        jumpBtn.onTouchEvent(MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_UP, 60f, 60f, 0))
        assertFalse(jumpBtn.isInputDown)
        assertTrue(attackBtn.isInputDown)
        assertTrue(joystick.isForwardPressed)

        sneakBtn.onTouchEvent(MotionEvent.obtain(now, now + 30, MotionEvent.ACTION_DOWN, 50f, 50f, 0))
        sneakBtn.onTouchEvent(MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, 50f, 50f, 0))
        assertTrue(sneakBtn.isToggledOn)
        assertEquals(true, bridge.pressedKeys[LwjglGlfwKeycode.GLFW_KEY_LEFT_SHIFT.toInt()])

        sneakBtn.releaseOnGuiTransition()
        assertFalse(sneakBtn.isToggledOn)
        assertEquals(false, bridge.pressedKeys[LwjglGlfwKeycode.GLFW_KEY_LEFT_SHIFT.toInt()])
    }

    @Test
    fun `TEST A and C stuck attack fix ref-counts concurrent Attack buttons and world gesture leases`() {
        val bridge = RecordingInputBridge()
        val mgr = StarHudInputManager.getInstance()
        mgr.setInputBridge(bridge)

        val lmb = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT.toInt()
        val attack1 = StarHudButtonView(context, StarHudElementId.ATTACK).apply {
            layout(0, 0, 120, 120)
        }
        val attack2 = StarHudButtonView(
            context,
            StarHudConfig.createButtonForAction("custom_attack_2", StarHudActionType.ATTACK, 0, 0.7f, 0.7f)
        ).apply {
            layout(0, 0, 120, 120)
        }

        val t = SystemClock.uptimeMillis()

        // Press Attack 1 -> Down
        attack1.onTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 60f, 60f, 0))
        assertEquals(true, bridge.pressedMouse[lmb])
        assertEquals(1, mgr.debugSnapshot.pressedMouseRefCounts[lmb])

        // Concurrent world LeftClickGesture acquires lease + releases prematurely -> Attack 1 keeps LMB held!
        mgr.acquireMouseLease("gesture:left_click", "world_gesture", 2, "WorldBreak", lmb)
        assertEquals(2, mgr.debugSnapshot.pressedMouseRefCounts[lmb])
        mgr.releaseLease("gesture:left_click", "gesture_cancel")
        assertEquals(true, bridge.pressedMouse[lmb])
        assertEquals(1, mgr.debugSnapshot.pressedMouseRefCounts[lmb])

        // Press Attack 2 concurrently -> still 1 down + refcount=2
        attack2.onTouchEvent(MotionEvent.obtain(t, t + 10, MotionEvent.ACTION_DOWN, 60f, 60f, 0))
        assertEquals(2, mgr.debugSnapshot.pressedMouseRefCounts[lmb])

        // Release Attack 1 -> Attack 2 still owns LMB -> remains DOWN
        attack1.onTouchEvent(MotionEvent.obtain(t, t + 20, MotionEvent.ACTION_UP, 60f, 60f, 0))
        assertFalse(attack1.isInputDown)
        assertTrue(attack2.isInputDown)
        assertEquals(true, bridge.pressedMouse[lmb])
        assertEquals(1, mgr.debugSnapshot.pressedMouseRefCounts[lmb])

        // Release Attack 2 -> last lease gone -> LMB immediately releases!
        attack2.onTouchEvent(MotionEvent.obtain(t, t + 30, MotionEvent.ACTION_UP, 60f, 60f, 0))
        assertFalse(attack2.isInputDown)
        assertEquals(false, bridge.pressedMouse[lmb])
        assertTrue(mgr.debugSnapshot.pressedMouseRefCounts.isEmpty())
        assertEquals(0, mgr.activeLeaseCount)
    }

    @Test
    fun `TEST B and D and F global releaseAllInputs missing pointer recovery and telemetry debug snapshot`() {
        val bridge = RecordingInputBridge()
        val mgr = StarHudInputManager.getInstance()
        mgr.setInputBridge(bridge)

        var participantCalled = 0
        val participant = StarHudInputManager.ResetParticipant { participantCalled++ }
        mgr.registerResetParticipant(participant)

        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val host = android.widget.FrameLayout(activity)
        activity.setContentView(host)

        val jumpBtn = StarHudButtonView(context, StarHudElementId.JUMP).apply {
            host.addView(this)
            layout(0, 0, 120, 120)
        }
        val customR = StarHudButtonView(
            context,
            StarHudConfig.createButtonForAction("custom_r", StarHudActionType.CUSTOM_KEY, LwjglGlfwKeycode.GLFW_KEY_R.toInt(), 0.5f, 0.5f)
        ).apply {
            host.addView(this)
            layout(0, 0, 100, 100)
        }
        val nextHotbar = StarHudButtonView(
            context,
            StarHudConfig.createButtonForAction("custom_next", StarHudActionType.HOTBAR_NEXT, 0, 0.6f, 0.5f)
        ).apply {
            host.addView(this)
            layout(0, 0, 100, 100)
        }

        val t = SystemClock.uptimeMillis()

        // Custom scroll button dispatches clean scroll without holding any key
        nextHotbar.onTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 50f, 50f, 0))
        nextHotbar.onTouchEvent(MotionEvent.obtain(t, t + 10, MotionEvent.ACTION_UP, 50f, 50f, 0))
        assertEquals(1, bridge.scrollEvents.size)
        assertEquals(0, mgr.activeLeaseCount)

        // Hold Jump & Custom Key R
        jumpBtn.onTouchEvent(MotionEvent.obtain(t, t + 20, MotionEvent.ACTION_DOWN, 60f, 60f, 0))
        customR.onTouchEvent(MotionEvent.obtain(t, t + 25, MotionEvent.ACTION_DOWN, 50f, 50f, 0))
        val snapDuringHold = mgr.debugSnapshot
        assertEquals(2, snapDuringHold.activeLeases.size)
        assertTrue(snapDuringHold.pressedKeyRefCounts.containsKey(LwjglGlfwKeycode.GLFW_KEY_SPACE.toInt()))
        assertTrue(snapDuringHold.pressedKeyRefCounts.containsKey(LwjglGlfwKeycode.GLFW_KEY_R.toInt()))

        // Global release (simulating pause / focus loss / surface destroyed) clears everything
        mgr.releaseAllInputs("window_focus_lost")
        assertEquals(0, mgr.activeLeaseCount)
        assertEquals(false, bridge.pressedKeys[LwjglGlfwKeycode.GLFW_KEY_SPACE.toInt()])
        assertEquals(false, bridge.pressedKeys[LwjglGlfwKeycode.GLFW_KEY_R.toInt()])
        assertFalse(jumpBtn.isInputDown)
        assertFalse(customR.isInputDown)
        assertEquals(1, participantCalled)
        assertTrue(mgr.debugSnapshot.lastEvent.contains("RESET ALL"))

        // Simulated bridge exception tracks bridgeErrorCount without crashing or wedging state
        bridge.throwOnSend = true
        mgr.acquireMouseLease("btn:err", "err", 0, "Attack", LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT.toInt())
        mgr.releaseAllInputs("error_cleanup")
        assertTrue(mgr.debugSnapshot.bridgeErrorCount >= 1)
        mgr.unregisterResetParticipant(participant)
    }

    @Test
    fun `TEST G multi-layout save switch rename duplicate delete corrupt fallback and action validation`() {
        // Unsupported action / keycode validation
        val invalidStructural = StarHudActionRegistry.validateBinding(StarHudActionType.JOYSTICK, 0)
        assertFalse(invalidStructural.isSupported)
        assertNotNull(invalidStructural.warningMessage)

        val invalidKey = StarHudActionRegistry.validateBinding(StarHudActionType.CUSTOM_KEY, -99999)
        assertFalse(invalidKey.isSupported)
        assertNotNull(invalidKey.warningMessage)

        val validCustom = StarHudActionRegistry.validateBinding(StarHudActionType.CUSTOM_KEY, LwjglGlfwKeycode.GLFW_KEY_F3.toInt())
        assertTrue(validCustom.isSupported)

        // Add & customize button
        val base = StarHudConfig.defaults()
        val f3Id = base.nextUniqueCustomButtonId(StarHudActionType.CUSTOM_KEY)
        val f3Btn = StarHudConfig.createButtonForAction(
            f3Id, StarHudActionType.CUSTOM_KEY, LwjglGlfwKeycode.GLFW_KEY_F3.toInt(), 0.45f, 0.25f
        ).copy(widthDp = 64f, heightDp = 48f, opacity = 0.88f, backgroundAlpha = 0.55f, label = "DBG")
        val combatBase = base.withAddedButton(f3Btn)

        // Create, Duplicate, Rename, Switch, Delete layouts
        val combat = StarHudConfigStore.createLayout(context, "Combat Layout", combatBase)
        assertEquals("Combat Layout", combat.layoutName)
        assertNotNull(combat.findButton(f3Id))

        val exploration = StarHudConfigStore.createLayout(context, "Exploration Layout", base)
        assertNull(exploration.findButton(f3Id))

        val renamed = StarHudConfigStore.renameLayout(context, combat.layoutId, "PvP Claw")
        assertNotNull(renamed)
        assertEquals("PvP Claw", renamed!!.layoutName)

        val dup = StarHudConfigStore.duplicateLayout(context, combat.layoutId, "PvP Copy")
        assertNotNull(dup)
        assertEquals("PvP Copy", dup!!.layoutName)
        assertNotNull(dup.findButton(f3Id))

        val listed = StarHudConfigStore.listLayouts(context)
        assertTrue(listed.any { it.layoutName == "PvP Claw" })
        assertTrue(listed.any { it.layoutName == "Exploration Layout" })
        assertTrue(listed.any { it.layoutName == "PvP Copy" })

        val switched = StarHudConfigStore.switchLayout(context, combat.layoutId)
        assertNotNull(switched)
        assertEquals("DBG", switched!!.findButton(f3Id)?.label)

        // Cannot delete default layout
        assertNull(StarHudConfigStore.deleteLayout(context, StarHudConfig.DEFAULT_LAYOUT_ID))

        // Corrupt primary config JSON falls back safely to defaults without crashing
        File(context.filesDir, "star_hud_config.json").writeText("{corrupted-json!!!")
        val recovered = StarHudConfigStore.load(context)
        assertTrue(recovered.buttons.isNotEmpty())

        // Alignment guide snap works cleanly near viewport center
        val snap = StarHudLayoutSolver.snapDragPosition(
            rawLeftPx = 474,
            rawTopPx = 220,
            elemWidthPx = 60,
            elemHeightPx = 60,
            containerWidth = 1000,
            containerHeight = 500,
            density = 2.0f,
            peerCenters = emptyList()
        )
        assertEquals(470, snap.leftPx)
        assertEquals(220, snap.topPx)
        assertEquals(500, snap.guideX)
        assertEquals(250, snap.guideY)
    }

    @Test
    fun `hotbar supports tap selection horizontal swipe and sync listeners`() {
        val bridge = RecordingInputBridge()
        val hotbar = HotbarView(context).apply {
            layoutParams = ViewGroup.MarginLayoutParams(900, 120)
            setInputBridge(bridge)
            applyStarHudConfig(StarHudConfig.defaults(), 0, 0, 0, 0)
            layout(0, 0, 900, 120)
        }

        val t = SystemClock.uptimeMillis()
        hotbar.onTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 150f, 60f, 0))
        assertEquals(1, hotbar.selectedSlot)
        assertTrue(bridge.keyEvents.any { it.keycode == LwjglGlfwKeycode.GLFW_KEY_2.toInt() && it.isDown })

        hotbar.onTouchEvent(MotionEvent.obtain(t, t + 20, MotionEvent.ACTION_MOVE, 650f, 60f, 0))
        hotbar.onTouchEvent(MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_UP, 650f, 60f, 0))
        assertEquals(6, hotbar.selectedSlot)
        assertTrue(bridge.keyEvents.any { it.keycode == LwjglGlfwKeycode.GLFW_KEY_7.toInt() && it.isDown })

        hotbar.onHotbarSlotSelected(8)
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertEquals(8, hotbar.selectedSlot)

        hotbar.onHotbarScroll(-1.0f)
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertEquals(0, hotbar.selectedSlot)
    }

    @Test
    fun `multi-touch camera pointer tracker never jumps when second finger lands or lifts`() {
        val tracker = PointerTracker()
        val t = SystemClock.uptimeMillis()

        val down0 = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 500f, 300f, 0)
        tracker.startTracking(down0, 0)

        val move0 = MotionEvent.obtain(t, t + 16, MotionEvent.ACTION_MOVE, 540f, 280f, 0)
        tracker.trackEvent(move0)
        assertEquals(40f, tracker.motionVector[0], 0.01f)
        assertEquals(-20f, tracker.motionVector[1], 0.01f)

        val props = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER }
        )
        val coords2 = arrayOf(
            MotionEvent.PointerCoords().apply { x = 550f; y = 275f },
            MotionEvent.PointerCoords().apply { x = 150f; y = 600f }
        )
        val p1Down = MotionEvent.obtain(
            t, t + 32,
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2, props, coords2, 0, 0, 1f, 1f, 0, 0, 0, 0
        )
        tracker.handlePointerDown(p1Down)
        tracker.trackEvent(p1Down)
        assertEquals(10f, tracker.motionVector[0], 0.01f)
        assertEquals(-5f, tracker.motionVector[1], 0.01f)

        val p1Up = MotionEvent.obtain(
            t, t + 48,
            MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2, props, coords2, 0, 0, 1f, 1f, 0, 0, 0, 0
        )
        tracker.handlePointerUp(p1Up)
        val moveAfterLift = MotionEvent.obtain(t, t + 64, MotionEvent.ACTION_MOVE, 562f, 270f, 0)
        tracker.trackEvent(moveAfterLift)
        assertEquals(12f, tracker.motionVector[0], 0.01f)
        assertEquals(-5f, tracker.motionVector[1], 0.01f)
    }

    @Test
    fun `vector icons draw cleanly for every icon ID and navigation route resolves`() {
        val bmp = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val path = Path()
        val bounds = RectF(16f, 16f, 112f, 112f)

        StarHudIconId.values().forEach { iconId ->
            StarHudIcons.drawIcon(canvas, iconId, bounds, stroke, fill, path)
        }

        val route = AetherRoute.decode("HUD")
        assertEquals(AetherRoute.HudCustomization, route)
        assertEquals(DashboardTab.SETTINGS, route?.ownerTab)
        assertEquals("hud_customization", AetherRoute.HudCustomization.encode())
    }
}
