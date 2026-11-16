# player: 94 controls in 16 files

Tick a line only after every dimension in private/UX-INTERACTION-PLAN.md was checked for it.

## app/src/main/java/com/tmplayer/cast/CastPicker.kt (2)
- [ ] L122 `fun CastPageButton(modifier: Modifier = Modifier) {`
- [ ] L132 `PageDiscButton(TmIcons.Cast, label, { open = true }, modifier, active = connected != null, prominent = true)`

## app/src/main/java/com/tmplayer/player/ExternalChooserSheet.kt (4)
- [ ] L108 `TmSecondaryButton(onClick = { onChoose(choice, true) }) { Text(s.externalAlways) }`
- [ ] L109 `TmButton(onClick = { onChoose(choice, false) }) { Text(s.externalJustOnce) }`
- [ ] L115 `PhoneSheet(onDismissRequest = onDismiss) {`
- [ ] L162 `.selectable(selected = selected, interactionSource = interactions, indication = null, role = Role.RadioButton,`

## app/src/main/java/com/tmplayer/player/ExternalPlayHost.kt (1)
- [ ] L80 `onClick = ExternalPlayRouter::playHereFromClosed,`

## app/src/main/java/com/tmplayer/player/NextUpCard.kt (1)
- [ ] L106 `Button(context, null, android.R.attr.borderlessButtonStyle).apply {`

## app/src/main/java/com/tmplayer/player/PlaybackStatsOverlay.kt (1)
- [ ] L13 `* no tick and no cost. Never focusable and never clickable: the television's remote and a finger`

## app/src/main/java/com/tmplayer/player/PlayerActivity.kt (4)
- [ ] L2111 `renderOrientationButton()`
- [ ] L3798 `renderOrientationButton()`
- [ ] L3803 `private fun renderOrientationButton() {`
- [ ] L7493 `renderOrientationButton()`

## app/src/main/java/com/tmplayer/player/PlayerBookmarkSheets.kt (2)
- [ ] L189 `BasicTextField(`
- [ ] L205 `.onPreviewKeyEvent { event ->`

## app/src/main/java/com/tmplayer/player/PlayerControls.kt (4)
- [ ] L142 `private fun isRowButton(view: View): Boolean =`
- [ ] L309 `if (now != null && isRowButton(now)) lastRowButton = now`
- [ ] L839 `setNextEpisodeButton(nextEpisode, nextLabel)`
- [ ] L878 `private fun setNextEpisodeButton(next: MediaItem?, label: String) {`

## app/src/main/java/com/tmplayer/player/PlayerEpisodes.kt (4)
- [ ] L120 `trailing = { CloseButton() },`
- [ ] L137 `trailing = { if (touch) CloseButton() },`
- [ ] L145 `private fun CloseButton() {`
- [ ] L147 `IconButton(onClick = ::close) {`

## app/src/main/java/com/tmplayer/player/PlayerErrorSheet.kt (2)
- [ ] L28 `SendLogsButton()`
- [ ] L29 `com.tmplayer.ui.settings.SendToDeveloperButton()`

## app/src/main/java/com/tmplayer/player/PlayerMorePanel.kt (11)
- [ ] L286 `BackHandler(enabled = state.targetState) {`
- [ ] L311 `.pointerInput(Unit) { detectTapGestures { close() } },`
- [ ] L327 `.pointerInput(Unit) { detectTapGestures { } }`
- [ ] L354 `PhoneSheet(onDismissRequest = { if (isOpen) close() else finish() }, sheetState = sheetState) {`
- [ ] L356 `BackHandler(enabled = current !is Page.Main) { pageBack?.invoke() ?: back() }`
- [ ] L636 `if (touch) CloseButton(s.commonClose)`
- [ ] L641 `private fun CloseButton(label: String) {`
- [ ] L643 `IconButton(onClick = ::close) { M3Icon(TmIcons.Close, contentDescription = label, tint = Tone.text) }`
- [ ] L674 `IconButton(onClick = choice.onBack, modifier = backModifier) {`
- [ ] L686 `CloseButton(s.commonClose)`
- [ ] L690 `TvChoiceRow(title = choice.title, icon = TmIcons.ArrowBack, modifier = backModifier, onClick = choice.onBack)`

## app/src/main/java/com/tmplayer/player/PlayerSheets.kt (6)
- [ ] L482 `FineButton(TmIcons.Remove, s.playerSpeedSlower, enabled = PlaybackSpeed.canStep(current, up = false)) {`
- [ ] L489 `FineButton(TmIcons.Add, s.playerSpeedFaster, enabled = PlaybackSpeed.canStep(current, up = true)) {`
- [ ] L498 `private fun FineButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean,`
- [ ] L501 `androidx.compose.material3.FilledTonalIconButton(`
- [ ] L502 `onClick = onClick,`
- [ ] L577 `.selectable(selected = selected, role = Role.RadioButton) { onPick(choice) },`

