@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.tmplayer.ui.browse

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.material3.Switch
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.tmplayer.ui.theme.collectIsFocusVisibleAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.LocalIndication
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Stable
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import com.tmplayer.i18n.Messages
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import com.tmplayer.data.AddedChats
import com.tmplayer.data.WorkStatus
import com.tmplayer.ui.components.WorkStatusLine
import com.tmplayer.ui.components.liveMessage
import com.tmplayer.data.ChatBrief
import com.tmplayer.data.ChatCount
import com.tmplayer.data.ChatCountCalls
import com.tmplayer.data.ChatFolderSummary
import com.tmplayer.data.ChatGate
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatPicking
import com.tmplayer.data.ChatSuggestions
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.ChatsGate
import com.tmplayer.data.CountAnswer
import com.tmplayer.data.DeviceForm
import com.tmplayer.data.KidsLock
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.Td
import com.tmplayer.ui.components.EdgeStep
import com.tmplayer.ui.components.bottomFade
import com.tmplayer.ui.components.LoadMoreAhead
import com.tmplayer.ui.components.deviceForm
import com.tmplayer.ui.components.rememberJumpAheadListState
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
import com.tmplayer.ui.theme.focusDrawn
import com.tmplayer.ui.theme.focusRingColor
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.runtime.State
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.role
import androidx.compose.runtime.MutableState
import com.tmplayer.data.AddedChat
import androidx.compose.foundation.lazy.LazyListState
import kotlinx.coroutines.CoroutineScope

/**
 * "Add chats" (HX27): every chat of the account with a tick box and the number chosen at the foot. A full screen on the phone, the television and the desktop alike, so
 * a choice made on one reads the same on the others.
 *
 * The screen decides how things look and nothing else. Which rows there are, in which band, what
 * Select all means under a search or a filter are [ChatPicking]'s; what is recommended is
 * [ChatSuggestions]'; every action is [model]'s. No video count is shown anywhere (Telegram's
 * figure can be wrong): a Recommended row says in words why it is there, and the figures only rank.
 *
 * @param chats every chat of the account, unnarrowed ([ChatListViewModel.everything]).
 * @param listComplete whether the chat list is whole: the Gone band only stands once it is.
 * @param partial the list came back short of the account (HX32), so the screen offers [onRetry].
 * @param loadingMore the next chats are being read, so [onLoadMore] waits for them.
 * @param onLoadMore asks the chat list for its next chats as the end of this list nears; nothing
 *   pages by itself, so an idle screen costs nothing.
 * @param folders the account's Telegram folders (HX29), offered as filters beside the scopes. Their
 *   members are read only once this screen is open ([folderStatus] says how that went), never
 *   ahead of it, since reading them on a large account asks Telegram for every chat.
 * @param onRetryFolders reads the folders' members again, from a folder that could not be read.
 * @param sendLogs the platform's Send logs button, drawn beside Try again when the list is short.
 * @param searchField the television's own search box, which only the app has, with what its
 *   keyboard's Search does (the remote goes to the first answer); the touch and
 *   desktop screens draw a plain text field.
 * @param required the mandatory chat choice ([com.tmplayer.data.ChatsGate]): its own heading and
 *   words, no Back button, Done only once a chat is ticked, and Back (the system's, Esc) calls
 *   [onLeave] instead of asking to discard, since there is nothing behind it to go back to.
 * @param onLeave what Back does in [required] mode: leaves the app on the phone and the
 *   television, nothing on the desktop.
 * @param confirmLeave in [required] mode, Back asks "Leave without choosing?" before [onLeave],
 *   with or without ticks, since a stray Back would close the app on the one question it asks.
 *   Off where leaving does nothing (the desktop).
 * @param onContinue in [required] mode, what Continue without choosing does (ON-4): offered only
 *   when the list is whole, nothing was recommended and nothing is ticked, since then there is
 *   nothing sensible to tick and Back would leave the app. The gate still stands whenever a
 *   Recommended band exists. Null offers nothing.
 * @param onAdded how many chats the save added, told just before [onClose] when it is more than
 *   none, so the host can say "3 chats added" as the picker leaves (WP-95).
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
    folders: List<ChatFolderSummary> = emptyList(),
    folderStatus: FolderStatus = FolderStatus(),
    onRetryFolders: () -> Unit = {},
    sendLogs: (@Composable () -> Unit)? = null,
    searchField: (@Composable (value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier, onSubmit: () -> Unit) -> Unit)? = null,
    required: Boolean = false,
    onLeave: () -> Unit = onClose,
    confirmLeave: Boolean = false,
    onContinue: (() -> Unit)? = null,
    onAdded: (Int) -> Unit = {},
) {
    // Split in three so that no method grows past what the release build's ProGuard can write stack
    // map frames for (HX1, as SeriesPanel was): PickUi is what the screen remembers, PickList the
    // rows it derives, PickScreen what it draws and how its keys behave.
    val s = LocalStrings.current
    val form = deviceForm()
    val tv = form == DeviceForm.Tv
    val stateHolder = model.state.collectAsState()
    val state by stateHolder
    val choice by AddedChats.choice.collectAsState()
    // "All chats" (HX86): every chat counts, the ticks are disabled (and kept), the folders and the
    // Recommended band are greyed.
    val allOn = state.allChats
    // The television's held Down jumps a screen at a time: the rows it lands on are composed ahead (HX76).
    val listState = rememberJumpAheadListState(enabled = tv)
    val ui = remember { PickUi() }
    // Typed here and handed on, since a text field fed back through a flow a frame late loses keys.
    val typedState = rememberSaveable { mutableStateOf(state.query) }
    val searchingState = rememberSaveable { mutableStateOf(state.query.isNotBlank()) }
    val lst = rememberPickList(chats, starred, listComplete, folders, folderStatus, stateHolder, choice.chats, tv, allOn, ui)

    // The remote's way round a long list (HX76): the first row takes the focus, not the search
    // box; Right (the end side) goes to Done from any row; Left (the start side) goes back to the
    // start of the row's band, then the band before, then the filters; Back goes up to the
    // filters before it leaves; Page and Channel Up or Down, and Down or Up held, move a screenful.
    val scope = rememberCoroutineScope()
    val nowShownState = rememberUpdatedState(lst.shown)
    val nowPlaceState = rememberUpdatedState(lst.placeOf)
    val nowFolderReadState = rememberUpdatedState(lst.folderRead)
    val nowPlace by nowPlaceState
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val doneReady = !(required && state.ticked.isEmpty() && !allOn)
    // The way out of a required choice with nothing to choose (ON-4): the head start has finished
    // with no recommendation and the whole list is in.
    val showHint = required && listComplete && !state.recommending && !allOn &&
        state.recommended.isEmpty() && state.ticked.isEmpty()
    val showEscape = showHint && onContinue != null
    val pick = PickScreen(
        s = s, form = form, state = state, allOn = allOn, chats = chats, listComplete = listComplete, partial = partial,
        model = model, onClose = onClose, onLeave = onLeave, confirmLeave = confirmLeave, required = required,
        onContinue = onContinue, onRetry = onRetry, onRetryFolders = onRetryFolders, sendLogs = sendLogs,
        searchField = searchField, folderStatus = folderStatus, listState = listState, ui = ui, lst = lst,
        scope = scope, nowShownState = nowShownState, nowPlaceState = nowPlaceState,
        nowFolderReadState = nowFolderReadState, rtl = rtl, doneReady = doneReady, showHint = showHint,
        showEscape = showEscape, tick = rememberToggleTick(), typedState = typedState, searchingState = searchingState,
    )

    LaunchedEffect(Unit) { if (typedState.value != state.query) model.type(typedState.value) }
    LaunchedEffect(chats.isNotEmpty()) { if (chats.isNotEmpty()) model.start(chats) }
    // Saved Messages may arrive after the rest of the list: a fresh start ticks it when it does (HX86).
    val savedId = remember(chats) { ChatPicking.savedId(chats) }
    LaunchedEffect(savedId) { model.knowSaved(savedId) }
    // Nothing is counted once the screen is gone, and a count already running is cancelled.
    DisposableEffect(model) { onDispose { model.stop() } }
    LaunchedEffect(state.saved) {
        if (state.saved) {
            if (state.justAdded > 0) onAdded(state.justAdded)
            onClose()
        }
    }
    // The remote starts on the first row, where OK ticks, not in the search box (HX76). Until a row
    // is there it stands on the filters, and moves down to the first row when one lands, unless a
    // key has been pressed since.
    // It starts on Done instead when something is already ticked and recommended (a picker
    // reopened over a choice already made: nothing is ticked for a first run); a band that lands
    // late (up to 3 s) never moves a remote that has already moved. No chats at all: it starts
    // on the rail.
    val firstId = lst.shown.firstOrNull()?.id
    // Only over a choice already made (HX86): a fresh install starts with Saved Messages ticked, and
    // its remote still starts on the first row.
    val startOnDone = tv && !state.recommending && state.recommended.isNotEmpty() && state.ticked.isNotEmpty() && doneReady &&
        state.added.isNotEmpty() && !allOn
    LaunchedEffect(firstId, startOnDone, allOn) {
        if (!tv || ui.keyed) return@LaunchedEffect
        // The list lays its first items out a frame after this runs, and a request before that
        // finds nothing to focus.
        withFrameNanos { }
        withFrameNanos { }
        if (ui.keyed) return@LaunchedEffect
        // Every row is disabled while all chats count, so the switch is where the remote starts.
        if (allOn && runCatching { ui.switchFocus.requestFocus() }.isSuccess) return@LaunchedEffect
        if (startOnDone && runCatching { ui.doneFocus.requestFocus() }.isSuccess) return@LaunchedEffect
        val first = firstId?.let { ui.rowFocus[it] }
        if (first == null || runCatching { first.requestFocus() }.isFailure) {
            runCatching { pick.railNow().requestFocus() }
        }
    }
    // Pressing a fold opens it, and the remote stands on its first chat.
    LaunchedEffect(ui.foldFocus) {
        val id = ui.foldFocus ?: return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        nowPlace[id]?.let { pick.focusRow(it) }
        ui.foldFocus = null
    }
    // The background count waits while the remote jumps, so the list is not rebuilt mid jump.
    // LaunchedEffect(Unit), not LaunchedEffect(racing): keying on racing read it in the body, so
    // the whole screen recomposed on every settle flip (HX76); snapshotFlow reads it off-composition.
    LaunchedEffect(Unit) { snapshotFlow { ui.racing }.collect { model.hold(it) } }
    // The whole account is counted behind the first band on every platform, so the Recommended band
    // finds a video channel however far down the list it sits (T5); the television asks one at a time.
    LaunchedEffect(chats.isNotEmpty(), listComplete) {
        if (!listComplete || chats.isEmpty()) return@LaunchedEffect
        if (tv) model.countRest(chats, AddChatsViewModel.BACKGROUND_ON_TV, AddChatsViewModel.BATCH_ON_TV_MS) else model.countRest(chats)
    }
    // The list's own end triggers the chat list's next chats, one request at a time.
    LoadMoreAhead(listState, enabled = !listComplete && !partial && !loadingMore, onLoadMore = onLoadMore)
    // Back from a question puts the remote on the button that asked it.
    var wasAsking by remember { mutableStateOf(false) }
    LaunchedEffect(ui.asking) {
        if (ui.asking == null && wasAsking && tv) pick.toFoot()
        wasAsking = ui.asking != null
    }
    BackHandler(onBack = pick.leave)
    pick.Body(modifier)
}

/**
 * What the screen remembers across recompositions: the remote's stops, the flags its key handlers
 * read, and the maps from a row to its focus and its place. One object, made once, so the screen's
 * own method holds none of them as locals. The states are read where they are used, never when
 * the screen is composed, so a focus move recomposes nothing.
 */
