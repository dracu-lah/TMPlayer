# phone-browse-1: 244 controls in 23 files

Tick a line only after every dimension in private/UX-INTERACTION-PLAN.md was checked for it.

## app/src/main/java/com/tmplayer/ui/browse/AddChatsPage.kt (2)
- [ ] L47 `sendLogs = { SendLogsButton() },`
- [ ] L65 `sendLogs = { SendLogsButton() },`

## app/src/main/java/com/tmplayer/ui/browse/ArchiveRows.kt (1)
- [ ] L57 `onClick = { onExplain(file.reason) },`

## app/src/main/java/com/tmplayer/ui/browse/AudioRows.kt (2)
- [ ] L58 `onClick = onOpen,`
- [ ] L122 `TmButton(onClick = list::loadMore) { Text(s.commonTryAgain) }`

## app/src/main/java/com/tmplayer/ui/browse/BrowseScreen.kt (44)
- [ ] L740 `if (listsStore != null) HeaderAction(label = s.listsOpen, icon = TmIcons.List, onClick = { openList = "" })`
- [ ] L744 `HeaderAction(label = s.browseSelect, icon = TmIcons.Checklist, onClick = { startSelecting(null) })`
- [ ] L748 `onClick = askClear,`
- [ ] L843 `onClick = { confirmClearFavorites = true },`
- [ ] L990 `BarIcon(label = s.listsOpen, icon = TmIcons.List, onClick = { openList = "" })`
- [ ] L993 `BarIcon(label = s.browseSelect, icon = TmIcons.Checklist, onClick = { startSelecting(null) })`
- [ ] L997 `onClick = askClear,`
- [ ] L1051 `BackHandler(enabled = !tvBarHasFocus) {`
- [ ] L1056 `BackHandler(enabled = tvBarHasFocus && !homeShown) {`
- [ ] L1095 `Modifier.fillMaxSize().onPreviewKeyEvent { event ->`
- [ ] L1309 `BackHandler(enabled = selecting) { leaveSelecting() }`
- [ ] L1458 `* the pill's ink (an [IconButton] here took the theme's content colour, black on the dark pill),`
- [ ] L1472 `TmDropdownMenu(expanded = open, onDismissRequest = { open = false }) {`
- [ ] L1474 `DropdownMenuItem(`
- [ ] L1476 `onClick = { open = false; action() },`
- [ ] L1685 `onClick = { resumeThis(record) },`
- [ ] L1808 `IconButton(onClick = onDone) { M3Icon(TmIcons.Close, contentDescription = s.commonDone) }`
- [ ] L1816 `androidx.compose.material3.TextButton(onClick = if (all) onSelectNone else onSelectAll) {`
- [ ] L1819 `androidx.compose.material3.Button(`
- [ ] L1820 `onClick = onRemove,`
- [ ] L1837 `HeaderAction(label = if (all) s.browseSelectNone else s.browseSelectAll, icon = TmIcons.Check, onClick = if (a`
- [ ] L1838 `HeaderAction(label = removeLabel, icon = TmIcons.Delete, onClick = onRemove)`
- [ ] L1839 `HeaderAction(label = s.commonDone, icon = TmIcons.Close, onClick = onDone)`
- [ ] L1945 `.clickable(interactionSource = interactions, indication = null, onClick = onClick)`
- [ ] L1992 `PillButton(label = s.browseVoiceSearch, icon = TmIcons.Mic, showLabel = false, onClick = startVoice)`
- [ ] L1998 `PillButton(s.commonClear, TmIcons.Close) {`
- [ ] L2069 `TouchFilterChip(chip.label, chip.icon, chip.selected, onClick = chip.onClick)`
- [ ] L2071 `TvChip(ChipSpec(chip.key, chip.label, chip.icon, chip.selected, onClick = chip.onClick))`
- [ ] L2195 `TouchFilterChip(chip.label, chip.icon, chip.selected, chip.description, chip.onClick)`
- [ ] L2203 `internal fun TouchFilterChip(`
- [ ] L2233 `androidx.compose.material3.FilterChip(`
- [ ] L2236 `onClick = { if (!selected) toggleTick(true); onClick() },`
- [ ] L2244 `colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(`
- [ ] L2260 `* A row of [TouchFilterChip]s that scrolls sideways, spaced and inset as History's tabs are.`
- [ ] L2305 `.clickable(interactionSource = interactions, indication = null, role = Role.Tab, onClick = chip.onClick)`
- [ ] L2322 `private fun PillButton(`
- [ ] L2346 `.clickable(interactionSource = interactions, indication = null, onClick = onClick)`
- [ ] L2515 `onClick = { open(chat, "r-${chat.id}") },`
- [ ] L2555 `onClick = { open(chat, chat.id) },`
- [ ] L2589 `onClick = { open(chat, chat.id) },`
- [ ] L2752 `.holdable(interactionSource = interactions, onClick = onClick, onHold = onHold),`
- [ ] L2766 `.holdable(interactionSource = interactions, onClick = onClick, onHold = onHold)`
- [ ] L2849 `.holdable(interactionSource = interactions, onClick = onClick, onHold = onHold)`
- [ ] L3004 `.holdable(interactionSource = interactions, onClick = onClick, onHold = onHold),`