## app/src/main/java/com/tmplayer/player/TrackPickerFragment.kt (12)
- [ ] L312 `return Line("chat", L.tracksInChat, L.tracksInChatDetail, icon = TmIcons.Folder, group = Group.Online, onClick`
- [ ] L318 `return Line("online", L.onlineSearchOnline, L.onlineSearchOnlineDetail, icon = TmIcons.Search, group = Group.O`
- [ ] L327 `return Line("msone", L.msoneSearch, L.msoneSearchDetail, icon = TmIcons.Search, group = Group.Online, onClick `
- [ ] L657 `OutlinedButton(onClick = { nudge(-1) }, modifier = Modifier.weight(1f).height(48.dp)) {`
- [ ] L665 `TextButton(onClick = { nudge(0) }, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizonta`
- [ ] L672 `OutlinedButton(onClick = { nudge(1) }, modifier = Modifier.weight(1f).height(48.dp)) {`
- [ ] L721 `Switch(checked = style.bold, onCheckedChange = null)`
- [ ] L724 `TextButton(onClick = { change(SubtitleStyle()) }, enabled = !style.isDefault) {`
- [ ] L759 `.selectable(selected = chosen, role = Role.RadioButton) { onPick(colour) }`
- [ ] L786 `SegmentedButton(`
- [ ] L788 `onClick = { if (index != selected) segmentTick(); onPick(index) },`
- [ ] L814 `onClick = line.onClick,`

## ui/src/shared/kotlin/com/tmplayer/ui/player/CastConverting.kt (1)
- [ ] L80 `.clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onCancel)`

## ui/src/shared/kotlin/com/tmplayer/ui/player/CastRemote.kt (25)
- [ ] L151 `.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},`
- [ ] L184 `StopButton(s, actions, Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 8.dp))`
- [ ] L213 `RoundIcon(TmIcons.ArrowBack, s.commonBack, onClick = actions::back, side = TipSide.Below)`
- [ ] L227 `if (showStop) StopButton(s, actions, compact = true)`
- [ ] L228 `RoundIcon(TmIcons.Cast, s.castButton, tint = if (state.lost) Tone.muted else Tone.accent, onClick = actions::d`
- [ ] L286 `if (state.lost) Pill(s.castReconnect, primary = true, onClick = actions::reconnect)`
- [ ] L287 `else Pill(s.commonTryAgain, primary = true, onClick = actions::retry)`
- [ ] L288 `Pill(s.castPlayHere, primary = false, onClick = actions::playHere)`
- [ ] L339 `Slider(`
- [ ] L374 `RoundIcon(TmIcons.SkipPrevious, s.playerPreviousEpisode, enabled = state.hasPrevious, onClick = actions::previ`
- [ ] L375 `RoundIcon(TmIcons.Rewind, s.notifySeekBack((CAST_SKIP_BACK_MS / 1000).toInt()), enabled = live, onClick = { ac`
- [ ] L376 `PlayButton(state, s, playSize, live, actions::togglePlay)`
- [ ] L377 `RoundIcon(TmIcons.FastForward, s.notifySeekForward((CAST_SKIP_FORWARD_MS / 1000).toInt()), enabled = live, onC`
- [ ] L378 `RoundIcon(TmIcons.SkipNext, s.playerNextEpisode, enabled = state.hasNext, onClick = actions::next)`
- [ ] L383 `private fun PlayButton(state: CastRemoteState, s: Messages, size: Dp, enabled: Boolean, onClick: () -> Unit) {`
- [ ] L395 `.clickable(interactionSource = interactions, indication = null, enabled = enabled, role = Role.Button, onClick`
- [ ] L412 `if (state.hasAudioChoice) RoundIcon(TmIcons.Audio, s.playerAudio, enabled = live, onClick = actions::audio)`
- [ ] L420 `onClick = actions::subtitles,`
- [ ] L433 `.clickable(interactionSource = interactions, indication = null, enabled = live, role = Role.Button, onClick = `
- [ ] L473 `onClick = {`
- [ ] L479 `Slider(`
- [ ] L501 `private fun StopButton(s: Messages, actions: CastRemoteActions, modifier: Modifier = Modifier, compact: Boolea`
- [ ] L508 `.clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = actions::stop)`
- [ ] L527 `.clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onClick)`
- [ ] L561 `.clickable(interactionSource = interactions, indication = null, enabled = enabled, role = Role.Button, onClick`

## ui/src/shared/kotlin/com/tmplayer/ui/player/EpisodesPanel.kt (14)
- [ ] L214 `onClick = { actions.onPlay(chosen) },`
- [ ] L345 `fun PreviousButton(m: Modifier) = previous?.let {`
- [ ] L347 `PanelButton(short ?: state.previousLabel ?: s.playerPreviousEpisode, onClick = { actions.onPlay(it) }, modifie`
- [ ] L350 `fun NextButton(m: Modifier) = next?.let {`
- [ ] L352 `PanelButton(short ?: state.nextLabel ?: s.playerNextEpisode, onClick = { actions.onPlay(it) }, modifier = m, i`
- [ ] L356 `PreviousButton(Modifier.weight(1f))`
- [ ] L357 `NextButton(Modifier.weight(1f))`
- [ ] L361 `PreviousButton(Modifier.fillMaxWidth())`
- [ ] L362 `NextButton(Modifier.fillMaxWidth())`
- [ ] L369 `private fun PanelButton(`
- [ ] L388 `.clickable(`
- [ ] L392 `onClick = onClick,`
- [ ] L432 `.clickable(`
- [ ] L435 `onClick = onToggle,`