@Stable
private class PickUi {
    val searchFocus = FocusRequester()
    val doneFocus = FocusRequester()
    val chipFocus = FocusRequester()
    // The television's rail (HX76): one stop per scope, and the Search entry.
    val railFocus = ChatPicking.Scope.entries.associateWith { FocusRequester() }
    // And one stop per folder under them (HX29), made as folders come.
    val folderFocus = HashMap<Int, FocusRequester>()
    // A folder that could not be read has no rows, so the remote lands on its Try again instead.
    val retryFocus = FocusRequester()
    val railSearchFocus = FocusRequester()
    // The television's own keyboard in the right-hand panel while the words are being typed: the
    // system keyboard covered the list it was typing for. The remote is on one of its keys.
    val keyFocus = FocusRequester()
    // The remote is on the "All chats" switch, the first stop on the television (HX86).
    val switchFocus = FocusRequester()
    val escapeFocus = FocusRequester()
    // The row the remote last stood on, per scope or folder, for Right from the rail.
    val lastIn = HashMap<Any, Long>()
    // The rows on screen and where each sits in the lazy list, written as the list is laid out.
    val rowFocus = HashMap<Long, FocusRequester>()
    val lazyIndex = HashMap<Long, Int>()
    val held = IntArray(1)
    val jump = arrayOfNulls<Job>(1)
    val settle = arrayOfNulls<Job>(1)
    var asking by mutableStateOf<Ask?>(null)
    // The row the remote stands on, kept here because the short list reads it (a focused row never folds away).
    var onRow by mutableStateOf<Long?>(null)
    var inSearch by mutableStateOf(false)
    var inKeys by mutableStateOf(false)
    // The row the remote last stood on, for Left from the panel.
    var lastRow by mutableStateOf<Long?>(null)
    var foldFocus by mutableStateOf<Long?>(null)
    var inFoot by mutableStateOf(false)
    var onSwitch by mutableStateOf(false)
    // The remote is on the Recommended band's button, which is not a row: Back from it climbs too.
    var onBandButton by mutableStateOf(false)
    // Once a key is pressed the remote is the viewer's, and the first focus stops following the list.
    var keyed by mutableStateOf(false)
    // True while the remote is jumping and for a moment after the last jump. The rows a held Down
    // runs past are not counted, so each jump does not also start a count in Telegram whose
    // answer rebuilds the whole list while the next jump is being drawn (52% janky frames on the
    // Mi TV, HX76). Cleared by time, not by the key's release, which can land on another control.
    var racing by mutableStateOf(false)
}

/** The rows the screen derives from the chats, the folders and the viewer's choices, and how they sit in bands. */
private class PickList(
    val pickable: List<ChatFolderSummary>,
    val folderId: Int?,
    val folderRead: FolderRead,
    val rows: List<ChatPicking.Row>,
    val foldOpen: MutableState<Set<ChatPicking.Band>>,
    val visible: Set<Long>,
    val holding: Boolean,
    val bands: Map<ChatPicking.Band, List<ChatPicking.Item>>,
    val shown: List<ChatPicking.Row>,
    val placeOf: HashMap<Long, Int>,
)

