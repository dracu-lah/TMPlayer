# desktop-ui-1: 259 controls in 29 files

Tick a line only after every dimension in private/UX-INTERACTION-PLAN.md was checked for it.

## desktop/src/main/kotlin/com/tmplayer/desktop/Main.kt (2)
- [ ] L263 `onPreviewKeyEvent = { shell.onPreviewKey(it) || com.tmplayer.desktop.player.PlayerKeyRoute.onWindowKey(it) },`
- [ ] L264 `onKeyEvent = shell::onKey,`

## desktop/src/main/kotlin/com/tmplayer/desktop/os/LinuxNotifications.kt (1)
- [ ] L44 `internal class NoticeButton(val label: String, val onPress: () -> Unit)`

## desktop/src/main/kotlin/com/tmplayer/desktop/os/X11SideScroll.kt (3)
- [ ] L72 `val from = InputEvent.getMaskForButton(event.button)`
- [ ] L73 `if (modifiers and from != 0) modifiers = (modifiers and from.inv()) or InputEvent.getMaskForButton(button)`
- [ ] L95 `private val BUTTON_MASKS = InputEvent.getMaskForButton(SCROLL_LEFT) or InputEvent.getMaskForButton(SCROLL_RIGH`

## desktop/src/main/kotlin/com/tmplayer/desktop/player/CastControls.kt (14)
- [ ] L55 `internal fun CastButton() {`
- [ ] L80 `OverlayButton(TmIcons.Cast, label, onClick = { open = true })`
- [ ] L87 `TmDropdownMenu(expanded = true, onDismissRequest = { open = false }, modifier = Modifier.widthIn(min = 260.dp,`
- [ ] L112 `TextButton(`
- [ ] L113 `onClick = {`
- [ ] L123 `DropdownMenuItem(`
- [ ] L142 `onClick = {`
- [ ] L149 `DropdownMenuItem(`
- [ ] L152 `onClick = {`
- [ ] L158 `DropdownMenuItem(`
- [ ] L161 `onClick = {`
- [ ] L251 `if (lost) Button(onClick = { DesktopCast.reconnect() }) { Text(s.castReconnect) }`
- [ ] L252 `else Button(onClick = { cast.retry() }) { Text(s.commonTryAgain) }`
- [ ] L253 `TextButton(onClick = onPlayHere) { Text(s.castPlayHere) }`

## desktop/src/main/kotlin/com/tmplayer/desktop/player/DevPlayerMain.kt (1)
- [ ] L143 `onPreviewKeyEvent = PlayerKeyRoute::onWindowKey,`

## desktop/src/main/kotlin/com/tmplayer/desktop/player/OnlineSubtitlesPanel.kt (8)
- [ ] L106 `OverlayButton(TmIcons.Close, s.commonClose, onClose, size = 32)`
- [ ] L125 `TextButton(onClick = { attempt++ }) { Text(s.commonTryAgain) }`
- [ ] L145 `TextButton(onClick = { force = true; attempt++ }) { Text(s.msoneCheckAgain) }`
- [ ] L147 `TextButton(onClick = { attempt++ }) { Text(s.commonTryAgain) }`
- [ ] L150 `if (msone && shown.hits.isEmpty()) TextButton(onClick = onPickFile) { Text(s.msonePickFile) }`
- [ ] L157 `.clickable(enabled = enabled) {`
- [ ] L189 `TextButton(onClick = { OpenExternal.browse(hit.pageUrl) }) { Text(s.msoneReleasePage, fontSize = 12.sp) }`
- [ ] L192 `TextButton(onClick = { OpenExternal.browse(hit.translatorUrl) }) { Text(s.msoneTranslator(hit.translator), fon`

## desktop/src/main/kotlin/com/tmplayer/desktop/player/PlayerBookmarks.kt (11)
- [ ] L66 `onDismissRequest = onDismiss,`
- [ ] L69 `OutlinedTextField(`
- [ ] L80 `TextButton(onClick = { onSave(note) }) {`
- [ ] L84 `dismissButton = { TextButton(onClick = onDismiss) { Text(s.commonCancel) } },`
- [ ] L107 `Modifier.weight(1f).heightIn(min = 48.dp).clickable(onClick = onJump).padding(start = 12.dp, end = 8.dp),`
- [ ] L120 `IconButton(onClick = onEdit) {`
- [ ] L125 `IconButton(onClick = onDelete) {`
- [ ] L148 `onDismissRequest = onDismiss,`
- [ ] L151 `OutlinedTextField(`
- [ ] L166 `confirmButton = { TextButton(onClick = ::go) { Text(s.playerGo) } },`
- [ ] L167 `dismissButton = { TextButton(onClick = onDismiss) { Text(s.commonCancel) } },`

