package com.tmplayer.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.interaction.collectIsPressedAsState
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.Tone

/**
 * What the television's own search keyboard types, kept apart from the drawing so a test can walk it
 * without a screen (TvKeyboardTest).
 */
object TvKeyboardKeys {
    /** Six keys across: the letters, then the digits, so the grid is six rows of six. */
    const val COLUMNS = 6

    val rows: List<List<Char>> = (('a'..'z') + ('0'..'9')).chunked(COLUMNS)

    /** A key pressed: its character goes on the end of the words. */
    fun type(text: String, key: Char): String = text + key

    /** The space key: never a leading space and never two in a row, as the search trims them anyway. */
    fun space(text: String): String = if (text.isEmpty() || text.endsWith(' ')) text else "$text "

    /** The end of long words, where the typing is: what fits the line over the keys, led by an ellipsis. */
    fun tail(text: String, keep: Int = 22): String = if (text.length <= keep) text else "…" + text.takeLast(keep)

    /** The delete key: the last character, a whole surrogate pair when that is what it is. */
    fun delete(text: String): String = when {
        text.isEmpty() -> text
        text.length >= 2 && Character.isLowSurrogate(text.last()) && Character.isHighSurrogate(text[text.length - 2]) -> text.dropLast(2)
        else -> text.dropLast(1)
    }
}

/**
 * The search keyboard of the television: letters and digits as a grid the remote walks, drawn beside
 * the results instead of over them. The system keyboard covers half the screen on a television and
 * hides the very results it is typing for; this one is a column on the left, so the results fill
 * in beside it as each letter lands. OK on the field still opens the system keyboard, for another
 * script or for voice typing where the keyboard dictates.
 *
 * @param firstKey takes the remote when the page opens.
 * @param onSearch the Search key: the words are final (kept as a recent search).
 */
@Composable
fun TvKeyboard(
    text: String,
    onText: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    firstKey: FocusRequester = remember { FocusRequester() },
    /** The system keyboard, for another script or voice typing; null leaves its key out. */
    onSystemKeyboard: (() -> Unit)? = null,
    /** Keys at least 46 dp tall by default; a pane short of height passes less and drops [showText]. */
    keyHeight: Dp = KEY_HEIGHT,
    /** The line of typed words over the keys; a screen with a field of its own in view drops it. */
    showText: Boolean = true,
) {
    val s = LocalStrings.current
    // The letters stay in the order of the alphabet whichever way the page reads: mirrored, a
    // right to left page would show F E D C B A.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(
            modifier.semantics { contentDescription = s.searchKeysLabel },
            verticalArrangement = Arrangement.spacedBy(KEY_GAP),
        ) {
            if (showText) {
                // What has been typed, always in view: the field in the results' list scrolls away with
                // them, and the viewer must see the words they are building while the keys are under them.
                // The end of long words, which is where the typing is. Silent to a screen reader: the
                // field says the same words.
                Box(Modifier.fillMaxWidth().height(keyHeight).padding(horizontal = 4.dp).clearAndSetSemantics { }, contentAlignment = Alignment.CenterStart) {
                    Text(
                        if (text.isEmpty()) s.searchFieldHint else TvKeyboardKeys.tail(text),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (text.isEmpty()) Tone.muted else Tone.text,
                        maxLines = 1,
                    )
                }
            }
            TvKeyboardKeys.rows.forEachIndexed { rowIndex, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                    row.forEachIndexed { column, key ->
                        TvKey(
                            label = key.uppercaseChar().toString(),
                            spoken = key.toString(),
                            height = keyHeight,
                            modifier = Modifier.weight(1f).let { if (rowIndex == 0 && column == 0) it.focusRequester(firstKey) else it },
                            onClick = { onText(TvKeyboardKeys.type(text, key)) },
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                TvKey(label = s.searchKeySpace, spoken = s.searchKeySpace, height = keyHeight, modifier = Modifier.weight(2f), onClick = { onText(TvKeyboardKeys.space(text)) })
                TvKey(
                    icon = TmIcons.Backspace,
                    spoken = s.commonDelete,
                    height = keyHeight,
                    modifier = Modifier.weight(1f),
                    enabled = text.isNotEmpty(),
                    // Held, it keeps deleting: one letter a press is a long way across a long name.
                    repeats = true,
                    onClick = { onText(TvKeyboardKeys.delete(text)) },
                )
                TvKey(icon = TmIcons.Search, spoken = s.commonSearch, height = keyHeight, modifier = Modifier.weight(1f), enabled = text.isNotBlank(), accent = true, onClick = onSearch)
                if (onSystemKeyboard != null) {
                    TvKey(label = s.searchKeyKeyboard, spoken = s.searchKeyKeyboard, height = keyHeight, modifier = Modifier.weight(2f), onClick = onSystemKeyboard)
                }
            }
        }
    }
}

/** One key: a letter, a digit or a word, filled under the remote the way every pill is. */
@Composable
private fun RowScope.TvKey(
    spoken: String,
    height: Dp,
    modifier: Modifier = Modifier,
    label: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    accent: Boolean = false,
    /** A held OK acts again with each repeat the remote sends, not once on release. */
    repeats: Boolean = false,
    onClick: () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val pressed by interactions.collectIsPressedAsState()
    val fill by animateColorAsState(
        when {
            // A press answers on the key itself (Android TV's guidance: Select shows a moment of selected state).
            pressed -> Tone.accent
            focused -> Tone.focusFill
            accent && enabled -> Tone.accent.copy(alpha = 0.22f)
            else -> Tone.surfaceHigh
        },
        Motion.standard(Motion.Fast),
        label = "tvKey",
    )
    val ink = when {
        pressed -> Tone.onFocusFill
        focused -> Tone.onFocusFill
        !enabled -> Tone.muted.copy(alpha = 0.5f)
        else -> Tone.text
    }
    val current = rememberUpdatedState(onClick)
    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(10.dp))
            .background(fill)
            .semantics { contentDescription = spoken }
            .then(
                if (repeats && enabled) {
                    Modifier.onPreviewKeyEvent { event ->
                        val select = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                        if (select && event.type == KeyEventType.KeyDown) {
                            current.value()
                            true
                        } else {
                            // The release of a press already acted on: not a second click.
                            select && event.type == KeyEventType.KeyUp
                        }
                    }
                } else {
                    Modifier
                },
            )
            // Focusable even when it does nothing (an empty Delete): the remote walking down the
            // column must not jump past a key that happens to be idle.
            .clickable(interactionSource = interactions, indication = null, role = Role.Button) { if (enabled) onClick() },
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(22.dp))
        } else if (label != null) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = ink, maxLines = 1, textAlign = TextAlign.Center)
        }
    }
}

private val KEY_HEIGHT = 46.dp
private val KEY_GAP = 4.dp