@Composable
private fun rememberPickList(
    chats: List<ChatSummary>,
    starred: Set<Long>,
    listComplete: Boolean,
    folders: List<ChatFolderSummary>,
    folderStatus: FolderStatus,
    stateHolder: State<AddChatsViewModel.State>,
    addedNames: List<AddedChat>,
    tv: Boolean,
    allOn: Boolean,
    ui: PickUi,
): PickList {
    // Read through the holder inside the derived values below, as the screen's own state was, so
    // they follow it without being rebuilt.
    val state by stateHolder

    // A chat ticked that the list does not hold: the Gone rows' candidates. Derived, so a tick on a
    // chat that is there does not rebuild the rows.
    val everyId = remember(chats) { chats.mapTo(HashSet()) { it.id } }
    val strays by remember(everyId) { derivedStateOf { state.ticked.filterTo(LinkedHashSet()) { it !in everyId } } }
    // The folders offered as filters (HX29). One that vanished while it was chosen (deleted in
    // Telegram, or the list read again without it) is not chosen any more: the list falls back to
    // every chat rather than sit on a filter with no chip.
    val pickable = remember(folders) { ChatPicking.pickable(folders) }
    val folderId = state.folder?.takeIf { id -> !allOn && pickable.any { it.id == id } }
    val folderRead = folderId?.let { folderStatus.read(it) } ?: FolderRead.Loaded
    val rows = remember(chats, state.query, state.scope, folderId, state.recommended, state.added, starred, listComplete, strays, addedNames, allOn) {
        ChatPicking.rows(
            chats = chats,
            // A chat left since it was ticked has nothing to remove while every chat counts.
            added = if (allOn) emptySet() else strays,
            addedNames = addedNames,
            starred = starred,
            recommended = state.recommended,
            query = state.query,
            scope = state.scope,
            folder = folderId,
            listComplete = listComplete,
            alreadyAdded = state.added,
        )
    }
    // The television folds the chats counted at no videos into one row per band (HX76), unless a
    // search asks a question about every chat. A derived list, so a count that changes nothing
    // visible does not rebuild the rows.
    val foldOpen = remember(state.scope, folderId) { mutableStateOf<Set<ChatPicking.Band>>(emptySet()) }
    val folding = tv && state.query.isBlank()
    // onRow is read untracked (HX76): a focus move alone never re-runs fold over every row (it ran
    // once per held-Down jump, 11 ms each plus a GC spike, even through a narrower derived value,
    // since a derived state tracks what its inner derived states read). The row the remote stands
    // on only matters when a count or a tick refolds, and those are tracked. A row kept for the
    // focus stays unfolded after the remote leaves it, until the next refold.
    val items by remember(rows, folding, foldOpen.value) {
        derivedStateOf {
            if (folding) {
                val focusedRow = Snapshot.withoutReadObservation { ui.onRow }
                val keep = focusedRow?.let { state.ticked + it } ?: state.ticked
                ChatPicking.fold(rows, state.counted, keep, foldOpen.value)
            } else {
                rows.map { ChatPicking.Item.Chat(it) }
            }
        }
    }
    val visible = remember(items) { ChatPicking.visible(items.mapNotNull { (it as? ChatPicking.Item.Chat)?.row }) }

    // The Recommended band is worked out in the first seconds and lands above every other band,
    // which moved the row under a click or a finger down a whole band. Until it is taken its place
    // is held by placeholders, and the bands under it wait with it.
    val holding = state.recommending && state.query.isBlank() && state.scope == ChatPicking.Scope.All && folderId == null
    val bands = remember(items) { items.groupBy { it.band } }
    // The rows as drawn, top to bottom, which is what the remote's jumps count in (HX76). A fold
    // row stands in as a row of its own, so the remote lands on it like any other.
    val shown = remember(bands, holding) {
        bands.flatMap { (band, inBand) ->
            if (holding && band != ChatPicking.Band.Saved) emptyList() else inBand.map(::itemRow)
        }
    }
    val placeOf = remember(shown) { HashMap<Long, Int>(shown.size * 2).apply { shown.forEachIndexed { i, row -> put(row.id, i) } } }
    return PickList(pickable, folderId, folderRead, rows, foldOpen, visible, holding, bands, shown, placeOf)
}

/**
 * What the screen draws and how its keys behave. Made again on each composition, so what its
 * functions hold is what that composition saw, as the closures they replace held it; the states
 * it works on are [PickUi]'s and are read when they are used.
 */