## app/src/main/java/com/tmplayer/ui/browse/ChatInfoSheet.kt (10)
- [ ] L72 `PhoneSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = fa`
- [ ] L83 `JoinChatButton(info)`
- [ ] L94 `JoinChatButton(info)`
- [ ] L106 `private fun JoinChatButton(info: ChatInfo?) {`
- [ ] L111 `com.tmplayer.ui.components.TmButton(`
- [ ] L112 `onClick = {`
- [ ] L128 `com.tmplayer.ui.components.TmButton(onClick = load.retry) { Text(s.commonTryAgain) }`
- [ ] L153 `.then(if (touch) Modifier.clickable(onClickLabel = s.chatsInfoCopyUsername) { copy(context, "@$username", L.ch`
- [ ] L170 `TmSecondaryButton(onClick = { share(context, link) }) {`
- [ ] L175 `TmSecondaryButton(onClick = { copy(context, link, L.commonLinkCopied) }) { Text(s.commonCopyLink) }`

## app/src/main/java/com/tmplayer/ui/browse/ChipSlider.kt (3)
- [ ] L35 `* The one pill behind a row of [TouchFilterChip]s that slides from the chip left to the chip`
- [ ] L96 `val slider = remember { ChipSlider() }`
- [ ] L134 `val height = FilterChipDefaults.Height.toPx()`

## app/src/main/java/com/tmplayer/ui/browse/FirstRun.kt (5)
- [ ] L134 `IconButton(onClick = { scope.launch { runCatching { settings.setFirstVideoTipDismissed() } } }) {`
- [ ] L272 `TextButton(onClick = turnOff) { Text(s.gridMetaNoticeTurnOff) }`
- [ ] L273 `TextButton(onClick = keep) { Text(s.gridMetaNoticeKeep) }`
- [ ] L284 `TmSecondaryButton(onClick = turnOff) { Text(s.gridMetaNoticeTurnOff) }`
- [ ] L285 `TmSecondaryButton(onClick = keep) { Text(s.gridMetaNoticeKeep) }`

## app/src/main/java/com/tmplayer/ui/browse/HomeCarousel.kt (2)
- [ ] L406 `onClick = { onPlay(playItem, chatTitle(playItem.chatId)) },`
- [ ] L417 `.clickable(role = Role.Button) { onDetails(pick) }`

## app/src/main/java/com/tmplayer/ui/browse/HomePane.kt (26)
- [ ] L480 `Modifier.onPreviewKeyEvent {`
- [ ] L513 `Modifier.onPreviewKeyEvent { event ->`
- [ ] L514 `if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false`
- [ ] L515 `if ((event.key == Key.DirectionDown || event.key == Key.DirectionUp) && staleRepeat(event)) return@onPreviewKe`
- [ ] L519 `if (focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Up)) return@onPreviewKeyEvent true`
- [ ] L520 `if (!listState.canScrollBackward) return@onPreviewKeyEvent false`
- [ ] L525 `return@onPreviewKeyEvent true`
- [ ] L527 `if (event.key != Key.DirectionDown) return@onPreviewKeyEvent false`
- [ ] L528 `if (focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)) return@onPreviewKeyEvent true`
- [ ] L529 `if (!listState.canScrollForward) return@onPreviewKeyEvent false`
- [ ] L536 `val row = info.visibleItemsInfo.firstOrNull { it.key == lastKey } ?: return@onPreviewKeyEvent true`
- [ ] L539 `return@onPreviewKeyEvent true`
- [ ] L685 `onClick = { onResume(record) },`
- [ ] L719 `onClick = { onOpenWatchLater(record) },`
- [ ] L743 `onClick = { onPlay(item, chatTitle(item.chatId)) },`
- [ ] L1002 `com.tmplayer.ui.nav.BackHandler { onClose() }`
- [ ] L1034 `androidx.compose.material3.IconButton(onClick = onClose) {`
- [ ] L1117 `onClick = { onPlay(entry.item, chatTitle(entry.item.chatId)) },`
- [ ] L1134 `onClick = { onOpenSeries(entry.series) },`
- [ ] L1166 `val stepping = Modifier.onPreviewKeyEvent { event ->`
- [ ] L1167 `if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false`
- [ ] L1168 `if (event.key != Key.DirectionRight && event.key != Key.DirectionLeft) return@onPreviewKeyEvent false`
- [ ] L1171 `if (staleRepeat(event)) return@onPreviewKeyEvent true`
- [ ] L1265 `onClick = seeAll,`
- [ ] L1359 `onClick = end.onClick,`
- [ ] L1530 `.clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onClick)`

