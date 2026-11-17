package com.tmplayer.ios

import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.ComposeUIViewController
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import platform.UIKit.UIViewController
import platform.UIKit.addChildViewController
import platform.UIKit.didMoveToParentViewController

/**
 * Puts a tree of Android Views on screen on iOS (docs/IOS.md, layers 3 and 4): measured
 * to the host's size, laid out, drawn with Skia on a transparent Compose canvas, and given the
 * touches. Drawing again when a View asks for it, laying out again when one changes size.
 *
 * The tree's ComposeViews have their content composed here, over the drawing, at each one's place
 * and size; a touch inside one goes to its content rather than to the Views under it, as on
 * Android, where the ComposeView is the View that takes it.
 */
internal class AndroidViewHost(
    private val root: View,
    /** Where a touch goes first: the tree's root, or an activity, which hands it on as Android's does. */
    private val touches: (MotionEvent) -> Boolean = { root.dispatchTouchEvent(it) },
    /** A trackpad's or mouse's hover and scroll, Android's generic motion; nobody's by default. */
    private val generic: (MotionEvent) -> Boolean = { false },
) : View.Host {
    private var frame by mutableIntStateOf(0)
    private var layoutNeeded = true
    private val overlays = mutableStateListOf<ComposeView>()

    /** The iOS views of the tree's SurfaceViews (the video engine's), under the drawing. */
    private val natives = ArrayList<android.view.SurfaceView>()
    private val nativeLayer = platform.UIKit.UIView()

    override fun invalidateTree() {
        frame++
    }

    /** The safe area last handed to the tree: left, top, right and bottom, in pixels. */
    private var insetsHanded: List<Int>? = null

    /**
     * iOS says the safe area moved: the status bar shown or hidden with the player's row, a turn,
     * an iPad window resized. The next frame hands it to the tree, playing or paused.
     */
    fun safeAreaChanged() {
        frame++
    }

    override fun requestTreeLayout() {
        layoutNeeded = true
        frame++
    }

    override fun overlayAttached(view: View) {
        if (view is ComposeView && view !in overlays) overlays += view
    }

    override fun overlayDetached(view: View) {
        overlays.remove(view)
    }

    override fun nativeAttached(view: android.view.SurfaceView) {
        val native = view.nativeView ?: return
        if (view !in natives) natives += view
        if (native.superview !== nativeLayer) {
            native.removeFromSuperview()
            nativeLayer.addSubview(native)
        }
        frame++
    }

    override fun nativeDetached(view: android.view.SurfaceView) {
        natives.remove(view)
        view.nativeView?.takeIf { it.superview === nativeLayer }?.removeFromSuperview()
    }

    /** Each SurfaceView's iOS view over the SurfaceView's place, in points, as laid out now. */
    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    private fun placeNatives() {
        val density = android.content.res.ResourceFiles.density.toDouble()
        for (view in natives) {
            val native = view.nativeView ?: continue
            // Through the parents' scale and move, so the picture follows the player's zoom.
            val (left, top) = view.mapToWindow(0f, 0f)
            val (right, bottom) = view.mapToWindow(view.width.toFloat(), view.height.toFloat())
            native.setFrame(platform.CoreGraphics.CGRectMake(left / density, top / density, (right - left) / density, (bottom - top) / density))
            native.hidden = !view.isShown
        }
    }

    /**
     * The tree on screen: the SurfaceViews' iOS views at the bottom, the drawing and the
     * ComposeViews over them, both filling the screen.
     */
    @OptIn(ExperimentalComposeUiApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)
    fun controller(): UIViewController {
        val drawing = ComposeUIViewController(configure = { opaque = false }) { Content() }
        val screen = UIViewController()
        val fill = platform.UIKit.UIViewAutoresizingFlexibleWidth or platform.UIKit.UIViewAutoresizingFlexibleHeight
        nativeLayer.setFrame(screen.view.bounds)
        nativeLayer.autoresizingMask = fill
        nativeLayer.clipsToBounds = true
        screen.view.addSubview(nativeLayer)
        screen.addChildViewController(drawing)
        drawing.view.setFrame(screen.view.bounds)
        drawing.view.autoresizingMask = fill
        drawing.view.backgroundColor = platform.UIKit.UIColor.clearColor
        screen.view.addSubview(drawing.view)
        drawing.didMoveToParentViewController(screen)
        return screen
    }

    // The fingers on the screen now, by Compose's id, each with Android's pointer id (the lowest
    // free, as Android hands them out) and where it is, in the order they went down.
    private val pointerIds = LinkedHashMap<androidx.compose.ui.input.pointer.PointerId, Int>()
    private val positions = HashMap<androidx.compose.ui.input.pointer.PointerId, androidx.compose.ui.geometry.Offset>()
    private var gestureToTree = false
    private var gestureCancelled = false
    private var gestureDownTime = 0L

    /** Sends the fingers as they are now, with [actionMasked] about the finger at [actionIndex]. */
    private fun send(actionMasked: Int, actionIndex: Int): Boolean {
        val order = pointerIds.entries.toList()
        val ids = IntArray(order.size) { order[it].value }
        val xs = FloatArray(order.size) { positions[order[it].key]?.x ?: 0f }
        val ys = FloatArray(order.size) { positions[order[it].key]?.y ?: 0f }
        return touches(MotionEvent.touches(actionMasked, actionIndex, ids, xs, ys, gestureDownTime))
    }

    /**
     * The system took the gesture under way (iOS's swipe in from the edge as Back): the tree is
     * told ACTION_CANCEL and hears nothing more of it, as Android's apps do when the system takes
     * a swipe for its Back.
     */
    fun cancelTouches() {
        if (pointerIds.isEmpty() || !gestureToTree || gestureCancelled) return
        gestureCancelled = true
        send(MotionEvent.ACTION_CANCEL, 0)
    }

    /** One Compose pointer event as Android's touches: a finger down, up, or the fingers moving. */
    private fun onPointer(event: androidx.compose.ui.input.pointer.PointerEvent) {
        if (event.type == PointerEventType.Scroll) {
            val change = event.changes.firstOrNull() ?: return
            val delta = change.scrollDelta
            // Android's vertical scroll is positive away from the viewer, Compose's toward.
            val motion = MotionEvent.pointer(MotionEvent.ACTION_SCROLL, change.position.x, change.position.y, vscroll = -delta.y, hscroll = delta.x)
            if (generic(motion)) change.consume()
            return
        }
        if (event.changes.none { it.pressed || it.previousPressed }) {
            // A trackpad's pointer moving with nothing pressed: a hover.
            if (event.type == PointerEventType.Move) {
                event.changes.firstOrNull()?.let { generic(MotionEvent.pointer(MotionEvent.ACTION_HOVER_MOVE, it.position.x, it.position.y)) }
            }
            return
        }
        for (change in event.changes) if (change.pressed || change.id in pointerIds) positions[change.id] = change.position
        var took = false
        var changed = false
        fun live() = gestureToTree && !gestureCancelled
        for (change in event.changes) {
            if (!change.pressed || change.previousPressed || change.id in pointerIds) continue
            changed = true
            if (pointerIds.isEmpty()) {
                // A gesture belongs to whoever took its start: the tree, or a ComposeView.
                gestureToTree = !inOverlay(change.position.x, change.position.y)
                gestureCancelled = false
                gestureDownTime = android.os.SystemClock.uptimeMillis()
            }
            pointerIds[change.id] = generateSequence(0) { it + 1 }.first { it !in pointerIds.values }
            val action = if (pointerIds.size == 1) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_POINTER_DOWN
            if (live()) took = send(action, pointerIds.keys.indexOf(change.id)) || took
        }
        for (change in event.changes) {
            if (change.pressed || change.id !in pointerIds) continue
            changed = true
            val action = if (pointerIds.size == 1) MotionEvent.ACTION_UP else MotionEvent.ACTION_POINTER_UP
            if (live()) took = send(action, pointerIds.keys.indexOf(change.id)) || took
            pointerIds.remove(change.id)
            positions.remove(change.id)
        }
        if (!changed && pointerIds.isNotEmpty() && live()) took = send(MotionEvent.ACTION_MOVE, 0)
        if (took) event.changes.forEach { it.consume() }
    }

    /** Whether a touch at [x], [y] lands in a ComposeView shown now, which takes it. */
    private fun inOverlay(x: Float, y: Float): Boolean = overlays.any { view ->
        if (view.effectiveAlpha() <= 0f) return@any false
        val at = IntArray(2).also(view::getLocationInWindow)
        // The content's place, inside the View's padding, where Android's ComposeView has its child.
        x >= at[0] + view.paddingLeft && y >= at[1] + view.paddingTop &&
            x < at[0] + view.width - view.paddingRight && y < at[1] + view.height - view.paddingBottom
    }

    @Composable
    private fun Content() {
        DisposableEffect(root) {
            root.attachAsRoot(this@AndroidViewHost)
            onDispose { root.detachAsRoot() }
        }
        Box(Modifier.fillMaxSize()) {
            Canvas(
                Modifier.fillMaxSize().pointerInput(root) {
                    awaitPointerEventScope {
                        while (true) onPointer(awaitPointerEvent())
                    }
                },
            ) {
                @Suppress("UNUSED_VARIABLE")
                val asked = frame
                val w = size.width.toInt()
                val h = size.height.toInt()
                // The window's insets reach the tree first, as Android hands them out before a layout
                // pass: on the first pass, when the size changes (a turn), and whenever the safe
                // area moves without one, as when the status bar comes with the player's row. Handed
                // on a size change alone, the row's top bar stayed under the status bar until some
                // later turn (iPad, 2026-11-14), where Android hands the bars' new insets at once.
                val insets = WindowInsetsCompat.ofKeyWindow()
                val sides = insets
                    ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                    ?.let { listOf(it.left, it.top, it.right, it.bottom) }
                val resized = root.width != w || root.height != h
                val moved = sides != null && sides != insetsHanded
                if (layoutNeeded || resized || moved || root.isLayoutRequested) {
                    if (insets != null && (resized || moved)) {
                        ViewCompat.dispatchApplyWindowInsets(root, insets)
                        insetsHanded = sides
                    }
                    layoutNeeded = false
                    root.measure(
                        View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
                    )
                    root.layout(0, 0, w, h)
                }
                placeNatives()
                PerfProbe.drawn++
                drawIntoCanvas { canvas ->
                    val android = android.graphics.Canvas(canvas.nativeCanvas)
                    val save = android.save()
                    if (root.alpha < 1f) android.saveLayerAlpha(0f, 0f, w.toFloat(), h.toFloat(), (root.alpha * 255).toInt())
                    root.draw(android)
                    android.restoreToCount(save)
                }
            }
            for (view in overlays) key(view) { Overlay(view) }
        }
    }

    /**
     * One ComposeView's content at the View's place, inside its padding: measured with the room the
     * View's parent gave it, which the View is told so it can wrap it, and faded with the View and
     * its parents.
     */
    @Composable
    private fun Overlay(view: ComposeView) {
        @Suppress("UNUSED_VARIABLE")
        val asked = frame
        val content = view.content ?: return
        // A hidden View takes no touches and draws nothing, as on Android: its content waits.
        if (view.effectiveAlpha() <= 0f) return
        // Nor is its content drawn before the View has been measured, as on Android: one shown
        // just now (the loader, a panel) would otherwise lay itself out for no room at all.
        if (!view.measured) return
        val at = IntArray(2).also(view::getLocationInWindow)
        Box(
            Modifier
                .offset { IntOffset(at[0] + view.paddingLeft, at[1] + view.paddingTop) }
                .graphicsLayer { alpha = view.effectiveAlpha() }
                .layout { measurable, _ ->
                    val placeable = measurable.measure(view.contentConstraints())
                    view.contentMeasured(placeable.width, placeable.height)
                    val width = maxOf(view.width - view.paddingLeft - view.paddingRight, 0)
                    val height = maxOf(view.height - view.paddingTop - view.paddingBottom, 0)
                    layout(width, height) { placeable.place(0, 0) }
                },
        ) {
            content()
        }
    }
}
