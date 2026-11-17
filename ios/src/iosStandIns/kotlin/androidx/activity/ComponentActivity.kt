package androidx.activity

// androidx.activity's ComponentActivity on iOS, for MainActivity as upstream wrote it. The iOS shell
// (com.tmplayer.ios.ActivityShell) creates the one activity, moves it through Android's lifecycle
// as the app comes and goes, and draws what setContent handed it in a ComposeUIViewController.

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

open class ComponentActivity : android.app.Activity(), LifecycleOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    override val viewModelStore: ViewModelStore = ViewModelStore()

    /**
     * Back, androidx's: with no callback of the activity's own it finishes the activity, as on
     * Android; the shell points the app's screens at their back stack instead.
     */
    val onBackPressedDispatcher = OnBackPressedDispatcher().also { it.onBack = { finish() } }

    open fun onBackPressed() = onBackPressedDispatcher.onBackPressed()

    /** What [androidx.activity.compose.setContent] handed over, for the shell to draw. */
    var content: (@Composable () -> Unit)? = null
        internal set

    /**
     * An activity result's launcher, Android's. Opening a document (OpenDocument, GetContent) is
     * iOS's Files picker (com.tmplayer.ios.DocumentPicker); the other contracts have no picker on
     * iOS yet and answer after the current work with their "nothing chosen", as Android's do when
     * the viewer backs out.
     */
    fun <I, O> registerForActivityResult(
        contract: androidx.activity.result.contract.ActivityResultContract<I, O>,
        callback: androidx.activity.result.ActivityResultCallback<O>,
    ): androidx.activity.result.ActivityResultLauncher<I> = object : androidx.activity.result.ActivityResultLauncher<I>() {
        private var registered = true

        override fun launch(input: I) {
            val types: Array<String>? = when (contract) {
                is androidx.activity.result.contract.ActivityResultContracts.OpenDocument -> (input as? Array<*>)?.map { it.toString() }?.toTypedArray()
                is androidx.activity.result.contract.ActivityResultContracts.GetContent -> arrayOf(input?.toString() ?: "*/*")
                else -> null
            }
            if (types != null) {
                com.tmplayer.ios.DocumentPicker.open(types) { uri ->
                    @Suppress("UNCHECKED_CAST")
                    if (registered) callback.onActivityResult(uri as O)
                }
                return
            }
            android.os.Handler().post {
                if (registered) callback.onActivityResult(contract.parseResult(android.app.Activity.RESULT_CANCELED, null))
            }
        }

        override fun unregister() {
            registered = false
        }
    }

    open fun attachBaseContext(newBase: Context) = Unit
    open fun applyOverrideConfiguration(overrideConfiguration: Configuration) = Unit

    open fun onCreate(savedInstanceState: Bundle?) {
        registry.currentState = Lifecycle.State.CREATED
    }

    open fun onStart() {
        registry.currentState = Lifecycle.State.STARTED
    }

    open fun onResume() {
        registry.currentState = Lifecycle.State.RESUMED
    }

    open fun onPause() {
        registry.currentState = Lifecycle.State.STARTED
    }

    open fun onStop() {
        registry.currentState = Lifecycle.State.CREATED
    }

    open fun onDestroy() {
        registry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }

    open fun onNewIntent(intent: Intent) = Unit

    /** Android's window focus: an iOS app's one window is not told, so this is never called. */
    open fun onWindowFocusChanged(hasFocus: Boolean) = Unit
    open fun onUserInteraction() = Unit
    /**
     * A key, as Android's Activity hands it on: [onUserInteraction], then [onKeyDown] or
     * [onKeyUp], and Back's release with nothing else taking it is [onBackPressed].
     */
    open fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        onUserInteraction()
        val down = event.action == android.view.KeyEvent.ACTION_DOWN
        if (if (down) onKeyDown(event.keyCode, event) else onKeyUp(event.keyCode, event)) return true
        if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            if (!down && !event.isCanceled) onBackPressed()
            return true
        }
        return false
    }
    /**
     * A touch, as Android's Activity hands it on: the start of one tells [onUserInteraction], then
     * the content's Views have it, and [onTouchEvent] gets what none of them took.
     */
    open fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) onUserInteraction()
        if (contentFrame?.dispatchTouchEvent(event) == true) return true
        return onTouchEvent(event)
    }

    open fun onTouchEvent(event: android.view.MotionEvent): Boolean = false
    open fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean = false
    open fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean = false
}

/** Android's dispatcher of Back presses; on iOS [onBack] is whatever the shell gives it. */
/**
 * Back, androidx's: the newest enabled callback handles it; with none, [onBack], which the shell
 * points at what Back does with nothing to close (the screens' back stack, or leaving the player).
 */
class OnBackPressedDispatcher {
    var onBack: () -> Unit = {}

    private val callbacks = ArrayList<OnBackPressedCallback>()

    fun addCallback(callback: OnBackPressedCallback) {
        callbacks.add(callback)
        callback.dispatcher = this
    }

    fun addCallback(owner: androidx.lifecycle.LifecycleOwner, callback: OnBackPressedCallback) = addCallback(callback)

    internal fun remove(callback: OnBackPressedCallback) {
        callbacks.remove(callback)
    }

    fun hasEnabledCallbacks(): Boolean = callbacks.any { it.isEnabled }

    fun onBackPressed() {
        val handler = callbacks.lastOrNull { it.isEnabled }
        if (handler != null) handler.handleOnBackPressed() else onBack()
    }
}

/** What Back does while enabled, androidx's. */
abstract class OnBackPressedCallback(enabled: Boolean) {
    var isEnabled: Boolean = enabled

    internal var dispatcher: OnBackPressedDispatcher? = null

    abstract fun handleOnBackPressed()

    fun remove() {
        dispatcher?.remove(this)
        dispatcher = null
    }
}

/** androidx's KTX: a callback from a lambda. */
fun OnBackPressedDispatcher.addCallback(
    owner: androidx.lifecycle.LifecycleOwner? = null,
    enabled: Boolean = true,
    onBackPressed: OnBackPressedCallback.() -> Unit,
): OnBackPressedCallback {
    val callback = object : OnBackPressedCallback(enabled) {
        override fun handleOnBackPressed() = onBackPressed()
    }
    addCallback(callback)
    return callback
}

/** Edge to edge is how a ComposeUIViewController always draws: under the bars, with insets. */
@Suppress("UNUSED_PARAMETER")
fun ComponentActivity.enableEdgeToEdge(statusBarStyle: Any? = null, navigationBarStyle: Any? = null) = Unit