private class PickScreen(
    val s: Messages,
    val form: DeviceForm,
    val state: AddChatsViewModel.State,
    val allOn: Boolean,
    val chats: List<ChatSummary>,
    val listComplete: Boolean,
    val partial: Boolean,
    val model: AddChatsViewModel,
    val onClose: () -> Unit,
    val onLeave: () -> Unit,
    val confirmLeave: Boolean,
    val required: Boolean,
    val onContinue: (() -> Unit)?,
    val onRetry: () -> Unit,
    val onRetryFolders: () -> Unit,
    val sendLogs: (@Composable () -> Unit)?,
    val searchField: (@Composable (value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier, onSubmit: () -> Unit) -> Unit)?,
    val folderStatus: FolderStatus,
    val listState: LazyListState,
    val ui: PickUi,
    val lst: PickList,
    val scope: CoroutineScope,
    val nowShownState: State<List<ChatPicking.Row>>,
    val nowPlaceState: State<HashMap<Long, Int>>,
    val nowFolderReadState: State<FolderRead>,
    val rtl: Boolean,
    val doneReady: Boolean,
    val showHint: Boolean,
    val showEscape: Boolean,
    val tick: (Boolean) -> Unit,
    val typedState: MutableState<String>,
    val searchingState: MutableState<Boolean>,
) {
    val tv = form == DeviceForm.Tv
    val compact = form != DeviceForm.Phone
    val chosen = state.ticked.size
    var typed by typedState
    var searching by searchingState
    val nowShown by nowShownState
    val nowPlace by nowPlaceState
    val nowFolderRead by nowFolderReadState
    val pickable = lst.pickable
    val folderId = lst.folderId
    val folderRead = lst.folderRead
    val rows = lst.rows
    val visible = lst.visible
    val holding = lst.holding
    val bands = lst.bands
    val shown = lst.shown
    val start = if (rtl) Key.DirectionRight else Key.DirectionLeft
    val end = if (rtl) Key.DirectionLeft else Key.DirectionRight
    val onType: (String) -> Unit = { typed = it; model.type(it) }
    val railNow = { folderId?.let { ui.folderFocus.getOrPut(it) { FocusRequester() } } ?: ui.railFocus.getValue(state.scope) }
    val flipAll = { tick(!allOn); model.setAllChats(!allOn, chats, listComplete) }


    fun focusRow(at: Int) {
        val list = nowShown
        if (list.isEmpty() || ui.jump[0]?.isActive == true) return
        val row = list[at.coerceIn(0, list.lastIndex)]
        ui.racing = true
        ui.settle[0]?.cancel()
        ui.settle[0] = scope.launch {
            delay(RACE_SETTLE_MS)
            ui.racing = false
        }
        ui.jump[0] = scope.launch {
            val index = ui.lazyIndex[row.id]
            if (index != null && listState.layoutInfo.visibleItemsInfo.none { it.key == row.id }) {
                // One item above it stays in sight, so the jump reads as a move and not a new page.
                listState.scrollToItem((index - 1).coerceAtLeast(0))
            }
            withFrameNanos { }
            ui.rowFocus[row.id]?.let { runCatching { it.requestFocus() } }
        }
    }
    fun toTop() {
        scope.launch {
            listState.scrollToItem(0)
            withFrameNanos { }
            runCatching { (if (tv) railNow() else ui.chipFocus).requestFocus() }
        }
    }
    // Right or Select on a rail entry: the scope (or folder) is chosen, and the remote goes to its
    // list, on the row it last stood on there or else the first (HX76). An empty one, or a folder
    // not read yet, keeps the remote on the rail.
    fun landIn(key: Any) {
        scope.launch {
            withFrameNanos { }
            withFrameNanos { }
            val list = nowShown
            if (list.isEmpty()) {
                // The button is composed with the list's next frame; ask until it is there.
                if (nowFolderRead == FolderRead.NotLoaded) repeat(5) {
                    if (runCatching { ui.retryFocus.requestFocus() }.getOrDefault(false)) return@launch
                    withFrameNanos { }
                }
                return@launch
            }
            val back = ui.lastIn[key]?.let { nowPlace[it] }
            focusRow(back ?: 0)
        }
    }
    fun intoList(chosen: ChatPicking.Scope) {
        model.scope(chosen)
        landIn(chosen)
    }
    fun intoFolder(id: Int) {
        model.folder(id)
        landIn(id)
    }
    fun intoSearch() {
        searching = true
        scope.launch {
            withFrameNanos { }
            withFrameNanos { }
            // The television types on its own keys; the field stays for the system keyboard (OK on it).
            runCatching { if (tv) ui.keyFocus.requestFocus() else ui.searchFocus.requestFocus() }
        }
    }
    fun backToRow() {
        val at = ui.lastRow?.let { nowPlace[it] } ?: 0
        focusRow(at)
    }
    // Done; with nothing ticked in the mandatory choice it is off and the remote stays put. No picker has a Select all for now (user, 2026-11-03).
    fun toFoot(): Boolean = when {
        doneReady -> runCatching { ui.doneFocus.requestFocus() }.isSuccess
        showEscape -> runCatching { ui.escapeFocus.requestFocus() }.isSuccess
        else -> false
    }
    fun rowKey(event: KeyEvent, id: Long): Boolean {
        val vertical = event.key == Key.DirectionDown || event.key == Key.DirectionUp
        if (event.type == KeyEventType.KeyUp) {
            if (vertical) ui.held[0] = 0
            return false
        }
        if (event.type != KeyEventType.KeyDown) return false
        val at = nowPlace[id] ?: return false
        val page = com.tmplayer.ui.components.PageKeys.listScreenful(listState.layoutInfo.visibleItemsInfo.size)
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
            Key.DirectionDown, Key.DirectionUp -> {
                // A held arrow walks row by row for a moment, then half a screen at a time; a jump
                // still running swallows the repeats, so a slow television is not queued up. Half,
                // not a full page: each jump composes about 4 new rows rather than 7, which is
                // what a held Down cost on the Mi TV (HX76). Page and Channel keys keep a screenful.
                ui.held[0]++
                if (ui.held[0] <= HOLD_REPEATS) return false
                val step = (page / 2).coerceAtLeast(2)
                focusRow(if (event.key == Key.DirectionDown) at + step else at - step)
                true
            }
            // Page, Channel and Fast forward move a screenful (PageKeys owns which keys they are).
            else -> {
                val dir = com.tmplayer.ui.components.PageKeys.step(event.key)
                when {
                    dir == 0 -> false
                    dir < 0 && at == 0 -> { toTop(); true }
                    else -> { focusRow(at + dir * page); true }
                }
            }
        }
    }
    fun rowModifier(id: Long, requester: FocusRequester): Modifier = Modifier
        .focusRequester(requester)
        .onFocusChanged {
            if (it.hasFocus) {
                ui.onRow = id
                ui.lastRow = id
                ui.lastIn[folderId ?: state.scope] = id
            } else if (ui.onRow == id) {
                ui.onRow = null
            }
        }
        .onPreviewKeyEvent { rowKey(it, id) }
    // Enter in the search box (the television keyboard's Search) puts the remote on the first answer.
    val toFirstRow = { if (nowShown.isNotEmpty()) focusRow(0) }

    val leave = {
        when {
            ui.asking != null -> ui.asking = null
            // Back empties the search words first (rule 2: clear, then close), and a remote only
            // from the field: from a row it climbs to the top, as below.
            typed.isNotEmpty() && (!tv || ui.inSearch || ui.inKeys) -> onType("")
            // On the television Back from the list or the foot goes up to the filters first, so
            // the top of a long list is one press away; Back there leaves.
            tv && (ui.onRow != null || ui.inFoot || ui.inSearch || ui.onBandButton || ui.onSwitch) -> toTop()
            // Nothing behind the mandatory choice to go back to: Back leaves the app (or, on the
            // desktop, does nothing). It always asks "Leave without choosing?" first.
            required && confirmLeave && !state.saving -> ui.asking = Ask.Leave
            required -> onLeave()
            model.changed -> ui.asking = Ask.Discard
            else -> onClose()
        }
    }

    @Composable
    fun Body(modifier: Modifier) {
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
                    if (event.type == KeyEventType.KeyDown) ui.keyed = true
                    false
                }
                .then(outer),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (tv) {
                TvLayout()
            } else {
                Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().fillMaxHeight()) {
                    ListPane()
                    FootPane()
                }
            }
        }
    }

    @Composable
    fun SearchBox() {
        val field = Modifier.fillMaxWidth().focusRequester(ui.searchFocus).onFocusChanged { ui.inSearch = it.hasFocus }
        Box(Modifier.padding(top = 12.dp)) {
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
                    // A cross to empty the box, on a touch screen and a pointer. Not on the television:
                    // a second stop beside the field took the remote's Down from the rows (T1's cross
                    // was taken back for it); there Back clears the words.
                    trailingIcon = if (typed.isNotEmpty() && !tv) {
                        { com.tmplayer.ui.components.ClearButton(s.browseClearSearch, onClick = { onType("") }) }
                    } else {
                        null
                    },
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

    @Composable
    fun ColumnScope.ListPane() {
        val windowHeight = LocalWindowInfo.current.containerSize.height
        val shortWindow = windowHeight in 1 until with(LocalDensity.current) { 480.dp.roundToPx() }
        val bigFont = LocalDensity.current.fontScale >= 1.5f
        // The count of chats read so far, above the list and not in it: a list scrolled down
        // would hide it, and on a big account that is where the viewer is (WP-95). Nothing
        // while the list is whole, and no space taken then.
        if (!listComplete && !partial) {
            val sofar = rememberThrottledCount(chats.size)
            val wait = s.pickerLoading
            Column(Modifier.fillMaxWidth()) {
                WorkStatusLine(
                    WorkStatus.Syncing(what = if (sofar > 0) s.pickerLoadingCount(sofar) else wait),
                    asked = true,
                )
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp).semantics { contentDescription = wait },
                    color = Tone.accent,
                    trackColor = Tone.track,
                )
            }
        }
        // On the television the list steps instead of sliding each focused row a third of the way
        // down: the slide was a fresh layout of the list on every frame of every press (HX76).
        EdgeStep(start = 24.dp, end = 24.dp, enabled = tv) {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().then(if (form == DeviceForm.Phone) Modifier.bottomFade({ listState.canScrollForward }, 20.dp, Tone.background) else Modifier),
                state = listState,
                contentPadding = PaddingValues(top = if (form == DeviceForm.Phone) 16.dp else 0.dp, bottom = 8.dp),
            ) {
                CountingScope(this).run {
                    val divider = 16.dp + (if (tv) 52.dp else 44.dp) + 12.dp
                    topItems(shortWindow, bigFont)
                    partialItem()
                    emptyItem()
                    folderItems()
                    bandItems(divider)
                    heldItem(divider)
                }
            }
        }
    }

    private fun CountingScope.topItems(shortWindow: Boolean, bigFont: Boolean) {
        if (!tv) {
            // A phone stacks Back, the title and the words; a wide screen has the room to put Back
            // beside the title, which leaves more of it for the rows.
            val headingText = if (required) s.setupWhichTitle else s.pickerChooseChats
            val blurbText = if (required) s.setupWhichBody else s.pickerBlurbOnly
            if (compact || shortWindow) {
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
                        if (!shortWindow) Text(blurbText, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
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
                        // At a large font the words give way to the switch (HX86): the filters and a whole row stay in sight.
                        if (!bigFont) Text(blurbText, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
                    }
                }
            }
            item(key = "all-chats") {
                Box(Modifier.padding(top = 12.dp)) { AllChatsSwitch(allOn, tv = false, onToggle = flipAll) }
            }
            item(key = "search") { SearchBox() }
            item(key = "scopes") {
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        for (scope in ChatPicking.Scope.entries) {
                            val here = folderId == null && state.scope == scope
                            CopyChip(
                                label = scopeLabel(scope, s),
                                chosen = here,
                                onClick = { model.scope(scope) },
                                modifier = if (here) Modifier.focusRequester(ui.chipFocus) else Modifier,
                            )
                            // Telegram's folders come right after All, as they do in its own tab bar (HX29).
                            if (scope == ChatPicking.Scope.All) for (folder in pickable) {
                                CopyChip(
                                    label = folderLabel(folder, s),
                                    chosen = folderId == folder.id,
                                    onClick = { model.folder(folder.id) },
                                    modifier = if (folderId == folder.id) Modifier.focusRequester(ui.chipFocus) else Modifier,
                                    enabled = !allOn,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun CountingScope.partialItem() {
        if (partial) {
            item(key = "partial") {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(s.chatsSomeNotLoaded, style = MaterialTheme.typography.bodyMedium, color = Tone.danger, modifier = Modifier.liveMessage())
                    // Wraps: the two buttons side by side ran off a narrow phone at a large font.
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        SetupButton(s.commonTryAgain, primary = true, onClick = onRetry)
                        sendLogs?.invoke()
                    }
                }
            }
        }
    }

    private fun CountingScope.emptyItem() {
        if (rows.isEmpty()) {
            item(key = "empty") {
                val words = when {
                    state.query.isNotBlank() -> s.chatsNoMatch(state.query.trim())
                    // A folder whose members are not read yet is not empty, and one that
                    // could not be read is not empty either (HX32): it says so.
                    folderId != null && folderRead == FolderRead.Loading -> s.browseFolderLoading
                    folderId != null && folderRead == FolderRead.NotLoaded -> s.browseFolderNotLoaded
                    folderId != null -> s.pickerFolderEmpty
                    else -> s.pickerEmpty
                }
                // While the list is still arriving an empty screen is not an answer yet.
                if (listComplete || partial || folderId != null) {
                    Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(words, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
                        if (folderRead == FolderRead.NotLoaded) {
                            SetupButton(
                                s.commonTryAgain,
                                primary = true,
                                modifier = Modifier
                                    .focusRequester(ui.retryFocus)
                                    .onFocusChanged { ui.onBandButton = it.hasFocus },
                                onClick = onRetryFolders,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun CountingScope.folderItems() {
        // A folder still being read shows its rows from the last snapshot with a line over them.
        if (rows.isNotEmpty() && folderId != null && folderRead != FolderRead.Loaded) {
            item(key = "folder-reading") {
                Text(
                    if (folderRead == FolderRead.Loading) s.browseFolderLoading else s.browseFolderNotLoaded,
                    style = MaterialTheme.typography.bodySmall,
                    color = Tone.muted,
                    modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 8.dp),
                )
            }
        }
        // A folder is a set the viewer made in Telegram, so it can be taken whole with one press (HX29):
        // the chats listed here and no others, a large folder after a question. Not Select all over the account.
        if (folderId != null && folderRead == FolderRead.Loaded && visible.size >= 2) {
            item(key = "folder-all") {
                val every = visible.all { it in state.ticked }
                val words = if (every) s.pickerRemoveThese(visible.size) else s.pickerAddThese(visible.size)
                SetupButton(
                    words,
                    modifier = Modifier
                        .padding(start = ROW_INSET, end = ROW_INSET, bottom = 10.dp)
                        .onFocusChanged { ui.onBandButton = it.hasFocus }
                        .onPreviewKeyEvent { event ->
                            val end = if (rtl) Key.DirectionLeft else Key.DirectionRight
                            val start = if (rtl) Key.DirectionRight else Key.DirectionLeft
                            when {
                                event.type != KeyEventType.KeyDown -> false
                                event.key == end -> toFoot()
                                event.key == start && tv -> { toTop(); true }
                                else -> false
                            }
                        },
                    onClick = {
                        when {
                            every -> { tick(false); model.clear(visible) }
                            ChatPicking.needsConfirm(model.adding(visible)) -> ui.asking = Ask.AddMany(visible)
                            else -> { tick(true); model.selectAll(visible) }
                        }
                    },
                )
            }
        }
    }

    private fun CountingScope.bandItems(divider: Dp) {
        for ((band, inBand) in bands) {
            if (holding && band != ChatPicking.Band.Saved) continue
            val label = if (bands.size > 1 || holding) bandLabel(band, inBand.size, s) else null
            if (label != null) {
                item(key = "band-$band") { BandLabel(label) }
            }
            if (band == ChatPicking.Band.Recommended && state.belowBar) {
                item(key = "below-bar") {
                    Text(
                        s.pickerBelowBarMany,
                        style = MaterialTheme.typography.bodySmall,
                        color = Tone.muted,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    )
                }
            }
            // One explicit press takes the whole band (the pickers tick nothing for the
            // viewer): 20 chats are one press, not 20 ticks or 40 presses of a remote.
            // Not for the fallback band, which says nothing reached the bar.
            if (band == ChatPicking.Band.Recommended && !state.belowBar && inBand.size >= 2) {
                item(key = "band-recommended-all") {
                    val ids = inBand.mapNotNullTo(LinkedHashSet()) { (it as? ChatPicking.Item.Chat)?.row?.id }
                    val every = ids.all { it in state.ticked }
                    val words = if (every) s.pickerRemoveThese(ids.size) else s.pickerAddThese(ids.size)
                    SetupButton(
                        words,
                        enabled = !allOn,
                        modifier = Modifier
                            .padding(start = ROW_INSET, end = ROW_INSET, bottom = 10.dp)
                            .onFocusChanged { ui.onBandButton = it.hasFocus }
                            // The remote's Right goes to Done and Left to the filters, as from a row.
                            .onPreviewKeyEvent { event ->
                                val end = if (rtl) Key.DirectionLeft else Key.DirectionRight
                                val start = if (rtl) Key.DirectionRight else Key.DirectionLeft
                                when {
                                    event.type != KeyEventType.KeyDown -> false
                                    event.key == end -> toFoot()
                                    event.key == start && tv -> { toTop(); true }
                                    else -> false
                                }
                            },
                        onClick = {
                            tick(!every)
                            if (every) model.clear(ids) else model.selectAll(ids)
                        },
                    )
                }
            }
            val base = count
            inBand.forEachIndexed { i, entry -> ui.lazyIndex[itemRow(entry).id] = base + i }
            // One content type, so a jump of the remote reuses the rows it scrolls away
            // rather than building a screenful from nothing in one frame.
            itemsIndexed(inBand, key = { _, entry -> itemRow(entry).id }, contentType = { _, _ -> ROW_TYPE }) { index, item ->
                RowEntry(item, index, index == inBand.lastIndex, divider)
            }
        }
    }

    private fun CountingScope.heldItem(divider: Dp) {
        if (holding) {
            item(key = "band-recommended-held") {
                Column {
                    BandLabel(s.pickerBandRecommended)
                    PickSkeleton(rows = HELD_ROWS, tv = tv, divider = divider)
                }
            }
        }
    }

    @Composable
    fun RowEntry(item: ChatPicking.Item, index: Int, last: Boolean, divider: Dp) {
        val row = itemRow(item)
        // Only a row on screen is counted: this effect leaves with the row. It
        // waits for the list to stop (a fling, a held Down), so the rows run past
        // on the way are never counted at all, and for the row to be in sight,
        // since the rows composed ahead of a jump run it too.
        if (tv && item is ChatPicking.Item.Chat && row.band != ChatPicking.Band.Gone) LaunchedEffect(row.id) {
            snapshotFlow {
                ui.racing || listState.isScrollInProgress ||
                    listState.layoutInfo.visibleItemsInfo.none { it.key == row.id }
            }.first { !it }
            model.count(row.id)
        }
        val ticked = allOn || row.id in state.ticked
        val requester = remember(row.id) { FocusRequester() }
        DisposableEffect(row.id) {
            ui.rowFocus[row.id] = requester
            onDispose { if (ui.rowFocus[row.id] === requester) ui.rowFocus.remove(row.id) }
        }
        val card = rowModifier(row.id, requester)
            .groupedCard(GroupPlace(first = index == 0, last = last), divider)
        if (item is ChatPicking.Item.Folded) {
            FoldRow(
                label = s.pickerFold(item.count),
                modifier = card,
                onOpen = {
                    ui.foldFocus = ChatPicking.foldedIn(rows, state.counted, state.ticked, item.band).firstOrNull()?.id
                    lst.foldOpen.value = lst.foldOpen.value + item.band
                },
            )
        } else if (row.band == ChatPicking.Band.Gone) {
            GoneRow(row, tv, card, onRemove = { model.toggle(row.id) })
        } else {
            PickRow(
                row = row,
                ticked = ticked,
                reason = if (row.band == ChatPicking.Band.Recommended) {
                    if (state.belowBar) s.pickerReasonSome else s.pickerReasonMany
                } else {
                    null
                },
                tv = tv,
                enabled = !allOn,
                modifier = card,
                onToggle = { tick(!ticked); model.toggle(row.id) },
                onToggleStar = { model.toggleStar(row.id) },
            )
        }
    }

    @Composable
    fun AskDialog(question: Ask) {
        when (question) {
            Ask.Discard -> Confirm(
                title = s.pickerDiscardTitle,
                body = s.pickerDiscardBody,
                action = s.pickerDiscardYes,
                onYes = {
                    ui.asking = null
                    onClose()
                },
                onNo = { ui.asking = null },
            )
            is Ask.AddMany -> Confirm(
                title = s.pickerConfirmTitle(model.adding(question.ids)),
                body = s.pickerConfirmBody,
                action = s.pickerConfirmYes,
                onYes = {
                    ui.asking = null
                    model.selectAll(question.ids)
                },
                onNo = { ui.asking = null },
            )
            Ask.Leave -> Confirm(
                title = s.pickerLeaveChoosingTitle,
                body = s.pickerLeaveChoosingBody,
                action = s.pickerLeaveYes,
                onYes = {
                    ui.asking = null
                    onLeave()
                },
                onNo = { ui.asking = null },
            )
        }
    }

    @Composable
    fun Failure() {
        val failed = state.error
        if (failed != null) {
            Column(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(failed, style = MaterialTheme.typography.bodyMedium, color = Tone.danger)
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    SetupButton(s.commonTryAgain, primary = true, onClick = { model.done(chats) })
                    sendLogs?.invoke()
                }
            }
        }
    }

    @Composable
    fun DoneButton() {
        val none = state.ticked.isEmpty() && !allOn
        if (required && none) {
            DisabledDone(s.pickerGateDoneNone)
        } else {
            // While the choice is written the button says so and keeps the focus (the model
            // ignores a second press); the screen closes when it is done.
            SetupButton(
                when {
                    state.saving -> s.pickerSaving
                    none -> s.pickerDoneNone
                    else -> s.commonDone
                },
                primary = true,
                busy = state.saving,
                modifier = Modifier.focusRequester(ui.doneFocus),
                onClick = { model.done(chats) },
            )
        }
    }

    @Composable
    fun Escape() {
        val go = onContinue
        // No chat holds videos yet (Saved Messages may be the only row): say what to do, and
        // leave the way on for this visit.
        if (showHint) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    s.pickerForwardHint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tone.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                if (go != null) {
                    QuietButton(s.pickerContinueWithout, modifier = Modifier.focusRequester(ui.escapeFocus), onClick = go)
                }
            }
        }
    }

    @Composable
    fun FootPane() {
        val question = ui.asking
        if (question != null) {
            AskDialog(question)
        } else {
            Column(Modifier.fillMaxWidth()) {
                Failure()
                val totalWords: @Composable (Modifier) -> Unit = { m ->
                    Text(
                        if (allOn) s.pickerAllChatsOn else totalLine(chosen, s),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tone.text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = m,
                    )
                }
                val card = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = if (form == DeviceForm.Phone) 12.dp else 0.dp)
                    .groupedCard(GroupPlace.Alone)
                    .onFocusChanged { ui.inFoot = it.hasFocus }
                    .padding(start = ROW_INSET, end = 10.dp, top = 10.dp, bottom = 10.dp)
                // At a large system font the button takes the whole card and left the count no
                // width at all (HX56): the count goes over the button then.
                // Only where the window is narrow: on its side a phone has 800 dp or more of room, and stacking took
                // a third of its 393 dp of height from the list (phone audit, 2026-11-25).
                val widthDp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp().value }
                if (LocalDensity.current.fontScale >= LARGE_FONT && widthDp < 600f) {
                    Column(card, verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
                        totalWords(Modifier.fillMaxWidth())
                        DoneButton()
                    }
                } else {
                    Row(card, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        totalWords(Modifier.weight(1f))
                        DoneButton()
                    }
                }
                if (showHint) {
                    Box(Modifier.fillMaxWidth().padding(bottom = if (form == DeviceForm.Phone) 8.dp else 0.dp), contentAlignment = Alignment.Center) { Escape() }
                }
            }
        }
    }

    @Composable
    fun TvLayout() {
        val title = if (required) s.setupWhichTitle else s.pickerChooseChats
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
                TvRail()
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    // The first stop (HX86): OK flips it, Right goes to Done, and Down goes to Done
                    // too while every chat counts, since the rows under it are disabled.
                    AllChatsSwitch(
                        allOn,
                        tv = true,
                        onToggle = flipAll,
                        modifier = Modifier
                            .focusRequester(ui.switchFocus)
                            .onFocusChanged { ui.onSwitch = it.hasFocus }
                            .onPreviewKeyEvent { event ->
                                when {
                                    event.type != KeyEventType.KeyDown -> false
                                    event.key == end -> toFoot()
                                    event.key == Key.DirectionDown && allOn -> toFoot()
                                    else -> false
                                }
                            },
                    )
                    if (searching || state.query.isNotBlank()) SearchBox()
                    ListPane()
                }
                TvPanel()
            }
        }
    }

    @Composable
    fun TvRail() {
        val scopeCounts = remember(chats) {
            ChatPicking.Scope.entries.associateWith { sc -> chats.count { ChatPicking.inScope(it, sc) } }
        }
        // Each folder's chats, counted once for the rail; a folder not read yet shows no number.
        val folderCounts = remember(chats, pickable) {
            val wanted = pickable.mapTo(HashSet()) { it.id }
            HashMap<Int, Int>().also { counts ->
                for (chat in chats) for (id in chat.folderIds) if (id in wanted) counts[id] = (counts[id] ?: 0) + 1
            }
        }
        Column(
            Modifier.width(RAIL_WIDTH).fillMaxHeight().verticalScroll(rememberScrollState()).focusGroup(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (sc in TV_RAIL) {
                val n = scopeCounts.getValue(sc)
                RailEntry(
                    label = scopeLabel(sc, s),
                    icon = railIcon(sc),
                    detail = if (n > 0) s.formatter.number(n) else null,
                    chosen = folderId == null && state.scope == sc,
                    dim = n == 0,
                    onClick = { intoList(sc) },
                    modifier = Modifier
                        .focusRequester(ui.railFocus.getValue(sc))
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
            if (pickable.isNotEmpty()) {
                Text(
                    s.pickerFolders,
                    style = MaterialTheme.typography.labelLarge,
                    color = Tone.muted,
                    modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 2.dp).semantics { heading() },
                )
            }
            for (folder in pickable) {
                val read = folderStatus.read(folder.id)
                val n = folderCounts[folder.id] ?: 0
                RailEntry(
                    label = folderLabel(folder, s),
                    icon = folderIcon(folder.icon),
                    detail = if (read == FolderRead.Loaded && n > 0) s.formatter.number(n) else null,
                    chosen = folderId == folder.id,
                    dim = allOn || (read == FolderRead.Loaded && n == 0),
                    onClick = { if (!allOn) intoFolder(folder.id) },
                    modifier = Modifier
                        .focusRequester(ui.folderFocus.getOrPut(folder.id) { FocusRequester() })
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == end) {
                                // Greyed while every chat counts: Right goes nowhere.
                                if (!allOn) intoFolder(folder.id)
                                true
                            } else {
                                false
                            }
                        },
                )
            }
            RailEntry(
                label = s.commonSearch,
                icon = TmIcons.Search,
                detail = state.query.trim().ifBlank { null },
                chosen = searching || state.query.isNotBlank(),
                dim = false,
                onClick = { intoSearch() },
                modifier = Modifier
                    .focusRequester(ui.railSearchFocus)
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
    }

    @Composable
    fun TvPanel() {
        Column(
            Modifier
                .width(PANEL_WIDTH)
                .fillMaxHeight()
                .onFocusChanged { ui.inFoot = it.hasFocus }
                .onPreviewKeyEvent { event ->
                    when {
                        event.type != KeyEventType.KeyDown -> false
                        // On the keys, Left and Right walk the key grid and Left from its first
                        // column finds the list by position.
                        event.key == start && !ui.inKeys -> {
                            backToRow()
                            true
                        }
                        // Done is the one control here: Up and Down stay on it, and never
                        // drop into whichever row a focus search finds beside it (HX86).
                        // The keys below it are walked by Up and Down like any grid.
                        event.key == Key.DirectionDown || event.key == Key.DirectionUp -> !ui.inKeys
                        else -> false
                    }
                },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val question = ui.asking
            if (question != null) {
                AskDialog(question)
            } else {
                Text(
                    s.pickerYourChoice,
                    style = MaterialTheme.typography.titleMedium,
                    color = Tone.muted,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    if (allOn) s.pickerAllChatsOn else totalLine(chosen, s),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Tone.text,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Failure()
                DoneButton()
                Escape()
                // The keys need the room the hint takes while the words are being typed.
                if (required && !(tv && (searching || state.query.isNotBlank()))) {
                    Text(s.setupWhichBody, style = MaterialTheme.typography.bodySmall, color = Tone.muted, maxLines = 6, overflow = TextOverflow.Ellipsis)
                }
                // The words are typed on the keys beside the list, which stays in view.
                val keysUp = tv && (searching || state.query.isNotBlank())
                if (keysUp) {
                    com.tmplayer.ui.components.TvKeyboard(
                        text = typed,
                        onText = onType,
                        onSearch = toFirstRow,
                        modifier = Modifier.fillMaxWidth().onFocusChanged { ui.inKeys = it.hasFocus },
                        firstKey = ui.keyFocus,
                        // The panel is short: keys of 40 dp, and no typed line, since the
                        // search box over the list shows the words.
                        keyHeight = 40.dp,
                        showText = false,
                    )
                }
                if (shown.isNotEmpty() && !keysUp) {
                    Text(s.pickerTvKeys, style = MaterialTheme.typography.bodySmall, color = Tone.muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
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
private val RAIL_WIDTH = 210.dp

/** The icon a scope wears in the rail, from the shared set, so the rail reads at a glance across the room. */
private fun railIcon(scope: ChatPicking.Scope) = when (scope) {
    ChatPicking.Scope.Channels -> TmIcons.Channel
    ChatPicking.Scope.Groups -> TmIcons.Group
    ChatPicking.Scope.People -> TmIcons.Person
    ChatPicking.Scope.Archived -> TmIcons.Archive
    else -> TmIcons.Chats
}
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
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(22.dp))
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
 * [count] as it grows, handed on at once and then at most once a second, so a list that gains a
 * page every few hundred milliseconds does not redraw its count line each time (a low-end stick).
 */
@Composable
private fun rememberThrottledCount(count: Int): Int {
    val latest by rememberUpdatedState(count)
    var shown by remember { mutableIntStateOf(count) }
    LaunchedEffect(Unit) {
        snapshotFlow { latest }.collect {
            shown = it
            delay(COUNT_EVERY_MS)
        }
    }
    return shown
}

private const val COUNT_EVERY_MS = 1_000L

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

/** How faint a row is drawn while "All chats" is on and its tick is disabled (HX86). */
private const val DISABLED_ROW_ALPHA = 0.55f

/**
 * The "All chats" switch (HX86), at the top of the picker above the search: the whole row is the
 * control, a screen reader hears On or Off, and on the television it is the first stop of the focus
 * order, OK flipping it. [onToggle] is the screen's one flip (the thumb's tick and the model).
 */
@Composable
private fun AllChatsSwitch(on: Boolean, tv: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    val round = RoundedCornerShape(Corner.Large)
    val onWord = s.commonOn
    val offWord = s.commonOff
    Row(
        modifier
            .fillMaxWidth()
            .clip(round)
            .background(if (focused) Tone.focusFill else Tone.surface)
            .focusRing(focused, round)
            .semantics(mergeDescendants = true) {
                stateDescription = if (on) onWord else offWord
                toggleableState = ToggleableState(on)
            }
            .clickable(
                interactionSource = interactions,
                indication = if (tv) null else LocalIndication.current,
                role = Role.Switch,
                onClick = onToggle,
            )
            .padding(horizontal = ROW_INSET, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                s.pickerAllChatsSwitch,
                style = if (tv) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                color = if (focused) Tone.onFocusFill else Tone.text,
            )
            // On, the line the greyed controls point at; off, what turning it on does. One place, so the
            // switch stays two lines tall at a large font (HX86).
            Text(
                if (on) s.pickerAllChatsOn else s.pickerAllChatsDetail,
                style = MaterialTheme.typography.bodySmall,
                color = if (focused) Tone.onFocusFill else Tone.muted,
            )
        }
        Switch(checked = on, onCheckedChange = null)
    }
}

/** The directions a key can move focus in, which is all the screen holds back from leaving it. */
private val ARROWS = setOf(FocusDirection.Up, FocusDirection.Down, FocusDirection.Left, FocusDirection.Right)

/** A question the foot is asking in place of the total. */
private sealed interface Ask {
    data object Discard : Ask
    data object Leave : Ask
    /** Taking a large folder whole (HX29): the chats it would add, asked about before they are ticked. */
    data class AddMany(val ids: Set<Long>) : Ask
}

/**
 * One chat: its picture, name and a tick box, the whole row the target so a thumb need not find
 * the box and a remote can land on it, and in the Recommended band one plain line on why it is
 * there ([reason], never a number). A long press (the Menu key on a remote or a keyboard) stars or
 * unstars it (HX30): the same star the Favourites tab reads, one press away without leaving the
 * picker.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PickRow(
    row: ChatPicking.Row,
    ticked: Boolean,
    reason: String?,
    tv: Boolean,
    onToggle: () -> Unit,
    onToggleStar: () -> Unit,
    modifier: Modifier = Modifier,
    /** False while "All chats" is on (HX86): the tick is drawn dimmed, the row takes no press and no focus. */
    enabled: Boolean = true,
) {
    val s = LocalStrings.current
    val interactions = remember { MutableInteractionSource() }
    // Kept as a State and read only where it is drawn (the fill, the ring, the words' and the
    // star's colours), so a focus move draws the row it leaves and the one it reaches again and
    // composes neither (HX76: the two rows composed again were 35 of PickRow's 81 samples a hold).
    val focused = interactions.collectIsFocusVisibleAsState()
    val round = RoundedCornerShape(12.dp)
    val onFocus = Tone.onFocusFill
    val text = Tone.text
    val muted = Tone.muted
    val ink = remember(focused, onFocus, text) { ColorProducer { if (focused.value) onFocus else text } }
    val quiet = remember(focused, onFocus, muted) { ColorProducer { if (focused.value) onFocus else muted } }
    val titleStyle = (if (tv) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall)
        .let { remember(it) { it.copy(fontWeight = FontWeight.SemiBold) } }
    val avatar = if (tv) 52.dp else 44.dp
    Row(
        modifier
            .fillMaxWidth()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    event.key == Key.Menu -> {
                        onToggleStar()
                        true
                    }
                    else -> false
                }
            }
            // A clip is a layer, and this TV (API 28) records each one through a View (HX76): the
            // TV has no ripple to bound, so its fill and ring take the shape without one.
            .then(if (tv) Modifier else Modifier.clip(round))
            .focusDrawn(focused, focusRingColor(), round, fill = Tone.focusFill)
            .then(if (enabled) Modifier else Modifier.alpha(DISABLED_ROW_ALPHA).focusProperties { canFocus = false })
            .semantics(mergeDescendants = true) {
                toggleableState = ToggleableState(ticked)
                if (!enabled) disabled()
                // The words are read here, when semantics are asked for, not while the row composes.
                customActions = listOf(
                    CustomAccessibilityAction(
                        if (row.starred) s.browseRemoveFavourite else s.browseAddFavourite,
                    ) { onToggleStar(); true },
                )
            }
            .then(
                if (enabled) {
                    Modifier.combinedClickable(
                        interactionSource = interactions,
                        indication = if (tv) null else androidx.compose.foundation.LocalIndication.current,
                        role = Role.Checkbox,
                        onLongClick = onToggleStar,
                        onClick = onToggle,
                    )
                } else {
                    Modifier
                },
            )
            .padding(horizontal = ROW_INSET, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowAvatar(row, avatar)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicText(
                    row.title.ifBlank { row.id.toString() },
                    style = titleStyle,
                    color = ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (row.starred) {
                    // The row's own long press (the Menu key on a remote or a keyboard) already
                    // toggles it, so the icon says what is true rather than act itself: a second,
                    // separately focusable target on this row would be a second thing a remote has
                    // to find, which the tick box already is.
                    RowStar(focused, s.browseFavourite)
                }
            }
            if (reason != null) {
                BasicText(
                    reason,
                    style = if (tv) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                    color = quiet,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // A drawn box on TV (HX76): Material3's Checkbox ran its color and check animations on
        // every row a hold passed over (226 ms over about 40 rows), which a hold never needs to
        // see settle. Phone and desktop keep the Checkbox and its ripple.
        if (tv) {
            PickTick(ticked)
        } else {
            Checkbox(checked = ticked, onCheckedChange = null, enabled = enabled)
        }
    }
}

/**
 * A starred row's star, in the accent or, on the focused row, the focus fill's ink: an [Icon]
 * whose tint is picked in the draw phase, so a focus move repaints it without composing it.
 */
@Composable
private fun RowStar(focused: State<Boolean>, description: String) {
    val painter = rememberVectorPainter(TmIcons.Star)
    val accent = Tone.accent
    val onFocus = Tone.onFocusFill
    Spacer(
        Modifier
            .size(14.dp)
            .semantics {
                contentDescription = description
                role = Role.Image
            }
            .drawWithCache {
                val plain = ColorFilter.tint(accent)
                val lit = ColorFilter.tint(onFocus)
                onDrawBehind {
                    with(painter) { draw(size, colorFilter = if (focused.value) lit else plain) }
                }
            },
    )
}

/**
 * The tick box [PickRow] draws on TV instead of Material3's animated [Checkbox] (HX76): one node
 * that paints the box and [TmIcons.Check]'s stroke itself. A box with a border and an [Icon] in it
 * was three nodes and a vector painter for every row a held Down brought in.
 */
@Composable
private fun PickTick(ticked: Boolean) {
    val fill = Tone.accent
    val outline = Tone.outline
    val mark = Tone.onAccent
    Spacer(
        Modifier.size(20.dp).drawWithCache {
            val ring = 2.dp.toPx()
            val half = ring / 2
            val corner = 4.dp.toPx()
            // TmIcons.Check (M5 14 L8.5 17.5 L19 6.5, a 1.5 round stroke on 24) at 14dp, centred.
            val unit = 14.dp.toPx() / 24f
            val origin = (size.width - 24f * unit) / 2
            val check = Path().apply {
                moveTo(origin + 5f * unit, origin + 14f * unit)
                lineTo(origin + 8.5f * unit, origin + 17.5f * unit)
                lineTo(origin + 19f * unit, origin + 6.5f * unit)
            }
            val checkStroke = Stroke(width = 1.5f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val ringStroke = Stroke(width = ring)
            onDrawBehind {
                if (ticked) {
                    drawRoundRect(fill, cornerRadius = CornerRadius(corner))
                    drawPath(check, mark, style = checkStroke)
                } else {
                    // Where `border` puts it: the stroke's middle half its width in from the edge.
                    drawRoundRect(
                        outline,
                        topLeft = Offset(half, half),
                        size = Size(size.width - ring, size.height - ring),
                        cornerRadius = CornerRadius(corner - half),
                        style = ringStroke,
                    )
                }
            }
        },
    )
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


private fun scopeLabel(scope: ChatPicking.Scope, s: com.tmplayer.i18n.Messages): String = when (scope) {
    ChatPicking.Scope.All -> s.browseFilterAll
    ChatPicking.Scope.Archived -> s.browseFilterArchived
    ChatPicking.Scope.Channels -> s.browseFilterChannels
    ChatPicking.Scope.Groups -> s.browseFilterGroups
    ChatPicking.Scope.People -> s.browseFilterPeople
}

/** The system font size from which the foot stacks its count over Done. */
private const val LARGE_FONT = 1.3f

/** A folder's chip or rail words: its title, or "Folder 3" when Telegram gave it none. */
private fun folderLabel(folder: ChatFolderSummary, s: com.tmplayer.i18n.Messages): String =
    folder.title.trim().ifBlank { s.browseFolderFallback(folder.id.toString()) }

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
 * reason line and a box) in the same card, shimmering, so the band arrives into the place it was
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
private fun bandLabel(band: ChatPicking.Band, count: Int, s: com.tmplayer.i18n.Messages): String? = when (band) {
    ChatPicking.Band.Saved -> null
    ChatPicking.Band.Added -> s.pickerAddedCount(count)
    ChatPicking.Band.Recommended -> s.pickerBandRecommended
    ChatPicking.Band.Starred -> s.pickerBandStarred
    ChatPicking.Band.Chats -> s.browseFilterAllChats
    ChatPicking.Band.Archived -> s.browseFilterArchived
    ChatPicking.Band.Gone -> s.pickerBandGone
}

/** The foot's line: how many chats are chosen. */
private fun totalLine(chosen: Int, s: com.tmplayer.i18n.Messages): String =
    if (chosen == 0) s.pickerTotalNone else s.pickerTotal(chosen)

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
    confirmLeave: Boolean = false,
    onAdded: (Int) -> Unit = {},
) {
    val everything by chatsViewModel.everything.collectAsState()
    val paging by chatsViewModel.paging.collectAsState()
    val refreshing by chatsViewModel.refreshing.collectAsState()
    val connected by Td.connected.collectAsState()
    val kids by ChatGate.allowed.collectAsState()
    val starred by remember(settings) { settings.favorites }.collectAsState(initial = emptySet())
    val chats = remember(everything, kids) { KidsLock.chats(everything, kids) }
    val folders by Td.folders.collectAsState()
    val folderStatus by chatsViewModel.folderStatus.collectAsState()
    // The folders' members are read now, with the picker open, and not before (HX29); folders that
    // arrive after it opened are read when they do.
    // Keyed on the folders themselves: one that appears later is unread until it is read, never empty.
    val folderKey = folders.map { it.id }
    LaunchedEffect(folderKey) { if (folderKey.isNotEmpty()) chatsViewModel.wantFolders() }
    // Leaving the picker stops the reads: the Chats list never reads the folders by itself.
    DisposableEffect(chatsViewModel) { onDispose { chatsViewModel.unwantFolders() } }
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
        folders = folders,
        folderStatus = folderStatus,
        onRetryFolders = chatsViewModel::retryFolders,
        sendLogs = sendLogs,
        searchField = searchField,
        required = required,
        onLeave = onLeave,
        confirmLeave = confirmLeave,
        onAdded = onAdded,
        // Nothing to tick in a required choice: go on for this visit, nothing written (ON-4).
        onContinue = if (required) ChatsGate::waive else null,
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

    override suspend fun zippedVideos(chatId: Long): CountAnswer {
        val live = calls ?: AddChatsViewModel.calls()?.also { calls = it } ?: return CountAnswer.Failed(null)
        return live.zippedVideos(chatId)
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
