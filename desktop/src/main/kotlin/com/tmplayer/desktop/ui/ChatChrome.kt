package com.tmplayer.desktop.ui

import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tmplayer.data.ChatSummary
import com.tmplayer.desktop.os.OsInfo
import com.tmplayer.ui.components.FloatingBarIcon
import com.tmplayer.ui.components.FloatingChrome
import com.tmplayer.ui.components.FloatingRoundButton
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.nav.BackHandler
import com.tmplayer.ui.theme.FloatingTone
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.floatingBorder

/*
 * A chat's own chrome on the desktop, as on the phone: Back as a round floating button at the top
 * start with the chat's picture and name in a pill beside it, and the chat's actions in a floating
 * bar at the foot (search, the star, sort, rows or tiles; F5 refreshes). The grid
 * scrolls under both. The search folds into the bar: opened, the bar becomes the field, with the
 * recent searches as plain words on a panel just over it.
 */

/** How far the grid's first row sits from the top, clear of Back and the chat's pill. */
internal val ChatTopClearance: Dp = 16.dp + FloatingChrome.Button + 12.dp

/** How much of the foot of the page the chat's bar keeps, its margin and a gap included. */
internal val ChatBarClearance: Dp = 16.dp + FloatingChrome.LabelledBar + 8.dp

/** Back and the chat's picture and name, floating at the top start of the page. */
@Composable
internal fun BoxScope.ChatTopChrome(state: ShellState, chat: ChatSummary) {
    val s = LocalStrings.current
    Row(
        Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 16.dp, end = 160.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FloatingRoundButton(TmIcons.ArrowBack, s.gridBackToChats, onClick = { state.closeChat() }, shortcut = "Esc")
        Row(
            Modifier
                .height(FloatingChrome.Button)
                .shadow(FloatingChrome.Shadow, CircleShape)
                .clip(CircleShape)
                .background(FloatingTone.menu)
                .border(floatingBorder(), CircleShape)
                .windowDrag()
                .padding(start = 5.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ChatAvatar(chat.miniThumbnail, chat.photoFileId, chat.title, 34.dp)
            Text(
                chat.title,
                style = MaterialTheme.typography.titleSmall,
                color = Tone.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 420.dp),
            )
        }
        // The chat's info: its picture, username, description and link (user, 2026-10-18).
        var info by remember { mutableStateOf(false) }
        FloatingRoundButton(TmIcons.Info, s.chatsInfoAbout, onClick = { info = true })
        if (info) ChatInfoPanel(chat, onDismiss = { info = false })
    }
}

/**
 * The chat's bar at the foot of the page, centred. With [searching] it is the search field, wider,
 * with Back to fold it again; Esc empties the field and then folds it, and Down moves into the grid.
 */