## desktop/src/main/kotlin/com/tmplayer/desktop/player/PlayerKeyRoute.kt (1)
- [ ] L17 `* So the window asks here first ([onWindowKey], from its onPreviewKeyEvent): while a player is`

## desktop/src/main/kotlin/com/tmplayer/desktop/player/PlayerLoaderSheet.kt (5)
- [ ] L219 `OverlayButton(TmIcons.ArrowBack, s.playerBackHint, onBack)`
- [ ] L310 `Button(onClick = onRetry, modifier = Modifier.focusRequester(focus)) { Text(s.commonTryAgain) }`
- [ ] L312 `OutlinedButton(onClick = lighter.second) { Text(lighter.first, color = Color.White) }`
- [ ] L315 `OutlinedButton(onClick = onDownloadFirst) { Text(s.playerDownloadFirst, color = Color.White) }`
- [ ] L317 `TextButton(onClick = onBack) { Text(s.playerGoBack, color = Color.White) }`

## desktop/src/main/kotlin/com/tmplayer/desktop/player/PlayerLock.kt (2)
- [ ] L107 `Button(`
- [ ] L108 `onClick = onUnlock,`

## desktop/src/main/kotlin/com/tmplayer/desktop/player/PlayerOverlay.kt (59)
- [ ] L491 `OverlayButton(TmIcons.ArrowBack, s.playerBackHint, onBack)`
- [ ] L500 `OverlayButton(`
- [ ] L503 `onClick = { if (panelOpen) onCloseMenu() else onOpenMenu(MenuAt(MenuPage.Main, MenuAt.Anchor.Overflow)) },`
- [ ] L527 `OverlayButton(TmIcons.SkipPrevious, s.playerPreviousEpisodeHint(s.playerPreviousUp(episodes.labelFor(previous)`
- [ ] L529 `PlayPauseButton(status, onTogglePlay)`
- [ ] L531 `OverlayButton(TmIcons.SkipNext, s.playerNextEpisodeHint(s.playerNextUp(episodes.labelFor(next))), { onEpisode(`
- [ ] L547 `OverlayButton(TmIcons.Subtitles, s.playerSubtitlesHint, onClick = { onOpenMenu(MenuAt(MenuPage.Subtitles, Menu`
- [ ] L554 `OverlayButton(TmIcons.Audio, s.playerAudioHint, onClick = { onOpenMenu(MenuAt(MenuPage.Audio, MenuAt.Anchor.Au`
- [ ] L563 `.clickable { onOpenMenu(MenuAt(MenuPage.Speed, MenuAt.Anchor.Speed)) }.padding(horizontal = 10.dp),`
- [ ] L572 `ShapeButton(status.scale) { onMenuAction(MenuAction.Do(PlayerAction.CycleShape)) }`
- [ ] L579 `if (miniPlayerAvailable) OverlayButton(TmIcons.PictureInPicture, s.playerMiniHint(commandKey(s)), onMiniPlayer`
- [ ] L580 `OverlayButton(`
- [ ] L587 `if (onEpisodes != null) EpisodesButton(onEpisodes)`
- [ ] L666 `.onPreviewKeyEvent { event ->`
- [ ] L667 `if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false`
- [ ] L671 `else -> return@onPreviewKeyEvent false`
- [ ] L698 `private fun ShapeButton(scale: VideoScale, onClick: () -> Unit) {`
- [ ] L711 `OverlayButton(icon, s.playerPictureShapeHint(scale.label), onClick)`
- [ ] L757 `internal fun OverlayButton(icon: ImageVector, label: String, onClick: () -> Unit, size: Int = 40, tonal: Boole`
- [ ] L769 `.clickable(onClick = onClick),`
- [ ] L795 `Slider(`
- [ ] L809 `OverlayButton(icon, if (muted) s.playerUnmuteHint else s.playerMuteHint, onToggleMute)`
- [ ] L855 `modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onToggleRemaining).padding(horizontal =`
- [ ] L1004 `DropdownMenuItem(`
- [ ] L1017 `onClick = onClick,`
- [ ] L1049 `TextButton(onClick = { onLess?.invoke() }, enabled = onLess != null) { Text(less) }`
- [ ] L1051 `TextButton(onClick = { onMore?.invoke() }, enabled = onMore != null) { Text(more) }`
- [ ] L1292 `.then(if (parent != null) Modifier.clickable { page(parent) } else Modifier)`
- [ ] L1307 `OverlayButton(TmIcons.Close, s.commonClose, onClose, size = 36)`
- [ ] L1312 `TmDropdownMenu(expanded = true, onDismissRequest = onClose, modifier = Modifier.widthIn(min = 240.dp, max = 42`
- [ ] L1454 `TextButton(onClick = onStartOver) { Text(s.playerStartOver) }`
- [ ] L1479 `TextButton(onClick = onPlayNow) { Text(s.playerPlayNow) }`
- [ ] L1480 `TextButton(onClick = onHide) { Text(s.commonHide) }`
- [ ] L1525 `.clickable(onClick = onSkip)`
- [ ] L1571 `.clickable(interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current, `
- [ ] L1585 `private fun EpisodesButton(onClick: () -> Unit) {`
- [ ] L1595 `.clickable(onClick = onClick)`
- [ ] L1620 `Modifier.matchParentSize().background(FloatingTone.scrim).clickable(onClick = onClose),`
- [ ] L1632 `.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}`
- [ ] L1633 `.onPreviewKeyEvent { event ->`
- [ ] L1634 `if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false`
- [ ] L1640 `else -> return@onPreviewKeyEvent false`
- [ ] L1652 `trailing = { OverlayButton(TmIcons.Close, s.commonClose, onClose, size = 36) },`
- [ ] L1678 `OverlayButton(TmIcons.Close, s.playerCloseDetailsHint, onClose, size = 32)`
- [ ] L1721 `Box(Modifier.matchParentSize().background(Color(0xCC000000)).clickable(enabled = false) {}, contentAlignment =`
- [ ] L1743 `Button(onClick = onFreeUpSpace, modifier = Modifier.focusRequester(free)) { Text(s.downloadsFreeUpSpace) }`
- [ ] L1749 `TextButton(onClick = onRetry, modifier = Modifier.focusRequester(retry)) { Text(s.commonTryAgain) }`
- [ ] L1750 `TextButton(onClick = onBack) { Text(s.commonBack) }`
- [ ] L1759 `TextButton(onClick = {`
- [ ] L1770 `TextButton(onClick = { onPlayNext(phase.next) }, modifier = Modifier.focusRequester(now)) { Text(s.playerPlayN`
- [ ] L1771 `TextButton(onClick = onCancelNext) { Text(s.commonCancel) }`
- [ ] L1781 `TextButton(onClick = onWatchAgain, modifier = Modifier.focusRequester(again)) { Text(s.playerWatchAgain) }`
- [ ] L1782 `TextButton(onClick = onBack) { Text(s.commonBack) }`
- [ ] L1799 `Button(onClick = onKeepWatching, modifier = Modifier.focusRequester(keep)) { Text(s.playerKeepWatching) }`
- [ ] L1801 `TextButton(onClick = onBack) { Text(s.commonBack) }`
- [ ] L1820 `Modifier.matchParentSize().background(FloatingTone.scrim).clickable(onClick = onClose),`
- [ ] L1835 `OverlayButton(TmIcons.Close, s.commonClose, onClose, size = 32)`
- [ ] L1874 `private fun PlayPauseButton(status: PlaybackStatus, onTogglePlay: () -> Unit) {`
- [ ] L1879 `Modifier.size(72.dp).clip(CircleShape).background(PlayerTonalButton).clickable(onClick = onTogglePlay)`

