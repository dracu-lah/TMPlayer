package android.view

import android.content.Context
import android.util.AttributeSet

/** Where pixels are drawn from outside the View tree, Android's Surface: on iOS, the engine's view. */
class Surface internal constructor() {
    /** Valid while its SurfaceView is in a window, as Android's while its holder has it. */
    var isValid: Boolean = false
        internal set

    fun release() {
        isValid = false
    }

    /** The frame rate the content wants, Android 11's; on iOS ProMotion follows the content by itself. */
    fun setFrameRate(frameRate: Float, compatibility: Int, changeFrameRateStrategy: Int) = Unit

    fun setFrameRate(frameRate: Float, compatibility: Int) = Unit

    companion object {
        const val FRAME_RATE_COMPATIBILITY_DEFAULT = 0
        const val FRAME_RATE_COMPATIBILITY_FIXED_SOURCE = 1
        const val CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS = 0
        const val CHANGE_FRAME_RATE_ALWAYS = 1
    }
}

/** A SurfaceView's surface and its callbacks, Android's. */
interface SurfaceHolder {
    val surface: Surface

    fun addCallback(callback: Callback)

    fun removeCallback(callback: Callback)

    interface Callback {
        fun surfaceCreated(holder: SurfaceHolder)

        fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int)

        fun surfaceDestroyed(holder: SurfaceHolder)
    }
}

/**
 * A View whose pixels come from elsewhere, Android's SurfaceView. On iOS the video engine draws
 * into its own view, under the View tree (com.tmplayer.ios.AndroidViewHost); this View marks
 * where, and its surface is valid while it is in a window.
 */
open class SurfaceView(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : View(context, attrs, defStyleAttr) {
    constructor(context: Context?) : this(context, null, 0)

    constructor(context: Context?, attrs: AttributeSet?) : this(context, attrs, 0)

    private val ownSurface = Surface()

    // The host it joined, kept: by the time it leaves, its parents may be gone already.
    private var shownBy: Host? = null

    /**
     * How the frame this surface shows is read on iOS, where the video engine draws it and the
     * View tree cannot (the engine's own capture, which PlayerView sets): it hands the frame to
     * its callback, or null when there is none. For [PixelCopy].
     */
    var readFrame: ((done: (android.graphics.Bitmap?) -> Unit) -> Unit)? = null

    /**
     * The iOS view that draws what this surface shows (the video engine's), which the host puts
     * exactly over this View's place, under the tree's drawing, as Android composes a SurfaceView.
     */
    var nativeView: platform.UIKit.UIView? = null
        set(value) {
            if (field === value) return
            shownBy?.nativeDetached(this)
            field = value
            if (isAttachedToWindow && value != null) shownBy = rootView.host?.also { it.nativeAttached(this) }
        }
    private val callbacks = ArrayList<SurfaceHolder.Callback>()

    val holder: SurfaceHolder = object : SurfaceHolder {
        override val surface: Surface get() = ownSurface

        override fun addCallback(callback: SurfaceHolder.Callback) {
            callbacks.add(callback)
        }

        override fun removeCallback(callback: SurfaceHolder.Callback) {
            callbacks.remove(callback)
        }
    }

    fun setZOrderOnTop(onTop: Boolean) = Unit

    /**
     * A hole in the drawing where the surface is, as Android's SurfaceView punches one in its
     * window: the engine's view under the drawing shows through whatever the Views behind drew.
     */
    override fun draw(canvas: android.graphics.Canvas) {
        if (nativeView != null) {
            val save = canvas.save()
            canvas.clipRect(0, 0, width, height)
            canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)
            canvas.restoreToCount(save)
        }
        super.draw(canvas)
    }

    fun setZOrderMediaOverlay(isMediaOverlay: Boolean) = Unit

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (nativeView != null) shownBy = rootView.host?.also { it.nativeAttached(this) }
        ownSurface.isValid = true
        callbacks.toList().forEach { it.surfaceCreated(holder) }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed) callbacks.toList().forEach { it.surfaceChanged(holder, 0, right - left, bottom - top) }
    }

    override fun onDetachedFromWindow() {
        callbacks.toList().forEach { it.surfaceDestroyed(holder) }
        shownBy?.nativeDetached(this)
        shownBy = null
        ownSurface.isValid = false
        super.onDetachedFromWindow()
    }
}

/**
 * Copies what a surface shows into a Bitmap, Android's PixelCopy: on iOS the frame the video
 * engine shows, read through [SurfaceView.readFrame] and scaled into [dest]; a surface with no way
 * to read it, or no frame, answers as Android's does for a surface that had none.
 */
object PixelCopy {
    const val SUCCESS = 0
    const val ERROR_UNKNOWN = 1
    const val ERROR_TIMEOUT = 2
    const val ERROR_SOURCE_NO_DATA = 3
    const val ERROR_SOURCE_INVALID = 4
    const val ERROR_DESTINATION_INVALID = 5

    fun interface OnPixelCopyFinishedListener {
        fun onPixelCopyFinished(copyResult: Int)
    }

    fun request(source: SurfaceView, dest: android.graphics.Bitmap, listener: OnPixelCopyFinishedListener, listenerThread: android.os.Handler) {
        val read = source.readFrame
        if (read == null || !source.holder.surface.isValid) {
            listenerThread.post { listener.onPixelCopyFinished(if (source.holder.surface.isValid) ERROR_SOURCE_NO_DATA else ERROR_SOURCE_INVALID) }
            return
        }
        read { frame ->
            val result = if (frame == null) ERROR_SOURCE_NO_DATA else runCatching { dest.copyFrom(frame) }.fold({ SUCCESS }, { ERROR_UNKNOWN })
            listenerThread.post { listener.onPixelCopyFinished(result) }
        }
    }
}
