@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.tmplayer.ui.browse

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.tmplayer.ui.theme.collectIsFocusVisibleAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import com.tmplayer.data.AddedChats
import com.tmplayer.data.ChatBrief
import com.tmplayer.data.ChatCount
import com.tmplayer.data.ChatCountCalls
import com.tmplayer.data.ChatGate
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatPicking
import com.tmplayer.data.ChatSuggestions
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.CountAnswer
import com.tmplayer.data.DeviceForm
import com.tmplayer.data.KidsLock
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.Td
import com.tmplayer.ui.components.LoadMoreAhead
import com.tmplayer.ui.components.deviceForm
import com.tmplayer.ui.components.rememberToggleTick
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.nav.BackHandler
import com.tmplayer.ui.onboarding.QuietButton
import com.tmplayer.ui.onboarding.SetupButton
import com.tmplayer.ui.settings.Confirm
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv
import com.tmplayer.ui.theme.focusRing

/**
 * "Add chats" (HX27): every chat of the account with a tick box, the videos each holds, and the
 * running total at the foot. A full screen on the phone, the television and the desktop alike, so
 * a choice made on one reads the same on the others.
 *
 * The screen decides how things look and nothing else. Which rows there are, in which band, what
 * Select all means under a search or a filter, and what the foot adds up are [ChatPicking]'s;
 * what is recommended and how a count reads are [ChatSuggestions]'; every action is [model]'s.
 * A row never changes height as its count arrives (a placeholder bar holds the line), so nothing
 * moves under a finger or a remote.
 *
 * @param chats every chat of the account, unnarrowed ([ChatListViewModel.everything]).
 * @param listComplete whether the chat list is whole: the Gone band only stands once it is.
 * @param partial the list came back short of the account (HX32), so the screen offers [onRetry].
 * @param loadingMore the next chats are being read, so [onLoadMore] waits for them.
 * @param onLoadMore asks the chat list for its next chats as the end of this list nears; nothing
 *   pages by itself, so an idle screen costs nothing.
 * @param sendLogs the platform's Send logs button, drawn beside Try again when the list is short.
 * @param searchField the television's own search box, which only the app has; the touch and
 *   desktop screens draw a plain text field.
 */
