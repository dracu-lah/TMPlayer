# desktop-ui-2: 251 controls in 19 files

Tick a line only after every dimension in private/UX-INTERACTION-PLAN.md was checked for it.

## desktop/src/main/kotlin/com/tmplayer/desktop/ui/LinkDialogs.kt (14)
- [ ] L109 `TextButton(onClick = flow::dismiss) { Text(s.commonClose) }`
- [ ] L114 `TextButton(onClick = flow::dismiss) { Text(s.commonClose) }`
- [ ] L136 `OpenInTelegramButton(onOpen = flow::openInTelegram, modifier = Modifier.focusRequester(joinFocus))`
- [ ] L137 `TextButton(onClick = flow::dismiss) { Text(s.commonClose) }`
- [ ] L155 `OpenInTelegramButton(onOpen = flow::openInTelegram, modifier = Modifier.focusRequester(joinFocus))`
- [ ] L156 `OutlinedButton(onClick = flow::look) { Text(s.linkLookFirst) }`
- [ ] L157 `TextButton(onClick = flow::dismiss) { Text(s.commonClose) }`
- [ ] L167 `internal fun OpenInTelegramButton(onOpen: () -> Unit, modifier: Modifier = Modifier) {`
- [ ] L169 `Button(onClick = onOpen, modifier = modifier.pointerHoverIcon(PointerIcon.Hand)) {`
- [ ] L204 `onClick = { onDismiss(); onPlay(item) },`
- [ ] L228 `OutlinedButton(onClick = list::loadMore) { Text(s.commonTryAgain) }`
- [ ] L231 `!shown.done -> TextButton(onClick = list::loadMore, modifier = Modifier.loadWhenShown(list::loadMore)) { Text(`
- [ ] L236 `TextButton(onClick = onDismiss) { Text(s.commonClose) }`
- [ ] L270 `Surface(onClick = onOpen, shape = MaterialTheme.shapes.medium, color = Tone.surface, modifier = Modifier.fillM`

## desktop/src/main/kotlin/com/tmplayer/desktop/ui/ListsPage.kt (2)
- [ ] L44 `BackHandler(enabled = true) { onBack() }`
- [ ] L57 `IconButton(onClick = onBack, modifier = Modifier.padding(start = side)) { Icon(TmIcons.ArrowBack, contentDescr`

