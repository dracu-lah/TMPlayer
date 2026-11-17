package com.tmplayer.ios

import android.content.pm.ActivityInfo
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationState
import platform.UIKit.UIInterfaceOrientationLandscapeLeft
import platform.UIKit.UIInterfaceOrientationLandscapeRight
import platform.UIKit.UIInterfaceOrientationMaskAll
import platform.UIKit.UIInterfaceOrientationMaskLandscape
import platform.UIKit.UIInterfaceOrientationMaskLandscapeLeft
import platform.UIKit.UIInterfaceOrientationMaskLandscapeRight
import platform.UIKit.UIInterfaceOrientationMaskPortrait
import platform.UIKit.UIInterfaceOrientationMaskPortraitUpsideDown
import platform.UIKit.UIInterfaceOrientationPortraitUpsideDown
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth
import platform.UIKit.UIViewController
import platform.UIKit.addChildViewController
import platform.UIKit.didMoveToParentViewController
import platform.UIKit.removeFromParentViewController
import platform.UIKit.willMoveToParentViewController

/**
 * Android activities built from Views (PlayerActivity) on screen as iOS shows one: Swift presents
 * this full screen over the app's screens (ActivityViewController.swift) and tells it when it is
 * up and when it has gone. Inside, the activities stack as Android stacks them: one started from
 * another (the next episode from another chat) goes on top and the one under it stops; the top
 * one finishing brings the one under it back; the last one finishing closes the screen. Each one's
 * lifecycle follows, and the orientation the top one asks for is the screen's.
 */
