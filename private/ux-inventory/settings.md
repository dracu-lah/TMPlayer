# settings: 203 controls in 16 files

Tick a line only after every dimension in private/UX-INTERACTION-PLAN.md was checked for it.

## app/src/main/java/com/tmplayer/ui/settings/AboutScreen.kt (12)
- [ ] L91 `BackHandler { reading = false }`
- [ ] L157 `onClick = { reading = true },`
- [ ] L165 `CoffeeButton(`
- [ ] L166 `onClick = { if (touch) open(About.Link(s.supportBuyCoffee, "", About.COFFEE)) else coffee = true },`
- [ ] L177 `onClick = { open(About.Link(s.supportSponsorGithub, "", About.SPONSORS)) },`
- [ ] L188 `onClick = { supporting = true },`
- [ ] L197 `onClick = { open(link) },`
- [ ] L214 `com.tmplayer.ui.components.FloatingBackButton(`
- [ ] L319 `.onKeyEvent { event ->`
- [ ] L320 `if (event.type != KeyEventType.KeyDown) return@onKeyEvent false`
- [ ] L325 `else -> return@onKeyEvent false`
- [ ] L360 `IconButton(onClick = onBack) {`

## app/src/main/java/com/tmplayer/ui/settings/DownloadLocationSettings.kt (5)
- [ ] L132 `onClick = { if (!DownloadRelocation.running) picking = true },`
- [ ] L140 `onClick = { forgetting = true },`
- [ ] L176 `onClick = {`
- [ ] L200 `onClick = {`
- [ ] L211 `onClick = {`

## app/src/main/java/com/tmplayer/ui/settings/LanguageAndFeedback.kt (14)
- [ ] L96 `BackHandler(onBack = onClose)`
- [ ] L112 `if (!touch) BackHandler(onBack = onKeep)`
- [ ] L134 `TmSecondaryButton(onClick = onChange) { Label(s.commonChange) }`
- [ ] L135 `TmButton(onClick = onKeep, modifier = Modifier.focusRequester(keep)) { Label(s.languageKeep) }`
- [ ] L155 `if (!touch) BackHandler(onBack = onDecline)`
- [ ] L162 `TmSecondaryButton(onClick = onDecline) { Label(if (again) s.tourOfferNoThanks else s.deviceNotNow) }`
- [ ] L163 `TmButton(onClick = onTake, modifier = Modifier.focusRequester(take)) { Label(s.tourOfferTake) }`
- [ ] L265 `TmSecondaryButton(onClick = onChangelog) { Label(s.whatsnewFullChangelog) }`
- [ ] L266 `TmButton(onClick = onClose, modifier = Modifier.focusRequester(close)) { Label(s.commonClose) }`
- [ ] L317 `TmButton(onClick = { if (openLink(context, issue)) onClose() }, modifier = Modifier.fillMaxWidth()) {`
- [ ] L320 `TmSecondaryButton(`
- [ ] L321 `onClick = { if (sendMail(context, Feedback.mailto(facts))) onClose() },`
- [ ] L326 `TmSecondaryButton(onClick = onClose) { Label(s.commonClose) }`
- [ ] L341 `TmButton(onClick = onClose, modifier = Modifier.focusRequester(close)) { Label(s.commonClose) }`

## app/src/main/java/com/tmplayer/ui/settings/LayoutSetting.kt (4)
- [ ] L117 `BackHandler(onBack = onLater)`
- [ ] L147 `TmSecondaryButton(onClick = onNever) { ButtonWords(s.interfaceNudgeNever) }`
- [ ] L148 `TmSecondaryButton(onClick = onLater) { ButtonWords(s.interfaceNudgeNotNow) }`
- [ ] L149 `TmButton(onClick = onSwitch, modifier = Modifier.focusRequester(first)) { ButtonWords(s.interfaceNudgeSwitch) `

## app/src/main/java/com/tmplayer/ui/settings/LearntLanguagesSettings.kt (1)
- [ ] L45 `onClick = onForgetAll,`