## desktop/src/main/kotlin/com/tmplayer/desktop/ui/MediaGrid.kt (57)
- [ ] L437 `if (keepLooking) TextButton(onClick = { autoPages = 0; model.loadMore() }) { Text(s.commonKeepLooking) }`
- [ ] L478 `OutlinedButton(onClick = { filterPaused = false }) { Text(s.gridFilterAgain) }`
- [ ] L480 `if (hidden > 0) OutlinedButton(onClick = { filterPaused = true }) { Text(s.gridFilterShowThem) }`
- [ ] L481 `if (keepLooking) OutlinedButton(onClick = { autoPages = 0; model.loadMore() }) { Text(s.commonKeepLooking) }`
- [ ] L483 `OutlinedButton(onClick = clearFilter) { Text(s.gridFilterClear) }`
- [ ] L509 `OutlinedButton(onClick = model::retryMore) { Text(s.commonTryAgain) }`
- [ ] L519 `OutlinedButton(onClick = model::searchDeeper) { Text(s.commonKeepLooking) }`
- [ ] L653 `BackHandler(enabled = selection.active) { selection.clear() }`
- [ ] L728 `TipIconButton(TmIcons.Grid, s.browseSmallerPosters, { state.stepPoster(-1) }, enabled = state.posterWidth != P`
- [ ] L729 `TipIconButton(TmIcons.Add, s.browseLargerPosters, { state.stepPoster(1) }, enabled = state.posterWidth != POST`
- [ ] L763 `BackHandler(enabled = selection.active) { selection.clear() }`
- [ ] L775 `if (!selection.active) OutlinedButton(onClick = { selection.start() }) { Text(s.browseSelect) }`
- [ ] L776 `OutlinedButton(onClick = clearAll) { Text(s.browseClearHistory) }`
- [ ] L868 `DropdownMenuItem(text = { Text(WatchedWords.markLabel(onList = false)) }, onClick = {`
- [ ] L872 `DropdownMenuItem(text = { Text(s.browseRemoveFromHistory) }, onClick = { close(); onForget() })`
- [ ] L1121 `.onPreviewKeyEvent { event ->`
- [ ] L1144 `Modifier.combinedClickable(interactionSource = interaction, indication = null, onDoubleClick = play, onClick =`
- [ ] L1146 `Modifier.clickable(interactionSource = interaction, indication = null, onClick = play)`
- [ ] L1211 `modifier = Modifier.size(48.dp).clickable { state.openPlayer(item, startFromBeginning = false) },`
- [ ] L1221 `IconButton(onClick = { openMenu() }) {`
- [ ] L1312 `TmDropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {`
- [ ] L1316 `DropdownMenuItem(text = { Text(s.playerPlay) }, onClick = { onDismiss(); state.openPlayer(item, startFromBegin`
- [ ] L1317 `DropdownMenuItem(text = { Text(s.playerPlayFromStart) }, onClick = { onDismiss(); state.openPlayer(item, start`
- [ ] L1321 `DropdownMenuItem(text = { Text(WatchedWords.markLabel(watchedToggle)) }, onClick = {`
- [ ] L1336 `DropdownMenuItem(text = { Text(if (later) s.detailWatchLaterRemove else s.detailWatchLaterAdd) }, onClick = {`
- [ ] L1345 `DropdownMenuItem(text = { Text(s.listsAddTo) }, onClick = {`
- [ ] L1352 `DropdownMenuItem(text = { Text(s.downloadsInDownloads) }, onClick = { onDismiss(); state.go(Destination.Downlo`
- [ ] L1353 `DropdownMenuItem(text = { Text(s.downloadsRemove) }, onClick = {`
- [ ] L1359 `DropdownMenuItem(text = { Text(s.downloadsDownloadingEllipsis) }, onClick = { onDismiss(); state.go(Destinatio`
- [ ] L1360 `DropdownMenuItem(text = { Text(s.downloadsCancel) }, onClick = {`
- [ ] L1368 `item.onDevice -> DropdownMenuItem(text = { Text(withFree(s.downloadsSave, free)) }, onClick = {`
- [ ] L1373 `else -> DropdownMenuItem(text = { Text(withFree(s.downloadsDownload, free)) }, onClick = {`
- [ ] L1379 `DropdownMenuItem(text = { Text(s.commonOpenElsewhere) }, onClick = {`
- [ ] L1395 `DropdownMenuItem(text = { Text(s.commonCopyLink) }, onClick = {`
- [ ] L1454 `OutlinedButton(onClick = {`
- [ ] L1461 `TextButton(onClick = { report(ad, byteArrayOf()) }) { Text(s.sponsoredReport, color = Tone.muted) }`
- [ ] L1470 `onDismissRequest = { reportOptions = null },`
- [ ] L1475 `TextButton(`
- [ ] L1476 `onClick = {`
- [ ] L1486 `dismissButton = { TextButton(onClick = { reportOptions = null }) { Text(s.commonCancel) } },`
- [ ] L1524 `onDismissRequest = { answer(turnOn = false) },`
- [ ] L1528 `confirmButton = { TextButton(onClick = { answer(turnOn = true) }) { Text(s.metadataInviteTurnOn) } },`
- [ ] L1529 `dismissButton = { TextButton(onClick = { answer(turnOn = false) }) { Text(s.metadataInviteNotNow) } },`
- [ ] L1563 `TextButton(onClick = {`
- [ ] L1568 `TextButton(onClick = { dismiss() }) { Text(s.gridMetaNoticeKeep) }`
- [ ] L1624 `onClick = { onExplain(file.reason) },`
- [ ] L1650 `androidx.compose.material3.Surface(onClick = onOpen, shape = MaterialTheme.shapes.medium, color = Tone.surface`
- [ ] L1690 `Button(onClick = { scope.launch { list.playAll()?.let(::play) } }) {`
- [ ] L1696 `androidx.compose.material3.FilterChip(`
- [ ] L1698 `onClick = { shuffle = !shuffle; com.tmplayer.data.PlayQueue.setShuffle(shuffle) },`
- [ ] L1701 `androidx.compose.material3.FilterChip(`
- [ ] L1703 `onClick = {`
- [ ] L1711 `androidx.compose.material3.FilterChip(`
- [ ] L1713 `onClick = {`
- [ ] L1729 `androidx.compose.material3.Surface(onClick = { play(list.queueFrom(item)) }, shape = MaterialTheme.shapes.smal`
- [ ] L1744 `OutlinedButton(onClick = list::loadMore) { Text(s.commonTryAgain) }`
- [ ] L1746 `!shown.done -> TextButton(onClick = list::loadMore, modifier = Modifier.loadWhenShown(list::loadMore)) { Text(`

