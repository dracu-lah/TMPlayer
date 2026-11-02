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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
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
import com.tmplayer.ui.components.EdgeStep
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.OutlinedTextFieldDefaults
import com.tmplayer.ui.components.GroupPlace
import com.tmplayer.ui.components.SettingsSectionLabel
import com.tmplayer.ui.components.SkeletonBox
import com.tmplayer.ui.components.groupedCard
import com.tmplayer.ui.components.rememberShimmer
import com.tmplayer.ui.theme.Corner
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
 * @param searchField the television's own search box, which only the app has, with what its
 *   keyboard's Search does (the remote goes to the first answer); the touch and
 *   desktop screens draw a plain text field.
 * @param required the mandatory chat choice ([com.tmplayer.data.ChatsGate]): its own heading and
 *   words, no Back button, Done only once a chat is ticked, and Back (the system's, Esc) calls
 *   [onLeave] instead of asking to discard, since there is nothing behind it to go back to.
 * @param onLeave what Back does in [required] mode: leaves the app on the phone and the
 *   television, nothing on the desktop.
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
    searchField: (@Composable (value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier, onSubmit: () -> Unit) -> Unit)? = null,
    required: Boolean = false,
    onLeave: () -> Unit = onClose,
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
    // The row the remote stands on, kept here because the short list reads it (a focused row never folds away).
    var onRow by remember { mutableStateOf<Long?>(null) }
    // The television folds the chats counted at no videos into one row per band (HX76), unless a
    // search asks a question about every chat. A derived list, so a count that changes nothing
    // visible does not rebuild the rows.
    var foldOpen by remember(state.scope) { mutableStateOf<Set<ChatPicking.Band>>(emptySet()) }
    val folding = tv && state.query.isBlank()
    val items by remember(rows, folding, foldOpen) {
        derivedStateOf {
            if (folding) {
                val keep = onRow?.let { state.ticked + it } ?: state.ticked
                ChatPicking.fold(rows, state.counted, keep, foldOpen)
            } else {
                rows.map { ChatPicking.Item.Chat(it) }
            }
        }
    }
    val visible = remember(items) { ChatPicking.visible(items.mapNotNull { (it as? ChatPicking.Item.Chat)?.row }) }
    val allTicked = ChatPicking.allTicked(state.ticked, visible)
    val total = ChatPicking.total(state.ticked, state.counted)

    // The Recommended band is worked out in the first seconds and lands above every other band,
    // which moved the row under a click or a finger down a whole band. Until it is taken its place
    // is held by placeholders, and the bands under it wait with it.
    val holding = state.recommending && state.query.isBlank() && state.scope == ChatPicking.Scope.All
    val bands = remember(items) { items.groupBy { it.band } }
    // The rows as drawn, top to bottom, which is what the remote's jumps count in (HX76). A fold
    // row stands in as a row of its own, so the remote lands on it like any other.
    val shown = remember(bands, holding) {
        bands.flatMap { (band, inBand) ->
            if (holding && band != ChatPicking.Band.Saved) emptyList() else inBand.map(::itemRow)
        }
    }
    val placeOf = remember(shown) { HashMap<Long, Int>(shown.size * 2).apply { shown.forEachIndexed { i, row -> put(row.id, i) } } }

    // The remote's way round a long list (HX76): the first row takes the focus, not the search
    // box; Right (the end side) goes to Done from any row; Left (the start side) goes back to the
    // start of the row's band, then the band before, then the filters; Back goes up to the
    // filters before it leaves; Page and Channel Up or Down, and Down or Up held, move a screenful.
    val scope = rememberCoroutineScope()
    val doneFocus = remember { FocusRequester() }
    val chipFocus = remember { FocusRequester() }
    // The television's rail (HX76): one stop per scope, and the Search entry.
    val railFocus = remember { ChatPicking.Scope.entries.associateWith { FocusRequester() } }
    val railSearchFocus = remember { FocusRequester() }
    var inSearch by remember { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(state.query.isNotBlank()) }
    // The row the remote last stood on, overall and per scope, for Left from the panel and Right from the rail.
    var lastRow by remember { mutableStateOf<Long?>(null) }
    val lastIn = remember { HashMap<ChatPicking.Scope, Long>() }
    var foldFocus by remember { mutableStateOf<Long?>(null) }
    // The rows on screen and where each sits in the lazy list, written as the list is laid out.
    val rowFocus = remember { HashMap<Long, FocusRequester>() }
    val lazyIndex = remember { HashMap<Long, Int>() }
    var inFoot by remember { mutableStateOf(false) }
    // Once a key is pressed the remote is the viewer's, and the first focus stops following the list.
    var keyed by remember { mutableStateOf(false) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val held = remember { IntArray(1) }
    val jump = remember { arrayOfNulls<Job>(1) }
    // True while the remote is jumping and for a moment after the last jump. The rows a held Down
    // runs past are not counted, so each jump does not also start a count in Telegram whose
    // answer rebuilds the whole list while the next jump is being drawn (52% janky frames on the
    // Mi TV, HX76). Cleared by time, not by the key's release, which can land on another control.
    var racing by remember { mutableStateOf(false) }
    val settle = remember { arrayOfNulls<Job>(1) }
    val nowShown by rememberUpdatedState(shown)
    val nowPlace by rememberUpdatedState(placeOf)
    val doneReady = !(required && state.ticked.isEmpty())

    fun focusRow(at: Int) {
        val list = nowShown
        if (list.isEmpty() || jump[0]?.isActive == true) return
        val row = list[at.coerceIn(0, list.lastIndex)]
        racing = true
        settle[0]?.cancel()
        settle[0] = scope.launch {
            delay(RACE_SETTLE_MS)
            racing = false
        }
        jump[0] = scope.launch {
            val index = lazyIndex[row.id]
            if (index != null && listState.layoutInfo.visibleItemsInfo.none { it.key == row.id }) {
                // One item above it stays in sight, so the jump reads as a move and not a new page.
                listState.scrollToItem((index - 1).coerceAtLeast(0))
            }
            withFrameNanos { }
            rowFocus[row.id]?.let { runCatching { it.requestFocus() } }
        }
    }
    fun toTop() {
        scope.launch {
            listState.scrollToItem(0)
            withFrameNanos { }
            runCatching { (if (tv) railFocus.getValue(state.scope) else chipFocus).requestFocus() }
        }
    }
    // Right or Select on a rail entry: the scope is chosen, and the remote goes to its list, on the
    // row it last stood on there or else the first (HX76). An empty scope keeps the remote on the rail.
    fun intoList(chosen: ChatPicking.Scope) {
        model.scope(chosen)
        scope.launch {
            withFrameNanos { }
            withFrameNanos { }
            val list = nowShown
            if (list.isEmpty()) return@launch
            val back = lastIn[chosen]?.let { nowPlace[it] }
            focusRow(back ?: 0)
        }
    }
    fun intoSearch() {
        searching = true
        scope.launch {
            withFrameNanos { }
            withFrameNanos { }
            runCatching { searchFocus.requestFocus() }
        }
    }
    fun backToRow() {
        val at = lastRow?.let { nowPlace[it] } ?: 0
        focusRow(at)
    }
    // Done, or while the mandatory choice has nothing ticked, the television's Select all beside it.
    fun toFoot(): Boolean = when {
        doneReady -> runCatching { doneFocus.requestFocus() }.isSuccess
        tv -> runCatching { selectFocus.requestFocus() }.isSuccess
        else -> false
    }
    fun rowKey(event: KeyEvent, id: Long): Boolean {
        val vertical = event.key == Key.DirectionDown || event.key == Key.DirectionUp
        if (event.type == KeyEventType.KeyUp) {
            if (vertical) held[0] = 0
            return false
        }
        if (event.type != KeyEventType.KeyDown) return false
        val at = nowPlace[id] ?: return false
        val page = (listState.layoutInfo.visibleItemsInfo.size - 2).coerceAtLeast(3)
        val end = if (rtl) Key.DirectionLeft else Key.DirectionRight
        val start = if (rtl) Key.DirectionRight else Key.DirectionLeft
        return when (event.key) {
            end, Key.MediaPlayPause, Key.MediaPlay -> toFoot()
            start -> if (tv) {
                toTop()
                true
            } else {
                val list = nowShown
                val band = list[at].band
                val bandStart = list.indexOfFirst { it.band == band }
                when {
                    at > bandStart -> focusRow(bandStart)
                    bandStart == 0 -> toTop()
                    else -> {
                        val before = list[bandStart - 1].band
                        focusRow(list.indexOfFirst { it.band == before })
                    }
                }
                true
            }
            Key.PageDown, Key.ChannelDown -> { focusRow(at + page); true }
            Key.PageUp, Key.ChannelUp -> { if (at == 0) toTop() else focusRow(at - page); true }
            Key.DirectionDown, Key.DirectionUp -> {
                // A held arrow walks row by row for a moment, then a screenful at a time; a jump
                // still running swallows the repeats, so a slow television is not queued up.
                held[0]++
                if (held[0] <= HOLD_REPEATS) return false
                focusRow(if (event.key == Key.DirectionDown) at + page else at - page)
                true
            }
            else -> false
        }
    }
    fun rowModifier(id: Long, requester: FocusRequester): Modifier = Modifier
        .focusRequester(requester)
        .onFocusChanged {
            if (it.hasFocus) {
                onRow = id
                lastRow = id
                lastIn[state.scope] = id
            } else if (onRow == id) {
                onRow = null
            }
        }
        .onPreviewKeyEvent { rowKey(it, id) }
    // Enter in the search box (the television keyboard's Search) puts the remote on the first answer.
    val toFirstRow = { if (nowShown.isNotEmpty()) focusRow(0) }

    LaunchedEffect(Unit) { if (typed != state.query) model.type(typed) }
    LaunchedEffect(chats.isNotEmpty()) { if (chats.isNotEmpty()) model.start(chats) }
    // Nothing is counted once the screen is gone, and a count already running is cancelled.
    DisposableEffect(model) { onDispose { model.stop() } }
    LaunchedEffect(state.saved) { if (state.saved) onClose() }
    // The remote starts on the first row, where OK ticks, not in the search box (HX76). Until a row
    // is there it stands on the filters, and moves down to the first row when one lands, unless a
    // key has been pressed since.
    // It starts on Done instead when the Recommended band ticked something, so most viewers press
    // OK once; a band that lands late (up to 3 s) never moves a remote that has already moved. No
    // chats at all: it starts on the rail.
    val firstId = shown.firstOrNull()?.id
    val startOnDone = tv && !state.recommending && state.recommended.isNotEmpty() && state.ticked.isNotEmpty() && doneReady
    LaunchedEffect(firstId, startOnDone) {
        if (!tv || keyed) return@LaunchedEffect
        // The list lays its first items out a frame after this runs, and a request before that
        // finds nothing to focus.
        withFrameNanos { }
        withFrameNanos { }
        if (keyed) return@LaunchedEffect
        if (startOnDone && runCatching { doneFocus.requestFocus() }.isSuccess) return@LaunchedEffect
        val first = firstId?.let { rowFocus[it] }
        if (first == null || runCatching { first.requestFocus() }.isFailure) {
            runCatching { railFocus.getValue(state.scope).requestFocus() }
        }
    }
    // Pressing a fold opens it, and the remote stands on its first chat.
    LaunchedEffect(foldFocus) {
        val id = foldFocus ?: return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        nowPlace[id]?.let { focusRow(it) }
        foldFocus = null
    }
    // The background count waits while the remote jumps, so the list is not rebuilt mid jump.
    LaunchedEffect(racing) { model.hold(racing) }
    LaunchedEffect(chats.isNotEmpty(), listComplete) { if (tv && listComplete && chats.isNotEmpty()) model.countRest(chats) }
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
            // On the television Back from the list or the foot goes up to the filters first, so
            // the top of a long list is one press away; Back there leaves.
            tv && (onRow != null || inFoot || inSearch) -> toTop()
            // Nothing behind the mandatory choice to go back to: Back leaves the app (or, on the
            // desktop, does nothing), and the ticks are not lost to a discard question.
            required -> onLeave()
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
    val tvScopeName = when {
        state.query.isNotBlank() -> s.pickerResults
        state.scope == ChatPicking.Scope.All -> s.pickerAllChats
        else -> scopeLabel(state.scope, s)
    }
    val selectAllButton: @Composable () -> Unit = {
        QuietButton(
            when {
                !tv -> if (allTicked) s.commonClear else s.browseSelectAll
                allTicked -> s.pickerClearScope(tvScopeName, visible.size)
                else -> s.pickerSelectScope(tvScopeName, visible.size)
            },
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
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) keyed = true
                false
            }
            .then(outer),
        contentAlignment = Alignment.TopCenter,
    ) {
        val searchBox: @Composable () -> Unit = {
        val field = Modifier.fillMaxWidth().focusRequester(searchFocus).onFocusChanged { inSearch = it.hasFocus }
        Box(Modifier.padding(top = 12.dp)) {
            val onType: (String) -> Unit = { typed = it; model.type(it) }
            if (searchField != null) {
                searchField(typed, onType, s.commonSearch, field, toFirstRow)
            } else {
                // In the settings cards' own shape and fill, with the glass in front, as
                // the app's other search boxes are, not Material's sharp outlined box.
                OutlinedTextField(
                    value = typed,
                    onValueChange = onType,
                    singleLine = true,
                    placeholder = { Text(s.commonSearch) },
                    leadingIcon = { Icon(TmIcons.Search, contentDescription = null, tint = Tone.muted) },
                    shape = RoundedCornerShape(Corner.Large),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Tone.surface,
                        unfocusedContainerColor = Tone.surface,
                        focusedBorderColor = Tone.accent,
                        unfocusedBorderColor = Tone.outline,
                        cursorColor = Tone.accent,
                    ),
                    // A keyboard's Enter goes to the first answer; a phone's keyboard
                    // only closes, since a touch screen has no focus to move.
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { if (compact) toFirstRow() else defaultKeyboardAction(ImeAction.Done) }),
                    modifier = field,
                )
            }
        }
        }
        val listPane: @Composable ColumnScope.() -> Unit = {
            // On the television the list steps instead of sliding each focused row a third of the way
            // down: the slide was a fresh layout of the list on every frame of every press (HX76).
            EdgeStep(start = 24.dp, end = 24.dp, enabled = tv) {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    state = listState,
                    contentPadding = PaddingValues(top = if (form == DeviceForm.Phone) 16.dp else 0.dp, bottom = 8.dp),
                ) { CountingScope(this).run {
                    if (!tv) {
                    // A phone stacks Back, the title and the words; a wide screen has the room to put Back
                    // beside the title, which leaves more of it for the rows.
                    val headingText = if (required) s.pickerGateTitle else s.pickerChooseChats
                    val blurbText = if (required) s.pickerGateBody else s.pickerBlurbOnly
                    if (compact) {
                        item(key = "title") {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    if (!required) QuietButton(s.commonBack, icon = true, onClick = leave)
                                    Text(
                                        headingText,
                                        style = MaterialTheme.typography.headlineMedium,
                                        color = Tone.text,
                                        modifier = Modifier.semantics { heading() },
                                    )
                                }
                                Text(blurbText, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
                            }
                        }
                    } else {
                        if (!required) {
                            item(key = "back") {
                                QuietButton(s.commonBack, icon = true, onClick = leave)
                            }
                        }
                        item(key = "title") {
                            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    headingText,
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = Tone.text,
                                    modifier = Modifier.semantics { heading() },
                                )
                                Text(blurbText, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
                            }
                        }
                    }
                    item(key = "search") { searchBox() }
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
                                        modifier = if (state.scope == scope) Modifier.focusRequester(chipFocus) else Modifier,
                                    )
                                }
                            }
                            // The television's Select all sits in the foot beside Done, where Right
                            // from any row lands, not at the far end of the filters (HX76).
                            if (compact && !tv && visible.isNotEmpty()) selectAllButton()
                        }
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
                    val divider = 16.dp + (if (tv) 52.dp else 44.dp) + 12.dp
                    for ((band, inBand) in bands) {
                        if (holding && band != ChatPicking.Band.Saved) continue
                        val label = if (bands.size > 1 || holding) bandLabel(band, s) else null
                        if (label != null) {
                            item(key = "band-$band") { BandLabel(label) }
                        }
                        if (band == ChatPicking.Band.Recommended && state.belowBar) {
                            item(key = "below-bar") {
                                Text(
                                    s.pickerBelowBar(ChatSuggestions.MIN_VIDEOS),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Tone.muted,
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                                )
                            }
                        }
                        val base = count
                        inBand.forEachIndexed { i, entry -> lazyIndex[itemRow(entry).id] = base + i }
                        // One content type, so a jump of the remote reuses the rows it scrolls away
                        // rather than building a screenful from nothing in one frame.
                        itemsIndexed(inBand, key = { _, entry -> itemRow(entry).id }, contentType = { _, _ -> ROW_TYPE }) { index, item ->
                            val row = itemRow(item)
                            // Only a row on screen is counted: this effect leaves with the row. It
                            // waits for the list to stop (a fling, a held Down), so the rows run past
                            // on the way are never counted at all.
                            if (item is ChatPicking.Item.Chat && row.band != ChatPicking.Band.Gone) LaunchedEffect(row.id) {
                                snapshotFlow { racing || listState.isScrollInProgress }.first { !it }
                                model.count(row.id)
                            }
                            val ticked = row.id in state.ticked
                            val requester = remember(row.id) { FocusRequester() }
                            DisposableEffect(row.id) {
                                rowFocus[row.id] = requester
                                onDispose { if (rowFocus[row.id] === requester) rowFocus.remove(row.id) }
                            }
                            val card = rowModifier(row.id, requester)
                                .groupedCard(GroupPlace(first = index == 0, last = index == inBand.lastIndex), divider)
                            if (item is ChatPicking.Item.Folded) {
                                FoldRow(
                                    label = s.pickerFold(item.count),
                                    modifier = card,
                                    onOpen = {
                                        foldFocus = ChatPicking.foldedIn(rows, state.counted, state.ticked, item.band).firstOrNull()?.id
                                        foldOpen = foldOpen + item.band
                                    },
                                )
                            } else if (row.band == ChatPicking.Band.Gone) {
                                GoneRow(row, tv, card, onRemove = { model.toggle(row.id) })
                            } else {
                                val shape = ChatSuggestions.shape(state.counted[row.id])
                                PickRow(
                                    row = row,
                                    ticked = ticked,
                                    shape = shape,
                                    tv = tv,
                                    modifier = card,
                                    onToggle = { tick(!ticked); model.toggle(row.id) },
                                    onCountAgain = { model.countAgain(row.id) },
                                    onToggleStar = { model.toggleStar(row.id) },
                                    onSelectAll = selectAllNow,
                                )
                            }
                        }
                    }
                    if (holding) {
                        item(key = "band-recommended-held") {
                            Column {
                                BandLabel(s.pickerBandRecommended)
                                PickSkeleton(rows = HELD_ROWS, tv = tv, divider = divider)
                            }
                        }
                    }
                } }
            }
        }

        val questionUi: (@Composable () -> Unit)? = asking?.let { question ->
            {
                when (question) {
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
                }
            }
        }
        // A save that failed says so, with Try again and Send logs; nothing tries again on its own.
        val failure: @Composable () -> Unit = {
            val failed = state.error
            if (failed != null) {
                Column(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(failed, style = MaterialTheme.typography.bodyMedium, color = Tone.danger)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        SetupButton(s.commonTryAgain, primary = true, onClick = { model.done(chats) })
                        sendLogs?.invoke()
                    }
                }
            }
        }
        // Nothing ticked is an answer in Settings, and Done reads as what it will do; the
        // mandatory choice needs at least one chat, and says so instead.
        val doneButton: @Composable () -> Unit = {
            val none = state.ticked.isEmpty()
            if (required && none) {
                DisabledDone(s.pickerGateDoneNone)
            } else {
                SetupButton(
                    if (none) s.pickerDoneNone else s.commonDone,
                    primary = true,
                    modifier = Modifier.focusRequester(doneFocus),
                    onClick = { model.done(chats) },
                )
            }
        }
        // The running total and Done on a card of their own, so the foot reads as the bar it is
        // rather than words left under the list.
        val footPane: @Composable ColumnScope.() -> Unit = {
            val question = questionUi
            if (question != null) {
                question()
            } else {
                Column(Modifier.fillMaxWidth()) {
                    failure()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = if (form == DeviceForm.Phone) 12.dp else 0.dp)
                            .groupedCard(GroupPlace.Alone)
                            .onFocusChanged { inFoot = it.hasFocus }
                            .padding(start = ROW_INSET, end = 10.dp, top = 10.dp, bottom = 10.dp),
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
                        doneButton()
                    }
                }
            }
        }

        if (tv) {
            // Three panes (HX76): the scopes, the list, and what is chosen with Done. Every key
            // lands on something that is on screen, so nothing is a shortcut to be found.
            val start = if (rtl) Key.DirectionRight else Key.DirectionLeft
            val end = if (rtl) Key.DirectionLeft else Key.DirectionRight
            val scopeCounts = remember(chats) {
                ChatPicking.Scope.entries.associateWith { sc -> chats.count { ChatPicking.inScope(it, sc) } }
            }
            val title = if (required) s.pickerGateTitle else s.pickerChooseChats
            Column(Modifier.fillMaxSize()) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Tone.text,
                    modifier = Modifier.semantics { heading() },
                )
                Row(
                    Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(Modifier.width(RAIL_WIDTH).fillMaxHeight().focusGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (sc in TV_RAIL) {
                            val n = scopeCounts.getValue(sc)
                            RailEntry(
                                label = scopeLabel(sc, s),
                                detail = if (n > 0) s.formatter.number(n) else null,
                                chosen = state.scope == sc,
                                dim = n == 0,
                                onClick = { intoList(sc) },
                                modifier = Modifier
                                    .focusRequester(railFocus.getValue(sc))
                                    .onPreviewKeyEvent { event ->
                                        if (event.type == KeyEventType.KeyDown && event.key == end) {
                                            intoList(sc)
                                            true
                                        } else {
                                            false
                                        }
                                    },
                            )
                        }
                        RailEntry(
                            label = s.commonSearch,
                            detail = state.query.trim().ifBlank { null },
                            chosen = searching || state.query.isNotBlank(),
                            dim = false,
                            onClick = { intoSearch() },
                            modifier = Modifier
                                .focusRequester(railSearchFocus)
                                .onPreviewKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown && event.key == end) {
                                        intoSearch()
                                        true
                                    } else {
                                        false
                                    }
                                },
                        )
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        if (searching || state.query.isNotBlank()) searchBox()
                        listPane()
                    }
                    Column(
                        Modifier
                            .width(PANEL_WIDTH)
                            .fillMaxHeight()
                            .onFocusChanged { inFoot = it.hasFocus }
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.key == start) {
                                    backToRow()
                                    true
                                } else {
                                    false
                                }
                            },
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val question = questionUi
                        if (question != null) {
                            question()
                        } else {
                            Text(
                                s.pickerYourChoice,
                                style = MaterialTheme.typography.titleMedium,
                                color = Tone.muted,
                                modifier = Modifier.semantics { heading() },
                            )
                            Text(
                                totalLine(total, s),
                                style = MaterialTheme.typography.bodyLarge,
                                color = Tone.text,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            failure()
                            doneButton()
                            if (visible.isNotEmpty()) selectAllButton()
                            if (required) {
                                Text(s.pickerGateBody, style = MaterialTheme.typography.bodySmall, color = Tone.muted, maxLines = 6, overflow = TextOverflow.Ellipsis)
                            }
                            if (shown.isNotEmpty()) {
                                Text(s.pickerTvKeys, style = MaterialTheme.typography.bodySmall, color = Tone.muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        } else {
            Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().fillMaxHeight()) {
                listPane()
                footPane()
            }
        }
    }
}