## desktop/src/main/kotlin/com/tmplayer/desktop/player/PlayerScreen.kt (3)
- [ ] L1893 `.onPreviewKeyEvent { playerKeyNow(it) }`
- [ ] L1947 `onClick = {`
- [ ] L2131 `castButton = { CastButton() },`

## desktop/src/main/kotlin/com/tmplayer/desktop/ui/About.kt (7)
- [ ] L64 `IconButton(`
- [ ] L65 `onClick = onBack,`
- [ ] L115 `modifier = Modifier.clickable(`
- [ ] L118 `onClick = DevOptions::tap,`
- [ ] L146 `CoffeeButton(onClick = { OpenExternal.browse(About.COFFEE) })`
- [ ] L147 `OutlinedButton(onClick = { OpenExternal.browse(About.SPONSORS) }) { Text(s.supportSponsorGithub) }`
- [ ] L152 `Action(s.aboutNoticesTitle, s.aboutNoticesDetail, TmIcons.List, onClick = onRead)`

## desktop/src/main/kotlin/com/tmplayer/desktop/ui/ChatChrome.kt (10)
- [ ] L88 `FloatingRoundButton(TmIcons.ArrowBack, s.gridBackToChats, onClick = { state.closeChat() }, shortcut = "Esc")`
- [ ] L113 `FloatingRoundButton(TmIcons.Info, s.chatsInfoAbout, onClick = { info = true })`
- [ ] L150 `BackHandler(enabled = searching) { onCloseSearch() }`
- [ ] L168 `BasicTextField(`
- [ ] L178 `.onPreviewKeyEvent { event ->`
- [ ] L179 `if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false`
- [ ] L213 `if (query.isNotEmpty()) BarTooltip(s.browseClearSearch, null, below = true) { com.tmplayer.ui.components.Clear`
- [ ] L223 `onClick = onToggleFavourite,`
- [ ] L312 `.clickable(role = Role.Button) { onPick(recent) }`
- [ ] L322 `TextButton(onClick = onClear) { Text(s.commonClear) }`