## app/src/main/java/com/tmplayer/ui/settings/LogActions.kt (3)
- [ ] L165 `fun SendLogsButton(modifier: Modifier = Modifier) {`
- [ ] L168 `TmSecondaryButton(onClick = send, modifier = modifier) {`
- [ ] L230 `TmButton(onClick = onClose, modifier = Modifier.focusRequester(close)) { Text(s.commonClose) }`

## app/src/main/java/com/tmplayer/ui/settings/OnlineMetadataSettings.kt (3)
- [ ] L98 `onClick = onEditKey,`
- [ ] L114 `onClick = onEdit,`
- [ ] L138 `onClick = { if (bytes > 0) asking = true else clear() },`

## app/src/main/java/com/tmplayer/ui/settings/OnlineSubtitlesSettings.kt (6)
- [ ] L88 `onClick = {`
- [ ] L114 `onClick = { toast(OnlineWords.status(status)) },`
- [ ] L120 `onClick = { onDialog(OnlineDialog.SignIn) },`
- [ ] L126 `onClick = { onDialog(OnlineDialog.SignOut) },`
- [ ] L178 `onClick = { onDialog(OnlineDialog.SubdlKey) },`
- [ ] L203 `onClick = { if (bytes > 0) asking = true else clear() },`

## app/src/main/java/com/tmplayer/ui/settings/PlaybackSettings.kt (3)
- [ ] L84 `onClick = onToggleAdvanced,`
- [ ] L166 `onClick = { open = true },`
- [ ] L266 `onClick = { open = true },`

## app/src/main/java/com/tmplayer/ui/settings/PlaybackTestScreen.kt (13)
- [ ] L118 `PanelActions { TmSecondaryButton(onClick = onClose, modifier = Modifier.focusRequester(primary)) { Text(s.comm`
- [ ] L122 `PanelActions { TmButton(onClick = onClose, modifier = Modifier.focusRequester(primary)) { Text(s.commonClose) `
- [ ] L126 `PanelActions { TmButton(onClick = onClose, modifier = Modifier.focusRequester(primary)) { Text(s.commonClose) `
- [ ] L139 `TmSecondaryButton(onClick = onClose) { Text(s.commonCancel) }`
- [ ] L140 `TmButton(onClick = onStart, modifier = Modifier.focusRequester(primary)) {`
- [ ] L148 `PanelActions { TmButton(onClick = onClose, modifier = Modifier.focusRequester(primary)) { Text(s.commonClose) `
- [ ] L152 `PanelActions { TmSecondaryButton(onClick = onCancel, modifier = Modifier.focusRequester(primary)) { Text(s.com`
- [ ] L157 `PanelActions { TmSecondaryButton(onClick = onCancel, modifier = Modifier.focusRequester(primary)) { Text(s.com`
- [ ] L172 `TmSecondaryButton(onClick = onClose) { Text(s.commonClose) }`
- [ ] L173 `TmButton(onClick = onAdd, modifier = Modifier.focusRequester(primary)) { Text(s.playbacktestAdd) }`
- [ ] L175 `TmButton(onClick = onClose, modifier = Modifier.focusRequester(primary)) { Text(s.commonClose) }`
- [ ] L182 `TmSecondaryButton(onClick = onClose) { Text(s.commonClose) }`
- [ ] L183 `TmButton(onClick = onAgain, modifier = Modifier.focusRequester(primary)) { Text(s.commonTryAgain) }`

