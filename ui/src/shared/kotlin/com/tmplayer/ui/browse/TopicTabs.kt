package com.tmplayer.ui.browse

import com.tmplayer.ui.theme.collectIsFocusVisibleAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tmplayer.data.ForumTopicSummary
import com.tmplayer.data.TopicSearch
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.focusRing

/**
 * The topics of a group that has Telegram's topics turned on, as a row of tabs over its videos:
 * "All topics" first, then the topics in Telegram's own order, pinned ones marked.
 *
 * Drawn like the season tabs so a viewer who has met one has met both. Unlike those, the remote
 * does not switch topic just by passing over one: each topic is a fresh search of the group, and
 * walking from the first tab to the eighth should not set off seven of them. OK picks.
 *
 * The chosen tab is scrolled into view when the row appears, so a group remembered on its
 * twentieth topic does not open with the chosen one off the edge of the screen. Coming back up
 * into the row from the grid lands on the tab last left, which is the chosen one after a pick.
 *
 * A group of more than [TopicSearch.CHIP_SEARCH_FROM] topics gets a "Find a topic" field after
 * All topics (U14): what is typed narrows the tabs to the names that answer it, best first, the
 * chosen tab always kept ([TopicSearch.chips]). Nothing is asked of Telegram: the names are here.
 */
@Composable
fun TopicTabs(
    bar: TopicBar,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** The screen's side margin, so the first tab lines up with the first tile. */
    edge: Dp = 0.dp,
) {
    val s = LocalStrings.current
    val tv = !isTouch()
    val searchable = TopicSearch.chipSearchWanted(bar.topics.size)
    var find by remember(searchable) { mutableStateOf("") }
    val shown = remember(bar.topics, bar.selected, find) {
        if (searchable) TopicSearch.chips(bar.topics, find, bar.selected) else bar.topics
    }
    // Where the chosen tab stands in the row: All topics first, then the field, then the topics.
    val lead = if (searchable) 2 else 1
    val chosenAt = if (bar.selected == 0) 0 else shown.indexOfFirst { it.id == bar.selected }.let { if (it < 0) 0 else it + lead }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (chosenAt - 1).coerceAtLeast(0))
    val chosenFocus = remember { FocusRequester() }
    LaunchedEffect(chosenAt) { listState.animateScrollToItem((chosenAt - 1).coerceAtLeast(0)) }

    LazyRow(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = s.gridTopicsLabel }
            .then(if (tv) Modifier.focusRestorer(chosenFocus) else Modifier),
        contentPadding = PaddingValues(horizontal = edge),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item(key = 0) {
            TopicTab(
                topic = null,
                chosen = chosenAt == 0,
                tv = tv,
                onClick = { onSelect(0) },
                modifier = if (chosenAt == 0) Modifier.focusRequester(chosenFocus) else Modifier,
            )
        }
        if (searchable) item(key = "find") { TopicFindField(find, { find = it }, tv) }
        itemsIndexed(shown, key = { _, topic -> topic.id }) { index, topic ->
            val chosen = index + lead == chosenAt
            TopicTab(
                topic = topic,
                chosen = chosen,
                tv = tv,
                onClick = { onSelect(topic.id) },
                modifier = if (chosen) Modifier.focusRequester(chosenFocus) else Modifier,
            )
        }
        if (searchable && find.isNotBlank() && shown.none { it.id != bar.selected }) {
            item(key = "find-none") {
                Text(s.gridTopicFindNone(find.trim()), style = MaterialTheme.typography.bodyMedium, color = Tone.muted, maxLines = 1)
            }
        }
    }
}

/**
 * "Find a topic": a pill the size of a tab with a magnifier, where typing narrows the row, and a
 * cross that empties it.
 *
 * On a television focus alone never starts editing, as the TV search box does: the remote
 * passes over the pill like any tab, OK opens the keyboard, and an arrow
 * while typing leaves for the neighbouring tab (a remote does not move a cursor mid-word). The
 * pill keeps the focus while the field comes and goes, so it is never lost to the window.
 */
