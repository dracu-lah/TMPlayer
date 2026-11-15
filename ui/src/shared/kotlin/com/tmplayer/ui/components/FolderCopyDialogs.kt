package com.tmplayer.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tmplayer.data.FolderCopy
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Tone

/**
 * Save to folder's last question, before a byte is written: how many videos and how much, where
 * to and the room there, what is skipped and why, and the "Remove from TMPlayer's downloads after
 * saving" tick (off unless ticked). When the lot does not fit, it says by how much and offers the
 * newest that do instead; when not even one fits, only Cancel.
 */
@Composable
fun <T> FolderCopyConfirmDialog(
    plan: FolderCopy.Plan<T>,
    remove: Boolean,
    onRemove: (Boolean) -> Unit,
    onSave: (List<T>) -> Unit,
    onCancel: () -> Unit,
) {
    val s = LocalStrings.current
    val someFit = plan.fitting.isNotEmpty()
    // Everything chosen was skipped (unfinished, or not allowed to be saved): the reasons and Cancel.
    val nothing = plan.items.isEmpty()
    val bodyMax = dialogBodyMax(CONFIRM_BODY_MAX)
    TmAlertDialog(
        onDismissRequest = onCancel,
        title = { Text(s.downloadsSaveToFolderConfirmTitle(folder = plan.folder), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(
                Modifier.heightIn(max = bodyMax).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(if (nothing) s.downloadsSaveToFolderNone else plan.summary, style = MaterialTheme.typography.bodyLarge)
                if (!nothing) Text(
                    plan.spaceLine,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (plan.fits) Tone.muted else Tone.danger,
                )
                if (!nothing && !plan.fits) {
                    Text(
                        if (someFit) {
                            s.downloadsSaveToFolderFitSome(count = plan.fitting.size) + ": " +
                                s.downloadsSaveToFolderConfirmSummary(count = plan.fitting.size, size = s.messages.formatter.bytes(plan.fittingBytes)) +
                                ". " + s.downloadsSaveToFolderFitSomeDetail
                        } else {
                            s.downloadsSaveToFolderNoneFit
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                plan.skippedLines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = Tone.muted) }
                if (!nothing && (plan.fits || someFit)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .toggleable(value = remove, role = Role.Checkbox, onValueChange = onRemove),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Checkbox(checked = remove, onCheckedChange = null, modifier = Modifier.height(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(s.downloadsSaveToFolderRemove, style = MaterialTheme.typography.bodyMedium)
                            Text(s.downloadsSaveToFolderRemoveDetail, style = MaterialTheme.typography.bodySmall, color = Tone.muted)
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                nothing -> Unit
                plan.fits -> TextButton(onClick = { onSave(plan.items) }) { Text(s.downloadsSaveToFolderStart) }
                someFit -> TextButton(onClick = { onSave(plan.fitting) }) { Text(s.downloadsSaveToFolderFitSome(count = plan.fitting.size)) }
                else -> Unit
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(s.commonCancel) } },
    )
}

/**
 * Save to folder under way: which video of how many, the bytes across the batch, the bar, and
 * Cancel, which stops after the buffer in hand and removes the part copy it leaves.
 */
@Composable
fun FolderCopyProgressDialog(progress: FolderCopy.Progress, onCancel: () -> Unit, gallery: Boolean = false) {
    val s = LocalStrings.current
    val bodyMax = dialogBodyMax(CONFIRM_BODY_MAX)
    TmAlertDialog(
        // Only Cancel ends it: a tap beside the dialog must not leave a copy running unseen.
        onDismissRequest = {},
        title = { Text(if (gallery) s.downloadsSaveToGalleryTitle else s.downloadsSaveToFolderTitle) },
        text = {
            Column(
                Modifier.heightIn(max = bodyMax).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(progress.line, style = MaterialTheme.typography.bodyMedium)
                Text(
                    progress.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = Tone.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                LinearProgressIndicator(
                    progress = { progress.overall },
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                    trackColor = Tone.surfaceHigh,
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                )
                progress.bytesLine?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Tone.muted) }
            }
        },
        confirmButton = { TextButton(onClick = onCancel) { Text(s.commonCancel) } },
    )
}

/**
 * What a Save to folder did, when it was more than "all saved": the count and the folder on top,
 * then why it stopped and what made it, each skipped kind with its count, each failed video by
 * name with its reason, and what a removal after saving did. A clean run is a toast instead.
 */
@Composable
fun FolderCopyResultDialog(outcome: FolderCopy.Outcome, onClose: () -> Unit) {
    val s = LocalStrings.current
    val bodyMax = dialogBodyMax(RESULT_BODY_MAX)
    TmAlertDialog(
        onDismissRequest = onClose,
        title = { Text(outcome.headline) },
        text = {
            Column(
                Modifier.heightIn(max = bodyMax).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                outcome.lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(s.commonClose) } },
    )
}

/**
 * Between the folder being picked and the confirmation: the sizes are added up and each chat is
 * asked whether it allows saving, which takes a moment for a long selection, so the press is
 * answered at once rather than with nothing.
 */
@Composable
fun FolderCopyPreparingDialog(gallery: Boolean = false) {
    val s = LocalStrings.current
    TmAlertDialog(
        onDismissRequest = {},
        title = { Text(if (gallery) s.downloadsSaveToGalleryTitle else s.downloadsSaveToFolderTitle) },
        text = { LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape), trackColor = Tone.surfaceHigh) },
        confirmButton = {},
    )
}

/**
 * The most a Save to folder dialog's words may take of this window: [cap], or less where the window
 * is short, so the title and the buttons stay on screen and the words scroll between them. A phone
 * held sideways is about 390 dp tall, and a 420 dp body there pushed the dialog past the window and
 * cut the confirmation off part way through the line under the removal tick, with nothing to scroll
 * (2026-10-22). Read from the app's window before the dialog opens: inside it, the window is the
 * dialog's own.
 */
@Composable
internal fun dialogBodyMax(cap: Dp): Dp {
    val height = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    if (height <= 0.dp) return cap
    return (height - DIALOG_CHROME).coerceIn(DIALOG_BODY_MIN, cap)
}

/** The confirmation's and the progress dialog's words at most, on a window tall enough for all of it. */
private val CONFIRM_BODY_MAX = 420.dp

/** The summary's list at most. */
private val RESULT_BODY_MAX = 320.dp

/**
 * What a dialog keeps around its words: the window's margins, the dialog's padding, a title of up
 * to two lines and the row of buttons.
 */
private val DIALOG_CHROME = 232.dp

/** Never less than this for the words, which still scroll, on the shortest window. */
private val DIALOG_BODY_MIN = 96.dp