## app/src/main/java/com/tmplayer/ui/browse/LinkSheets.kt (5)
- [ ] L179 `onClick = onOpenTelegram,`
- [ ] L182 `ChoiceLine(s.linkLookFirst, detail = s.linkLookFirstDetail, icon = TmIcons.ArrowForward, onClick = onLook)`
- [ ] L242 `com.tmplayer.ui.components.TmButton(onClick = list::loadMore) { Text(s.commonTryAgain) }`
- [ ] L267 `com.tmplayer.ui.components.TvChoiceRow(title = s.gridRoundTitle, detail = s.gridRoundCount(count), icon = TmIc`
- [ ] L285 `onClick = {`

## app/src/main/java/com/tmplayer/ui/browse/ListPage.kt (3)
- [ ] L72 `Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsS`
- [ ] L106 `onClick = { onOpen(tile) },`
- [ ] L113 `if (touch) FloatingBackButton(icon = TmIcons.ArrowBack, label = s.commonBack, onClick = onDismiss)`

## app/src/main/java/com/tmplayer/ui/browse/MediaDetailSheet.kt (2)
- [ ] L600 `onDismissRequest = back,`
- [ ] L625 `topEndAction = if (touch) ({ com.tmplayer.cast.CastPageButton() }) else null,`

## app/src/main/java/com/tmplayer/ui/browse/MediaGridScreen.kt (46)
- [ ] L555 `BackHandler(enabled = selecting) { leaveSelection() }`
- [ ] L853 `onClick = {`
- [ ] L878 `onClick = {`
- [ ] L1050 `TmButton(onClick = viewModel::retryMore) { Text(s.commonTryAgain) }`
- [ ] L1333 `BackHandler(enabled = searching) {`
- [ ] L1386 `.then(if (onChatInfo != null) Modifier.clickable(onClickLabel = LocalStrings.current.chatsInfoAbout, onClick =`
- [ ] L1407 `FloatingBackButton(`
- [ ] L1417 `onClick = {`
- [ ] L1458 `onClick = { tick(!isFavorite); onToggleFavorite() },`
- [ ] L1540 `BasicTextField(`
- [ ] L1572 `com.tmplayer.ui.components.ClearButton(s.browseClearSearch, onClick = { onQueryChange("") })`
- [ ] L1576 `IconButton(onClick = onVoiceSearch) {`
- [ ] L1620 `BackHandler(enabled = query.isNotBlank()) {`
- [ ] L1636 `Pill(s.gridBackToChats, TmIcons.ArrowBack, showLabel = false, onClick = onBack)`
- [ ] L1661 `Pill(s.chatsInfoAbout, TmIcons.Info, showLabel = false, onClick = onChatInfo)`
- [ ] L1697 `Pill(label = s.browseVoiceSearch, icon = TmIcons.Mic, showLabel = false, onClick = startVoice)`
- [ ] L1714 `onClick = onToggleFavorite,`
- [ ] L1720 `Pill(s.gridSizeHidden(sizeHidden), TmIcons.FilterHorizontal, tintWhenIdle = Tone.accent, onClick = onSizeHidde`
- [ ] L1723 `onSort?.let { Pill(if (filtered) s.gridFilterOn else s.gridSort, TmIcons.Sort, tintWhenIdle = if (filtered) To`
- [ ] L1728 `Pill(s.commonRefresh, TmIcons.Refresh, showLabel = false, spin = spin, onClick = {`
- [ ] L1790 `.clickable(role = Role.Button) { onPick(recent) }`
- [ ] L1799 `TextButton(onClick = onClear) { M3Text(s.commonClear) }`
- [ ] L1835 `TextButton(onClick = onSelectAll) { M3Text(s.gridSelectAll, maxLines = 1) }`
- [ ] L1838 `if (onDownload != null) Button(`
- [ ] L1839 `onClick = onDownload,`
- [ ] L1869 `if (onDownload != null) Pill(s.gridDownload, TmIcons.Download, onClick = onDownload)`
- [ ] L1870 `Pill(s.gridSelectAll, TmIcons.Check, onClick = onSelectAll)`
- [ ] L1871 `Pill(s.commonCancel, TmIcons.Close, onClick = onCancel)`
- [ ] L1990 `SponsoredButton(item.buttonText, primary = true, onClick = onOpen)`
- [ ] L1993 `SponsoredButton(s.gridReport, primary = false, onClick = onReport)`
- [ ] L2020 `private fun SponsoredButton(label: String, primary: Boolean, onClick: () -> Unit) {`
- [ ] L2023 `FilledTonalButton(onClick = onClick) { M3Text(label, maxLines = 1) }`
- [ ] L2025 `TextButton(onClick = onClick) { M3Text(label, maxLines = 1) }`
- [ ] L2204 `onClick = onClick,`
- [ ] L2315 `Modifier.secondaryPress(onLongClick).combinedClickable(`
- [ ] L2319 `onClick = onClick,`
- [ ] L2416 `onClick = onClick,`
- [ ] L2748 `* [combinedClickable] rather than 'Card(onClick =)' because a card's own click has nowhere to hang`
- [ ] L2749 `* a long press. Where there is no long press to hang, this is a plain clickable and the ripple is`
- [ ] L2755 `if (onLongClick == null) clickable(onClick = onClick)`
- [ ] L2756 `else secondaryPress(onLongClick).combinedClickable(onClick = onClick, onLongClick = onLongClick)`
- [ ] L3014 `com.tmplayer.ui.components.TmSecondaryButton(onClick = onFilterAgain) { Text(s.gridFilterAgain) }`
- [ ] L3016 `if (hidden > 0) com.tmplayer.ui.components.TmSecondaryButton(onClick = onShowThem) { Text(s.gridFilterShowThem`
- [ ] L3017 `onKeepLooking?.let { com.tmplayer.ui.components.TmSecondaryButton(onClick = it) { Text(s.commonKeepLooking) } `
- [ ] L3019 `com.tmplayer.ui.components.TmSecondaryButton(onClick = onClear) { Text(s.gridFilterClear) }`
- [ ] L3040 `androidx.compose.material3.TextButton(onClick = it) { Text(com.tmplayer.ui.components.StateAction.KeepLooking.`