## app/src/main/java/com/tmplayer/ui/settings/SendToDeveloper.kt (12)
- [ ] L103 `fun SendToDeveloperButton(modifier: Modifier = Modifier) {`
- [ ] L106 `TmSecondaryButton(onClick = open, modifier = modifier) {`
- [ ] L135 `PanelActions { TmSecondaryButton(onClick = onClose, modifier = Modifier.focusRequester(primary)) { Text(s.comm`
- [ ] L146 `TmSecondaryButton(onClick = onClose, modifier = Modifier.focusRequester(primary)) { Text(s.commonCancel) }`
- [ ] L147 `TmButton(onClick = onSend) { Text(s.logsSendDevConfirm) }`
- [ ] L154 `TmSecondaryButton(onClick = onCancelSend, modifier = Modifier.focusRequester(primary)) { Text(s.commonCancel) `
- [ ] L155 `TmButton(onClick = {}, enabled = false, loading = true, busyLabel = s.logsSendDevSending) { Text(s.logsSendDev`
- [ ] L174 `PanelActions { TmButton(onClick = onClose, modifier = Modifier.focusRequester(primary)) { Text(s.commonClose) `
- [ ] L180 `if (state.why != Why.SLOW_DOWN) TmSecondaryButton(onClick = onAgain) { Text(s.commonTryAgain) }`
- [ ] L181 `TmButton(onClick = onClose, modifier = Modifier.focusRequester(primary)) { Text(s.commonClose) }`
- [ ] L188 `TmSecondaryButton(onClick = onClose) { Text(s.commonClose) }`
- [ ] L189 `TmButton(onClick = onAgain, modifier = Modifier.focusRequester(primary)) { Text(s.commonTryAgain) }`