@Composable
internal fun ChatBar(
    state: ShellState,
    searching: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    /** Enter, or a recent search picked: the chat searched at once, past the typing's settle. */
    onSubmit: (String) -> Unit = {},
    /** A search of the chat is on its way while the old listing stays up: a spinner in the field. */
    busy: Boolean = false,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onSearchDown: () -> Unit,
    recent: List<String>,
    onClearRecent: () -> Unit,
    favourite: Boolean,
    onToggleFavourite: () -> Unit,
    onSort: () -> Unit,
    sortAnchor: com.tmplayer.ui.components.PopoverAnchor,
    /** A filter is on in this chat: the Sort button is drawn in the accent. */
    filtered: Boolean = false,
) {
    val s = LocalStrings.current
    val command = if (OsInfo.isMac) "⌘" else "Ctrl+"
    BackHandler(enabled = searching) { onCloseSearch() }
    Column(
        Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AnimatedVisibility(
            visible = searching && query.isBlank() && recent.isNotEmpty(),
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = fadeOut() + slideOutVertically { it / 3 },
        ) {
            RecentPanel(recent, onPick = { onQuery(it); onSubmit(it) }, onClear = onClearRecent)
        }
        if (searching) {
            BarPill(Modifier.widthIn(min = 360.dp, max = SEARCH_WIDTH).fillMaxWidth()) {
                BarIcon(TmIcons.ArrowBack, s.browseCloseSearch, "Esc", onCloseSearch)
                Icon(TmIcons.Search, contentDescription = null, tint = Tone.muted, modifier = Modifier.padding(start = 4.dp).size(20.dp))
                val held = com.tmplayer.ui.components.rememberHeldText(query)
                BasicTextField(
                    value = held.value,
                    onValueChange = { held.edit(it, onQuery) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Tone.text),
                    cursorBrush = SolidColor(Tone.accent),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp)
                        .focusRequester(state.searchFocus)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionDown -> {
                                    onSearchDown()
                                    true
                                }
                                Key.Enter, Key.NumPadEnter -> {
                                    onSubmit(query)
                                    true
                                }
                                Key.Escape -> {
                                    if (query.isNotEmpty()) onQuery("") else onCloseSearch()
                                    true
                                }
                                else -> false
                            }
                        },
                    decorationBox = { field ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                Text(s.gridSearchThisChat, style = MaterialTheme.typography.bodyLarge, color = Tone.muted, maxLines = 1)
                            }
                            field()
                        }
                    },
                )
                if (busy && query.isNotEmpty()) {
                    // Shown only once Telegram is asked, never while the typing settles.
                    androidx.compose.material3.CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = Tone.muted,
                        modifier = Modifier.padding(horizontal = 6.dp).size(16.dp),
                    )
                }
                if (query.isNotEmpty()) BarIcon(TmIcons.Close, s.browseClearSearch, null, { onQuery("") })
            }
        } else {
            BarPill {
                // Each with its name under it, as the phone's bar has them.
                BarIcon(TmIcons.Search, s.gridSearchThisChat, "${command}F", onOpenSearch, caption = s.commonSearch)
                BarTooltip(if (favourite) s.browseRemoveFavourite else s.browseAddFavourite, null) {
                    FloatingBarIcon(
                        icon = if (favourite) TmIcons.Star else TmIcons.StarOutline,
                        label = if (favourite) s.browseRemoveFavourite else s.browseAddFavourite,
                        onClick = onToggleFavourite,
                        // Only the filled star is coloured, so the outline sits with the others.
                        tint = if (favourite) Tone.accent else Tone.text,
                        checked = favourite,
                        caption = s.browseFavourite,
                        // The star pops as it fills: the answer to the press, with no message.
                        glyph = {
                            com.tmplayer.ui.components.StarIcon(favourite, TmIcons.Star, TmIcons.StarOutline, on = Tone.accent, off = Tone.text)
                        },
                    )
                }
                BarIcon(TmIcons.Sort, if (filtered) s.gridFilterOn else s.gridSort, null, onSort, caption = s.gridSort, modifier = sortAnchor.modifier, tint = if (filtered) Tone.accent else Tone.text)
            }
        }
    }
}

/** The floating pill the bar's buttons sit in: the floating bar's own look. */
@Composable
private fun BarPill(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier
            .height(FloatingChrome.LabelledBar)
            .shadow(FloatingChrome.Shadow, CircleShape)
            .clip(CircleShape)
            .background(FloatingTone.menu)
            .border(floatingBorder(), CircleShape)
            // As far from the round ends as from the top and bottom of a captioned button.
            .padding(horizontal = (FloatingChrome.LabelledBar - FloatingChrome.LabelledIcon) / 2),
        // The window's bar's gap, so a hovered button and a focused one beside it stay apart.
        horizontalArrangement = Arrangement.spacedBy(BarGap),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun BarIcon(
    icon: ImageVector,
    label: String,
    shortcut: String?,
    onClick: () -> Unit,
    enabled: Boolean = true,
    caption: String? = null,
    modifier: Modifier = Modifier,
    tint: androidx.compose.ui.graphics.Color = Tone.text,
) {
    // A glyph alone carries its own tooltip; one with its caption has the bar's here, as before.
    if (caption != null && shortcut == null) {
        BarTooltip(label, null) { FloatingBarIcon(icon, label, onClick, modifier = modifier, tint = tint, enabled = enabled, caption = caption) }
    } else {
        FloatingBarIcon(icon, label, onClick, modifier = modifier, tint = tint, enabled = enabled, caption = caption, shortcut = shortcut)
    }
}

/** The recent searches, plain words with a history mark on one panel, Clear at its end. */
@Composable
private fun RecentPanel(searches: List<String>, onPick: (String) -> Unit, onClear: () -> Unit) {
    val s = LocalStrings.current
    Row(
        Modifier
            .widthIn(max = SEARCH_WIDTH)
            .shadow(6.dp, CircleShape)
            .clip(CircleShape)
            .background(Tone.surfaceHigh)
            .border(floatingBorder(), CircleShape)
            .horizontalScroll(rememberScrollState())
            // Every entry and Clear are 40 dp pills, 2 dp clear of the panel on every side.
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (recent in searches) {
            // No box round each: the panel already says these go together.
            Row(
                Modifier
                    .clip(CircleShape)
                    .clickable(role = Role.Button) { onPick(recent) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(TmIcons.History, contentDescription = null, tint = Tone.muted, modifier = Modifier.size(18.dp))
                Text(recent, style = MaterialTheme.typography.bodyMedium, color = Tone.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(4.dp))
        TextButton(onClick = onClear) { Text(s.commonClear) }
    }
}

private val SEARCH_WIDTH = 600.dp