## desktop/src/main/kotlin/com/tmplayer/desktop/ui/ModalPage.kt (1)
- [ ] L104 `.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { state.closeModal(`

## desktop/src/main/kotlin/com/tmplayer/desktop/ui/NavBar.kt (22)
- [ ] L262 `NavButton(TmIcons.Home, s.browseTabHome, "${COMMAND}1", current == NavPlace.Home) { state.go(Destination.Home)`
- [ ] L263 `NavButton(TmIcons.Chats, s.navChats, "${COMMAND}2", current == NavPlace.Chats) {`
- [ ] L267 `NavButton(TmIcons.Search, s.commonSearch, "${COMMAND}K", current == NavPlace.Search) { state.paletteOpen = !st`
- [ ] L268 `ProfileButton(`
- [ ] L274 `onClick = { state.profileOpen = !state.profileOpen },`
- [ ] L296 `private fun NavButton(`
- [ ] L334 `.clickable(interactionSource = interactions, indication = androidx.compose.foundation.LocalIndication.current,`
- [ ] L362 `private fun ProfileButton(`
- [ ] L377 `onClick = onClick,`
- [ ] L426 `internal fun TipIconButton(`
- [ ] L435 `androidx.compose.material3.IconButton(onClick = onClick, enabled = enabled) {`
- [ ] L451 `BackHandler(enabled = open) { state.profileOpen = false }`
- [ ] L457 `.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { state.profileOpen`
- [ ] L490 `.onPreviewKeyEvent { event ->`
- [ ] L491 `if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false`
- [ ] L513 `PopoverRow(TmIcons.StarOutline, s.browseTabFavourites, favourites.size.takeIf { it > 0 }?.toString(), Modifier`
- [ ] L514 `PopoverRow(TmIcons.Download, s.navDownloads, inFlight.takeIf { it > 0 }?.toString(), onClick = close { state.g`
- [ ] L515 `PopoverRow(TmIcons.History, s.browseTabHistory, null, onClick = close { state.showHistory(HistoryTab.Continue)`
- [ ] L516 `PopoverRow(TmIcons.Clock, s.browseTabWatchLater, null, onClick = close { state.showHistory(HistoryTab.WatchLat`
- [ ] L518 `PopoverRow(TmIcons.Settings, s.navSettings, null, shortcut = "$COMMAND,", onClick = close { state.go(Destinati`
- [ ] L522 `PopoverRow(TmIcons.Refresh, update.label, update.version, ink = Tone.caution, onClick = close { state.updatePo`
- [ ] L551 `.clickable(interactionSource = interactions, indication = androidx.compose.foundation.LocalIndication.current,`

## desktop/src/main/kotlin/com/tmplayer/desktop/ui/OnlineMetadataSettings.kt (8)
- [ ] L119 `onDismissRequest = onClose,`
- [ ] L124 `OutlinedTextField(key, { key = it }, label = { Text(s.metadataKey) }, singleLine = true, modifier = Modifier.f`
- [ ] L129 `TextButton(onClick = {`
- [ ] L134 `dismissButton = { TextButton(onClick = onClose) { Text(s.commonCancel) } },`
- [ ] L145 `onDismissRequest = onClose,`
- [ ] L150 `OutlinedTextField(key, { key = it }, label = { Text(s.metadataOmdbKey) }, singleLine = true, modifier = Modifi`
- [ ] L155 `TextButton(onClick = {`
- [ ] L160 `dismissButton = { TextButton(onClick = onClose) { Text(s.commonCancel) } },`

## desktop/src/main/kotlin/com/tmplayer/desktop/ui/OnlineSubtitlesSettings.kt (9)
- [ ] L178 `onDismissRequest = onClose,`
- [ ] L183 `OutlinedTextField(username, { username = it }, label = { Text(s.onlineUsername) }, singleLine = true, modifier`
- [ ] L184 `OutlinedTextField(`
- [ ] L196 `TextButton(enabled = !busy && username.isNotBlank() && password.isNotEmpty(), onClick = {`
- [ ] L212 `dismissButton = { TextButton(onClick = onClose) { Text(s.commonCancel) } },`
- [ ] L223 `onDismissRequest = onClose,`
- [ ] L228 `OutlinedTextField(key, { key = it }, label = { Text(s.onlineSubdl) }, singleLine = true, modifier = Modifier.f`
- [ ] L232 `TextButton(onClick = {`
- [ ] L238 `dismissButton = { TextButton(onClick = onClose) { Text(s.commonCancel) } },`