class ActivityScreen internal constructor(
    first: ActivityRun,
    private val whenShown: () -> Unit,
    private val whenClosed: () -> Unit,
) {
    private val stack = ArrayList<ActivityRun>()
    private val hosts = HashMap<ActivityRun, UIViewController>()
    private val inputs = HashMap<ActivityRun, AndroidViewHost>()

    /** The keyboard's keys, for whichever activity is in front. */
    private val keys = KeyInput { top.activity.dispatchKeyEvent(it) }
    private val container = UIViewController()
    private var shown = false
    private var closed = false

    /** Swift's: takes the screen away, animated, then calls [closed]. */
    var onFinish: () -> Unit = {}

    /** Swift's: the orientations changed, to apply with [orientations]. */
    var onOrientation: () -> Unit = {}

    /** Swift's: the top activity hid or showed Android's system bars, to apply with [systemBarsHidden]. */
    var onSystemBars: () -> Unit = {}

    /** Whether the top activity hides Android's system bars: iOS's status bar hidden, the home indicator hiding itself. */
    val systemBarsHidden: Boolean get() = top.activity.window.systemBarsHidden

    /** The activity in front. */
    internal val top: ActivityRun get() = stack.last()

    init {
        add(first)
    }

    /** The activities' Views, the top one's in front, each drawn and touched through its Activity. */
    fun controller(): UIViewController = container

    /**
     * [run], already created, over the one in front, as Android starts an activity from another:
     * the one in front pauses, the new one shows, starts and resumes, then the one under it stops.
     */
    internal fun push(run: ActivityRun) {
        if (closed) {
            run.destroy()
            return
        }
        val under = top
        under.pause()
        add(run)
        if (shown) {
            bringUp(run)
            under.stop()
        }
        onOrientation()
        onSystemBars()
    }

    private fun add(run: ActivityRun) {
        stack += run
        val activity = run.activity
        activity.shellFinish = { finished(run) }
        activity.shellOrientation = { if (run === top) onOrientation() }
        activity.window.onSystemBars = { if (run === top) onSystemBars() }
        mount(run)
    }

    /**
     * An activity finished: Android pauses it at once; the one under it, if any, comes back, and
     * then it ends. The last one leaves with the screen, which Swift takes away.
     */
    private fun finished(run: ActivityRun) {
        if (run !in stack) return
        run.pause()
        if (run !== top) {
            // Finished from under another, as the player that handed over to the next episode.
            end(run)
            return
        }
        if (stack.size == 1) {
            onFinish()
            return
        }
        stack.remove(run)
        bringUp(top)
        end(run)
        onOrientation()
        onSystemBars()
    }

    private fun bringUp(run: ActivityRun) {
        run.start()
        if (UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive) run.resume()
    }

    private fun end(run: ActivityRun) {
        stack.remove(run)
        run.destroy()
        unmount(run)
        run.activity.shellFinish = null
        run.activity.shellOrientation = null
    }

    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    private fun mount(run: ActivityRun) {
        val activity = run.activity
        val frame = activity.contentFrame ?: android.widget.FrameLayout(activity)
        val host = AndroidViewHost(
            root = frame,
            touches = { activity.dispatchTouchEvent(it) },
            generic = { activity.dispatchGenericMotionEvent(it) },
        )
        inputs[run] = host
        val child = host.controller()
        container.addChildViewController(child)
        child.view.setFrame(container.view.bounds)
        child.view.autoresizingMask = UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
        container.view.addSubview(child.view)
        child.didMoveToParentViewController(container)
        hosts[run] = child
    }

    private fun unmount(run: ActivityRun) {
        inputs.remove(run)
        val child = hosts.remove(run) ?: return
        child.willMoveToParentViewController(null)
        child.view.removeFromSuperview()
        child.removeFromParentViewController()
    }

    /**
     * The orientations the top activity asks for, as iOS's UIInterfaceOrientationMask: Android's
     * requested orientation, the sensor's choices among them; locked is the way the screen is now.
     */
    val orientations: ULong
        get() = when (top.activity.requestedOrientation) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> UIInterfaceOrientationMaskLandscapeRight
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE -> UIInterfaceOrientationMaskLandscapeLeft
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE -> UIInterfaceOrientationMaskLandscape
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> UIInterfaceOrientationMaskPortrait
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT -> UIInterfaceOrientationMaskPortraitUpsideDown
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT -> UIInterfaceOrientationMaskPortrait or UIInterfaceOrientationMaskPortraitUpsideDown
            ActivityInfo.SCREEN_ORIENTATION_LOCKED -> current()
            else -> UIInterfaceOrientationMaskAll
        }

    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    private fun current(): ULong = when (container.view.window?.windowScene?.interfaceOrientation) {
        UIInterfaceOrientationLandscapeLeft -> UIInterfaceOrientationMaskLandscapeLeft
        UIInterfaceOrientationLandscapeRight -> UIInterfaceOrientationMaskLandscapeRight
        UIInterfaceOrientationPortraitUpsideDown -> UIInterfaceOrientationMaskPortraitUpsideDown
        null -> UIInterfaceOrientationMaskAll
        else -> UIInterfaceOrientationMaskPortrait
    }

    /** Swift's, when iOS moves the screen's safe area: each activity's views get it at once. */
    fun safeAreaChanged() {
        inputs.values.forEach { it.safeAreaChanged() }
    }

    /** Swift's, once the screen is up: the top activity starts and resumes, the screens under it stop. */
    fun shown() {
        if (shown || closed) return
        shown = true
        bringUp(top)
        whenShown()
    }

    /** Swift's, once the screen has gone: the screens come back, then the activities end. */
    fun closed() {
        if (closed) return
        closed = true
        whenClosed()
        keys.releaseAll()
        stack.toList().asReversed().forEach(::end)
        onFinish = {}
        onOrientation = {}
        onSystemBars = {}
    }

    /** Back, from iOS's swipe in from the edge: the top activity's own Back. */
    internal fun back() = top.activity.onBackPressedDispatcher.onBackPressed()

    /**
     * Swift's, for a hardware keyboard's press or release ([usage] is the key's HID usage). The
     * screen in front takes every key, as an Android activity in front does; true when taken.
     */
    fun keyDown(usage: Long): Boolean = keys.down(usage)

    fun keyUp(usage: Long): Boolean = keys.up(usage)

    /** iOS took the swipe under way as Back: the top activity's touch is cancelled, as Android's is. */
    internal fun cancelTouches() {
        inputs[top]?.cancelTouches()
    }

    /**
     * Whether a swipe starting at [x], [y] (window pixels) is the top activity's own, not Back:
     * inside a shown View's system gesture exclusion rects, as Android leaves those to the app.
     */
    internal fun excludes(x: Float, y: Float): Boolean {
        val frame = top.activity.contentFrame ?: return false
        fun walk(view: android.view.View): Boolean {
            if (view.visibility != android.view.View.VISIBLE) return false
            if (view.systemGestureExclusionRects.isNotEmpty()) {
                val at = IntArray(2).also(view::getLocationInWindow)
                if (view.systemGestureExclusionRects.any { x >= at[0] + it.left && x < at[0] + it.right && y >= at[1] + it.top && y < at[1] + it.bottom }) return true
            }
            val group = view as? android.view.ViewGroup ?: return false
            for (i in 0 until group.childCount) if (walk(group.getChildAt(i))) return true
            return false
        }
        return walk(frame)
    }
}