/** The scopes down the television's left pane, in the order the viewer looks for them. */
private val TV_RAIL = listOf(
    ChatPicking.Scope.All,
    ChatPicking.Scope.Channels,
    ChatPicking.Scope.Groups,
    ChatPicking.Scope.People,
    ChatPicking.Scope.Archived,
)
private val RAIL_WIDTH = 190.dp
private val PANEL_WIDTH = 240.dp

/** The row a line of the list stands for; a fold has no chat, so it gets an id no chat can have. */
private fun itemRow(item: ChatPicking.Item): ChatPicking.Row = when (item) {
    is ChatPicking.Item.Chat -> item.row
    is ChatPicking.Item.Folded -> ChatPicking.Row(Long.MIN_VALUE + item.band.ordinal, "", item.band)
}

/** One stop of the television's rail: a scope (marked when it is the one shown) or Search. */
@Composable
private fun RailEntry(
    label: String,
    detail: String?,
    chosen: Boolean,
    dim: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    val round = RoundedCornerShape(12.dp)
    val ink = when {
        focused -> Tone.onFocusFill
        dim -> Tone.muted
        else -> Tone.text
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(round)
            .background(if (focused) Tone.focusFill else if (chosen) Tone.surface else Color.Transparent)
            .focusRing(focused, round)
            .semantics(mergeDescendants = true) { selected = chosen }
            .clickable(interactionSource = interactions, indication = null, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (chosen) FontWeight.Bold else FontWeight.Medium,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (detail != null) {
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (focused) Tone.onFocusFill else Tone.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The one row that stands for a band's chats with no videos; pressing it opens them in place. */
@Composable
private fun FoldRow(label: String, modifier: Modifier, onOpen: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    val round = RoundedCornerShape(12.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(round)
            .background(if (focused) Tone.focusFill else Color.Transparent)
            .focusRing(focused, round)
            .clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onOpen)
            .padding(horizontal = ROW_INSET, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            color = if (focused) Tone.onFocusFill else Tone.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The mandatory choice's Done while nothing is ticked: the button's shape and place, dimmed and
 * not pressable, so the foot says what is missing rather than offering a press that does nothing.
 */
@Composable
private fun DisabledDone(label: String) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(Tone.surfaceHigh)
            .semantics { disabled() }
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = Tone.muted)
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
 * remote or a keyboard) counts again a chat that could not be counted, or else stars or unstars
 * it (HX30): the same star the Favourites tab reads, one press away without leaving the picker.
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
    onToggleStar: () -> Unit,
    onSelectAll: () -> Unit,
    modifier: Modifier = Modifier,
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
        modifier
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
                    !unknown && event.key == Key.Menu -> {
                        onToggleStar()
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
                customActions = if (unknown) {
                    listOf(CustomAccessibilityAction(retry) { onCountAgain(); true })
                } else {
                    listOf(
                        CustomAccessibilityAction(
                            if (row.starred) s.browseRemoveFavourite else s.browseAddFavourite,
                        ) { onToggleStar(); true },
                    )
                }
            }
            .combinedClickable(
                interactionSource = interactions,
                indication = if (tv) null else androidx.compose.foundation.LocalIndication.current,
                role = Role.Checkbox,
                onLongClick = if (unknown) onCountAgain else onToggleStar,
                onClick = onToggle,
            )
            .padding(horizontal = ROW_INSET, vertical = 10.dp),
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
                        // The row's own long press (the Menu key on a remote or a keyboard)
                        // already toggles it, so the icon says what is true rather than act
                        // itself: a second, separately focusable target on this row would be
                        // a second thing a remote has to find, which the tick box already is.
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
private fun GoneRow(row: ChatPicking.Row, tv: Boolean, modifier: Modifier, onRemove: () -> Unit) {
    val s = LocalStrings.current
    Row(
        modifier.fillMaxWidth().padding(horizontal = ROW_INSET, vertical = 10.dp),
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

/** A row's words and box sit this far in from its card's edges, as a settings row's do. */
private val ROW_INSET = 16.dp

/** How many placeholder rows hold the Recommended band's place while it is worked out. */
private const val HELD_ROWS = 5

/** A band's name over its card, in the settings sections' small capitals and in line with the rows' words. */
@Composable
private fun BandLabel(label: String) {
    SettingsSectionLabel(
        label,
        Modifier.padding(start = ROW_INSET, end = ROW_INSET, top = 20.dp, bottom = 8.dp).semantics { heading() },
    )
}

/**
 * The Recommended band's stand in: rows shaped like the real ones (a round picture, a name, a
 * count and a box) in the same card, shimmering, so the band arrives into the place it was
 * already given.
 */
@Composable
private fun PickSkeleton(rows: Int, tv: Boolean, divider: Dp) {
    val shimmer = rememberShimmer()
    val avatar = if (tv) 52.dp else 44.dp
    val words = LocalStrings.current.pickerLoading
    Column(Modifier.semantics(mergeDescendants = true) { contentDescription = words }) {
        repeat(rows) { index ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .groupedCard(GroupPlace(first = index == 0, last = index == rows - 1), divider)
                    .padding(horizontal = ROW_INSET, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SkeletonBox(shimmer, Modifier.size(avatar), CircleShape)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Names of differing lengths, as a real list has.
                    SkeletonBox(shimmer, Modifier.fillMaxWidth(NAME_WIDTHS[index % NAME_WIDTHS.size]).height(if (tv) 16.dp else 14.dp))
                    SkeletonBox(shimmer, Modifier.width(if (tv) 120.dp else 96.dp).height(if (tv) 12.dp else 10.dp))
                }
                SkeletonBox(shimmer, Modifier.size(20.dp), RoundedCornerShape(Corner.Small))
            }
        }
    }
}

/** The placeholder names' widths, as shares of the row. */
private val NAME_WIDTHS = floatArrayOf(0.55f, 0.4f, 0.65f, 0.35f, 0.5f)

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
    searchField: (@Composable (value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier, onSubmit: () -> Unit) -> Unit)? = null,
    required: Boolean = false,
    onLeave: () -> Unit = onClose,
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
        listComplete = listComplete(everything, connected, refreshing, paging),
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
        required = required,
        onLeave = onLeave,
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
 * What Settings' "Chosen chats" row says under its name, the same on every platform: how many
 * chats are chosen (TMPlayer shows videos only from these), none yet for an account that never
 * answered, or, for one that kept every chat before choosing became mandatory, that it did.
 */
fun chosenChatsDetail(choice: com.tmplayer.data.AddedChoice, s: com.tmplayer.i18n.Strings): String = when (choice.mode) {
    com.tmplayer.data.AddedMode.Picked -> s.pickerRowDetail(choice.chats.size)
    com.tmplayer.data.AddedMode.All -> s.pickerRowDetailAll
    com.tmplayer.data.AddedMode.Unanswered -> s.pickerRowDetail(0)
}

/**
 * Whether the picker is up on the desktop: one switch for the window, read by the shell, which
 * draws [AddChatsScreen] over everything while it is on (the Devices page's switch, with the host
 * in the shell because the screen's view model must be made when it opens and not before). The
 * phone and the television route to it as a screen of their own instead.
 */
object AddChatsState {
    var open by mutableStateOf(false)
}

/**
 * The lazy list's own scope, counting the items given to it so far, so a row's place in the
 * list is known while it is off screen and a jump of the remote can scroll to it (HX76).
 */
private class CountingScope(private val inner: LazyListScope) : LazyListScope by inner {
    var count = 0
        private set

    override fun item(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
        count++
        inner.item(key, contentType, content)
    }

    override fun items(
        count: Int,
        key: ((index: Int) -> Any)?,
        contentType: (index: Int) -> Any?,
        itemContent: @Composable LazyItemScope.(index: Int) -> Unit,
    ) {
        this.count += count
        inner.items(count, key, contentType, itemContent)
    }
}

/** Repeats of a held Down or Up that walk row by row before the rest move a screenful each. */
private const val HOLD_REPEATS = 6

/** How long after the remote's last jump the rows on screen start counting. */
private const val RACE_SETTLE_MS = 400L

private const val ROW_TYPE = "row"