## app/src/main/java/com/tmplayer/ui/settings/SettingsScreen.kt (85)
- [ ] L539 `BackHandler(enabled = page != null) { closePage() }`
- [ ] L888 `onClick = { pickingLanguage = true },`
- [ ] L903 `onClick = { pickingLayout = true },`
- [ ] L963 `onClick = {`
- [ ] L1046 `onClick = { pickingDecoder = true },`
- [ ] L1171 `onClick = { stylingSubtitles = true },`
- [ ] L1180 `onClick = { trackPicker = TrackLanguagePicker.Mode },`
- [ ] L1188 `onClick = { trackPicker = TrackLanguagePicker.Subtitle },`
- [ ] L1197 `onClick = { trackPicker = TrackLanguagePicker.Audio },`
- [ ] L1211 `onClick = { showingLearnt = true },`
- [ ] L1348 `onClick = { editingSizes = true },`
- [ ] L1375 `onClick = {`
- [ ] L1428 `onClick = {`
- [ ] L1445 `onClick = { prompt = Prompt.DeleteResumeSync },`
- [ ] L1458 `onClick = onOpenAddChats,`
- [ ] L1467 `onClick = { prompt = Prompt.ClearFavorites },`
- [ ] L1479 `onClick = {`
- [ ] L1787 `onClick = { pinFlow = PinFlow.Set },`
- [ ] L1797 `onClick = { pinFlow = PinFlow.ChangeCheck },`
- [ ] L1839 `onClick = { pickingKidsChats = true },`
- [ ] L1848 `onClick = { pinFlow = PinFlow.Remove },`
- [ ] L1873 `onClick = { openPage(About.PRIVACY, s.settingsQrPrivacyPage) },`
- [ ] L1908 `onClick = { devicesOpen = true },`
- [ ] L1914 `SignOutButton(`
- [ ] L1916 `onClick = { prompt = Prompt.SignOut },`
- [ ] L1937 `onClick = { showUpdate = true },`
- [ ] L1947 `onClick = {`
- [ ] L2021 `onClick = backup.backUp,`
- [ ] L2029 `onClick = backup.restore,`
- [ ] L2044 `onClick = { com.tmplayer.ui.connect.ProxyScreenState.open = true },`
- [ ] L2052 `onClick = { scope.launch { settings.openTour() } },`
- [ ] L2060 `onClick = { whatsNew = true },`
- [ ] L2068 `onClick = { openPage(WhatsNew.CHANGELOG, s.settingsQrChangelogPage) },`
- [ ] L2076 `onClick = { reporting = true },`
- [ ] L2085 `onClick = saveLogs,`
- [ ] L2094 `onClick = playbackTest,`
- [ ] L2104 `onClick = sendToDeveloper,`
- [ ] L2126 `onClick = saveNameCheck,`
- [ ] L2135 `onClick = { openPage(About.LEGAL, s.settingsQrLawfulUsePage) },`
- [ ] L2145 `onClick = { supporting = true },`
- [ ] L2157 `onClick = onOpenAbout,`
- [ ] L2207 `onClick = { openHit(hit.row) },`
- [ ] L2218 `onClick = { showPage(SettingsPage.Playback) },`
- [ ] L2229 `onClick = { showPage(SettingsPage.Gestures) },`
- [ ] L2245 `onClick = { showPage(SettingsPage.Subtitles) },`
- [ ] L2260 `onClick = { showPage(SettingsPage.Storage) },`
- [ ] L2271 `onClick = { showPage(SettingsPage.Notifications) },`
- [ ] L2286 `onClick = { showPage(SettingsPage.Appearance) },`
- [ ] L2296 `onClick = { showPage(SettingsPage.Account) },`
- [ ] L2305 `onClick = { showPage(SettingsPage.Library) },`
- [ ] L2314 `onClick = { showPage(SettingsPage.Home) },`
- [ ] L2326 `onClick = { showPage(SettingsPage.TvHome) },`
- [ ] L2344 `onClick = { showPage(SettingsPage.About) },`
- [ ] L2355 `onClick = {`
- [ ] L2438 `FloatingBackButton(TmIcons.ArrowBack, s.aboutBackToSettings, { closePage() }, Modifier.align(Alignment.TopStar`
- [ ] L2455 `FloatingBackButton(TmIcons.ArrowBack, s.commonBack, onBack, Modifier.align(Alignment.TopStart))`
- [ ] L2798 `.onKeyEvent { event ->`
- [ ] L2799 `if (event.type != KeyEventType.KeyDown) return@onKeyEvent false`
- [ ] L2875 `* Both ends are draggable at once, so there is no live end to keep in mind. The track stays,`
- [ ] L2914 `RangeSlider(`
- [ ] L3050 `AssistChip(`
- [ ] L3051 `onClick = onClick,`
- [ ] L3058 `modifier = Modifier.size(AssistChipDefaults.IconSize),`
- [ ] L3079 `.clickable(`
- [ ] L3083 `onClick = { if (enabled) onClick() },`
- [ ] L3148 `SegmentedButton(`
- [ ] L3150 `onClick = { onPick(choice) },`
- [ ] L3292 `role = Role.Checkbox,`
- [ ] L3298 `Checkbox(checked = checked, onCheckedChange = null)`
- [ ] L3336 `IconButton(onClick = { step(); onStep(-1) }, enabled = canDecrease) {`
- [ ] L3341 `IconButton(onClick = { step(); onStep(1) }, enabled = canIncrease) {`
- [ ] L3371 `.onKeyEvent { event ->`
- [ ] L3372 `if (event.type != KeyEventType.KeyDown) return@onKeyEvent false`
- [ ] L3518 `private fun SignOutButton(label: String, onClick: () -> Unit) {`
- [ ] L3526 `androidx.compose.material3.Button(`
- [ ] L3527 `onClick = onClick,`
- [ ] L3558 `onClick = onClick,`
- [ ] L3602 `onClick = toggle,`
- [ ] L3608 `trailing = { M3Switch(checked = checked, onCheckedChange = { toggle() }) },`
- [ ] L3630 `Switch(checked = checked, focused = focused, touch = false)`
- [ ] L3642 `private fun Switch(checked: Boolean, focused: Boolean, touch: Boolean = false) {`
- [ ] L3722 `.clickable(`
- [ ] L3727 `onClick = onClick,`
- [ ] L3743 `.clickable(`
- [ ] L3747 `onClick = onClick,`