## desktop/src/main/kotlin/com/tmplayer/desktop/ui/ChatInfoPanel.kt (6)
- [ ] L70 `OpenInTelegramButton(onOpen = { shown.link?.let(OpenExternal::browse) })`
- [ ] L75 `OutlinedButton(onClick = load.retry) { Text(s.commonTryAgain) }`
- [ ] L85 `.clickable(onClickLabel = s.chatsInfoCopyUsername) { copy("@$username", L.chatsInfoUsernameCopied) }`
- [ ] L96 `OutlinedButton(onClick = { copy(link, L.commonLinkCopied) }) { Text(s.commonCopyLink) }`
- [ ] L97 `TextButton(onClick = { OpenExternal.browse(link) }) { Text(link.removePrefix("https://")) }`
- [ ] L100 `TextButton(onClick = onDismiss) { Text(s.commonClose) }`

## desktop/src/main/kotlin/com/tmplayer/desktop/ui/Chats.kt (15)
- [ ] L157 `com.tmplayer.ui.components.ChooseChatsButton(`
- [ ] L160 `onClick = { state.chooseChats() },`
- [ ] L329 `FilterChip(`
- [ ] L331 `onClick = choice.onClick,`
- [ ] L335 `colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(`
- [ ] L528 `.onPreviewKeyEvent { event ->`
- [ ] L532 `return@onPreviewKeyEvent true`
- [ ] L537 `return@onPreviewKeyEvent false`
- [ ] L541 `Key.H -> actions.onHide?.invoke(chat) ?: return@onPreviewKeyEvent false`
- [ ] L542 `else -> return@onPreviewKeyEvent false`
- [ ] L546 `.clickable(onClick = onOpen)`
- [ ] L578 `TipIconButton(`
- [ ] L608 `DropdownMenuItem(`
- [ ] L612 `onClick = {`
- [ ] L618 `TmDropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {`

