package com.tmplayer.ui.downloads

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Which row of the page opened the player, for a remote to be put back on it when the player
 * closes (R2: "Back from the player returns focus to the opener"). The player is an activity of its
 * own over this one, so nothing in the page knows it came back except the lifecycle: this page
 * resumes, and the row that was playing takes the remote again, instead of whatever the window
 * decided to focus on its own.
 */
internal class OpenerMemory(restored: String? = null) {
    private var key: String? = restored

    /** The row kept across a destroyed activity (WP-15), or null. */
    internal fun saved(): String? = key

    /** The row [key] opened the player. */
    fun opened(key: String) {
        this.key = key
    }

    /** The row to give the remote back to, once: null when nothing was opened (the page's first resume). */
    fun takeOnReturn(): String? = key.also { key = null }
}

/** An [OpenerMemory] that survives the activity being destroyed behind the player ("Don't keep activities"). */
@Composable
internal fun rememberOpenerMemory(): OpenerMemory = rememberSaveable(
    saver = Saver<OpenerMemory, String>(
        save = { it.saved() ?: "" },
        restore = { OpenerMemory(it.ifEmpty { null }) },
    ),
) { OpenerMemory() }

/**
 * Calls [restore] with the row that opened the player each time this page resumes after one, and not
 * on a resume that had nothing opened (the first one, or coming back from the notification shade).
 */
@Composable
internal fun RestoreOpenerOnResume(memory: OpenerMemory, restore: (String) -> Unit) {
    val owner = LocalLifecycleOwner.current
    val latest = rememberUpdatedState(restore)
    DisposableEffect(owner, memory) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) memory.takeOnReturn()?.let { latest.value(it) }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}
