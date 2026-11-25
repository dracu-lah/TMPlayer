package com.tmplayer.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.ui.components.SettingIconChip
import com.tmplayer.ui.components.TabSwitch
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import androidx.compose.material3.MaterialTheme as M3Theme
import androidx.compose.material3.Text as M3Text
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * A setting that is one question with a few short answers ("While streaming: Keep video | Save
 * space", "On mobile data: Ask first | Never | Allow"): the answers as tabs with the chosen one's
 * sentence under them, where it has the whole width. A phone gets Material's segmented buttons, as
 * Theme does; a television gets the setup's [TabSwitch], one stop where Left and Right pick an
 * answer and the focus ring is drawn round the group, since separate segments are a thumb's control.
 *
 * @param switchTag the one node a television's focus ring and a test find the group by.
 *
 * A television holds the sentence's room at the tallest of [details], by drawing all of them in one
 * box with only the chosen one visible, so moving from one answer to the next never makes the row
 * taller or shorter under the remote (WP-06). A `Box` of texts, not a measuring subcompose: a
 * Settings page holds about a hundred rows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> SegmentedChoiceRow(
    title: String,
    icon: ImageVector,
    choices: List<T>,
    selected: T,
    label: (T) -> String,
    detail: String,
    tag: (T) -> String,
    switchTag: String,
    onPick: (T) -> Unit,
    modifier: Modifier = Modifier,
    /** Every answer's sentence, so a television reserves the tallest one's height (see below). */
    details: List<String> = emptyList(),
) {
    val touch = isTouch()
    Column(
        modifier
            .seekable(title)
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
            SettingIconChip(icon)
            Spacer(Modifier.width(16.dp))
            if (touch) {
                M3Text(
                    title,
                    style = M3Theme.typography.bodyLarge,
                    color = Tone.text,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Tone.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        if (touch) {
            // Equal cells: the chosen answer wrapping to two lines made its cell taller than the others, which stood
            // short and centred inside it (phone audit, 2026-11-25), so the row takes the tallest and every cell fills it.
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                choices.forEachIndexed { index, choice ->
                    SegmentedButton(
                        selected = choice == selected,
                        onClick = { if (choice != selected) onPick(choice) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = choices.size),
                        modifier = Modifier.fillMaxHeight().testTag(tag(choice)),
                        // Two lines rather than "Keep vid...": at twice the font size on a narrow phone each
                        // word takes its own line, and a cut label names no choice.
                        label = { SegmentLabel(label(choice)) },
                        // The check takes a word's width: past 1.3 times the font the chosen cell would wrap to a
                        // second line and stand taller than its neighbours, so the fill alone says which it is.
                        icon = { if (segmentCheckFits()) SegmentedButtonDefaults.Icon(active = choice == selected) },
                    )
                }
            }
        } else {
            TabSwitch(
                choices = choices,
                selected = selected,
                title = label,
                tag = tag,
                onPick = onPick,
                modifier = Modifier.testTag(switchTag),
                label = title,
            )
        }
        Spacer(Modifier.height(8.dp))
        if (touch) {
            M3Text(detail, style = M3Theme.typography.bodyMedium, color = Tone.muted)
        } else {
            Box {
                details.forEach { other ->
                    if (other != detail) {
                        Text(
                            other,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Tone.muted,
                            modifier = Modifier.alpha(0f).clearAndSetSemantics {},
                        )
                    }
                }
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
            }
        }
    }
}

/** Whether a segmented button has room for its check mark: not at a very large font, where it would cut the word. */
@Composable
internal fun segmentCheckFits(): Boolean = androidx.compose.ui.platform.LocalDensity.current.fontScale <= 1.3f

/**
 * A segment's words, at most 1.5 times the font: a third of a phone is about 100 dp, and "System" at twice the
 * font broke into "Syste" and "m" (phone audit, 2026-11-25). Two lines at that size still break only between words.
 */
@Composable
internal fun SegmentLabel(text: String) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, density.fontScale.coerceAtMost(1.5f)),
    ) {
        M3Text(text, maxLines = 2, textAlign = TextAlign.Center)
    }
}