## app/src/main/java/com/tmplayer/ui/browse/PhoneBrowse.kt (1)
- [ ] L492 `actions = { com.tmplayer.ui.components.ChooseChatsButton() },`

## app/src/main/java/com/tmplayer/ui/browse/PhoneSearch.kt (35)
- [ ] L196 `BackHandler(enabled = !sheetOpen) { if (text.text.isNotEmpty()) clear() else onClose() }`
- [ ] L360 `BasicTextField(`
- [ ] L400 `com.tmplayer.ui.components.ClearButton(s.browseClearSearch, onClick = onClear)`
- [ ] L403 `IconButton(onClick = { onVoice?.invoke() }) {`
- [ ] L439 `Modifier.width(64.dp).clip(RoundedCornerShape(12.dp)).clickable { onOpenChat(chat) }.padding(vertical = 4.dp),`
- [ ] L461 `TextButton(onClick = onClearAll) { Text(s.searchClearRecent) }`
- [ ] L472 `.combinedClickable(onClick = { onPick(entry) }, onLongClick = { onRemove(entry) })`
- [ ] L481 `IconButton(onClick = { onFill(entry) }) {`
- [ ] L486 `IconButton(onClick = { onRemove(entry) }) {`
- [ ] L498 `Modifier.fillMaxWidth().clickable { onResume(record) }.padding(horizontal = 16.dp, vertical = 6.dp),`
- [ ] L544 `Modifier.fillMaxWidth().clickable { onOpenChat(chat) }.padding(horizontal = 16.dp, vertical = 8.dp),`
- [ ] L554 `TextButton(onClick = onShowAllChats, modifier = Modifier.padding(start = 8.dp)) {`
- [ ] L567 `if (state.error != null || state.offline) TmSecondaryButton(onClick = onRetry) { Text(LocalStrings.current.com`
- [ ] L576 `TmSecondaryButton(onClick = onAddChats) { Text(LocalStrings.current.pickerAddMore) }`
- [ ] L591 `TextButton(onClick = onSearchOthers) { Text(LocalStrings.current.searchOtherChats) }`
- [ ] L623 `TmSecondaryButton(onClick = onClearFilters) { Text(s.searchClearFilters) }`
- [ ] L633 `TmSecondaryButton(onClick = onSearchOthers) { Text(s.searchOtherChats) }`
- [ ] L635 `TmSecondaryButton(onClick = onAddChats) { Text(s.pickerAddMore) }`
- [ ] L637 `TmSecondaryButton(onClick = { onSearchWord(word) }) { Text(s.searchSearchFor(word)) }`
- [ ] L653 `.clickable { onOpenGroup(group) },`
- [ ] L660 `TextButton(onClick = { onPlayCopy(group, copy) }) {`
- [ ] L677 `TmSecondaryButton(onClick = onRetryMore) { Text(LocalStrings.current.commonTryAgain) }`
- [ ] L705 `TextButton(onClick = onShowAllTopics, modifier = Modifier.padding(start = 8.dp)) {`
- [ ] L716 `TmSecondaryButton(onClick = onRetryTopics) { Text(s.commonRetry) }`
- [ ] L717 `com.tmplayer.ui.settings.SendLogsButton()`
- [ ] L734 `Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),`
- [ ] L773 `FilterChip(`
- [ ] L775 `onClick = onMore,`
- [ ] L777 `leadingIcon = { Icon(TmIcons.FilterHorizontal, contentDescription = null, modifier = Modifier.size(FilterChipD`
- [ ] L782 `FilterChip(`
- [ ] L784 `onClick = {`
- [ ] L799 `PhoneSheet(onDismissRequest = onDismiss) {`
- [ ] L817 `com.tmplayer.ui.components.ChoiceRow(label, icon = null, chosen = chosen, modifier = Modifier.padding(horizont`
- [ ] L824 `Modifier.fillMaxWidth().clickable(role = Role.Switch) { tick(!on); onChange(!on) }.padding(horizontal = 16.dp)`
- [ ] L828 `Switch(checked = on, onCheckedChange = null)`