@Composable
fun AddChatsScreen(
    chats: List<ChatSummary>,
    listComplete: Boolean,
    starred: Set<Long>,
    model: AddChatsViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    partial: Boolean = false,
    loadingMore: Boolean = false,
    onRetry: () -> Unit = {},
    onLoadMore: () -> Unit = {},
    sendLogs: (@Composable () -> Unit)? = null,
    searchField: (@Composable (value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier) -> Unit)? = null,
) {
    val s = LocalStrings.current
    val form = deviceForm()
    val tv = form == DeviceForm.Tv
    val state by model.state.collectAsState()
    val choice by AddedChats.choice.collectAsState()
    val listState = rememberLazyListState()
    val searchFocus = remember { FocusRequester() }
    val selectFocus = remember { FocusRequester() }
    var asking by remember { mutableStateOf<Ask?>(null) }
    // Typed here and handed on, since a text field fed back through a flow a frame late loses keys.
    var typed by rememberSaveable { mutableStateOf(state.query) }

    // A chat ticked that the list does not hold: the Gone rows' candidates. Derived, so a tick on a
    // chat that is there does not rebuild the rows.
    val everyId = remember(chats) { chats.mapTo(HashSet()) { it.id } }
    val strays by remember(everyId) { derivedStateOf { state.ticked.filterTo(LinkedHashSet()) { it !in everyId } } }
    val rows = remember(chats, state.query, state.scope, state.recommended, starred, listComplete, strays, choice.chats) {
        ChatPicking.rows(
            chats = chats,
            added = strays,
            addedNames = choice.chats,
            starred = starred,
            recommended = state.recommended,
            query = state.query,
            scope = state.scope,
            listComplete = listComplete,
        )
    }
    val visible = remember(rows) { ChatPicking.visible(rows) }
    val allTicked = ChatPicking.allTicked(state.ticked, visible)
    val total = ChatPicking.total(state.ticked, state.counted)

    LaunchedEffect(Unit) { if (typed != state.query) model.type(typed) }
    LaunchedEffect(chats.isNotEmpty()) { if (chats.isNotEmpty()) model.start(chats) }
    // Nothing is counted once the screen is gone, and a count already running is cancelled.
    DisposableEffect(model) { onDispose { model.stop() } }
    LaunchedEffect(state.saved) { if (state.saved) onClose() }
    LaunchedEffect(Unit) {
        if (!tv) return@LaunchedEffect
        // The list lays its first items out a frame after this runs, and a request before that
        // finds nothing to focus.
        withFrameNanos { }
        withFrameNanos { }
        runCatching { searchFocus.requestFocus() }
    }
    // The list's own end triggers the chat list's next chats, one request at a time.
    LoadMoreAhead(listState, enabled = !listComplete && !partial && !loadingMore, onLoadMore = onLoadMore)
    // Back from a question puts the remote on the button that asked it.
    var wasAsking by remember { mutableStateOf(false) }
    LaunchedEffect(asking) {
        if (asking == null && wasAsking && tv) runCatching { selectFocus.requestFocus() }
        wasAsking = asking != null
    }

    val leave = {
        when {
            asking != null -> asking = null
            model.changed -> asking = Ask.Discard
            else -> onClose()
        }
    }
    BackHandler(onBack = leave)

    // Select all over the rows shown, with a word first when it is a lot. Ctrl+A runs the same.
    val selectAllNow = {
        val adding = model.adding(visible)
        when {
            adding == 0 -> Unit
            ChatPicking.needsConfirm(adding) -> asking = Ask.SelectAll(adding)
            else -> model.selectAll(visible)
        }
    }
    val tick = rememberToggleTick()
    val compact = form != DeviceForm.Phone
    val selectAllButton: @Composable () -> Unit = {
        QuietButton(
            if (allTicked) s.commonClear else s.browseSelectAll,
            modifier = Modifier.focusRequester(selectFocus).selectAllKeys(selectAllNow),
            onClick = { if (allTicked) model.clear(visible) else selectAllNow() },
        )
    }

    val outer = when (form) {
        DeviceForm.Tv -> Modifier.padding(horizontal = Tv.SafeH, vertical = Tv.SafeV)
        DeviceForm.Desktop -> Modifier.padding(horizontal = 40.dp, vertical = 28.dp)
        DeviceForm.Phone -> Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 16.dp)
    }
    Box(
        modifier
            .fillMaxSize()
            .background(Tone.background)
            // Over another screen on the desktop: it takes the pointer, and the arrows never leave it.
            .pointerInput(Unit) {}
            .focusProperties { onExit = { if (requestedFocusDirection in ARROWS) cancelFocusChange() } }
            .focusGroup()
            .then(outer),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().fillMaxHeight()) {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(top = if (form == DeviceForm.Phone) 16.dp else 0.dp, bottom = 8.dp),
            ) {
                // A phone stacks Back, the title and the words; a wide screen has the room to put Back
                // beside the title, which leaves more of it for the rows.
                if (compact) {
                    item(key = "title") {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                QuietButton(s.commonBack, icon = true, onClick = leave)
                                Text(
                                    s.browseAddChats,
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = Tone.text,
                                    modifier = Modifier.semantics { heading() },
                                )
                            }
                            Text(s.pickerBlurb, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
                        }
                    }
                } else {
                    item(key = "back") {
                        QuietButton(s.commonBack, icon = true, onClick = leave)
                    }
                    item(key = "title") {
                        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                s.browseAddChats,
                                style = MaterialTheme.typography.headlineMedium,
                                color = Tone.text,
                                modifier = Modifier.semantics { heading() },
                            )
                            Text(s.pickerBlurb, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
                        }
                    }
                }
                item(key = "search") {
                    val field = Modifier.fillMaxWidth().focusRequester(searchFocus)
                    Box(Modifier.padding(top = 12.dp)) {
                        val onType: (String) -> Unit = { typed = it; model.type(it) }
                        if (searchField != null) {
                            searchField(typed, onType, s.commonSearch, field)
                        } else {
                            OutlinedTextField(
                                value = typed,
                                onValueChange = onType,
                                singleLine = true,
                                placeholder = { Text(s.commonSearch) },
                                modifier = field,
                            )
                        }
                    }
                }
                item(key = "scopes") {
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            for (scope in ChatPicking.Scope.entries) {
                                CopyChip(
                                    label = scopeLabel(scope, s),
                                    chosen = state.scope == scope,
                                    onClick = { model.scope(scope) },
                                )
                            }
                        }
                        if (compact && visible.isNotEmpty()) selectAllButton()
                    }
                }
                if (!listComplete && !partial) {
                    item(key = "loading") {
                        val words = s.pickerLoading
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(2.dp).semantics { contentDescription = words },
                            color = Tone.accent,
                            trackColor = Tone.track,
                        )
                    }
                }
                if (partial) {
                    item(key = "partial") {
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(s.chatsSomeNotLoaded, style = MaterialTheme.typography.bodyMedium, color = Tone.danger)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                SetupButton(s.commonTryAgain, primary = true, onClick = onRetry)
                                sendLogs?.invoke()
                            }
                        }
                    }
                }
                if (!compact && visible.isNotEmpty()) {
                    item(key = "selectAll") {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { selectAllButton() }
                    }
                }
                if (rows.isEmpty()) {
                    item(key = "empty") {
                        val words = if (state.query.isNotBlank()) s.chatsNoMatch(state.query.trim()) else s.pickerEmpty
                        // While the list is still arriving an empty screen is not an answer yet.
                        if (listComplete || partial) {
                            Text(words, style = MaterialTheme.typography.bodyMedium, color = Tone.muted, modifier = Modifier.padding(vertical = 16.dp))
                        }
                    }
                }
                val bands = rows.groupBy { it.band }
                for ((band, inBand) in bands) {
                    val label = if (bands.size > 1) bandLabel(band, s) else null
                    if (label != null) {
                        item(key = "band-$band") {
                            Text(
                                label,
                                style = MaterialTheme.typography.titleSmall,
                                color = Tone.muted,
                                modifier = Modifier.padding(top = 14.dp, bottom = 4.dp).semantics { heading() },
                            )
                        }
                    }
                    if (band == ChatPicking.Band.Recommended && state.belowBar) {
                        item(key = "below-bar") {
                            Text(
                                s.pickerBelowBar(ChatSuggestions.MIN_VIDEOS),
                                style = MaterialTheme.typography.bodySmall,
                                color = Tone.muted,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                        }
                    }
                    items(inBand, key = { it.id }) { row ->
                        // Only a row on screen is counted: this effect leaves with the row.
                        if (row.band != ChatPicking.Band.Gone) LaunchedEffect(row.id) { model.count(row.id) }
                        val ticked = row.id in state.ticked
                        if (row.band == ChatPicking.Band.Gone) {
                            GoneRow(row, tv, onRemove = { model.toggle(row.id) })
                        } else {
                            val shape = ChatSuggestions.shape(state.counted[row.id])
                            PickRow(
                                row = row,
                                ticked = ticked,
                                shape = shape,
                                tv = tv,
                                onToggle = { tick(!ticked); model.toggle(row.id) },
                                onCountAgain = { model.countAgain(row.id) },
                                onSelectAll = selectAllNow,
                            )
                        }
                    }
                }
            }

            when (val question = asking) {
                is Ask.SelectAll -> Confirm(
                    title = s.pickerConfirmTitle(question.count),
                    body = s.pickerConfirmBody,
                    action = s.browseSelectAll,
                    onYes = {
                        asking = null
                        model.selectAll(visible)
                    },
                    onNo = { asking = null },
                )
                Ask.Discard -> Confirm(
                    title = s.pickerDiscardTitle,
                    body = s.pickerDiscardBody,
                    action = s.pickerDiscardYes,
                    onYes = {
                        asking = null
                        onClose()
                    },
                    onNo = { asking = null },
                )
                null -> Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp, bottom = if (form == DeviceForm.Phone) 12.dp else 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        totalLine(total, s),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tone.text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // Nothing ticked is an answer too: Done reads as what it will do.
                    SetupButton(
                        if (state.ticked.isEmpty()) s.pickerAddLater else s.commonDone,
                        primary = true,
                        onClick = { model.done(chats) },
                    )
                }
            }
        }
    }
}

