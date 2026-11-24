package android.app

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent

/** The iOS app's one screen, as the Activity Android's screens may look for. */
open class Activity : ContextWrapper(null) {
    /** The activity's own window, as on Android: what it keeps on or sets goes with it. */
    open val window: android.view.Window by lazy { android.view.Window.forActivity() }
    private var current = Intent()

    /** Java's getIntent/setIntent pair, as Kotlin reads it. */
    open var intent: Intent
        get() = current
        set(value) {
            current = value
        }

    open fun setIntent(newIntent: Intent) {
        current = newIntent
    }

    /** The only screen of its task on iOS, the app's player being a screen of its own. */
    open val isTaskRoot: Boolean get() = true

    /** A pointer's or controller's motion that is not a touch, Android's; iOS hands none in yet. */
    open fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean = false

    /** The viewer leaving for the home screen, Android's; picture in picture listens for it. */
    open fun onUserLeaveHint() {}

    open fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {}

    open fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {}

    /** The window's size, orientation or fold changed and the activity handles it itself, Android's; the iOS shell does not call it. */
    open fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}

    /** True while the window is shared with another app, Android's; iOS has no such mode. */
    open val isInMultiWindowMode: Boolean get() = false

    /**
     * Picture in picture on iOS is Apple's player's (WP-31, [IosExternalPlayback]): the request that
     * keeps the system's automatic entry in step with playback goes to the engine, and this
     * activity hears when the corner window opens or closes.
     */
    open fun setPictureInPictureParams(params: PictureInPictureParams) {
        watchPictureInPicture()
        IosExternalPlayback.controls?.setAutoPictureInPicture(params.autoEnter)
    }

    private fun watchPictureInPicture() {
        IosExternalPlayback.onPictureInPictureChanged = { active ->
            onPictureInPictureModeChanged(active, resources.configuration)
        }
    }

    /**
     * Android 13's switch for the recents picture (HX88): nothing here; iOS's app switcher picture
     * of a PIN profile is covered by the shell instead (ios, SnapshotCover).
     */
    open fun setRecentsScreenshotEnabled(enabled: Boolean) {}

    /**
     * For the iOS shell (com.tmplayer.ios.ActivityShell): told when the activity asks to finish
     * and when it asks for another orientation. An activity the shell cannot close (the app's own
     * screens, as iOS has no going back past them) has no finish handler, and finish does nothing.
     */
    var shellFinish: (() -> Unit)? = null
    var shellOrientation: (() -> Unit)? = null

    /** The orientation the activity asks for, Android's; the shell turns the screen to it. */
    open var requestedOrientation: Int = -1
        set(value) {
            if (field == value) return
            field = value
            shellOrientation?.invoke()
        }
    open val isChangingConfigurations: Boolean get() = false

    private var finishing = false
    open val isFinishing: Boolean get() = finishing
    open val isInPictureInPictureMode: Boolean get() = IosExternalPlayback.pictureInPictureActive
    /** Closes the activity, as Android's: the shell takes it off the screen and ends its lifecycle. */
    open fun finish() {
        val close = shellFinish ?: return
        if (finishing) return
        finishing = true
        IosExternalPlayback.onPictureInPictureChanged = null
        close()
    }
    open fun recreate() = Unit
    open fun moveTaskToBack(nonRoot: Boolean): Boolean = false
    open fun startActivityForResult(intent: Intent, requestCode: Int) = startActivity(intent)
    /** Only notifications are asked on iOS, of iOS; nothing waits on the answer, as upstream's callers do not. */
    open fun requestPermissions(permissions: Array<String>, requestCode: Int) {
        if (android.Manifest.permission.POST_NOTIFICATIONS in permissions) IosNotifications.ask { }
    }
    open fun shouldShowRequestPermissionRationale(permission: String): Boolean = false
    open fun enterPictureInPictureMode(params: Any?): Boolean {
        watchPictureInPicture()
        return IosExternalPlayback.startPictureInPicture()
    }
    open fun runOnUiThread(action: java.lang.Runnable) = android.os.Handler().post(action).let { }
    override val applicationContext: Context get() = this

    /**
     * The activity's own Views, Android's: setContentView puts them in a content frame (id
     * android.R.id.content), which the shell shows (com.tmplayer.ios.AndroidViewHost).
     */
    var contentFrame: android.widget.FrameLayout? = null
        private set

    private fun frame(): android.widget.FrameLayout = contentFrame ?: android.widget.FrameLayout(this).also {
        it.id = android.R.id.content
        contentFrame = it
    }

    open fun setContentView(layoutResID: Int) {
        val frame = frame()
        frame.removeAllViews()
        android.view.LayoutInflater.from(this).inflate(layoutResID, frame, true)
    }

    open fun setContentView(view: android.view.View) {
        val frame = frame()
        frame.removeAllViews()
        frame.addView(view, android.widget.FrameLayout.LayoutParams(-1, -1))
    }

    /** A View of the content by its id, as Java hands it to Kotlin: null passed through when it is not there. */
    fun <T : android.view.View> findViewById(id: Int): T {
        @Suppress("UNCHECKED_CAST")
        return contentFrame?.findViewById<android.view.View>(id) as T
    }

    companion object {
        const val RESULT_OK = -1
        const val RESULT_CANCELED = 0
    }
}

/**
 * The process, as Android's Application. iOS has one: the shell creates it once at launch and
 * reports its one activity's starts and stops to the callbacks, as Android does.
 */
