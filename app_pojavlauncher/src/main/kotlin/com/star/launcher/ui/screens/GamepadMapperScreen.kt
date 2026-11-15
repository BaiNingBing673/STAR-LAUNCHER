package com.star.launcher.ui.screens

import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.star.launcher.ui.theme.AetherSpacing
import fr.spse.gamepad_remapper.RemapperManager
import fr.spse.gamepad_remapper.RemapperView
import net.kdt.pojavlaunch.R
import net.kdt.pojavlaunch.customcontrols.gamepad.Gamepad
import net.kdt.pojavlaunch.customcontrols.gamepad.GamepadMapperAdapter

@Composable
fun GamepadMapperScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val latestOnBack by rememberUpdatedState(onBack)
    val exitHandler = remember { Handler(Looper.getMainLooper()) }
    val exitRunnable = remember { Runnable { latestOnBack() } }

    DisposableEffect(Unit) {
        onDispose {
            exitHandler.removeCallbacks(exitRunnable)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(AetherSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
    ) {
        AetherSubscreenHeader(
            title = "Gamepad Button Mapping",
            subtitle = "Hold Select or tap Back to save & return",
            backLabel = "Settings",
            onBack = onBack
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    val root = LayoutInflater.from(context)
                        .inflate(R.layout.fragment_controller_remapper, null, false)
                    val recycler = root.findViewById<RecyclerView>(R.id.gamepad_remapper_recycler)
                    val modeSpinner = root.findViewById<Spinner>(R.id.gamepad_remapper_mode_spinner)

                    val mapperAdapter = GamepadMapperAdapter(context)
                    recycler.layoutManager = LinearLayoutManager(context)
                    recycler.adapter = mapperAdapter

                    val builder = RemapperView.Builder(null)
                        .remapA(true)
                        .remapB(true)
                        .remapX(true)
                        .remapY(true)
                        .remapLeftJoystick(true)
                        .remapRightJoystick(true)
                        .remapStart(true)
                        .remapSelect(true)
                        .remapLeftShoulder(true)
                        .remapRightShoulder(true)
                        .remapLeftTrigger(true)
                        .remapRightTrigger(true)
                        .remapDpad(true)
                    val inputManager = RemapperManager(context, builder)
                    var gamepad: Gamepad? = null

                    fun ensureGamepad(device: InputDevice): Gamepad {
                        val existing = gamepad
                        if (existing != null) return existing
                        val created = object : Gamepad(root, device, mapperAdapter, false) {
                            override fun handleGamepadInput(keycode: Int, value: Float) {
                                if (keycode == KeyEvent.KEYCODE_BUTTON_SELECT) {
                                    if (value > 0.5f) {
                                        exitHandler.postDelayed(exitRunnable, 400)
                                    } else {
                                        exitHandler.removeCallbacks(exitRunnable)
                                    }
                                }
                                super.handleGamepadInput(keycode, value)
                            }
                        }
                        gamepad = created
                        return created
                    }

                    recycler.setOnKeyListener { _, _, event ->
                        if (!Gamepad.isGamepadEvent(event)) return@setOnKeyListener false
                        inputManager.handleKeyEventInput(context, event, ensureGamepad(event.device))
                        true
                    }
                    recycler.setOnGenericMotionListener { _, event ->
                        if (!Gamepad.isGamepadEvent(event)) return@setOnGenericMotionListener false
                        inputManager.handleMotionEventInput(context, event, ensureGamepad(event.device))
                        true
                    }
                    recycler.requestFocus()

                    val modeAdapter = ArrayAdapter<String>(
                        context,
                        R.layout.support_simple_spinner_dropdown_item
                    ).apply {
                        addAll(
                            context.getString(R.string.customctrl_visibility_in_menus),
                            context.getString(R.string.customctrl_visibility_ingame)
                        )
                    }
                    modeSpinner.adapter = modeAdapter
                    modeSpinner.setSelection(0)
                    modeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(
                            parent: AdapterView<*>?,
                            view: View?,
                            position: Int,
                            id: Long
                        ) {
                            mapperAdapter.setGrabState(position == 1)
                        }

                        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                    }

                    root
                }
            )
        }
    }
}
