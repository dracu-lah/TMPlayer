package com.tmplayer.ui.browse

import com.tmplayer.ui.components.FastScroller
import com.tmplayer.ui.components.bottomFade
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.components.riseIn
import com.tmplayer.ui.components.rememberLessMotion
import com.tmplayer.ui.components.pressScale
import com.tmplayer.ui.components.Micro
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material3.ripple
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.togetherWith
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.data.AllChatsSearch
import com.tmplayer.data.ChatBrief
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.GlobalSearch
import com.tmplayer.data.MediaItem
import com.tmplayer.data.ResultGroup
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.SearchChatType
import com.tmplayer.data.SearchFilters
import com.tmplayer.data.SearchQuality
import com.tmplayer.data.SearchSort
import com.tmplayer.data.TopicHit
import androidx.compose.ui.graphics.graphicsLayer
import com.tmplayer.ui.components.PhoneSheet
import com.tmplayer.ui.components.Spinner
import com.tmplayer.ui.components.secondaryPress
import com.tmplayer.ui.components.TmButton
import com.tmplayer.ui.components.TmSecondaryButton
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.nav.BackHandler
import com.tmplayer.ui.theme.FloatingTone
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.floatingBorder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.tmplayer.ui.components.SlowAnswerNotice

/**
 * The phone's search across every chat (CP73): a page of its own over the browser, its field
 * docked at the bottom above the keyboard where the bar's magnifier was. It has no close button of
 * its own: Back clears the words while there are any and closes the page on an empty field.
 *
 * Before anything is typed it offers the way back to things: recent chats (starred first), recent
 * searches (fill one in with the arrow, take one off with its cross or a hold, clear them all with
 * a few seconds to undo), and up to three videos to carry on with. Typing lists at once the chats
 * whose names match and the videos this device already knows, then Telegram's answer once the
 * typing settles ([GlobalSearchViewModel]). Each video is one card however many chats it was
 * posted in; a tap opens it as a video's tile does.
 *
 * Back closes the keyboard first (the system does that), then the filter sheet, then clears the
 * words, then closes the search.
 * There is no swipe down to close: the page is a list, and a list is swiped.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun PhoneSearchScreen(
    state: GlobalSearchState,
    text: TextFieldValue,
    onText: (TextFieldValue) -> Unit,
    onSubmit: () -> Unit,
    onClose: () -> Unit,
    /** Goes on the field, which the bar's magnifier grows into. */
    fieldModifier: Modifier,
    chats: List<ChatSummary>,
    favourites: Set<Long>,
    /** Hidden while the kids lock is on, and while searches are not remembered. */
    recentSearches: List<String>,
    continueWatching: List<ResumeRecord>,
    continueArt: (ResumeRecord) -> MediaItem,
    progress: (MediaItem) -> Float?,
    onOpenChat: (ChatSummary) -> Unit,
    /** A topic found by its name: its chat, opened on it ([openOnTopic]). */
    onOpenTopic: (TopicHit) -> Unit = {},
    /** The topics' own Try again ([GlobalSearchViewModel.retryTopics]). */
    onRetryTopics: () -> Unit = {},
    onOpenGroup: (ResultGroup) -> Unit,
    /** Plays (resumes) one copy of a result that has progress, without opening its page. */
    onPlayCopy: (ResultGroup, MediaItem) -> Unit = { _, _ -> },
    onResume: (ResumeRecord) -> Unit,
    onFilters: (SearchFilters) -> Unit,
    onSort: (SearchSort) -> Unit,
    onLoadMore: () -> Unit,
    /** The Try again row after a page failed to load. */
    onRetryMore: () -> Unit = {},
    onRetry: () -> Unit,
    /** "Search my other chats" (HX62): the same words over the chats that were not added, once. */
    onSearchOthers: () -> Unit = {},
    /** "Where to watch" for a title not in the viewer's chats (HX41), and its close. */
    onWhereToWatch: (com.tmplayer.online.PosterChoice) -> Unit = {},
    onCloseWhereToWatch: () -> Unit = {},
    /** The titles' own Try again ([GlobalSearchViewModel.retryTitles]). */
    onRetryTitles: () -> Unit = {},
    onRemoveRecent: (String) -> Unit,
    /** Clears them all and hands back what was there, for the Undo. */
    onClearRecent: () -> List<String>,
    onRestoreRecent: (List<String>) -> Unit,
    onVoice: (() -> Unit)?,
    listState: LazyListState,
) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var allChats by rememberSaveable(state.query.text) { mutableStateOf(false) }
    var allTopics by rememberSaveable(state.query.text) { mutableStateOf(false) }

    // Clearing the box empties the results, the filters and the sort at once, back to the recent
    // searches, with the cursor in the field and the keyboard up. Back does the same while there
    // is text, and closes the page only on an empty field (there is no separate close button).
    val clear: (Boolean) -> Unit = { keyboardUp ->
        onText(TextFieldValue(""))
        onFilters(SearchFilters())
        onSort(SearchSort.BestMatch)
        if (keyboardUp) {
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
    }
    // Back is how a viewer leaves, and the keyboard is already down when Back reaches the page (the
    // system closes it first), so clearing by Back must not raise it again: that made a typed
    // search take four Backs to leave (Raju 4.8). The cross is a tap in the field, so it keeps it.
    BackHandler(enabled = !sheetOpen) { if (text.text.isNotEmpty()) clear(false) else onClose() }

    // The keyboard comes up once the field has finished growing out of the magnifier. Asked for at
    // once, it rose during the growth, and the field chased a spot the keyboard kept moving.
    val entered = LocalSearchEntered.current
    LaunchedEffect(Unit) {
        snapshotFlow { entered() }.first { it }
        runCatching { focus.requestFocus() }
    }

    val query = text.text
    // Every chat's name matched off the main thread, the last answer kept up meanwhile, so the
    // field never waits for it between letters.
    var localChats by remember { mutableStateOf(emptyList<ChatSummary>()) }
    LaunchedEffect(chats, query, favourites) {
        localChats = if (query.isBlank()) emptyList() else withContext(Dispatchers.Default) { GlobalSearch.localChats(chats, query, favourites) }
    }
    // The viewer's own chats only (HX62): no username is looked up on Telegram.
    val chatMatches = localChats
    // A pasted post or invite link (CP79).
    val offer = rememberLinkOffer(query)
    val recentChats = remember(chats, favourites) { GlobalSearch.recentChats(chats, favourites) }
    val briefs = remember(state.chats, chats) { state.chats + chats.associate { it.id to ChatBrief.of(it) } }
    val titleOf: (Long) -> String = { id -> briefs[id]?.title.orEmpty() }

    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(Tone.background)) {
        // A tablet's or an open foldable's results and field stand in a centred column (U17); zero on a phone.
        val gutter = com.tmplayer.ui.components.largePaneGutter(maxWidth)
        Column(Modifier.fillMaxSize().imePadding()) {
            Box(Modifier.weight(1f).fillMaxWidth().bottomFade({ listState.canScrollForward }, 20.dp, Tone.background)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = gutter, end = gutter, bottom = 8.dp),
            ) {
                item(key = "top") { Spacer(Modifier.windowInsetsPadding(WindowInsets.statusBars).height(8.dp)) }
                if (query.isBlank()) {
                    emptySearch(
                        chats = recentChats,
                        recent = recentSearches.take(GlobalSearch.RECENT_SHOWN),
                        continueWatching = continueWatching.take(CONTINUE_SHOWN),
                        continueArt = continueArt,
                        onOpenChat = onOpenChat,
                        onPick = { picked ->
                            onText(TextFieldValue(picked, TextRange(picked.length)))
                            onSubmit()
                        },
                        onFill = { picked -> onText(TextFieldValue("$picked ", TextRange(picked.length + 1))) },
                        onRemove = onRemoveRecent,
                        onClearAll = {
                            val before = onClearRecent()
                            scope.launch {
                                val answer = snackbar.showSnackbar(s.searchRecentCleared, actionLabel = s.searchUndo)
                                if (answer == SnackbarResult.ActionPerformed) onRestoreRecent(before)
                            }
                        },
                        onResume = onResume,
                    )
                } else {
                    offer?.let { found ->
                        item(key = "link-offer") {
                            com.tmplayer.ui.components.ChoiceLine(found.label, detail = found.detail, icon = TmIcons.Send) {
                                keyboard?.hide()
                                onClose()
                                LinkRequests.ask(found.link)
                            }
                        }
                    }
                    results(
                        state = state,
                        chatMatches = chatMatches,
                        allChats = allChats,
                        onShowAllChats = { allChats = true },
                        allTopics = allTopics,
                        onShowAllTopics = { allTopics = true },
                        onOpenTopic = { keyboard?.hide(); onOpenTopic(it) },
                        onRetryTopics = onRetryTopics,
                        briefs = briefs,
                        progress = progress,
                        onOpenChat = onOpenChat,
                        onOpenGroup = { keyboard?.hide(); onOpenGroup(it) },
                        onPlayCopy = { group, copy -> keyboard?.hide(); onPlayCopy(group, copy) },
                        onRetry = onRetry,
                        onRetryMore = onRetryMore,
                        onClearFilters = { onFilters(SearchFilters()) },
                        onSearchOthers = onSearchOthers,
                        onWhereToWatch = { keyboard?.hide(); onWhereToWatch(it) },
                        onRetryTitles = onRetryTitles,
                        onAddChats = { keyboard?.hide(); onClose(); com.tmplayer.ui.browse.AddChatsRequest.ask() },
                        onSearchWord = { word -> onText(TextFieldValue(word, TextRange(word.length))); onSubmit() },
                    )
                }
            }
            // A long run of results is one hold away from its end.
            FastScroller(listState)
            }
            state.watching?.let { (title, answer) ->
                WhereToWatchDialog(
                    title = title,
                    answer = answer,
                    canSearchOthers = state.canSearchOthers,
                    onSearchOthers = onSearchOthers,
                    onRetry = { onWhereToWatch(title) },
                    onClose = onCloseWhereToWatch,
                )
            }

            // The filters once there is something to filter, or a filter is on.
            if (query.isNotBlank() && (state.groups.isNotEmpty() || !state.filters.isDefault || state.sort != SearchSort.BestMatch)) {
                FilterRow(state, onFilters = onFilters, onMore = { keyboard?.hide(); sheetOpen = true }, gutter = gutter)
            }

            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp + gutter, end = 16.dp + gutter, top = 8.dp, bottom = BAR_MARGIN),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchField(
                    text = text,
                    onText = onText,
                    onSubmit = { keyboard?.hide(); onSubmit() },
                    onVoice = onVoice,
                    onClear = { clear(true) },
                    modifier = Modifier.weight(1f).then(fieldModifier).focusRequester(focus),
                )
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).imePadding().navigationBarsPadding().padding(bottom = BAR_HEIGHT + BAR_MARGIN + BAR_GAP))
    }

    // The next page well before the end, the way a chat's grid pages.
    com.tmplayer.ui.components.LoadMoreAhead(
        listState,
        enabled = state.groups.isNotEmpty() && !state.loadingMore && !state.endReached && !state.moreFailed,
        ahead = PAGE_LEAD - 1,
        onLoadMore = onLoadMore,
    )

    if (sheetOpen) {
        FilterSheet(state, onFilters = onFilters, onSort = onSort, onDismiss = { sheetOpen = false })
    }
}