open class Application : ContextWrapper(null) {
    private val callbacks = ArrayList<ActivityLifecycleCallbacks>()

    private val componentCallbacks = ArrayList<android.content.ComponentCallbacks>()

    open fun onCreate() = Unit

    /** Tells whoever registered a [android.content.ComponentCallbacks2], as Android's Application does. */
    open fun onTrimMemory(level: Int) {
        componentCallbacks.toList().forEach { (it as? android.content.ComponentCallbacks2)?.onTrimMemory(level) }
    }

    override fun registerComponentCallbacks(callback: android.content.ComponentCallbacks) {
        componentCallbacks += callback
    }

    override fun unregisterComponentCallbacks(callback: android.content.ComponentCallbacks) {
        componentCallbacks -= callback
    }

    override val applicationContext: Context get() = this

    fun registerActivityLifecycleCallbacks(callback: ActivityLifecycleCallbacks) {
        callbacks += callback
    }

    fun unregisterActivityLifecycleCallbacks(callback: ActivityLifecycleCallbacks) {
        callbacks -= callback
    }

    /** For the shell: tells every callback what happened to [activity]. */
    fun dispatchActivity(activity: Activity, event: (ActivityLifecycleCallbacks) -> Unit) {
        callbacks.toList().forEach(event)
    }

    interface ActivityLifecycleCallbacks {
        fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?)
        fun onActivityStarted(activity: Activity)
        fun onActivityResumed(activity: Activity)
        fun onActivityPaused(activity: Activity)
        fun onActivityStopped(activity: Activity)
        fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle)
        fun onActivityDestroyed(activity: Activity)
    }

    companion object {
        const val TRIM_MEMORY_RUNNING_MODERATE = 5
        const val TRIM_MEMORY_RUNNING_LOW = 10
        const val TRIM_MEMORY_RUNNING_CRITICAL = 15
        const val TRIM_MEMORY_UI_HIDDEN = 20
        const val TRIM_MEMORY_BACKGROUND = 40
        const val TRIM_MEMORY_COMPLETE = 80
    }
}

/**
 * Android's per-app memory budget in megabytes. iOS gives no such number; a sixteenth of the
 * device's memory, between 128 and 512, is what Android's own classes come to on like devices.
 */
class ActivityManager internal constructor() {
    val memoryClass: Int get() = (platform.Foundation.NSProcessInfo.processInfo.physicalMemory / (16uL * 1024uL * 1024uL)).toInt().coerceIn(128, 512)
    val largeMemoryClass: Int get() = memoryClass * 2
    val isLowRamDevice: Boolean get() = platform.Foundation.NSProcessInfo.processInfo.physicalMemory < 3uL * 1024uL * 1024uL * 1024uL

    /** What memory the device has, Android's: iOS tells the device's total. */
    class MemoryInfo {
        var availMem: Long = 0L
        var totalMem: Long = 0L
        var threshold: Long = 0L
        var lowMemory: Boolean = false
    }

    fun getMemoryInfo(outInfo: MemoryInfo) {
        outInfo.totalMem = platform.Foundation.NSProcessInfo.processInfo.physicalMemory.toLong()
        // iOS tells an app no such number outside its own memory probe: not known reads as none.
        outInfo.availMem = 0L
        outInfo.threshold = 0L
        outInfo.lowMemory = false
    }

    /** How earlier runs of the app ended, Android 11's: iOS keeps no such list for an app, so it is empty. */
    @Suppress("UNUSED_PARAMETER")
    fun getHistoricalProcessExitReasons(packageName: String?, pid: Int, maxNum: Int): List<ApplicationExitInfo> = emptyList()

    companion object {
        internal val Shared = ActivityManager()
    }
}

class PendingIntent private constructor() {
    companion object {
        /** A broadcast to the app's own receiver: iOS has none, so the object only stands for the Android one. */
        @Suppress("UNUSED_PARAMETER")
        fun getBroadcast(context: android.content.Context, requestCode: Int, intent: android.content.Intent, flags: Int): PendingIntent =
            PendingIntent()

        const val FLAG_IMMUTABLE = 0x04000000
        const val FLAG_UPDATE_CURRENT = 0x08000000
        const val FLAG_CANCEL_CURRENT = 0x10000000
    }
}

class LocaleManager

/** A button in the picture in picture window (Android 8 to 10); iOS shows none, so it only carries what it was built with. */
class RemoteAction(
    val icon: android.graphics.drawable.Icon,
    val title: CharSequence,
    val contentDescription: CharSequence,
    val actionIntent: PendingIntent,
)

/** Picture in picture's settings, Android's; only the automatic entry reaches iOS's player (WP-31). */
class PictureInPictureParams private constructor(internal val autoEnter: Boolean) {
    class Builder {
        private var autoEnter = false

        fun setAspectRatio(aspectRatio: android.util.Rational?): Builder = this

        fun setAutoEnterEnabled(autoEnterEnabled: Boolean): Builder {
            autoEnter = autoEnterEnabled
            return this
        }

        fun setSeamlessResizeEnabled(seamlessResizeEnabled: Boolean): Builder = this

        fun setSourceRectHint(launchBounds: android.graphics.Rect?): Builder = this

        fun setActions(actions: List<Any>): Builder = this

        fun setTitle(title: CharSequence?): Builder = this

        fun build(): PictureInPictureParams = PictureInPictureParams(autoEnter)
    }
}