## desktop/src/main/kotlin/com/tmplayer/desktop/ui/PosterPickerDialog.kt (8)
- [ ] L83 `onDismissRequest = onDismiss,`
- [ ] L88 `TextButton(onClick = onReset) { Text(s.posterReset) }`
- [ ] L91 `dismissButton = { TextButton(onClick = onDismiss) { Text(s.commonCancel) } },`
- [ ] L126 `Modifier.fillMaxWidth().testTag("poster-others").clickable { alsoOthers = !alsoOthers },`
- [ ] L129 `Checkbox(checked = alsoOthers, onCheckedChange = { alsoOthers = it })`
- [ ] L141 `if (load.canRetry) TextButton(onClick = onRetry, modifier = Modifier.focusRequester(first)) { Text(s.commonTry`
- [ ] L171 `.onKeyEvent { event ->`
- [ ] L180 `.clickable {`

## desktop/src/main/kotlin/com/tmplayer/desktop/ui/RenameDialog.kt (5)
- [ ] L49 `onDismissRequest = onDismiss,`
- [ ] L53 `OutlinedTextField(`
- [ ] L63 `confirmButton = { TextButton(onClick = { save() }) { Text(s.gridRenameSave) } },`
- [ ] L66 `TextButton(onClick = { onSave(null) }) { Text(s.gridRenameReset) }`
- [ ] L67 `TextButton(onClick = onDismiss) { Text(s.commonCancel) }`

## desktop/src/main/kotlin/com/tmplayer/desktop/ui/SearchPalette.kt (22)
- [ ] L264 `BackHandler(enabled = true) { if (state.paletteQuery.text.isNotEmpty()) clearAll() else state.paletteOpen = fa`
- [ ] L397 `.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = ::close),`
- [ ] L407 `.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),`
- [ ] L416 `BasicTextField(`
- [ ] L430 `.onPreviewKeyEvent { event ->`
- [ ] L431 `if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false`
- [ ] L458 `com.tmplayer.ui.components.ClearButton(s.browseClearSearch, onClick = ::clearAll)`
- [ ] L479 `onClick = { pick(row) },`
- [ ] L612 `.clickable(onClick = onClick)`
- [ ] L634 `if (row.retry) TextButton(onClick = onRetry) { Text(s.commonRetry) }`
- [ ] L639 `IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {`
- [ ] L689 `TextButton(onClick = onRetryTopics) { Text(s.commonRetry) }`
- [ ] L734 `FilterChip(`
- [ ] L736 `onClick = { search.setFilters(filters.copy(chatType = type)) },`
- [ ] L742 `FilterChip(`
- [ ] L744 `onClick = { search.setFilters(filters.copy(hideSmall = !filters.hideSmall)) },`
- [ ] L747 `FilterChip(`
- [ ] L749 `onClick = { search.setFilters(filters.copy(includeArchived = !filters.includeArchived)) },`
- [ ] L761 `FilterChip(`
- [ ] L763 `onClick = { open = true },`
- [ ] L767 `TmDropdownMenu(expanded = open, onDismissRequest = { open = false }) {`
- [ ] L769 `DropdownMenuItem(text = { Text(text) }, onClick = { open = false; choose() })`

## desktop/src/main/kotlin/com/tmplayer/desktop/ui/SendLogs.kt (3)
- [ ] L100 `OutlinedButton(`
- [ ] L102 `onClick = {`
- [ ] L120 `TextButton(onClick = { OpenExternal.browse(SendLogs.issueUrl()) }) {`

## desktop/src/main/kotlin/com/tmplayer/desktop/ui/SeriesViews.kt (8)
- [ ] L126 `.onPreviewKeyEvent { event ->`
- [ ] L127 `if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false`
- [ ] L150 `.clickable(interactionSource = interaction, indication = null, onClick = onOpen)`
- [ ] L213 `BackHandler(enabled = true) { onClose() }`
- [ ] L289 `OutlinedButton(onClick = { open = true }) {`
- [ ] L296 `DropdownMenu(expanded = open, onDismissRequest = { open = false }) {`
- [ ] L298 `DropdownMenuItem(`
- [ ] L312 `onClick = {`