/** The directions a key can move focus in, which is all the screen holds back from leaving it. */
private val ARROWS = setOf(FocusDirection.Up, FocusDirection.Down, FocusDirection.Left, FocusDirection.Right)

/** A question the foot is asking in place of the total. */
private sealed interface Ask {
    data class SelectAll(val count: Int) : Ask

    data object Discard : Ask
}

/**
 * One chat: its picture, name, how many videos it holds and a tick box, the whole row the target
 * so a thumb need not find the box and a remote can land on it. A long press (the Menu key on a
 * remote or a keyboard) counts again a chat that could not be counted.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PickRow(
    row: ChatPicking.Row,
    ticked: Boolean,
    shape: ChatSuggestions.CountShape,
    tv: Boolean,
    onToggle: () -> Unit,
    onCountAgain: () -> Unit,
    onSelectAll: () -> Unit,
) {
    val s = LocalStrings.current
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    val unknown = shape == ChatSuggestions.CountShape.Unknown
    val round = RoundedCornerShape(12.dp)
    val ink = if (focused) Tone.onFocusFill else Tone.text
    val quiet = if (focused) Tone.onFocusFill else Tone.muted
    val avatar = if (tv) 52.dp else 44.dp
    val retry = s.pickerCountAgain
    Row(
        Modifier
            .fillMaxWidth()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    (event.isCtrlPressed || event.isMetaPressed) && event.key == Key.A -> {
                        onSelectAll()
                        true
                    }
                    unknown && event.key == Key.Menu -> {
                        onCountAgain()
                        true
                    }
                    else -> false
                }
            }
            .clip(round)
            .background(if (focused) Tone.focusFill else Color.Transparent)
            .focusRing(focused, round)
            .semantics(mergeDescendants = true) {
                toggleableState = ToggleableState(ticked)
                if (unknown) customActions = listOf(CustomAccessibilityAction(retry) { onCountAgain(); true })
            }
            .combinedClickable(
                interactionSource = interactions,
                indication = if (tv) null else androidx.compose.foundation.LocalIndication.current,
                role = Role.Checkbox,
                onLongClick = if (unknown) onCountAgain else null,
                onClick = onToggle,
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowAvatar(row, avatar)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    row.title.ifBlank { row.id.toString() },
                    style = if (tv) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (row.starred) {
                    Icon(
                        TmIcons.Star,
                        contentDescription = s.browseFavourite,
                        tint = if (focused) Tone.onFocusFill else Tone.accent,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            CountLine(shape, quiet, tv)
        }
        Checkbox(checked = ticked, onCheckedChange = null)
    }
}

/**
 * The count under a name. Its height is the line's own whether it holds words or a placeholder
 * bar, which is what keeps a row from changing height the moment its count lands.
 */
