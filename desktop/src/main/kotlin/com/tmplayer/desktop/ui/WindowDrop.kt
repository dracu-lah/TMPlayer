package com.tmplayer.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import com.tmplayer.data.TelegramLinks
import com.tmplayer.data.TgLink
import java.net.URI
import java.util.Locale

/** What something dropped on the window is, and so what the window does with it (WP-52). */
internal sealed interface WindowDropKind {
    /** A Telegram post or invite link: opened through the search palette, the one place that opens pasted links. */
    data class Link(val text: String) : WindowDropKind

    /** A `.json` file: a backup, to restore from Settings. */
    data class Backup(val path: String) : WindowDropKind

    /** Anything else: TMPlayer says what it plays. */
    data object Unsupported : WindowDropKind
}

internal object WindowDrop {

    /** As much dropped text as is read, as Android caps a shared text (SEC2-09): a log's worth would stall the window. */
    const val MAX_TEXT = 8_000

    /**
     * Text first (a link dragged from a browser), then files; the first file that is a backup. Of the
     * text only the link goes on, never the rest of it, and no more than [MAX_TEXT] of it is read.
     */
    fun classify(text: String?, files: List<String>): WindowDropKind {
        val link = text?.take(MAX_TEXT)?.trim()?.takeIf { it.isNotEmpty() }?.let { t ->
            if (TelegramLinks.opensSomething(t)) t
            else TelegramLinks.find(t)?.takeIf { it is TgLink.Post || it is TgLink.Invite }?.url
        }
        if (link != null) return WindowDropKind.Link(link)
        val backup = files.firstOrNull { path -> path.substringAfterLast('.', "").lowercase(Locale.ROOT) == "json" }
        if (backup != null) return WindowDropKind.Backup(toPath(backup))
        return WindowDropKind.Unsupported
    }

    private fun toPath(uri: String): String = runCatching {
        if (uri.startsWith("file:", ignoreCase = true)) java.io.File(URI(uri)).path else uri
    }.getOrDefault(uri)
}

/**
 * The window takes drops (WP-52): a Telegram link opens as one pasted into the search, a backup
 * file leads to Restore in Settings, and anything else says what TMPlayer plays. The player's own
 * subtitle target sits inside this one and gets files first while a video is open. Nothing is
 * opened that the signed in user could not open from the same link.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun Modifier.windowDropTarget(onDrop: (WindowDropKind) -> Unit): Modifier {
    val drop by rememberUpdatedState(onDrop)
    val target = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val data = event.dragData()
                val text = (data as? DragData.Text)?.readText()
                val files = (data as? DragData.FilesList)?.readFiles().orEmpty()
                drop(WindowDrop.classify(text, files))
                return true
            }
        }
    }
    return dragAndDropTarget(shouldStartDragAndDrop = { it.dragData().let { d -> d is DragData.Text || d is DragData.FilesList } }, target = target)
}