## desktop/src/main/kotlin/com/tmplayer/desktop/ui/Settings.kt (20)
- [ ] L302 `.onKeyEvent { event ->`
- [ ] L305 `return@onKeyEvent runCatching { searchFocus.requestFocus() }.isSuccess`
- [ ] L307 `if (event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isMetaPressed || event.isAltPressed) re`
- [ ] L423 `IconButton(`
- [ ] L424 `onClick = aboutBack,`
- [ ] L441 `IconButton(`
- [ ] L442 `onClick = closePage,`
- [ ] L557 `Setting(hit.title, hit.page, icon = TmIcons.Search, onClick = { onHit(hit.row) }) {`
- [ ] L604 `onClick = { onOpen(category) },`
- [ ] L809 `onDismissRequest = onClose,`
- [ ] L819 `Modifier.fillMaxWidth().clickable {`
- [ ] L824 `Checkbox(checked = ticked, onCheckedChange = null)`
- [ ] L831 `confirmButton = { TextButton(onClick = onClose) { Text(s.commonClose) } },`
- [ ] L859 `internal fun SignOutButton(label: String, onClick: () -> Unit) {`
- [ ] L861 `androidx.compose.material3.Button(`
- [ ] L862 `onClick = onClick,`
- [ ] L886 `onDismissRequest = onClose,`
- [ ] L894 `confirmButton = { TextButton(onClick = onClose) { Text(s.commonClose) } },`
- [ ] L896 `TextButton(`
- [ ] L897 `onClick = { scope.launch { settings.setSubtitleStyle(SubtitleStyle()) } },`

## desktop/src/main/kotlin/com/tmplayer/desktop/ui/SettingsPages.kt (1)
- [ ] L610 `SignOutButton(s.settingsSignOut) { confirmSignOut = true }`

## desktop/src/main/kotlin/com/tmplayer/desktop/ui/SettingsRows.kt (16)
- [ ] L115 `.onPreviewKeyEvent {`
- [ ] L173 `onClick == null -> Modifier`
- [ ] L181 `else -> Modifier.clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = `
- [ ] L307 `onClick = if (enabled) onClick else null,`
- [ ] L337 `Setting(title, detail, modifier = modifier, icon = icon, onClick = { onChange(!checked) }, toggled = checked) `
- [ ] L338 `Switch(checked = checked, onCheckedChange = null)`
- [ ] L349 `SegmentedButton(`
- [ ] L351 `onClick = { onPick(choice) },`
- [ ] L371 `RingIconButton(TmIcons.Remove, s.commonLess, canLess, onLess)`
- [ ] L382 `RingIconButton(TmIcons.Add, s.commonMore, canMore, onMore)`
- [ ] L387 `private fun RingIconButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {`
- [ ] L390 `IconButton(`
- [ ] L391 `onClick = onClick,`
- [ ] L401 `internal fun RowButton(label: String, enabled: Boolean = true, color: Color = Color.Unspecified, onClick: () -`
- [ ] L404 `OutlinedButton(`
- [ ] L405 `onClick = onClick,`

## desktop/src/main/kotlin/com/tmplayer/desktop/ui/Shell.kt (3)
- [ ] L379 `BackHandler(enabled = state.openChat != null) { state.closeChat() }`
- [ ] L382 `BackHandler(enabled = state.openChat == null && state.destination != Destination.Home) {`
- [ ] L551 `BackHandler(enabled = !docked) { state.closePlayer() }`