/** The field: a rounded pill with the magnifier, the text, and Clear while there is text or the microphone while there is none. */
@Composable
private fun SearchField(
    text: TextFieldValue,
    onText: (TextFieldValue) -> Unit,
    onSubmit: () -> Unit,
    onVoice: (() -> Unit)?,
    onClear: () -> Unit,
    modifier: Modifier,
) {
    val s = LocalStrings.current
    // While the field has the keyboard its edge and its magnifier take the accent, softly, so the
    // eye finds where the typing goes.
    var focused by remember { mutableStateOf(false) }
    val edge by animateColorAsState(
        if (focused) Tone.accent.copy(alpha = 0.6f) else Color.Transparent,
        Motion.standard(Motion.Fast),
        label = "searchEdge",
    )
    val glass by animateColorAsState(if (focused) Tone.accent else Tone.muted, Motion.standard(Motion.Fast), label = "searchGlass")
    Row(
        modifier
            .onFocusChanged { focused = it.hasFocus }
            .height(BAR_HEIGHT)
            .shadow(BAR_SHADOW, CircleShape)
            .clip(CircleShape)
            .background(FloatingTone.menu)
            .border(floatingBorder(), CircleShape)
            .border(1.5.dp, edge, CircleShape)
            // Clear and the microphone draw a 40 dp circle in a 48 dp target: 8 dp here puts it
            // 12 dp from the round end, as from the top and bottom of the 64 dp pill.
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(TmIcons.Search, contentDescription = null, tint = glass, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = text,
            onValueChange = onText,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Tone.text),
            cursorBrush = SolidColor(Tone.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (text.text.isEmpty()) {
                        Text(s.searchFieldHint, style = MaterialTheme.typography.bodyLarge, color = Tone.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    inner()
                }
            },
        )
        // Clear grows in as the first letter is typed and the microphone comes back as the last
        // goes, one turning into the other rather than blinking.
        val end = when {
            text.text.isNotEmpty() -> FieldEnd.Clear
            onVoice != null -> FieldEnd.Voice
            else -> FieldEnd.None
        }
        val still = rememberLessMotion()
        AnimatedContent(
            targetState = end,
            transitionSpec = {
                if (still) {
                    fadeIn(Motion.standard(Motion.Fast)) togetherWith fadeOut(Motion.standard(Motion.Fast))
                } else {
                    (fadeIn(Motion.standard(Motion.Fast)) + scaleIn(Micro.release(), initialScale = 0.5f)) togetherWith
                        (fadeOut(Motion.standard(Motion.Fast)) + scaleOut(Motion.standard(Motion.Fast), targetScale = 0.5f))
                }
            },
            label = "searchFieldEnd",
        ) { shown ->
            when (shown) {
                FieldEnd.Clear -> com.tmplayer.ui.components.IconTip(s.browseClearSearch) {
                    com.tmplayer.ui.components.ClearButton(s.browseClearSearch, onClick = onClear)
                }
                FieldEnd.Voice -> com.tmplayer.ui.components.IconTip(s.browseSearchByVoice) {
                    IconButton(onClick = { onVoice?.invoke() }) {
                        Icon(TmIcons.Mic, contentDescription = s.browseSearchByVoice, tint = Tone.muted)
                    }
                }
                FieldEnd.None -> Unit
            }
        }
    }
}