## app/src/main/java/com/tmplayer/ui/settings/SettingsSearchBox.kt (2)
- [ ] L37 `OutlinedTextField(`
- [ ] L47 `IconButton(onClick = { onQuery("") }) { Icon(TmIcons.Close, contentDescription = s.settingsSearchClear) }`

## app/src/main/java/com/tmplayer/ui/settings/Support.kt (22)
- [ ] L115 `TmSecondaryButton(onClick = onClose, modifier = Modifier.focusRequester(close)) { Label(s.commonClose) }`
- [ ] L152 `TmButton(onClick = onOpen) { Label(s.commonOpen) }`
- [ ] L197 `CoffeeButton(onClick = { openLink(context, About.COFFEE) }, modifier = Modifier.fillMaxWidth())`
- [ ] L204 `androidx.compose.material3.TextButton(onClick = { openLink(context, About.SPONSORS) }) {`
- [ ] L207 `androidx.compose.material3.TextButton(`
- [ ] L208 `onClick = {`
- [ ] L222 `androidx.compose.material3.TextButton(onClick = onNever, modifier = Modifier.weight(1f)) {`
- [ ] L225 `TmSecondaryButton(onClick = onLater, modifier = Modifier.weight(1f)) { Label(s.supportMaybeLater) }`
- [ ] L228 `TmSecondaryButton(onClick = onLater) { Label(s.commonClose) }`
- [ ] L250 `TmSecondaryButton(onClick = onLater, modifier = Modifier.focusRequester(calm)) {`
- [ ] L253 `if (asking) TmSecondaryButton(onClick = onNever) { Text(s.supportDontShow) }`
- [ ] L262 `BackHandler(onBack = onLater)`
- [ ] L272 `fun CoffeeButton(onClick: () -> Unit, modifier: Modifier = Modifier) {`
- [ ] L277 `androidx.tv.material3.Button(`
- [ ] L278 `onClick = onClick,`
- [ ] L302 `androidx.compose.material3.Button(`
- [ ] L303 `onClick = onClick,`
- [ ] L415 `TmSecondaryButton(`
- [ ] L416 `onClick = {`
- [ ] L451 `TmSecondaryButton(onClick = onClose) { Label(s.commonClose) }`
- [ ] L469 `androidx.compose.material3.TextButton(`
- [ ] L470 `onClick = {`

## ui/src/shared/kotlin/com/tmplayer/ui/settings/DevicesScreen.kt (8)
- [ ] L73 `BackHandler(onBack = onClose)`
- [ ] L118 `QuietButton(s.commonBack, icon = true, modifier = if (sessions == null) Modifier.focusRequester(firstFocus) el`
- [ ] L127 `SetupButton(s.commonTryAgain, primary = true, onClick = { error = null; scope.launch { reload() } })`
- [ ] L149 `SetupButton(s.sessionsEndAll, onClick = { asking = ALL; note = null })`
- [ ] L174 `QuietButton(`
- [ ] L177 `onClick = { asking = session.id; note = null },`
- [ ] L257 `SetupButton(action, primary = true, onClick = onYes)`
- [ ] L258 `SetupButton(s.commonCancel, modifier = Modifier.focusRequester(cancel), onClick = onNo)`

## ui/src/shared/kotlin/com/tmplayer/ui/settings/HomeOrderEditor.kt (10)
- [ ] L155 `.onPreviewKeyEvent { event ->`
- [ ] L156 `if (picked != rowKey) return@onPreviewKeyEvent false`
- [ ] L182 `.clickable(`
- [ ] L186 `onClick = { if (picked == null) picked = rowKey },`
- [ ] L203 `detectDragGestures(`
- [ ] L271 `IconButton(`
- [ ] L272 `onClick = { put(HomeOrder.move(order, rowKey, -1)) },`
- [ ] L278 `IconButton(`
- [ ] L279 `onClick = { put(HomeOrder.move(order, rowKey, 1)) },`
- [ ] L318 `TextButton(onClick = onReset, modifier = Modifier.padding(top = 2.dp)) {`
