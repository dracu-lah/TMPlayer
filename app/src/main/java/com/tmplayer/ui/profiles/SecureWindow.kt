package com.tmplayer.ui.profiles

import android.app.Activity
import android.os.Build
import android.view.WindowManager
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.tmplayer.data.OutsideWrites
import com.tmplayer.data.openAccounts
import kotlinx.coroutines.launch

/**
 * Keeps a PIN profile out of the recents screen (HX88): while the open profile has a PIN, Android
 * 13 and later leave the window out of the recents thumbnail; below 13 the only way is
 * FLAG_SECURE, which also blocks screenshots and screen recording, and some casting, of that
 * window. Off whenever the open profile has no PIN, so nothing changes for anyone without one.
 */
object SecureWindow {
    fun <A> follow(activity: A) where A : Activity, A : LifecycleOwner {
        openAccounts(activity)
        activity.lifecycleScope.launch {
            runCatching { OutsideWrites.openHasPin().collect { apply(activity, OutsideWrites.secureWindow(it)) } }
        }
    }

    internal fun apply(activity: Activity, secure: Boolean, sdk: Int = Build.VERSION.SDK_INT) {
        runCatching {
            if (sdk >= Build.VERSION_CODES.TIRAMISU) {
                activity.setRecentsScreenshotEnabled(!secure)
            } else if (secure) {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }
}
