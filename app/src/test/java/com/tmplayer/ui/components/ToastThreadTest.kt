package com.tmplayer.ui.components

import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * A message asked for off the main thread (a coroutine that resumed on the settings store's
 * writer, as Forget all in the remembered languages did on CI, 2026-11-17) is shown, not a crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ToastThreadTest {

    @Test
    fun `a message from a background thread is shown on the main thread`() {
        val context = RuntimeEnvironment.getApplication()
        var failure: Throwable? = null
        val worker = Thread { runCatching { showMessage(context, "Every series will start afresh") }.onFailure { failure = it } }
        worker.start()
        worker.join()
        assertNull("no crash off the main thread", failure)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Every series will start afresh", ShadowToast.getTextOfLatestToast())
    }
}
