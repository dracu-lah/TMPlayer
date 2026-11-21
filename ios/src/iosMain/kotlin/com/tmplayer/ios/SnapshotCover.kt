package com.tmplayer.ios

import com.tmplayer.data.Accounts
import com.tmplayer.data.OutsideWrites
import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIColor
import platform.UIKit.UIView
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth
import platform.UIKit.UIWindow
import platform.UIKit.systemBackgroundColor

/**
 * The app switcher's picture of the window (HX88, invariant 6): iOS takes it as the app goes to
 * the background. While the open profile has a PIN a plain cover goes over the window first, so
 * the picture shows nothing of that profile, and it comes off once the app is active again (the
 * profile's own PIN pad is then the screens' to ask, ProfileUnlock). A profile without a PIN is
 * left as it always was, as Android's recents rule ([OutsideWrites.secureWindow]). Screenshots and
 * screen recording while the app is open are not blocked: iOS gives an app no way to.
 */
object SnapshotCover {
    private var cover: UIView? = null

    /** Whether the window is covered on leaving: only while the open profile has a PIN. */
    fun covers(openHasPin: Boolean): Boolean = OutsideWrites.secureWindow(openHasPin)

    private fun openHasPin(): Boolean = Accounts.book?.list?.value?.active?.pin != null

    /** The app is going to the background: the cover over [window], when the rule asks for it. */
    @OptIn(ExperimentalForeignApi::class)
    fun cover(window: UIWindow?) {
        if (window == null || cover != null || !covers(openHasPin())) return
        val view = UIView(frame = window.bounds)
        view.backgroundColor = UIColor.systemBackgroundColor
        view.autoresizingMask = UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
        window.addSubview(view)
        cover = view
    }

    /** Active again: the cover comes off. */
    fun uncover() {
        cover?.removeFromSuperview()
        cover = null
    }
}