## app/src/main/java/com/tmplayer/ui/browse/PosterPickerSheet.kt (1)
- [ ] L91 `TmButton(onClick = { attempt++ }, modifier = Modifier.focusRequester(first)) { Text(s.commonTryAgain) }`

## app/src/main/java/com/tmplayer/ui/browse/SearchNoteRow.kt (1)
- [ ] L34 `TmButton(onClick = onDeeper) { Text(L.commonKeepLooking) }`

## app/src/main/java/com/tmplayer/ui/browse/SeriesScreens.kt (2)
- [ ] L62 `onClick = onClick,`
- [ ] L186 `onDismissRequest = motion.close,`

## app/src/main/java/com/tmplayer/ui/browse/TouchNav.kt (18)
- [ ] L267 `BackHandler(enabled = profileOpen) { profileOpen = false }`
- [ ] L801 `BarButton(TmIcons.Home, s.browseTabHome, current == TouchDestination.Home, onHome)`
- [ ] L802 `BarButton(TmIcons.Chats, s.browseTabChats, current == TouchDestination.Chats, onChats)`
- [ ] L803 `BarButton(TmIcons.Search, s.commonSearch, false, onSearch, role = Role.Button, modifier = searchShared)`
- [ ] L804 `ProfileButton(account, inProfile, badged, onProfile)`
- [ ] L818 `private fun ProfileButton(account: Account?, inProfile: Boolean, badged: Boolean, onProfile: () -> Unit) {`
- [ ] L829 `.clickable(interactionSource = profilePress, indication = ripple(), role = Role.Button, onClick = onProfile)`
- [ ] L885 `private fun BarButton(`
- [ ] L899 `.clickable(interactionSource = press, indication = ripple(), role = role, onClick = onClick)`
- [ ] L969 `.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss`
- [ ] L1014 `.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}`
- [ ] L1029 `add { m -> PopoverRow(TmIcons.StarOutline, s.browseTabFavourites, favoriteCount.takeIf { it > 0 }?.toString(),`
- [ ] L1030 `add { m -> PopoverRow(TmIcons.Download, s.navDownloads, downloadCount.takeIf { it > 0 }?.toString(), modifier `
- [ ] L1031 `add { m -> PopoverRow(TmIcons.History, s.browseTabHistory, null, modifier = m, onClick = onHistory) }`
- [ ] L1032 `add { m -> PopoverRow(TmIcons.Clock, s.browseTabWatchLater, null, modifier = m, onClick = onWatchLater) }`
- [ ] L1033 `add { m -> PopoverRow(TmIcons.Settings, s.navSettings, null, modifier = m, onClick = onSettings) }`
- [ ] L1036 `add { m -> PopoverRow(TmIcons.Refresh, s.navUpdate, updateVersion, ink = Tone.caution, modifier = m, onClick =`
- [ ] L1074 `.clickable(interactionSource = press, indication = ripple(), role = Role.Button, onClick = onClick)`

