package com.tmplayer.ui.profiles

import android.view.WindowManager
import androidx.activity.ComponentActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A PIN profile stays out of the recents screen (HX88): below Android 13 through FLAG_SECURE, on and
 * off with the profile's PIN; and the app lock is never read on the main thread at the first frame.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SecureWindowTest {

    @Test
    fun `below Android 13 the window is secure only while the open profile has a PIN`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        fun secure() = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE
        assertEquals(0, secure())
        SecureWindow.apply(activity, secure = true, sdk = 28)
        assertEquals(WindowManager.LayoutParams.FLAG_SECURE, secure())
        SecureWindow.apply(activity, secure = false, sdk = 28)
        assertEquals("off again, so screenshots and casting work for a profile with no PIN", 0, secure())
    }

    @Test
    fun `the app lock is not read on the main thread when the flow is made`() {
        // Fail closed instead: unknown until the store's flow answers on its own thread.
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        assertNull(AndroidProfilePlatform(context).appLockNow())
    }
}
