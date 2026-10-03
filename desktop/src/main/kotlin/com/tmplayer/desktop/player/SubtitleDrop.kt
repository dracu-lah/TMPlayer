package com.tmplayer.desktop.player

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
import java.io.File
import java.net.URI
import java.util.Locale

/** Which of the files dropped on the player is a subtitle mpv can load (B2.3). */
object SubtitleDrop {

    /** Text subtitle formats libass and mpv's own readers handle. Case does not matter (Windows). */
    val EXTENSIONS = setOf("srt", "ass", "ssa", "vtt", "sub")

    /** The first subtitle among [uris] (as a drop delivers them, `file:` URIs or plain paths), as a path. */
    fun pick(uris: List<String>): String? = uris.asSequence()
        .mapNotNull(::toFile)
        .firstOrNull { it.extension.lowercase(Locale.ROOT) in EXTENSIONS }
        ?.absolutePath

    private fun toFile(uri: String): File? = runCatching {
        if (uri.startsWith("file:", ignoreCase = true)) File(URI(uri)) else File(uri)
    }.getOrNull()
}

/**
 * Lets a subtitle file be dropped on the picture: [onDrop] gets its path, [onRefused] a sentence
 * when what arrived was not a subtitle.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun Modifier.subtitleDropTarget(onDrop: (String) -> Unit, onRefused: (String) -> Unit): Modifier {
    val drop by rememberUpdatedState(onDrop)
    val refuse by rememberUpdatedState(onRefused)
    val target = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val files = (event.dragData() as? DragData.FilesList)?.readFiles().orEmpty()
                val path = SubtitleDrop.pick(files)
                if (path == null) {
                    refuse("Drop a subtitle file: .srt, .ass, .vtt")
                    return false
                }
                drop(path)
                return true
            }
        }
    }
    return dragAndDropTarget(shouldStartDragAndDrop = { it.dragData() is DragData.FilesList }, target = target)
}
