package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import com.tmplayer.data.VideoCorrections
import com.tmplayer.ui.components.TmAlertDialog
import com.tmplayer.ui.i18n.LocalStrings

/**
 * The title to show for a saved record of a video (a download, a Watched entry): the viewer's own
 * where they renamed it, else [title]. Redraws when a rename is made.
 */
@Composable
internal fun correctedTitle(chatId: Long, messageId: Long, title: String): String {
    val corrections by VideoCorrections.state.collectAsState()
    return corrections["$chatId:$messageId"]?.title ?: title
}

/**
 * "Rename": the title the viewer wants for a video, with the cursor in the field. Enter or Save
 * keeps it; "Use the file name" (or saving it empty) goes back to what the file name says.
 *
 * @param onSave the new title, or null to go back to the file name.
 */
@Composable
internal fun RenameDialog(initial: String, onSave: (String?) -> Unit, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    var title by remember { mutableStateOf(initial) }
    val field = remember { FocusRequester() }
    fun save() = onSave(title.trim().ifEmpty { null })
    TmAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.gridRenameTitle) },
        text = {
            val held = com.tmplayer.ui.components.rememberHeldText(title)
            OutlinedTextField(
                value = held.value,
                onValueChange = { held.edit(it, { title = it.take(VideoCorrections.TITLE_MAX) }) },
                label = { Text(s.gridRenameHint) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.fillMaxWidth().focusRequester(field),
            )
        },
        confirmButton = { TextButton(onClick = { save() }) { Text(s.gridRenameSave) } },
        dismissButton = {
            Row {
                TextButton(onClick = { onSave(null) }) { Text(s.gridRenameReset) }
                TextButton(onClick = onDismiss) { Text(s.commonCancel) }
            }
        },
    )
    FocusWhenShown(field)
}
