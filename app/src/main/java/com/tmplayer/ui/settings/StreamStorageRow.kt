package com.tmplayer.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.ui.components.SettingIconChip
import com.tmplayer.ui.components.TabSwitch
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import androidx.compose.material3.MaterialTheme as M3Theme
import androidx.compose.material3.Text as M3Text
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/** Test tags of the two tabs of [StreamStorageRow]. */
internal const val STREAM_KEEP_TAG = "stream-keep"
internal const val STREAM_SAVE_TAG = "stream-save"

/** The television's one stop for both tabs, the node that takes the focus ring. */
internal const val STREAM_SWITCH_TAG = "stream-switch"

/**
 * "While streaming: Keep video | Save space" (the stored `low_storage_mode`, on is Save space), as
 * two tabs with the chosen one's sentence under them, where it has the whole width. A phone gets
 * Material's segmented buttons, as Theme does; a television gets the setup's [TabSwitch], one
 * stop where Left and Right pick a side and the focus ring is drawn round the pair, since the
 * separate segments are a thumb's control.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StreamStorageRow(
    saveSpace: Boolean,
    onPick: (saveSpace: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val touch = isTouch()
    val detail = if (saveSpace) s.settingsStreamSaveDetail else s.settingsStreamKeepDetail
    val labels = listOf(s.settingsStreamKeep, s.settingsStreamSave)
    Column(
        modifier
            .seekable(s.settingsStreamTitle)
            .fillMaxWidth()
            .then(
                if (touch) {
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                } else {
                    Modifier
                        .clip(RoundedCornerShape(Corner.Large))
                        .background(Tone.surface)
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                },
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettingIconChip(TmIcons.Archive)
            Spacer(Modifier.width(16.dp))
            if (touch) {
                M3Text(
                    s.settingsStreamTitle,
                    style = M3Theme.typography.bodyLarge,
                    color = Tone.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    s.settingsStreamTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = Tone.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        if (touch) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(false, true).forEachIndexed { index, on ->
                    SegmentedButton(
                        selected = on == saveSpace,
                        onClick = { if (on != saveSpace) onPick(on) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                        modifier = Modifier.testTag(if (on) STREAM_SAVE_TAG else STREAM_KEEP_TAG),
                        // Two lines rather than "Keep vid...": at twice the font size on a narrow phone each
                        // word takes its own line, and a cut label names no choice.
                        label = { M3Text(labels[index], maxLines = 2, textAlign = TextAlign.Center) },
                    )
                }
            }
        } else {
            TabSwitch(
                choices = listOf(false, true),
                selected = saveSpace,
                title = { if (it) labels[1] else labels[0] },
                tag = { if (it) STREAM_SAVE_TAG else STREAM_KEEP_TAG },
                onPick = onPick,
                modifier = Modifier.testTag(STREAM_SWITCH_TAG),
            )
        }
        Spacer(Modifier.height(8.dp))
        if (touch) {
            M3Text(detail, style = M3Theme.typography.bodyMedium, color = Tone.muted)
        } else {
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
        }
    }
}
