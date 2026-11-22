package com.tmplayer.ui

import android.graphics.BitmapFactory
import org.robolectric.annotation.GraphicsMode
import org.robolectric.config.ConfigurationRegistry
import org.robolectric.nativeruntime.DefaultNativeRuntimeLoader
import org.robolectric.pluginapi.TestEnvironmentLifecyclePlugin
import java.io.File
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Starts Robolectric's native graphics library on one thread before any test body runs (HX88: the
 * app test JVM that ended with exit value 134, SIGABRT, after every test had passed).
 *
 * The fault: the library (`libandroid_runtime.so`, the real Android graphics code) fills a table of
 * Java classes the first time any thread decodes a picture from a file (`JniConstants`, behind a
 * `pthread_once`). When several threads make that first decode at once, which the app's own
 * picture loading does on its I/O and default pools as soon as a screen shows posters, one lookup
 * in that table fails ("Class not found: java/nio/ShortBuffer"), the library answers with
 * `LOG_ALWAYS_FATAL` and `abort()`s the whole JVM from `__android_log_assert`, with no `hs_err`
 * file, only a core dump. It is a race, so it hit about one full run in two, in whichever class
 * drew posters first. Decoding one 1 by 1 picture here, with the library loaded and the thread
 * alone, fills the table; every later decode only reads it. [NativeJniWarmUpTest] makes 32 threads
 * decode at once in a fresh JVM: it aborts without this.
 *
 * Registered through `META-INF/services/org.robolectric.pluginapi.TestEnvironmentLifecyclePlugin`.
 * Only for classes in the native graphics mode, where the library is loaded for them anyway; costs
 * one tiny decode per sandbox, and when that is not possible it prints one line to System.err
 * and goes on (the tests then behave as they did before).
 */
class NativeJniWarmUp : TestEnvironmentLifecyclePlugin {

    override fun onSetupApplicationState() {
        try {
            if (ConfigurationRegistry.get(GraphicsMode.Mode::class.java) != GraphicsMode.Mode.NATIVE) return
            if (!done.compareAndSet(false, true)) return
            DefaultNativeRuntimeLoader.injectAndLoad()
            val file = File.createTempFile("jni-warm-up", ".png")
            try {
                file.writeBytes(Base64.getDecoder().decode(ONE_PIXEL_PNG))
                BitmapFactory.decodeFile(file.path)
            } finally {
                file.delete()
            }
        } catch (failed: Throwable) {
            // Never fails a test, but says so: without the warm-up the intermittent abort is back.
            System.err.println("NativeJniWarmUp: the warm-up decode did not run (${failed.javaClass.simpleName}: ${failed.message})")
        }
    }

    private companion object {
        val done = AtomicBoolean(false)
        const val ONE_PIXEL_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNgYGBgAAAABQABpfZFQAAAAABJRU5ErkJggg=="
    }
}