@Composable
private fun TopicFindField(value: String, onValue: (String) -> Unit, tv: Boolean) {
    val s = LocalStrings.current
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    var editing by remember(tv) { mutableStateOf(!tv) }
    val field = remember { FocusRequester() }
    val pill = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val shape = CircleShape
    val style = if (tv) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelLarge
    LaunchedEffect(editing) { if (editing && tv) runCatching { field.requestFocus() } }
    Row(
        Modifier
            .focusRequester(pill)
            .heightIn(min = if (tv) TV_HEIGHT else TOUCH_HEIGHT)
            .widthIn(min = 140.dp, max = if (tv) 260.dp else 200.dp)
            .clip(shape)
            .background(if (editing && tv) Tone.surfaceHigh else Tone.surface)
            .focusRing(focused || (editing && tv), shape, filled = false)
            .then(
                if (tv) {
                    Modifier
                        .semantics(mergeDescendants = !editing) { contentDescription = s.gridTopicFind }
                        .clickable(interactionSource = interactions, indication = null) { editing = true }
                } else {
                    Modifier.semantics { contentDescription = s.gridTopicFind }
                },
            )
            .padding(horizontal = if (tv) 18.dp else 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(TmIcons.Search, contentDescription = null, tint = Tone.muted, modifier = Modifier.size(if (tv) 18.dp else 16.dp))
        Box(Modifier.weight(1f, fill = false)) {
            if (value.isEmpty()) Text(s.gridTopicFind, style = style, color = Tone.muted, maxLines = 1)
            if (editing) {
                BasicTextField(
                    value = value,
                    onValueChange = onValue,
                    singleLine = true,
                    textStyle = style.copy(color = Tone.text),
                    cursorBrush = SolidColor(Tone.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { if (tv) { runCatching { pill.requestFocus() }; editing = false } }),
                    modifier = Modifier
                        .focusRequester(field)
                        .onPreviewKeyEvent { event ->
                            if (!tv || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            val direction = when (event.key) {
                                Key.DirectionLeft -> FocusDirection.Left
                                Key.DirectionRight -> FocusDirection.Right
                                Key.DirectionUp -> FocusDirection.Up
                                Key.DirectionDown -> FocusDirection.Down
                                Key.Back, Key.Escape -> null
                                else -> return@onPreviewKeyEvent false
                            }
                            // The pill takes the focus first, so the field leaves with none on it.
                            runCatching { pill.requestFocus() }
                            editing = false
                            if (direction != null) focusManager.moveFocus(direction)
                            true
                        },
                )
            } else if (value.isNotEmpty()) {
                Text(value, style = style, color = Tone.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (value.isNotEmpty()) {
            com.tmplayer.ui.components.ClearButton(
                s.commonClear,
                onClick = { onValue("") },
                target = if (tv) 40.dp else 36.dp,
                disc = if (tv) 22.dp else 20.dp,
            )
        }
    }
}

@Composable
private fun TopicTab(
    topic: ForumTopicSummary?,
    chosen: Boolean,
    tv: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    // Quiet pills: the plain surface at rest, and the focus fill for the chosen topic and for the
    // one under the remote. The two are told apart by the ring, which only focus draws, and by the
    // chosen one's weight.
    val shape = CircleShape
    val lit = focused || chosen
    val fill = if (lit) Tone.focusFill else Tone.surface
    val ink = if (lit) Tone.onFocusFill else Tone.text
    val name = topic?.name ?: s.gridTopicsAll
    val described = buildList {
        add(name)
        if (topic?.isPinned == true) add(s.gridTopicPinned)
        if (topic?.isClosed == true) add(s.gridTopicClosed)
    }.joinToString(", ")
    Row(
        modifier
            .semantics(mergeDescendants = true) {
                role = Role.Tab
                selected = chosen
                contentDescription = described
            }
            .clickable(interactionSource = interactions, indication = null, onClick = onClick)
            // A finger's 48 dp round a pill that stays 36 dp: the press lands on the taller box.
            .then(if (tv) Modifier else Modifier.minimumInteractiveComponentSize())
            .heightIn(min = if (tv) TV_HEIGHT else TOUCH_HEIGHT)
            .clip(shape)
            .background(fill)
            .focusRing(focused, shape)
            .padding(horizontal = if (tv) 18.dp else 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val glyph = if (tv) 18.dp else 16.dp
        if (topic?.isPinned == true) {
            Icon(TmIcons.Pin, contentDescription = null, tint = ink.copy(alpha = 0.7f), modifier = Modifier.size(glyph))
        }
        Text(
            name,
            style = if (tv) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelLarge,
            fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // A topic called a whole sentence still leaves room for its neighbours.
            modifier = Modifier.widthIn(max = if (tv) 260.dp else 200.dp),
        )
        if (topic?.isClosed == true) {
            Icon(TmIcons.Lock, contentDescription = null, tint = ink.copy(alpha = 0.7f), modifier = Modifier.size(glyph))
        }
    }
}

/** A topic pill's height: a touch target's on a phone, and the size of the TV's other pills. */
private val TOUCH_HEIGHT = 36.dp
private val TV_HEIGHT = 44.dp