## app/src/main/java/com/tmplayer/ui/browse/TvHomeCarousel.kt (5)
- [ ] L239 `onClick = { onPlay(playItem, chatTitle(playItem.chatId)) },`
- [ ] L245 `.onPreviewKeyEvent { event ->`
- [ ] L258 `onClick = { onDetails(pick) },`
- [ ] L261 `.onPreviewKeyEvent { event ->`
- [ ] L439 `.clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onClick)`

## app/src/main/java/com/tmplayer/ui/browse/TvSearch.kt (16)
- [ ] L159 `com.tmplayer.ui.nav.BackHandler(enabled = text.isNotEmpty(), onBack = clear)`
- [ ] L220 `if (voice != null) MicOrb(onClick = voice, modifier = Modifier.focusRequester(micFocus))`
- [ ] L231 `com.tmplayer.ui.components.ClearButton(s.browseClearSearch, target = 56.dp, disc = 40.dp, onClick = clear)`
- [ ] L313 `TvPill(s.commonRetry, TmIcons.Refresh, onClick = onRetryTopics)`
- [ ] L314 `com.tmplayer.ui.settings.SendLogsButton()`
- [ ] L339 `if (state.error != null || state.offline) TvPill(s.commonRetry, TmIcons.Refresh, onClick = onRetry)`
- [ ] L357 `TvPill(s.searchOtherChats, TmIcons.Search, modifier = Modifier.testTag("search-other-chats"), onClick = onSear`
- [ ] L360 `TvPill(s.pickerAddMore, TmIcons.PersonAdd, onClick = onAddChats)`
- [ ] L395 `TvPill(s.searchOtherChats, TmIcons.Search, modifier = Modifier.testTag("search-other-chats"), onClick = onSear`
- [ ] L397 `TvPill(s.pickerAddMore, TmIcons.PersonAdd, onClick = onAddChats)`
- [ ] L417 `onClick = { onOpenGroup(group) },`
- [ ] L431 `TvPill(s.commonTryAgain, TmIcons.Refresh, onClick = onRetryMore)`
- [ ] L524 `.clickable(interactionSource = interactions, indication = null, onClick = onClick),`
- [ ] L564 `.clickable(interactionSource = interactions, indication = null, onClick = onClick)`
- [ ] L583 `.clickable(interactionSource = interactions, indication = null, onClick = onClick)`
- [ ] L630 `.holdable(interactionSource = interactions, onClick = onClick, onHold = onHold),`

## app/src/main/java/com/tmplayer/ui/browse/TvTopBar.kt (13)
- [ ] L189 `.onPreviewKeyEvent { event ->`
- [ ] L200 `ProfileButton(`
- [ ] L209 `onClick = { profileOpen = true },`
- [ ] L229 `s.navDownloads, TmIcons.Download, selected = inProfile && !searchOpen && downloadsShown, onClick = { beforePro`
- [ ] L233 `s.browseTabWatchLater, TmIcons.Clock, selected = false, onClick = { beforeProfile(); nav.onHistory(HistoryTab.`
- [ ] L239 `com.tmplayer.ui.components.ChooseChatsButton(`
- [ ] L242 `onClick = onChooseChats,`
- [ ] L354 `.clickable(interactionSource = interactions, indication = null, role = Role.Tab, onClick = onClick)`
- [ ] L374 `private fun ProfileButton(`
- [ ] L425 `.clickable(interactionSource = interactions, indication = null, role = Role.Button, onClickLabel = label, onCl`
- [ ] L471 `Modifier.fillMaxSize().onPreviewKeyEvent { event ->`
- [ ] L500 `.onKeyEvent { event ->`
- [ ] L501 `if (event.type != KeyEventType.KeyDown || event.key != Key.DirectionUp) return@onKeyEvent false`

## app/src/main/java/com/tmplayer/ui/browse/VideoSortSheet.kt (1)
- [ ] L40 `onClick = choice.onPick,`
