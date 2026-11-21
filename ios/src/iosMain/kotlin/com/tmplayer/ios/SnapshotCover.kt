package com.tmplayer.ios

import com.tmplayer.data.Accounts
import com.tmplayer.data.OutsideWrites
import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIApplication
import platform.UIKit.UIColor
import platform.UIKit.UIView
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UIKit.systemBackgroundColor

/**
 * The app switcher's picture of the window (HX88, invariant 6): iOS takes it as the app goes to
 * the background, and the switcher's live card shows the app as soon as it is only inactive. While
 * the open profile has a PIN a plain cover goes over every window of every scene (an iPad can show
 * several) as the app resigns active, so neither shows anything of that profile, and it comes off
 * once the app is active again (the
 * profile's own PIN pad is then the screens' to ask, ProfileUnlock). A profile without a PIN is
 * left as it always was, as Android's recents rule ([OutsideWrites.secureWindow]). Screenshots and
 * screen recording while the app is open are not blocked: iOS gives an app no way to.
 */
object SnapshotCover {
    /** The covers up now, one per window ([WindowCovers]). Main thread only. */
    @OptIn(ExperimentalForeignApi::class)
    private val views = com.tmplayer.data.WindowCovers<UIWindow, UIView>(
        make = { window ->
            val view = UIView(frame = window.bounds)
            view.backgroundColor = UIColor.systemBackgroundColor
            view.autoresizingMask = UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
            window.addSubview(view)
            view
        },
        remove = { it.removeFromSuperview() },
    )

    /** Whether the window is covered on leaving: only while the open profile has a PIN. */
    fun covers(openHasPin: Boolean): Boolean = OutsideWrites.secureWindow(openHasPin)

    private fun openHasPin(): Boolean = Accounts.book?.list?.value?.active?.pin != null

    /** Every window of the app's connected scenes: an iPad's split view and Stage Manager show several. */
    fun appWindows(): List<UIWindow> =
        UIApplication.sharedApplication.connectedScenes
            .mapNotNull { it as? UIWindowScene }
            .flatMap { scene -> scene.windows.mapNotNull { it as? UIWindow } }

    /**
     * The app is leaving (inactive, then the background): a cover over each of [windows] that has
     * none yet, when the rule asks for it. A window that came since the last call (a second iPad
     * window or scene, an external display) is covered too; one that went away is forgotten.
     */
    fun cover(windows: List<UIWindow>) {
        if (!covers(openHasPin())) return
        views.cover(windows)
    }

    /** Active again: every cover comes off. */
    fun uncover() = views.uncover()
}