/** What sits at the field's end: Clear while there is text, else the microphone where there is one. */
private enum class FieldEnd { Clear, Voice, None }

/** What an empty search offers: recent chats, recent searches, and videos to carry on with. */
@OptIn(ExperimentalFoundationApi::class)
private fun androidx.compose.foundation.lazy.LazyListScope.emptySearch(
    chats: List<ChatSummary>,
    recent: List<String>,
    continueWatching: List<ResumeRecord>,
    continueArt: (ResumeRecord) -> MediaItem,
    onOpenChat: (ChatSummary) -> Unit,
    onPick: (String) -> Unit,
    onFill: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClearAll: () -> Unit,
    onResume: (ResumeRecord) -> Unit,
) {
    if (chats.isNotEmpty()) {
        item(key = "chats-h") { Heading(LocalStrings.current.searchChatsHeading) }
        item(key = "chats") {
            // The label grows with the font, and wraps to two lines before it is cut: at twice the font a
            // fixed 64 dp left "Wee..." and "Harb..." for names (phone audit, 2026-11-25).
            val labelWidth = (64f * androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceIn(1f, 1.75f)).dp
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(chats, key = { it.id }) { chat ->
                    Column(
                        Modifier.width(labelWidth).clip(RoundedCornerShape(12.dp)).clickable { onOpenChat(chat) }.padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        ChatAvatar(ChatBrief.of(chat), 56.dp)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            chat.title, style = MaterialTheme.typography.labelSmall, color = Tone.text, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
    if (recent.isNotEmpty()) {
        item(key = "recent-h") {
            val s = LocalStrings.current
            Row(
                Modifier
                    .then(if (com.tmplayer.ui.theme.Motion.reduced) Modifier else Modifier.animateItem())
                    .fillMaxWidth()
                    .padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) { Heading(s.browseRecentSearches) }
                TextButton(onClick = onClearAll) { Text(s.searchClearRecent) }
            }
        }
        items(recent, key = { "r-$it" }) { entry ->
            val s = LocalStrings.current
            Row(
                Modifier
                    // A search taken off folds away and the rest close up; one added fades in.
                    .then(if (com.tmplayer.ui.theme.Motion.reduced) Modifier else Modifier.animateItem())
                    .fillMaxWidth()
                    .secondaryPress { onRemove(entry) }
                    .combinedClickable(onClick = { onPick(entry) }, onLongClick = { onRemove(entry) })
                    .padding(start = 16.dp, end = 4.dp)
                    .height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(TmIcons.History, contentDescription = null, tint = Tone.muted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(16.dp))
                Text(entry, style = MaterialTheme.typography.bodyLarge, color = Tone.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                com.tmplayer.ui.components.IconTip(s.searchFillIn(entry)) {
                    IconButton(onClick = { onFill(entry) }) {
                        Icon(TmIcons.ArrowUpLeft, contentDescription = s.searchFillIn(entry), tint = Tone.muted, modifier = Modifier.size(20.dp))
                    }
                }
                com.tmplayer.ui.components.IconTip(s.searchRemoveRecent(entry)) {
                    IconButton(onClick = { onRemove(entry) }) {
                        Icon(TmIcons.Close, contentDescription = s.searchRemoveRecent(entry), tint = Tone.muted, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
    if (continueWatching.isNotEmpty()) {
        item(key = "continue-h") { Heading(LocalStrings.current.homeRowContinue) }
        items(continueWatching, key = { "c-${it.chatId}_${it.messageId}" }) { record ->
            val item = continueArt(record)
            Row(
                Modifier.fillMaxWidth().clickable { onResume(record) }.padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ResultPoster(item, Modifier.width(48.dp), fraction = record.fraction())
                Column(Modifier.weight(1f)) {
                    // The name Home's tiles use, not the file's with its dots and handle (Raju 4.6).
                    Text(continueTitle(record), style = MaterialTheme.typography.titleSmall, color = Tone.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (record.chatTitle.isNotBlank()) {
                        Text(record.chatTitle, style = MaterialTheme.typography.bodySmall, color = Tone.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** What a Continue watching line calls the video: the tile's name ([SeriesShelf.tileTitle]), the stored title when that reads blank. */
internal fun continueTitle(record: ResumeRecord): String =
    com.tmplayer.data.SeriesShelf.tileTitle(record.toMediaItem()).ifBlank { record.title }

private fun ResumeRecord.fraction(): Float? =
    if (durationMs <= 0) null else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

/** What a search with text lists: matching chats, then the videos, then how the search is going. */
private fun androidx.compose.foundation.lazy.LazyListScope.results(
    state: GlobalSearchState,
    chatMatches: List<ChatSummary>,
    allChats: Boolean,
    onShowAllChats: () -> Unit,
    allTopics: Boolean,
    onShowAllTopics: () -> Unit,
    onOpenTopic: (TopicHit) -> Unit,
    onRetryTopics: () -> Unit,
    briefs: Map<Long, ChatBrief>,
    progress: (MediaItem) -> Float?,
    onOpenChat: (ChatSummary) -> Unit,
    onOpenGroup: (ResultGroup) -> Unit,
    onPlayCopy: (ResultGroup, MediaItem) -> Unit,
    onRetry: () -> Unit,
    onRetryMore: () -> Unit,
    onClearFilters: () -> Unit,
    onSearchOthers: () -> Unit,
    onAddChats: () -> Unit,
    onSearchWord: (String) -> Unit,
    onWhereToWatch: (com.tmplayer.online.PosterChoice) -> Unit = {},
    onRetryTitles: () -> Unit = {},
) {
    if (chatMatches.isNotEmpty()) {
        item(key = "chats-h") { Heading(LocalStrings.current.searchChatsHeading) }
        val shown = if (allChats) chatMatches else chatMatches.take(GlobalSearch.CHAT_PREVIEW)
        items(shown, key = { "chat-${it.id}" }) { chat ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpenChat(chat) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ChatAvatar(ChatBrief.of(chat), 40.dp)
                Text(chat.title, style = MaterialTheme.typography.bodyLarge, color = Tone.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
        if (!allChats && chatMatches.size > GlobalSearch.CHAT_PREVIEW) {
            item(key = "chats-all") {
                TextButton(onClick = onShowAllChats, modifier = Modifier.padding(start = 8.dp)) {
                    Text(LocalStrings.current.searchShowAllChats(chatMatches.size))
                }
            }
        }
    }
    topicResults(state, allTopics, onShowAllTopics, briefs, onOpenTopic, onRetryTopics)
    titleResults(state, briefs, onOpenGroup, onWhereToWatch, onRetryTitles)
    item(key = "videos-h") { Heading(LocalStrings.current.searchVideosHeading) }
    searchStatus(state)?.let { status ->
        item(key = "status") {
            Note(status, spinner = state.stillSearching || state.floodWait > 0) {
                // Offline too: the search runs again on its own once back, and this is the way
                // to ask sooner, so the line is never one with nothing to press.
                if (state.error != null || state.offline) TmSecondaryButton(onClick = onRetry) { Text(LocalStrings.current.commonRetry) }
            }
        }
    }
    // What the search covered: said once, with the way to choose chats (HX62) and, over the
    // added chats, the one-off button for the others.
    if (state.everyChatNote) {
        item(key = "every-chat") {
            Note(LocalStrings.current.searchEveryChatNote) {
                TmSecondaryButton(onClick = onAddChats) { Text(LocalStrings.current.pickerAddMore) }
            }
        }
    }
    val shown = state.shown
    searchCountLine(state)?.let { count ->
        item(key = "count") {
            // A row that wraps: at twice the font "4 videos in 3 chats" was split into "video" and "s in 3 chats"
            // by the button beside it (phone audit, 2026-11-25); the button goes under the line when there is no room.
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(
                // Room under the line before the first card, with or without the button beside it.
                Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(count, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
                if (state.canSearchOthers) {
                    TextButton(onClick = onSearchOthers) { Text(LocalStrings.current.searchOtherChats) }
                }
            }
        }
    }
    when {
        // While the typing settles too, rather than a heading over nothing until Telegram is asked.
        shown.isEmpty() && (state.searching || state.settling) -> {
            items(SKELETONS, key = { "sk-$it" }) {
                SearchResultSkeleton(Modifier.padding(horizontal = 8.dp))
            }
            // A first page that will not come: Telegram is slow, with Retry, after the usual wait.
            if (state.searching) {
                item(key = "slow") { SlowAnswerNotice(key = state.query, onRetry = onRetry, modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp)) }
            }
        }
        shown.isEmpty() && !state.busy && state.error == null -> item(key = "none") {
            val s = LocalStrings.current
            // Rises in a beat after the search settles, rather than snapping in over the skeletons,
            // under the empty pages' picture ([EmptyArt]) drawing itself in.
            Column(
                Modifier.fillMaxWidth().riseIn().padding(horizontal = 16.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val hidden = state.hiddenBy
                com.tmplayer.ui.components.EmptyArt(
                    if (hidden.isNotEmpty()) TmIcons.FilterHorizontal else TmIcons.SearchRemove,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                if (hidden.isNotEmpty()) {
                    hidden.forEach { Text(it.message(state), style = MaterialTheme.typography.bodyLarge, color = Tone.text, textAlign = TextAlign.Center) }
                    TmSecondaryButton(onClick = onClearFilters) { Text(s.searchClearFilters) }
                } else {
                    Text(s.searchNone(state.query.text), style = MaterialTheme.typography.titleMedium, color = Tone.text, textAlign = TextAlign.Center)
                    Text(s.searchNoneDetail, style = MaterialTheme.typography.bodyMedium, color = Tone.muted, textAlign = TextAlign.Center)
                    // Nothing in the chats the viewer chose: the way to choose more (HX62), and the
                    // one-off look at the others, rather than a dead end.
                    if (state.addedOnly) {
                        Text(s.searchNoneAdded, style = MaterialTheme.typography.bodyMedium, color = Tone.muted, textAlign = TextAlign.Center)
                    }
                    // One main way on and the rest as text links (Raju pass 2, 4.7): two pills with
                    // similar words side by side left the viewer weighing them.
                    EmptySearchWays(
                        canSearchOthers = state.canSearchOthers,
                        canAddChats = state.canAddChats,
                        word = AllChatsSearch.fallbackQuery(state.query.server),
                        onSearchOthers = onSearchOthers,
                        onAddChats = onAddChats,
                        onSearchWord = onSearchWord,
                    )
                }
            }
        }
        else -> items(shown, key = { it.key }) { group ->
            SearchResultCard(
                group = group,
                chats = briefs,
                progress = progress,
                // Results fade in as they arrive and slide to their places when the order or a
                // filter changes, rather than the list redrawing at once.
                modifier = Modifier
                    .then(if (shown.size <= ANIMATED_RESULTS_MAX && !com.tmplayer.ui.theme.Motion.reduced) Modifier.animateItem() else Modifier)
                    .padding(horizontal = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onOpenGroup(group) },
                // A film with progress resumes from its card; a show's page picks its episode.
                trailing = if (group.isShow) null else {
                    val copy = group.copies.firstOrNull { (progress(it) ?: 0f) > 0f }
                    if (copy == null) null else {
                        {
                            val s = LocalStrings.current
                            TextButton(onClick = { onPlayCopy(group, copy) }) {
                                Icon(TmIcons.Play, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(s.commonResume)
                            }
                        }
                    }
                },
            )
        }
    }
    if (state.loadingMore) item(key = "more") { Note(LocalStrings.current.gridLoadingMore, spinner = true) }
    // The last page failed: said where the spinner was, with Try again, rather than the list
    // quietly stopping short (the chat grid's row, MediaGridScreen).
    if (state.moreFailed && !state.loadingMore) {
        item(key = "more-failed") {
            Note(LocalStrings.current.gridMoreFailed) {
                TmSecondaryButton(onClick = onRetryMore) { Text(LocalStrings.current.commonTryAgain) }
            }
        }
    }
}

/**
 * The Titles group (HX41): the films and shows TMDB names for the words, each with the copies the
 * viewer has. A tap on one with copies opens its best card, whose lead is the recommended copy;
 * one "Not in your chats" says so and offers Where to watch, whose sheet also holds the one-off
 * search of the other chats. Nothing shows with lookups off or no key: the videos are the answer.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.titleResults(
    state: GlobalSearchState,
    briefs: Map<Long, ChatBrief>,
    onOpenGroup: (ResultGroup) -> Unit,
    onWhereToWatch: (com.tmplayer.online.PosterChoice) -> Unit,
    onRetryTitles: () -> Unit,
) {
    val hits = state.titleHits
    if (hits.isEmpty() && state.titleLookup != TitleLookup.Unavailable) return
    item(key = "titles-h") { Heading(LocalStrings.current.searchTitlesHeading) }
    if (state.titleLookup == TitleLookup.Unavailable) {
        item(key = "titles-failed") {
            Note(LocalStrings.current.searchTitlesUnavailable) {
                TmSecondaryButton(onClick = onRetryTitles) { Text(LocalStrings.current.searchTitlesRetry) }
            }
        }
    }
    items(hits, key = { "title-${it.title.onlineKey}" }) { hit ->
        val best = hit.best
        SearchTitleCard(
            hit = hit,
            chats = briefs,
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable { if (best != null) onOpenGroup(best) else onWhereToWatch(hit.title) }
                .testTag("title-${hit.title.onlineKey}"),
            trailing = if (!hit.notInChats) null else {
                { TextButton(onClick = { onWhereToWatch(hit.title) }) { Text(LocalStrings.current.searchWhereToWatch) } }
            },
        )
    }
}

/**
 * The Topics group (U14): "Chat > Topic" rows, a few at first and the rest behind Show all, a
 * topic with no videos dimmed and saying so, pinned and closed ones marked. While Telegram is asked
 * a quiet spinner line; when every group refused, the reason with Try again and Send logs; when
 * the budget stopped short of every group, a line saying which were searched.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.topicResults(
    state: GlobalSearchState,
    allTopics: Boolean,
    onShowAllTopics: () -> Unit,
    briefs: Map<Long, ChatBrief>,
    onOpenTopic: (TopicHit) -> Unit,
    onRetryTopics: () -> Unit,
) {
    val hits = state.topics
    val failed = state.topicsError
    if (hits.isEmpty() && failed == null) return
    item(key = "topics-h") { Heading(LocalStrings.current.searchTopicsHeading) }
    val shown = if (allTopics) hits else hits.take(TOPIC_PREVIEW)
    items(shown, key = { "topic-${it.key}" }) { hit -> TopicRow(hit, briefs[hit.chatId]) { onOpenTopic(hit) } }
    if (!allTopics && hits.size > TOPIC_PREVIEW) {
        item(key = "topics-all") {
            TextButton(onClick = onShowAllTopics, modifier = Modifier.padding(start = 8.dp)) {
                Text(LocalStrings.current.searchShowAllTopics(hits.size))
            }
        }
    }
    when {
        failed != null -> item(key = "topics-failed") { TopicsFailed(failed, onRetryTopics) }
        state.topicsSearching -> item(key = "topics-busy") { Note(LocalStrings.current.searchStillSearching, spinner = true) }
        state.topicsPartial -> item(key = "topics-partial") {
            Note(LocalStrings.current.searchTopicsPartial(com.tmplayer.data.TopicSearch.CHATS_PER_SEARCH))
        }
    }
}

/** Why every group refused the topic search: the reason, Try again and Send logs, which wrap at a large font. */
@Composable
internal fun TopicsFailed(failed: String, onRetry: () -> Unit) {
    val s = LocalStrings.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(s.searchTopicsFailed(failed), style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
        // Wraps at a large font: Retry and Send logs side by side ran off a narrow phone.
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TmSecondaryButton(onClick = onRetry) { Text(s.commonRetry) }
            com.tmplayer.ui.settings.SendLogsButton()
        }
    }
}

/** One topic found by its name: the chat's picture, "Chat > Topic", and why it may not be worth a tap. */
@Composable
private fun TopicRow(hit: TopicHit, chat: ChatBrief?, onClick: () -> Unit) {
    val s = LocalStrings.current
    val empty = !hit.topic.hasVideos
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(40.dp).graphicsLayer { alpha = if (empty) 0.5f else 1f }, contentAlignment = Alignment.Center) {
            if (chat != null) ChatAvatar(chat, 40.dp) else Icon(TmIcons.ChatsMany, contentDescription = null, tint = Tone.muted)
        }
        Column(Modifier.weight(1f)) {
            Text(
                topicHitLabel(hit, s),
                style = MaterialTheme.typography.bodyLarge,
                color = if (empty) Tone.muted else Tone.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (empty) Text(s.searchTopicNoVideos, style = MaterialTheme.typography.bodySmall, color = Tone.muted, maxLines = 1)
        }
        if (hit.topic.isPinned) Icon(TmIcons.Pin, contentDescription = s.gridTopicPinned, tint = Tone.muted, modifier = Modifier.size(18.dp))
        if (hit.topic.isClosed) Icon(TmIcons.Lock, contentDescription = s.gridTopicClosed, tint = Tone.muted, modifier = Modifier.size(18.dp))
    }
}

/** The quick filters over the field: the kind of chat, and the rest behind Filters. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterRow(state: GlobalSearchState, onFilters: (SearchFilters) -> Unit, onMore: () -> Unit, gutter: androidx.compose.ui.unit.Dp = 0.dp) {
    val s = LocalStrings.current
    val extras = listOf(
        state.filters.quality != SearchQuality.Any,
        state.filters.hideSmall,
        !state.filters.includeArchived,
        state.sort != SearchSort.BestMatch,
    ).count { it }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp + gutter),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    ) {
        item(key = "more") {
            FilterChip(
                selected = extras > 0,
                onClick = onMore,
                label = { Text(if (extras > 0) "${s.searchFilters} $extras" else s.searchFilters) },
                leadingIcon = { Icon(TmIcons.FilterHorizontal, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
            )
        }
        items(SearchChatType.entries, key = { it.name }) { type ->
            val chipTick = com.tmplayer.ui.components.rememberToggleTick()
            FilterChip(
                selected = state.filters.chatType == type,
                onClick = {
                    if (state.filters.chatType != type) chipTick(true)
                    onFilters(state.filters.copy(chatType = type))
                },
                label = { Text(type.label) },
            )
        }
    }
}

/** Everything else: the order, the picture size, small clips, the archive. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterSheet(state: GlobalSearchState, onFilters: (SearchFilters) -> Unit, onSort: (SearchSort) -> Unit, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    PhoneSheet(onDismissRequest = onDismiss) {
        Heading(s.searchSort)
        SearchSort.entries.forEach { sort ->
            ChoiceLine(sort.label, chosen = state.sort == sort) { onSort(sort) }
        }
        Heading(s.searchFilters)
        SearchQuality.entries.forEach { quality ->
            ChoiceLine(quality.label, chosen = state.filters.quality == quality) { onFilters(state.filters.copy(quality = quality)) }
        }
        SwitchLine(s.searchFilterHideSmall, state.filters.hideSmall) { onFilters(state.filters.copy(hideSmall = it)) }
        SwitchLine(s.searchFilterIncludeArchived, state.filters.includeArchived) { onFilters(state.filters.copy(includeArchived = it)) }
        Spacer(Modifier.height(16.dp))
    }
}

/** One choice in the sheet: no radio button, the chosen line tinted and ticked, as in the Sort popover. */
@Composable
private fun ChoiceLine(label: String, chosen: Boolean, onClick: () -> Unit) {
    com.tmplayer.ui.components.ChoiceRow(label, icon = null, chosen = chosen, modifier = Modifier.padding(horizontal = 8.dp), onClick = onClick)
}

/**
 * A switch and its words as one line that toggles. Toggleable, not clickable: a screen reader then
 * says on or off, which a plain click with a state-less [Switch] inside it never did. A floor of
 * 56 dp rather than a fixed height, so a label that wraps at a large font is not cut.
 */
@Composable
internal fun SwitchLine(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val tick = com.tmplayer.ui.components.rememberToggleTick()
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = on, role = Role.Switch) { tick(it); onChange(it) }
            .padding(horizontal = 16.dp)
            .heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Tone.text, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = null)
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = Tone.muted, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp))
}

@Composable
private fun Note(text: String, spinner: Boolean = false, action: (@Composable () -> Unit)? = null) {
    // At a large font the words and the button no longer fit side by side: the words get the
    // whole width and the button sits under them, at the end.
    val stacked = action != null && LocalDensity.current.fontScale > 1.15f
    if (stacked) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (spinner) Spinner(size = 18.dp, strokeWidth = 2.dp)
                Text(text, style = MaterialTheme.typography.bodyMedium, color = Tone.muted, modifier = Modifier.weight(1f))
            }
            action?.invoke()
        }
        return
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (spinner) Spinner(size = 18.dp, strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Tone.muted, modifier = Modifier.weight(1f, fill = false))
        action?.invoke()
    }
}

/** Topics listed before Show all: the group is a way into a chat, not a second results list. */
private const val TOPIC_PREVIEW = 4

/** Videos to carry on with on an empty search: three is a glance, more is History. */
private const val CONTINUE_SHOWN = 3

private const val PAGE_LEAD = 6

/** The longest list of results that slides to a new order rather than being redrawn in it. */
private const val ANIMATED_RESULTS_MAX = 300

private val SKELETONS = List(5) { it }

/**
 * What a search that found nothing offers: the other chats when only the added ones were searched,
 * then Add chats, then the word it could try instead. The first is the page's one primary button, the others text links at least 48 dp tall.
 */
@Composable
internal fun EmptySearchWays(
    canSearchOthers: Boolean,
    word: String?,
    onSearchOthers: () -> Unit,
    onAddChats: () -> Unit,
    onSearchWord: (String) -> Unit,
    /** False with All chats on: every chat counts, so there is nothing to add. */
    canAddChats: Boolean = true,
) {
    val s = LocalStrings.current
    val ways = buildList {
        if (canSearchOthers) add(s.searchOtherChats to onSearchOthers)
        if (canAddChats) add(s.pickerAddMore to onAddChats)
        word?.let { add(s.searchSearchFor(it) to { onSearchWord(it) }) }
    }
    ways.forEachIndexed { index, (label, go) ->
        if (index == 0) {
            TmButton(onClick = go) { Text(label) }
        } else {
            TextButton(onClick = go, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
        }
    }
}
