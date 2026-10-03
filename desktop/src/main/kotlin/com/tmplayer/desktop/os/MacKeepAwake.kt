package com.tmplayer.desktop.os

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.platform.mac.CoreFoundation.CFStringRef
import com.sun.jna.ptr.IntByReference
import com.tmplayer.platform.Logger

/**
 * macOS: an IOKit power assertion of type `PreventUserIdleDisplaySleep` (which also keeps the
 * system awake), created through JNA. If IOKit cannot be loaded or refuses, `caffeinate -d -i -w
 * <pid>` does the same job as a child process that also ends when this JVM does.
 *
 * Only constructed when [OsInfo.isMac]; IOKit is never loaded elsewhere.
 */
internal class MacKeepAwake : SerialKeepAwake("macos") {

    private var assertionId: Int? = null
    private var caffeinate: Process? = null

    override fun hold(reason: String): Boolean {
        val viaIoKit = runCatching {
            val type = CFStringRef.createCFString(ASSERTION_TYPE)
            val name = CFStringRef.createCFString("TMPlayer: $reason")
            try {
                val id = IntByReference()
                val result = IOKitPower.INSTANCE.IOPMAssertionCreateWithName(type, LEVEL_ON, name, id)
                if (result == IO_RETURN_SUCCESS) id.value else null
            } finally {
                type.release()
                name.release()
            }
        }.onFailure { Logger.w(TAG, "IOKit assertion failed: ${it.message}") }.getOrNull()
        if (viaIoKit != null) {
            assertionId = viaIoKit
            return true
        }
        caffeinate = runCatching {
            ProcessBuilder("caffeinate", "-d", "-i", "-w", OsInfo.pid.toString())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        }.onFailure { Logger.w(TAG, "caffeinate failed: ${it.message}") }.getOrNull()
        return caffeinate != null
    }

    override fun letGo() {
        assertionId?.let { id -> runCatching { IOKitPower.INSTANCE.IOPMAssertionRelease(id) } }
        assertionId = null
        caffeinate?.destroy()
        caffeinate = null
    }

    private companion object {
        const val TAG = SerialKeepAwake.TAG
        const val ASSERTION_TYPE = "PreventUserIdleDisplaySleep"
        const val LEVEL_ON = 255
        const val IO_RETURN_SUCCESS = 0
    }
}

/** The two IOKit power management calls, bound by JNA (public, so JNA can proxy it). */
@Suppress("FunctionName")
internal interface IOKitPower : Library {
    fun IOPMAssertionCreateWithName(type: CFStringRef, level: Int, name: CFStringRef, id: IntByReference): Int
    fun IOPMAssertionRelease(id: Int): Int

    companion object {
        val INSTANCE: IOKitPower by lazy { Native.load("IOKit", IOKitPower::class.java) }
    }
}
