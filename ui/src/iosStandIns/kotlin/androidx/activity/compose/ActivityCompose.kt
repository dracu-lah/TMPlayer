package androidx.activity.compose

// androidx.activity's Compose entry points, on iOS.

import android.app.Activity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf

/** Back is the shared screens' own back stack on iOS, as on the desktop (com.tmplayer.ui.nav). */
@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) = com.tmplayer.ui.nav.BackHandler(enabled, onBack)

val LocalActivity = staticCompositionLocalOf<Activity?> { androidx.compose.ui.platform.IosAppContext }

/**
 * A launcher for another app's activity. None answers on iOS (a document tree, voice search), so a
 * launch reports what Android reports when the user backs out: the contract's empty result. The
 * notification permission is iOS's to answer ([android.app.IosNotifications]).
 */
@Composable
fun <I, O> rememberLauncherForActivityResult(contract: ActivityResultContract<I, O>, onResult: (O) -> Unit): ManagedActivityResultLauncher<I, O> {
    val current = rememberUpdatedState(onResult)
    return remember(contract) { ManagedActivityResultLauncher(contract) { current.value(it) } }
}

class ManagedActivityResultLauncher<I, O> internal constructor(
    private val contract: ActivityResultContract<I, O>,
    private val deliver: (O) -> Unit,
) : ActivityResultLauncher<I>() {
    override fun launch(input: I) {
        if (contract is androidx.activity.result.contract.ActivityResultContracts.RequestPermission &&
            input == android.Manifest.permission.POST_NOTIFICATIONS
        ) {
            @Suppress("UNCHECKED_CAST")
            android.app.IosNotifications.ask { granted -> deliver(granted as O) }
            return
        }
        deliver(contract.parseResult(Activity.RESULT_CANCELED, null))
    }
    override fun unregister() = Unit
}