## desktop/src/main/kotlin/com/tmplayer/desktop/ui/SignIn.kt (46)
- [ ] L158 `IconButton(onClick = { moreOpen = true }) {`
- [ ] L161 `com.tmplayer.ui.components.TmDropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {`
- [ ] L162 `androidx.compose.material3.DropdownMenuItem(`
- [ ] L164 `onClick = { moreOpen = false; com.tmplayer.ui.connect.ProxyScreenState.open = true },`
- [ ] L166 `androidx.compose.material3.DropdownMenuItem(`
- [ ] L168 `onClick = {`
- [ ] L275 `TextButton(onClick = { scope.launch { Td.chooseSignInMethod(SignInMethod.Phone) } }) {`
- [ ] L278 `QrRefreshButton({ scope.launch { Td.refreshQrCode() } })`
- [ ] L369 `Dialog(onDismissRequest = { picking = false }) {`
- [ ] L389 `OutlinedButton(`
- [ ] L390 `onClick = { picking = true },`
- [ ] L415 `OutlinedTextField(`
- [ ] L430 `OutlinedTextField(`
- [ ] L445 `.onPreviewKeyEvent { event ->`
- [ ] L453 `Button(onClick = { submit() }, enabled = !request.busy && ready, modifier = Modifier.fillMaxWidth()) {`
- [ ] L457 `TextButton(onClick = onBack) { Text(s.commonBack) }`
- [ ] L461 `TextButton(onClick = { scope.launch { Td.chooseSignInMethod(SignInMethod.Qr) } }) { Text(s.signinUseQr) }`
- [ ] L505 `OutlinedTextField(`
- [ ] L517 `Button(onClick = { submit() }, enabled = !request.busy && code.isNotBlank() && code != submitted, modifier = M`
- [ ] L524 `TextButton(`
- [ ] L525 `onClick = {`
- [ ] L535 `TextButton(onClick = onChangeNumber) { Text(s.loginWrongNumber) }`
- [ ] L536 `TextButton(onClick = { scope.launch { Td.restartSignIn() } }) { Text(s.signinStartOver) }`
- [ ] L553 `OutlinedTextField(`
- [ ] L564 `IconButton(onClick = { shown = !shown }) {`
- [ ] L592 `Button(onClick = { if (confirming) confirming = false else forgot = false }, modifier = Modifier.fillMaxWidth(`
- [ ] L595 `OutlinedButton(`
- [ ] L596 `onClick = { if (confirming) scope.send(asking) { Td.resetAccount() } else confirming = true },`
- [ ] L619 `Button(onClick = { submit() }, enabled = !request.busy && password.isNotEmpty(), modifier = Modifier.fillMaxWi`
- [ ] L624 `TextButton(`
- [ ] L625 `onClick = {`
- [ ] L630 `TextButton(onClick = { scope.launch { Td.restartSignIn() } }) { Text(s.signinStartOver) }`
- [ ] L643 `TextButton(onClick = { scope.launch { Td.backToPassword() } }) { Text(s.loginBackToPassword) }`
- [ ] L651 `OutlinedTextField(`
- [ ] L662 `Button(onClick = { submit() }, enabled = !request.busy && code.isNotBlank(), modifier = Modifier.fillMaxWidth(`
- [ ] L683 `OutlinedTextField(`
- [ ] L691 `Button(onClick = { submit() }, enabled = !request.busy, modifier = Modifier.fillMaxWidth()) {`
- [ ] L710 `OutlinedTextField(`
- [ ] L721 `Button(onClick = { submit() }, enabled = !request.busy && ready, modifier = Modifier.fillMaxWidth()) {`
- [ ] L724 `TextButton(onClick = { scope.launch { Td.restartSignIn() } }) { Text(s.signinStartOver) }`
- [ ] L757 `OutlinedTextField(`
- [ ] L768 `Button(onClick = { submit() }, enabled = !request.busy && code.isNotBlank() && code != submitted, modifier = M`
- [ ] L773 `TextButton(onClick = { submitted = null; scope.send(asking) { Td.resendCode() } }, enabled = !asking.busy) { T`
- [ ] L775 `TextButton(onClick = { scope.send(asking) { Td.resetEmailAddress() } }, enabled = !asking.busy) { Text(s.login`
- [ ] L777 `TextButton(onClick = { scope.launch { Td.restartSignIn() } }) { Text(s.signinStartOver) }`
- [ ] L791 `Button(onClick = { scope.launch { Td.restartSignIn() } }, enabled = left <= 0) {`

## desktop/src/main/kotlin/com/tmplayer/desktop/ui/SignOut.kt (1)
- [ ] L62 `Checkbox(checked = alsoDownloads, onCheckedChange = { alsoDownloads = it })`

## desktop/src/main/kotlin/com/tmplayer/desktop/ui/StorageSettings.kt (5)
- [ ] L153 `RowButton(s.commonChange, enabled = idle) {`
- [ ] L161 `RowButton(s.commonReset, enabled = idle) { scope.launch { dialog = check(state, null) } }`
- [ ] L180 `RowButton(s.commonReset) { setLimit(state, DesktopSettings.DEFAULT_CACHE_LIMIT_BYTES) }`
- [ ] L235 `onDismissRequest = { dialog = null },`
- [ ] L238 `confirmButton = { TextButton(onClick = { dialog = null }) { Text(s.commonOk) } },`
