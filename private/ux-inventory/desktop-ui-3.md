# desktop-ui-3: 62 controls in 7 files

Tick a line only after every dimension in private/UX-INTERACTION-PLAN.md was checked for it.

## desktop/src/main/kotlin/com/tmplayer/desktop/ui/Support.kt (15)
- [ ] L89 `onDismissRequest = onClose,`
- [ ] L101 `confirmButton = { TextButton(onClick = onClose) { Text(s.commonClose) } },`
- [ ] L127 `OutlinedButton(onClick = { OpenExternal.browse(link.url) }) { Text(s.commonOpen) }`
- [ ] L149 `onDismissRequest = onLater,`
- [ ] L158 `TextButton(onClick = { OpenExternal.browse(About.SPONSORS) }) { Text(s.supportSponsorGithub) }`
- [ ] L165 `TextButton(onClick = { OpenExternal.browse(About.SOURCE) }) {`
- [ ] L173 `TextButton(onClick = onSupporter) { Text(s.webCoffeeAlready) }`
- [ ] L195 `confirmButton = { TextButton(onClick = onLater) { Text(if (asking) s.supportMaybeLater else s.commonClose) } }`
- [ ] L196 `dismissButton = if (asking) ({ TextButton(onClick = onNever) { Text(s.supportDontShow) } }) else null,`
- [ ] L202 `fun CoffeeButton(onClick: () -> Unit, modifier: Modifier = Modifier) {`
- [ ] L203 `Button(`
- [ ] L204 `onClick = onClick,`
- [ ] L241 `.clickable(interactionSource = interaction, indication = LocalIndication.current, role = Role.Button, onClick `
- [ ] L400 `TextButton(`
- [ ] L401 `onClick = {`

## desktop/src/main/kotlin/com/tmplayer/desktop/ui/TrackLanguageSettings.kt (8)
- [ ] L116 `Setting(title, detail, icon = icon, onClick = { open = true }) {`
- [ ] L123 `DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 420.dp))`
- [ ] L125 `DropdownMenuItem(`
- [ ] L140 `onClick = {`
- [ ] L167 `onDismissRequest = { open = false },`
- [ ] L182 `TextButton(onClick = { scope.launch { settings.forgetLearntLanguages(series.key) } }) {`
- [ ] L189 `confirmButton = { TextButton(onClick = { open = false }) { Text(s.commonClose) } },`
- [ ] L193 `{ TextButton(onClick = { scope.launch { settings.forgetAllLearntLanguages() } }) { Text(s.settingsLearntLangua`

## desktop/src/main/kotlin/com/tmplayer/desktop/ui/TvTopBar.kt (6)
- [ ] L159 `.onPreviewKeyEvent { event ->`
- [ ] L179 `onClick = { state.profileOpen = !state.profileOpen },`
- [ ] L204 `com.tmplayer.ui.components.ChooseChatsButton(`
- [ ] L208 `onClick = {`
- [ ] L270 `.clickable(interactionSource = interactions, indication = null, role = Role.Tab, onClick = onClick)`
- [ ] L342 `.clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onClick)`

## desktop/src/main/kotlin/com/tmplayer/desktop/ui/UpdatePopup.kt (8)
- [ ] L152 `Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {`
- [ ] L171 `TextButton(onClick = onClose) { Text(s.updateLaterShort) }`
- [ ] L173 `Button(onClick = onRestart) { Text(s.updateRestartNow) }`
- [ ] L178 `TextButton(onClick = onClose) { Text(s.commonHide) }`
- [ ] L181 `TextButton(onClick = onSkip) { Text(UpdateWords.SKIP, color = Tone.muted) }`
- [ ] L183 `TextButton(onClick = onLater) { Text(UpdateWords.LATER) }`
- [ ] L186 `Button(onClick = onUpdate) {`
- [ ] L190 `Button(onClick = onOpenPage) { Text(UpdateWords.OPEN_RELEASE_PAGE) }`

## desktop/src/main/kotlin/com/tmplayer/desktop/ui/Watched.kt (12)
- [ ] L112 `com.tmplayer.ui.nav.BackHandler(enabled = selection.active) { selection.clear() }`
- [ ] L132 `OutlinedButton(onClick = { state.openLists() }) { Text(s.listsOpen) }`
- [ ] L134 `if (!selection.active) OutlinedButton(onClick = { selection.start() }) { Text(s.browseSelect) }`
- [ ] L135 `OutlinedButton(onClick = clearAll) { Text(s.browseClearHistory) }`
- [ ] L218 `DropdownMenuItem(text = { Text(s.watchedPlayAgain) }, onClick = {`
- [ ] L223 `DropdownMenuItem(text = { Text(s.browseRemoveFromHistory) }, onClick = {`
- [ ] L265 `com.tmplayer.ui.nav.BackHandler(enabled = selection.active) { selection.clear() }`
- [ ] L277 `OutlinedButton(onClick = { state.openLists() }) { Text(s.listsOpen) }`
- [ ] L279 `if (!selection.active) OutlinedButton(onClick = { selection.start() }) { Text(s.browseSelect) }`
- [ ] L280 `OutlinedButton(onClick = clearAll) { Text(s.browseClearWatchLater) }`
- [ ] L367 `DropdownMenuItem(text = { Text(if (open != null) s.detailOpenDetails else s.playerPlay) }, onClick = {`
- [ ] L371 `DropdownMenuItem(text = { Text(s.detailWatchLaterRemove) }, onClick = {`

## desktop/src/main/kotlin/com/tmplayer/desktop/ui/WindowControls.kt (7)
- [ ] L130 `WindowButton(WindowGlyphs.Close, s.windowClose, onArt, danger = false, onClick = chrome.onClose)`
- [ ] L133 `chrome.onMinimize?.let { WindowButton(WindowGlyphs.Minimize, s.windowMinimize, onArt, danger = false, onClick `
- [ ] L135 `WindowButton(`
- [ ] L140 `onClick = chrome.onToggleMaximize,`
- [ ] L143 `WindowButton(WindowGlyphs.Close, s.windowClose, onArt, danger = chrome.mode == WindowChromeMode.Custom, onClic`
- [ ] L188 `private fun WindowButton(icon: ImageVector, label: String, onArt: Boolean, danger: Boolean, onClick: () -> Uni`
- [ ] L211 `.clickable(onClick = onClick, onClickLabel = label)`

## desktop/src/main/kotlin/com/tmplayer/desktop/ui/WindowRecovery.kt (6)
- [ ] L196 `onPreviewKeyEvent: (KeyEvent) -> Boolean,`
- [ ] L197 `onKeyEvent: (KeyEvent) -> Boolean,`
- [ ] L208 `onPreviewKeyEvent = onPreviewKeyEvent,`
- [ ] L209 `onKeyEvent = onKeyEvent,`
- [ ] L254 `Button(onClick = onTryAgain, modifier = Modifier.focusRequester(first)) { Text(s.commonTryAgain) }`
- [ ] L255 `OutlinedButton(onClick = onSettings) { Text(s.navSettings) }`