@Composable
private fun CountLine(shape: ChatSuggestions.CountShape, ink: Color, tv: Boolean) {
    val s = LocalStrings.current
    val style = if (tv) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall
    val density = LocalDensity.current
    val line: Dp = remember(style, density) {
        with(density) { style.lineHeight.takeIf { it.isSpecified }?.toDp() } ?: 18.dp
    }
    Box(Modifier.fillMaxWidth().height(line), contentAlignment = Alignment.CenterStart) {
        when (shape) {
            ChatSuggestions.CountShape.Counting -> Box(
                Modifier
                    .width(if (tv) 120.dp else 96.dp)
                    .height(line * 0.6f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Tone.surfaceHigh),
            )
            is ChatSuggestions.CountShape.Exact -> Text(s.pickerCountAbout(shape.videos), style = style, color = ink, maxLines = 1)
            ChatSuggestions.CountShape.Many -> Text(s.pickerCountMany, style = style, color = ink, maxLines = 1)
            ChatSuggestions.CountShape.Unknown -> Text(
                if (tv) s.pickerCountUnknownKeys else s.pickerCountUnknownTouch,
                style = style,
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A chat that was added and has since gone (left, deleted or banned): its remembered name, a word
 * about it and Remove, in place of a tick box. Remove unticks it, so it leaves the choice when Done
 * is pressed.
 */
@Composable
private fun GoneRow(row: ChatPicking.Row, tv: Boolean, onRemove: () -> Unit) {
    val s = LocalStrings.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowAvatar(row, if (tv) 52.dp else 44.dp)
        Column(Modifier.weight(1f)) {
            Text(
                row.title.ifBlank { s.chatsUntitled(row.id) },
                style = if (tv) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Tone.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                s.pickerGoneDetail,
                style = if (tv) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                color = Tone.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        QuietButton(s.pickerRemove, onClick = onRemove)
    }
}

/** The chat's picture, or its first letter where it has none; a gone chat has only its name. */
@Composable
private fun RowAvatar(row: ChatPicking.Row, size: Dp) {
    val brief = remember(row) { row.chat?.let(ChatBrief::of) ?: ChatBrief(row.id, row.title, ChatKind.Group) }
    ChatAvatar(brief, size)
}

/** Ctrl+A (Cmd+A on a Mac) on a control of the list ticks every row shown; a text field keeps its own. */
private fun Modifier.selectAllKeys(onSelectAll: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    if (event.type == KeyEventType.KeyDown && (event.isCtrlPressed || event.isMetaPressed) && event.key == Key.A) {
        onSelectAll()
        true
    } else {
        false
    }
}

private fun scopeLabel(scope: ChatPicking.Scope, s: com.tmplayer.i18n.Messages): String = when (scope) {
    ChatPicking.Scope.All -> s.browseFilterAll
    ChatPicking.Scope.Archived -> s.browseFilterArchived
    ChatPicking.Scope.Channels -> s.browseFilterChannels
    ChatPicking.Scope.Groups -> s.browseFilterGroups
    ChatPicking.Scope.People -> s.browseFilterPeople
}

/** What a band's heading says; Saved Messages is one row and needs none. */
private fun bandLabel(band: ChatPicking.Band, s: com.tmplayer.i18n.Messages): String? = when (band) {
    ChatPicking.Band.Saved -> null
    ChatPicking.Band.Recommended -> s.pickerBandRecommended
    ChatPicking.Band.Starred -> s.pickerBandStarred
    ChatPicking.Band.Chats -> s.browseFilterAllChats
    ChatPicking.Band.Archived -> s.browseFilterArchived
    ChatPicking.Band.Gone -> s.pickerBandGone
}

/** The foot's line: the chats chosen and, once counted, about how many videos they hold. */
private fun totalLine(total: ChatPicking.Total, s: com.tmplayer.i18n.Messages): String = when {
    total.chats == 0 -> s.pickerTotalNone
    total.counted == 0 -> s.pickerTotal(total.chats)
    total.whole -> s.pickerTotalVideos(total.chats, s.formatter.number(total.videos))
    else -> s.pickerTotalCounting(total.chats, s.formatter.number(total.videos))
}

/**
 * [AddChatsScreen] fed from the shell's chat list, the same on every platform that draws it.
 *
 * The list is [ChatListViewModel.everything], whole and unnarrowed, since the picker is the one
 * screen that must see a chat the viewer has not added; only the kids lock still narrows it, so a
 * child's profile cannot add a chat it was never shown. The list counts as complete only once the
 * sync has answered and Telegram is reachable, so a chat not yet loaded is never called gone.
 */
@Composable
fun AddChatsFed(
    settings: SettingsStore,
    chatsViewModel: ChatListViewModel,
    model: AddChatsViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    sendLogs: (@Composable () -> Unit)? = null,
    searchField: (@Composable (value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier) -> Unit)? = null,
) {
    val everything by chatsViewModel.everything.collectAsState()
    val paging by chatsViewModel.paging.collectAsState()
    val refreshing by chatsViewModel.refreshing.collectAsState()
    val connected by Td.connected.collectAsState()
    val kids by ChatGate.allowed.collectAsState()
    val starred by remember(settings) { settings.favorites }.collectAsState(initial = emptySet())
    val chats = remember(everything, kids) { KidsLock.chats(everything, kids) }
    AddChatsScreen(
        chats = chats,
        listComplete = everything.isNotEmpty() && connected && !refreshing && !paging.loading && !paging.more && !paging.partial,
        starred = starred,
        model = model,
        onClose = onClose,
        modifier = modifier,
        partial = paging.partial,
        loadingMore = paging.loading || refreshing,
        // A short list's Try again reads it all again; the end of the list reads the next chats.
        onRetry = chatsViewModel::loadMoreChats,
        onLoadMore = chatsViewModel::loadMoreChats,
        sendLogs = sendLogs,
        searchField = searchField,
    )
}

/**
 * Counts through the live Telegram session, whichever one is up when the first row asks, so the
 * view model can be made at once instead of after the session is awaited. A session that never
 * comes answers as a failure, which the row shows as "could not count".
 */
private class LiveCounts : ChatCountCalls {
    private var calls: ChatCountCalls? = null

    override suspend fun videoCount(chatId: Long, localOnly: Boolean): CountAnswer {
        val live = calls ?: AddChatsViewModel.calls()?.also { calls = it } ?: return CountAnswer.Failed(null)
        return live.videoCount(chatId, localOnly)
    }
}

/**
 * A picker's view model, counting over the live session and already reading the stored choice.
 * Made once per opening by the host, so a rotation keeps the ticks and a new opening starts clean.
 */
fun newAddChatsViewModel(settings: SettingsStore): AddChatsViewModel =
    AddChatsViewModel(settings, LiveCounts()).also { it.open() }

/**
 * Whether the picker is up on the desktop: one switch for the window, read by the shell, which
 * draws [AddChatsScreen] over everything while it is on (the Devices page's switch, with the host
 * in the shell because the screen's view model must be made when it opens and not before). The
 * phone and the television route to it as a screen of their own instead.
 */
object AddChatsState {
    var open by mutableStateOf(false)
}
