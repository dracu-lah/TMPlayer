@file:OptIn(kotlinx.cinterop.BetaInteropApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package com.tmplayer.ios

import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.useContents
import platform.Foundation.NSSelectorFromString
import platform.UIKit.UIApplication
import platform.UIKit.UIGestureRecognizer
import platform.UIKit.UIGestureRecognizerDelegateProtocol
import platform.UIKit.UIGestureRecognizerStateBegan
import platform.UIKit.UIGestureRecognizerStateEnded
import platform.UIKit.UIRectEdgeLeft
import platform.UIKit.UIRectEdgeRight
import platform.UIKit.UIScreenEdgePanGestureRecognizer
import platform.UIKit.UITouch
import platform.UIKit.UIUserInterfaceLayoutDirection
import platform.UIKit.UIWindow
import platform.darwin.NSObject

/**
 * iOS's swipe from the leading edge as Android's Back, for every screen: the pages, and the dialogs
 * over them, whose own layer Compose's back gesture does not reach. Recognised on the window, so it
 * sees the dialogs too, and handed to [onBack] (the screens' back stack) once the swipe ends far
 * enough in. The shell turns it off while the player covers the screens.
 */
internal class SystemBack(private val onBack: () -> Unit) : NSObject(), UIGestureRecognizerDelegateProtocol {
    private var window: UIWindow? = null
    var enabled = true

    /** Whether a swipe starting at this point (window pixels) is the screen's own, not Back. */
    var excluded: (Float, Float) -> Boolean = { _, _ -> false }

    /** The swipe was recognised as the system's: the screen under it lets go of the touch. */
    var began: () -> Unit = {}

    private val rightToLeft: Boolean
        get() = UIApplication.sharedApplication.userInterfaceLayoutDirection ==
            UIUserInterfaceLayoutDirection.UIUserInterfaceLayoutDirectionRightToLeft

    /** Puts the recogniser on [target] once; the window is known only after the screens are shown. */
    fun attach(target: UIWindow?) {
        if (target == null || target == window) return
        window = target
        val recognizer = UIScreenEdgePanGestureRecognizer(target = this, action = NSSelectorFromString("swiped:"))
        recognizer.edges = if (rightToLeft) UIRectEdgeRight else UIRectEdgeLeft
        recognizer.delegate = this
        target.addGestureRecognizer(recognizer)
    }

    /** Alongside Compose's own recognisers, which would otherwise keep the swipe to themselves. */
    override fun gestureRecognizer(
        gestureRecognizer: UIGestureRecognizer,
        shouldRecognizeSimultaneouslyWithGestureRecognizer: UIGestureRecognizer,
    ): Boolean = true

    /** A touch starting where the screen keeps swipes for itself never becomes Back, as on Android. */
    override fun gestureRecognizer(gestureRecognizer: UIGestureRecognizer, shouldReceiveTouch: UITouch): Boolean {
        val density = android.content.res.ResourceFiles.density
        return shouldReceiveTouch.locationInView(window).useContents { !excluded((x * density).toFloat(), (y * density).toFloat()) }
    }

    @ObjCAction
    fun swiped(recognizer: UIScreenEdgePanGestureRecognizer) {
        if (enabled && recognizer.state == UIGestureRecognizerStateBegan) began()
        if (!enabled || recognizer.state != UIGestureRecognizerStateEnded) return
        val moved = recognizer.translationInView(recognizer.view).useContents { x }
        val inward = if (rightToLeft) -moved else moved
        if (inward >= MIN_DISTANCE) onBack()
    }
}

/** Points the finger travels in before a swipe counts as Back, as far as iOS's own. */
private const val MIN_DISTANCE = 60.0