## desktop/src/main/kotlin/com/tmplayer/desktop/ui/Common.kt (13)
- [ ] L121 `if (action != null && onAction != null) OutlinedButton(onClick = { onAction(action) }) {`
- [ ] L132 `if (onRetry != null) OutlinedButton(onClick = onRetry) { Text(s.commonTryAgain) }`
- [ ] L161 `OutlinedButton(onClick = onRetry) { Text(SlowAnswer.RETRY) }`
- [ ] L181 `onClick = { onPick(recent) },`
- [ ] L188 `TextButton(onClick = onClear) { Text(s.commonClear) }`
- [ ] L207 `if (actionLabel != null && onAction != null) OutlinedButton(onClick = onAction) { Text(actionLabel) }`
- [ ] L239 `BackHandler(enabled = focused || query.isNotEmpty()) {`
- [ ] L257 `BasicTextField(`
- [ ] L268 `.onPreviewKeyEvent { event ->`
- [ ] L289 `com.tmplayer.ui.components.ClearButton(s.browseClearSearch, onClick = { onQuery("") }, target = 32.dp, disc = `
- [ ] L420 `onDismissRequest = onDismiss,`
- [ ] L430 `TextButton(onClick = onConfirm, modifier = Modifier.focusRequester(confirmFocus)) {`
- [ ] L435 `TextButton(onClick = onDismiss, modifier = Modifier.focusRequester(cancelFocus)) { Text(cancelLabel ?: s.commo`

## desktop/src/main/kotlin/com/tmplayer/desktop/ui/CrashQuestion.kt (2)
- [ ] L63 `Modifier.fillMaxWidth().clickable { always = !always },`
- [ ] L66 `Checkbox(checked = always, onCheckedChange = null)`

## desktop/src/main/kotlin/com/tmplayer/desktop/ui/DesktopChecks.kt (6)
- [ ] L76 `val buttons = if (play != null) listOf(NoticeButton(L.commonPlay) { playFromNotice(state, play, alert.chatTitl`
- [ ] L85 `val buttons = if (play != null) listOf(NoticeButton(L.commonPlay) { playFromNotice(state, play, alert.chatTitl`
- [ ] L95 `notices.notice(title, body, { openFreeUp(state) }, listOf(NoticeButton(L.downloadsFreeUpSpace) { openFreeUp(st`
- [ ] L126 `if (!star) add(NoticeButton(L.supportBuyCoffee) { OpenExternal.browse(About.COFFEE) })`
- [ ] L129 `NoticeButton(L.supportStarGithub) {`
- [ ] L136 `add(NoticeButton(L.notifyActionNotNow) {})`

## desktop/src/main/kotlin/com/tmplayer/desktop/ui/DetailPane.kt (4)
- [ ] L104 `BackHandler(enabled = open) { state.closeDetail() }`
- [ ] L117 `.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { state.closeAllDet`
- [ ] L162 `.onPreviewKeyEvent { event ->`
- [ ] L591 `onDismissRequest = onDismiss,`

