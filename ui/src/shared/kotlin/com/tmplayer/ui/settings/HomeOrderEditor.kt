package com.tmplayer.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.HomeLayout
import com.tmplayer.data.HomeOrder
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone

/**
 * One row of Home as Settings lists it: [key] is the [HomeOrder] key, [title] what the row is
 * called, [subtitle] a line under it (that the row is switched off, for one).
 */
data class HomeOrderRow(val key: String, val title: String, val subtitle: String? = null)

/**
 * Settings, Home: the rows of Home in the order they are drawn, to rearrange (EG-F7, EG-F23,
 * EG-F27). One list for the phone, the television and the desktop:
 * - A phone and a desktop: Move up and Move down on every row, and a handle to drag it by (a
 *   long list scrolls, so the buttons are the way for a row that has to go far).
 * - A television ([remote]): OK picks a row up, Up and Down move it, OK puts it down and Back puts
 *   it back where it was. Nothing is written until it is put down.
 *
 * [onOrder] gets the whole new order as keys, once per move (a drag is one move, written when the
 * finger lifts). The list is [rows] until the new order comes back through it, so a row does not
 * jump back for the frames the setting takes to be written.
 */
@Composable
fun HomeOrderEditor(
    rows: List<HomeOrderRow>,
    onOrder: (List<String>) -> Unit,
    remote: Boolean,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val density = LocalDensity.current
    val rowHeight = if (remote) 64.dp else 56.dp
    val gap = 6.dp
    val step = with(density) { (rowHeight + gap).toPx() }
    val byKey = remember(rows) { rows.associateBy { it.key } }
    val stored = remember(rows) { rows.map { it.key } }
    // The order being worked on: a drag's or a picked-up row's, or the one just put down until the
    // setting comes back with it. Reset by any change to [rows].
    var working by remember(rows) { mutableStateOf<List<String>?>(null) }
    val order = working ?: stored
    // The row picked up on a television, the one being dragged, and how far its finger has gone.
    var picked by remember(rows) { mutableStateOf<String?>(null) }
    var dragging by remember(rows) { mutableStateOf<String?>(null) }
    var dragY by remember(rows) { mutableStateOf(0f) }
    val total = order.size

    fun put(next: List<String>) {
        if (next == stored) {
            working = null
        } else {
            working = next
            onOrder(next)
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        order.forEachIndexed { index, rowKey ->
            val row = byKey[rowKey] ?: return@forEachIndexed
            key(rowKey) {
                val interactions = remember { MutableInteractionSource() }
                val focused by interactions.collectIsFocusedAsState()
                val isPicked = picked == rowKey
                val isDragged = dragging == rowKey
                val bringIntoView = remember { BringIntoViewRequester() }
                // A row that moves on a television keeps the remote, and stays in sight.
                LaunchedEffect(index, isPicked) { if (isPicked) bringIntoView.bringIntoView() }
                val shape = RoundedCornerShape(Corner.Large)
                val position = s.commonOfTotal((index + 1).toString(), total.toString())
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(rowHeight)
                        .bringIntoViewRequester(bringIntoView)
                        .zIndex(if (isDragged) 1f else 0f)
                        .graphicsLayer { translationY = if (isDragged) dragY else 0f }
                        .then(if (isDragged) Modifier.shadow(6.dp, shape) else Modifier)
                        .clip(shape)
                        .background(
                            when {
                                isPicked -> Tone.accent
                                remote && focused -> Tone.focusFill
                                else -> Tone.surface
                            },
                        )
                        .then(if (isDragged) Modifier.border(1.dp, Tone.accent, shape) else Modifier)
                        .semantics { stateDescription = position }
                        .then(
                            if (remote) {
                                Modifier
                                    .onFocusChanged { state ->
                                        // The remote went elsewhere: the row goes back, as Back does.
                                        if (!state.isFocused && picked == rowKey) {
                                            picked = null
                                            working = null
                                        }
                                    }
                                    .onPreviewKeyEvent { event ->
                                        if (picked != rowKey) return@onPreviewKeyEvent false
                                        val down = event.type == KeyEventType.KeyDown
                                        when (event.key) {
                                            Key.DirectionUp, Key.DirectionDown -> {
                                                if (down) working = HomeOrder.move(order, rowKey, if (event.key == Key.DirectionUp) -1 else 1)
                                                true
                                            }
                                            // Held on the row: nothing else may take the remote while it is in hand.
                                            Key.DirectionLeft, Key.DirectionRight -> true
                                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                                if (down) {
                                                    picked = null
                                                    put(order)
                                                }
                                                true
                                            }
                                            Key.Back, Key.Escape -> {
                                                if (down) {
                                                    picked = null
                                                    working = null
                                                }
                                                true
                                            }
                                            else -> false
                                        }
                                    }
                                    .clickable(
                                        interactionSource = interactions,
                                        indication = null,
                                        role = Role.Button,
                                        onClick = { if (picked == null) picked = rowKey },
                                    )
                            } else {
                                Modifier
                            },
                        )
                        .padding(start = 12.dp, end = if (remote) 16.dp else 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val onFill = isPicked || (remote && focused)
                    if (!remote) {
                        val handleLabel = s.settingsHomeOrderHandle
                        Box(
                            Modifier
                                .size(40.dp)
                                .semantics { contentDescription = handleLabel }
                                .pointerInput(rowKey, step, rows) {
                                    detectDragGestures(
                                        onDragStart = {
                                            dragging = rowKey
                                            dragY = 0f
                                            working = order
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            dragY += amount.y
                                            val now = working ?: order
                                            val at = now.indexOf(rowKey)
                                            // Past half a row, it takes the next place and the row it passed takes its old one.
                                            if (dragY > step / 2 && at < now.lastIndex) {
                                                working = HomeOrder.moveTo(now, at, at + 1)
                                                dragY -= step
                                            } else if (dragY < -step / 2 && at > 0) {
                                                working = HomeOrder.moveTo(now, at, at - 1)
                                                dragY += step
                                            }
                                        },
                                        onDragEnd = {
                                            val done = working ?: order
                                            dragging = null
                                            dragY = 0f
                                            put(done)
                                        },
                                        onDragCancel = {
                                            dragging = null
                                            dragY = 0f
                                            working = null
                                        },
                                    )
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(TmIcons.Menu, contentDescription = null, tint = Tone.muted, modifier = Modifier.size(20.dp))
                        }
                    }
                    Spacer(Modifier.width(if (remote) 4.dp else 0.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp), verticalArrangement = Arrangement.Center) {
                        Text(
                            row.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (onFill) Tone.onFocusFill else Tone.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val line = if (isPicked) s.settingsHomeOrderMoving else row.subtitle
                        if (line != null) {
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (onFill) Tone.onFocusFill.copy(alpha = 0.85f) else Tone.muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (remote) {
                        Text(
                            position,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (onFill) Tone.onFocusFill.copy(alpha = 0.85f) else Tone.muted,
                            maxLines = 1,
                        )
                    } else {
                        val first = index == 0
                        val last = index == order.lastIndex
                        IconButton(
                            onClick = { put(HomeOrder.move(order, rowKey, -1)) },
                            enabled = !first,
                            modifier = Modifier.size(48.dp).alpha(if (first) 0.35f else 1f),
                        ) {
                            Icon(TmIcons.ChevronUp, contentDescription = s.settingsHomeOrderUp, tint = Tone.text)
                        }
                        IconButton(
                            onClick = { put(HomeOrder.move(order, rowKey, 1)) },
                            enabled = !last,
                            modifier = Modifier.size(48.dp).alpha(if (last) 0.35f else 1f),
                        ) {
                            Icon(TmIcons.ChevronDown, contentDescription = s.settingsHomeOrderDown, tint = Tone.text)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The editor with its hint under it and, once an order is set, "Default order" to give it up. The
 * same on the phone, the television and the desktop; the caller puts it where its page puts a
 * block of its own.
 *
 * @param hasOrder an order is stored, so there is one to give up.
 */
@Composable
fun HomeOrderSection(
    rows: List<HomeOrderRow>,
    hasOrder: Boolean,
    onOrder: (List<String>) -> Unit,
    onReset: () -> Unit,
    remote: Boolean,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    Column(modifier.fillMaxWidth()) {
        HomeOrderEditor(rows, onOrder, remote)
        Text(
            if (remote) s.settingsHomeOrderHintTv else s.settingsHomeOrderHint,
            style = MaterialTheme.typography.bodySmall,
            color = Tone.muted,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp),
        )
        if (hasOrder) {
            TextButton(onClick = onReset, modifier = Modifier.padding(top = 2.dp)) {
                Text(s.settingsHomeOrderReset)
            }
        }
    }
}

/**
 * What Settings lists for Home's order: [HomeOrder.editorRows] over the starred chats, each named
 * as its switch is (a chat by its title) and marked Off where its switch is off, then the named
 * lists on Home (HX43, [lists]: the ones with a row), by their names.
 */
@Composable
fun rememberHomeOrderRows(layout: HomeLayout, starred: List<ChatSummary>, lists: List<com.tmplayer.data.ListInfo> = emptyList()): List<HomeOrderRow> {
    val s = LocalStrings.current
    val continueTitle = s.settingsHomeContinue
    val recentTitle = s.settingsHomeRecent
    val off = s.commonOff
    return remember(layout, starred, lists, continueTitle, recentTitle, off) {
        HomeOrder.editorRows(starred, layout.order, lists).map { entry ->
            when {
                entry.key == HomeOrder.CONTINUE -> HomeOrderRow(entry.key, continueTitle, off.takeIf { !layout.continueWatching })
                entry.key == HomeOrder.RECENT -> HomeOrderRow(entry.key, recentTitle, off.takeIf { !layout.recentlyAdded })
                entry.listName != null -> HomeOrderRow(entry.key, entry.listName.orEmpty())
                else -> HomeOrderRow(entry.key, entry.chatTitle?.ifBlank { null } ?: entry.key, off.takeIf { !layout.starredRows })
            }
        }
    }
}

/** The named lists that have a row on Home (on Home, and something in them the kids lock shows). */
fun homeListInfos(lists: List<com.tmplayer.data.VisibleList>): List<com.tmplayer.data.ListInfo> =
    lists.filter { it.info.home && it.records.isNotEmpty() }.map { it.info }
