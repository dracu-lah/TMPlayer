package com.tmplayer.desktop.player

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tmplayer.data.Bookmark
import com.tmplayer.data.BookmarkStore
import com.tmplayer.ui.components.TmAlertDialog
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons

/**
 * What the note dialog is for: a new bookmark at [positionMs] in the video [chatId], [messageId],
 * or a new note for [editing], which was made at that same position. The video is taken when the
 * dialog opens: playback runs on behind it, and autoplay may have moved to the next episode by Save.
 */
internal data class NotePrompt(
    val chatId: Long,
    val messageId: Long,
    val positionMs: Long,
    val editing: Bookmark? = null,
)

/**
 * The note for a bookmark, asked in a dialog with the cursor already in the field: B, a few words
 * and Enter saves it, B and Enter alone saves it without one. Cancel (or Esc) makes no bookmark.
 */
@Composable
internal fun BookmarkNoteDialog(prompt: NotePrompt, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    var note by remember(prompt) { mutableStateOf(prompt.editing?.note.orEmpty()) }
    val field = remember { FocusRequester() }
    TmAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.playerBookmarkNoteTitle(SeekMath.clock(prompt.positionMs))) },
        text = {
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(BookmarkStore.MAX_NOTE) },
                label = { Text(s.playerBookmarkNoteHint) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSave(note) }),
                modifier = Modifier.fillMaxWidth().focusRequester(field),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(note) }) {
                Text(if (prompt.editing != null) s.playerBookmarkSaveNote else s.playerBookmarkSave)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.commonCancel) } },
    )
    // After the dialog's first frame: its window is not attached when the effect first runs.
    LaunchedEffect(Unit) {
        repeat(5) {
            withFrameNanos { }
            if (runCatching { field.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
}

/**
 * One bookmark in the player menu's list: its time and note, a click away from playing there,
 * with its own buttons to change the note and to delete it.
 */
@Composable
internal fun BookmarkLine(mark: Bookmark, onJump: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val s = LocalStrings.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val jump = remember { MutableInteractionSource() }
        Row(
            Modifier.weight(1f).heightIn(min = 48.dp)
                .overlayFocusRing(jump, RoundedCornerShape(8.dp))
                .clickable(interactionSource = jump, indication = LocalIndication.current, role = Role.Button, onClick = onJump)
                .padding(start = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Lines up with the entries' text, past the space their tick takes.
            Spacer(Modifier.width(30.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(SeekMath.clock(mark.positionMs), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                if (mark.note.isNotBlank()) {
                    Text(mark.note, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        com.tmplayer.ui.components.IconTip(if (mark.note.isBlank()) s.playerBookmarkAddNote else s.playerBookmarkEditNote) {
            IconButton(onClick = onEdit) {
                Icon(TmIcons.Edit, contentDescription = if (mark.note.isBlank()) s.playerBookmarkAddNote else s.playerBookmarkEditNote, modifier = Modifier.size(18.dp))
            }
        }
        com.tmplayer.ui.components.IconTip(s.playerBookmarkDelete) {
            IconButton(onClick = onDelete) {
                Icon(TmIcons.Delete, contentDescription = s.playerBookmarkDelete, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * "Go to a time" ({cmd}+G, and the menu): a time typed the way it is said, read by
 * [com.tmplayer.player.TimeEntry]. Enter goes; text that is no place in this video says so under
 * the field and the dialog stays, so the viewer can mend it rather than start again.
 */
@Composable
internal fun GoToTimeDialog(durationMs: Long, positionMs: Long, onGo: (Long) -> Unit, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    var text by remember { mutableStateOf("") }
    var refused by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    fun go() {
        val target = com.tmplayer.player.TimeEntry.parse(text, durationMs, positionMs)
        if (target == null) refused = true else onGo(target)
    }
    TmAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.playerGoToTime) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it.take(MAX_TIME_TEXT)
                    refused = false
                },
                label = { Text(if (durationMs > 0) "${SeekMath.clock(positionMs)} / ${SeekMath.clock(durationMs)}" else SeekMath.clock(positionMs)) },
                supportingText = { Text(if (refused) s.playerGoToTimeInvalid else s.playerGoToTimeHint) },
                isError = refused,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { go() }, onDone = { go() }),
                modifier = Modifier.fillMaxWidth().focusRequester(field).testTag("go-to-time"),
            )
        },
        confirmButton = { TextButton(onClick = ::go) { Text(s.playerGo) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.commonCancel) } },
    )
    LaunchedEffect(Unit) {
        repeat(5) {
            withFrameNanos { }
            if (runCatching { field.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
}

private const val MAX_TIME_TEXT = 24