## desktop/src/main/kotlin/com/tmplayer/desktop/ui/Downloads.kt (35)
- [ ] L161 `BackHandler(enabled = picked.isNotEmpty()) { picked = emptySet() }`
- [ ] L246 `Modifier.fillMaxSize().onPreviewKeyEvent { event ->`
- [ ] L271 `TextButton(onClick = { picked = emptySet() }) { Text(s.commonClearSelection) }`
- [ ] L272 `TextButton(onClick = selectAll, enabled = picked.size < history.size) { Text(s.downloadsSelectAll) }`
- [ ] L273 `OutlinedButton(onClick = { saveToFolder(pickedRecords) }) {`
- [ ] L278 `OutlinedButton(onClick = { confirm = history.filter { it.key in picked } }) {`
- [ ] L392 `TextButton(onClick = { OfflineDownloads.pauseAll(state.downloads) }) { Text(s.downloadsPauseAll) }`
- [ ] L394 `TextButton(onClick = { OfflineDownloads.resumeAll(state.downloads) }) { Text(s.downloadsResumeAll) }`
- [ ] L406 `TextButton(onClick = { OpenExternal.open(downloadsDir.apply { mkdirs() }) }) { Text(s.downloadsOpenFolder) }`
- [ ] L409 `if (history.isNotEmpty()) TextButton(onClick = selectAll) { Text(s.downloadsSelectAll) }`
- [ ] L411 `if (playRandom != null) TextButton(onClick = playRandom) { Text(s.gridPlayRandom) }`
- [ ] L503 `onClick = { cachedOpen = !cachedOpen },`
- [ ] L938 `androidx.compose.material3.OutlinedButton(onClick = onAction) { Text(actionLabel) }`
- [ ] L941 `androidx.compose.material3.OutlinedButton(onClick = onSecondAction) { Text(secondLabel) }`
- [ ] L986 `Modifier.clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onClick)`
- [ ] L997 `if (more && menu.isNotEmpty()) TipIconButton(TmIcons.MoreVert, LocalStrings.current.commonMoreOptions, { open `
- [ ] L999 `com.tmplayer.ui.components.TmDropdownMenu(expanded = open, onDismissRequest = { open = false }) {`
- [ ] L1001 `DropdownMenuItem(`
- [ ] L1004 `onClick = {`
- [ ] L1045 `.toggleable(value = pick.picked, role = Role.Checkbox, onValueChange = pick.onPick)`
- [ ] L1123 `VideoRow(onClick = null, menu = menu) {`
- [ ] L1142 `row.busy -> TipIconButton(TmIcons.Pause, s.playerPause, pause)`
- [ ] L1144 `if (noSpace) TipIconButton(TmIcons.Delete, s.downloadsFreeUpSpace, onFreeUp!!)`
- [ ] L1145 `if (savable) TipIconButton(TmIcons.Refresh, s.commonTryAgain, resume)`
- [ ] L1147 `else -> TipIconButton(TmIcons.Play, s.downloadsResume, resume)`
- [ ] L1149 `TipIconButton(TmIcons.Close, if (failed) s.commonDismiss else s.commonCancel, cancel)`
- [ ] L1223 `VideoRow(onClick = click, menu = menu, more = playable) { lit ->`
- [ ] L1235 `TextButton(onClick = onDownloadAgain) { Text(s.downloadsDownloadAgain) }`
- [ ] L1236 `TextButton(onClick = onRemove) { Text(s.commonRemove) }`
- [ ] L1238 `partial -> TextButton(onClick = onResume) { Text(s.downloadsResume) }`
- [ ] L1251 `VideoRow(onClick = null, menu = menu) {`
- [ ] L1253 `TextButton(onClick = onKeep) { Text(s.downloadsKeep) }`
- [ ] L1254 `TextButton(onClick = onDelete) { Text(s.commonDelete, color = Tone.danger) }`
- [ ] L1277 `VideoRow(onClick = onPlay, menu = menu) {`
- [ ] L1286 `TextButton(onClick = onSave) {`

## desktop/src/main/kotlin/com/tmplayer/desktop/ui/FocusVisible.kt (1)
- [ ] L69 `.onPreviewKeyEvent { event ->`

## desktop/src/main/kotlin/com/tmplayer/desktop/ui/GridSelection.kt (3)
- [ ] L129 `TextButton(onClick = { selection.selectAll(items.filter { it.canBeSaved }.map { it.id }) }) { Text(s.browseSel`
- [ ] L130 `TextButton(onClick = { selection.clear() }) { Text(s.commonClear) }`
- [ ] L131 `Button(onClick = {`

## desktop/src/main/kotlin/com/tmplayer/desktop/ui/HistorySelection.kt (8)
- [ ] L76 `onPreviewKeyEvent { event ->`
- [ ] L77 `if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false`
- [ ] L82 `if (all.isEmpty()) return@onPreviewKeyEvent false`
- [ ] L115 `TextButton(onClick = { if (all) selection.clear() else selection.selectAll(allIds) }) {`
- [ ] L118 `TextButton(onClick = { selection.clear() }) { Text(s.commonDone) }`
- [ ] L119 `Button(onClick = onRemove, enabled = count > 0) { Text(s.browseRemoveCount(count)) }`
- [ ] L134 `.semantics { role = Role.Checkbox; stateDescription = if (ticked) s.browseBadgeSelected else "" }`
- [ ] L135 `.clickable(onClick = onToggle),`

## desktop/src/main/kotlin/com/tmplayer/desktop/ui/Home.kt (10)
- [ ] L472 `BackHandler(enabled = true) { onBack() }`
- [ ] L484 `IconButton(onClick = onBack) { Icon(TmIcons.ArrowBack, contentDescription = s.commonBack) }`
- [ ] L489 `TextButton(onClick = more.second) {`
- [ ] L690 `{ close -> androidx.compose.material3.DropdownMenuItem(text = { Text(LocalStrings.current.listsRemove) }, onCl`
- [ ] L710 `androidx.compose.material3.DropdownMenuItem(text = { Text(LocalStrings.current.detailOpenDetails) }, onClick =`
- [ ] L712 `androidx.compose.material3.DropdownMenuItem(text = { Text(LocalStrings.current.listsRemove) }, onClick = { clo`
- [ ] L714 `androidx.compose.material3.DropdownMenuItem(text = { Text(LocalStrings.current.detailWatchLaterRemove) }, onCl`
- [ ] L886 `.clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onClick),`
- [ ] L1015 `.clickable(interactionSource = interactions, indication = null, role = androidx.compose.ui.semantics.Role.Butt`
- [ ] L1043 `TextButton(onClick = onRefresh) { Text(com.tmplayer.ui.components.SlowAnswer.RETRY) }`

