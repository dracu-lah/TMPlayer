package com.tmplayer.player

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.tmplayer.data.MediaItem
import com.tmplayer.data.SettingsStore
import com.tmplayer.i18n.L
import com.tmplayer.ui.components.showMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// The default player and "Open in another app" on iOS, in place of upstream's InstalledPlayers.kt,
// ExternalPlayRouter.kt, ExternalChooserSheet.kt and ExternalStreams.kt: those find the video apps
// an Android device has (queryIntentActivities), hand them a content or loopback address and run a
// foreground service for the stream. iOS has no such list and a sideloaded app cannot hand a
// Telegram video to another one, so every press plays in TMPlayer's own player. The names are
// upstream's, so Settings, the main screen and the video menu compile unchanged.

/** The video players installed on this device: none on iOS, so Settings and the chooser have nothing to list. */
object InstalledPlayers {
    /** An app with the icon it shows in the chooser; never made on iOS. */
    class Found(val app: ExternalApp, val icon: Drawable?)

    @Suppress("UNUSED_PARAMETER")
    suspend fun find(context: Context): List<Found> = emptyList()
}

/**
 * Where a press on a video can go to another app: nowhere on iOS. [take] says false, so the
 * press carries on to TMPlayer's own player, and the viewer's own "Open in another app" says why.
 */
object ExternalPlayRouter {

    /** What the chooser is for; the chooser is never opened on iOS, the type is upstream's. */
    data class Chooser(
        val item: MediaItem,
        val chatTitle: String,
        val apps: List<InstalledPlayers.Found>,
        val gone: PlayerChoice.App?,
        val forced: Boolean,
    )

    /** The app that closed right away, for the offer to play the video in TMPlayer; never set on iOS. */
    data class Closed(val item: MediaItem, val chatTitle: String, val appLabel: String)

    private val _chooser = MutableStateFlow<Chooser?>(null)
    val chooser: StateFlow<Chooser?> = _chooser

    private val _closed = MutableStateFlow<Closed?>(null)
    val closed: StateFlow<Closed?> = _closed

    /** Set by [ExternalPlayHost]: starts an intent for a result (no other app is ever started here), and plays a video in TMPlayer's own player. */
    @Volatile
    var launcher: ((Intent) -> Unit)? = null

    @Volatile
    var playBuiltIn: ((MediaItem, String) -> Unit)? = null

    /** TMPlayer's player is always the one used, so there is nothing to skip once. */
    @Suppress("UNUSED_PARAMETER")
    fun bypassNext(item: MediaItem) = Unit

    /** Plays [item] in TMPlayer's own player. */
    fun playHere(item: MediaItem, chatTitle: String) {
        playBuiltIn?.invoke(item, chatTitle)
    }

    /** False: carry on in TMPlayer's player. A [forceAsk] press (the menu's "Open in another app") is told there is no app to open. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun take(
        context: Context,
        settings: SettingsStore,
        item: MediaItem,
        chatTitle: String,
        forceAsk: Boolean = false,
    ): Boolean {
        if (forceAsk) showMessage(context, L.externalNoApps)
        return false
    }

    @Suppress("UNUSED_PARAMETER")
    fun chosen(context: Context, settings: SettingsStore, request: Chooser, choice: PlayerChoice, always: Boolean) {
        _chooser.value = null
        playHere(request.item, request.chatTitle)
    }

    fun dismissChooser() {
        _chooser.value = null
    }

    fun dismissClosed() {
        _closed.value = null
    }

    fun playHereFromClosed() {
        val offer = _closed.value ?: return
        _closed.value = null
        playHere(offer.item, offer.chatTitle)
    }

    fun onStopped() = Unit

    @Suppress("UNUSED_PARAMETER")
    fun onResult(context: Context, settings: SettingsStore, data: Intent?) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun onResumed(context: Context) = Unit
}

/** Our own "Open with": there is no app to choose on iOS, so it closes at once if something ever shows it. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun ExternalChooserSheet(
    apps: List<InstalledPlayers.Found>,
    gone: String?,
    onChoose: (choice: PlayerChoice, always: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(Unit) { onDismiss() }
}
