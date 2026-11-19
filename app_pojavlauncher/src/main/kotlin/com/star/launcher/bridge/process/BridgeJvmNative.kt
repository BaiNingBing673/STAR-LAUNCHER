package com.star.launcher.bridge.process

import android.content.Context
import androidx.annotation.Keep

/**
 * Native bridge methods implemented in `stdio_is.c` (`libpojavexec.so`) for headless
 * server/Geyser JVM lifecycle management without spawning `ExitActivity`.
 */
object BridgeJvmNative {
    @JvmStatic
    external fun setupHeadlessExit(context: Context)

    @JvmStatic
    external fun openStdinPipe(): Boolean

    @JvmStatic
    external fun writeStdin(command: String): Boolean

    @JvmStatic
    external fun closeStdinPipe()
}

@Keep
object BridgeJvmExitBridge {
    @Volatile
    var onExitListener: ((context: Context?, code: Int, isSignal: Boolean) -> Unit)? = null

    @JvmStatic
    @Keep
    fun onJvmExit(context: Context?, code: Int, isSignal: Boolean) {
        runCatching {
            onExitListener?.invoke(context, code, isSignal)
        }
    }
}