## desktop/src/main/kotlin/com/tmplayer/desktop/ui/HomeCarousel.kt (10)
- [ ] L288 `.onPreviewKeyEvent { event ->`
- [ ] L289 `if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false`
- [ ] L371 `if (count > 1) ArrowSlot(arrows) { ArrowButton(TmIcons.ArrowBack, s.homeRowScrollBack) { go(target - 1) } }`
- [ ] L373 `if (count > 1) ArrowSlot(arrows) { ArrowButton(TmIcons.ArrowForward, s.homeRowScrollOn) { go(target + 1) } }`
- [ ] L495 `HeroPill(label = playLabel, onClick = { onPlay(playItem) }, modifier = Modifier.warmUpOnIntent(playItem))`
- [ ] L496 `RoundButton(TmIcons.Info, s.detailOpenDetails, size = com.tmplayer.ui.browse.HERO_PILL_HEIGHT) { onDetails(pic`
- [ ] L503 `private fun ArrowButton(icon: ImageVector, label: String, onClick: () -> Unit) {`
- [ ] L504 `RoundButton(icon, label, size = ARROW_SIZE, onClick = onClick)`
- [ ] L517 `private fun RoundButton(icon: ImageVector, label: String, size: Dp, onClick: () -> Unit) {`
- [ ] L527 `.clickable(interactionSource = press, indication = androidx.compose.foundation.LocalIndication.current, role =`

## desktop/src/main/kotlin/com/tmplayer/desktop/ui/IdleCursor.kt (1)
- [ ] L105 `.onPreviewKeyEvent {`

## desktop/src/main/kotlin/com/tmplayer/desktop/ui/KeyboardNav.kt (5)
- [ ] L162 `return onKeyEvent { event ->`
- [ ] L163 `if (event.type != KeyEventType.KeyDown || event.isAltPressed || event.isShiftPressed) return@onKeyEvent false`
- [ ] L167 `else -> return@onKeyEvent false`
- [ ] L234 `fun Modifier.navKeys(nav: KeyboardNav, count: () -> Int): Modifier = onPreviewKeyEvent { nav.onKey(it, count()`
- [ ] L238 `* Put on the cell's focusable (clickable) element, with the [FocusRequester] it uses.`

## desktop/src/main/kotlin/com/tmplayer/desktop/ui/LanguageAndFeedback.kt (13)
- [ ] L73 `onDismissRequest = onClose,`
- [ ] L84 `confirmButton = { TextButton(onClick = onClose) { Text(s.commonClose) } },`
- [ ] L93 `onDismissRequest = onClose,`
- [ ] L106 `dismissButton = { TextButton(onClick = { OpenExternal.browse(WhatsNew.CHANGELOG) }) { Text(s.whatsnewFullChang`
- [ ] L107 `confirmButton = { TextButton(onClick = onClose) { Text(s.commonClose) } },`
- [ ] L163 `onDismissRequest = onClose,`
- [ ] L170 `Button(onClick = { OpenExternal.browse(issue); onClose() }) { Text(s.feedbackOpenGithub) }`
- [ ] L171 `OutlinedButton(onClick = { OpenExternal.mail(Feedback.mailto(facts)); onClose() }) { Text(s.feedbackEmail) }`
- [ ] L182 `confirmButton = { TextButton(onClick = onClose) { Text(s.commonClose) } },`
- [ ] L203 `TextButton(onClick = onChange) { Text(s.commonChange) }`
- [ ] L204 `Button(onClick = onKeep) { Text(s.languageKeep) }`
- [ ] L226 `TextButton(onClick = onDismiss) { Text(s.commonDismiss) }`
- [ ] L227 `Button(onClick = onOpen) { Text(s.commonOpen) }`
