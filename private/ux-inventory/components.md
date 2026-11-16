# components: 174 controls in 33 files

Tick a line only after every dimension in private/UX-INTERACTION-PLAN.md was checked for it.

## app/src/main/java/com/tmplayer/ui/components/ChoiceSheet.kt (8)
- [ ] L147 `SheetCloseButton(onClose, if (closeFocus != null) Modifier.focusRequester(closeFocus) else Modifier)`
- [ ] L209 `IconButton(onClick = onClose) {`
- [ ] L258 `fun SheetCloseButton(onClick: () -> Unit, modifier: Modifier = Modifier) {`
- [ ] L261 `TextButton(onClick = onClick, modifier = modifier) { M3Text(label) }`
- [ ] L264 `TmSecondaryButton(onClick = onClick, modifier = modifier) { Text(label) }`
- [ ] L331 `.clickable(`
- [ ] L334 `role = if (selected != null) Role.RadioButton else Role.Button,`
- [ ] L443 `TvChoiceRow(title, modifier, detail, selected = selected, icon = icon, enabled = enabled, onClick = onClick)`

## app/src/main/java/com/tmplayer/ui/components/ConnectionStatus.kt (1)
- [ ] L72 `.then(if (leads) Modifier.clickable(role = Role.Button, onClickLabel = s.downloadsTitle, onClick = { onOpenDow`

## app/src/main/java/com/tmplayer/ui/components/FloatingWindow.kt (1)
- [ ] L50 `onDismissRequest = onDismiss,`

## app/src/main/java/com/tmplayer/ui/components/Holdable.kt (16)
- [ ] L34 `* On the remote this is written out of key events: 'combinedClickable''s long press does not fire`
- [ ] L41 `* them, so touch goes to 'combinedClickable': its long press does work on a real screen, and it`
- [ ] L52 `return secondaryPress(onHold).combinedClickable(`
- [ ] L57 `onClick = onClick,`
- [ ] L71 `.onKeyEvent { event ->`
- [ ] L76 `return@onKeyEvent true`
- [ ] L78 `if (event.key !in OK_KEYS) return@onKeyEvent false`
- [ ] L107 `* press ([combinedClickable], which also gives the long press its buzz).`
- [ ] L109 `* Goes before the clickable in the chain: the right click is taken on the way in, so the`
- [ ] L110 `* clickable never sees it as the start of a press.`
- [ ] L128 `.onKeyEvent { event ->`
- [ ] L130 `if (!menu) return@onKeyEvent false`
- [ ] L147 `): Modifier = clickable(`
- [ ] L151 `onClick = onClick,`
- [ ] L168 `return onPreviewKeyEvent { event ->`
- [ ] L169 `if (event.key !in OK_KEYS) return@onPreviewKeyEvent false`

## app/src/main/java/com/tmplayer/ui/components/PhoneSheet.kt (3)
- [ ] L40 `onDismissRequest: () -> Unit,`
- [ ] L52 `onDismissRequest = onDismissRequest,`
- [ ] L94 `PhoneSheet(onDismissRequest = onDismiss, fullScreen = fullScreen, content = content)`

## app/src/main/java/com/tmplayer/ui/components/StateScaffold.kt (7)
- [ ] L206 `TmButton(onClick = onRetry, modifier = Modifier.focusRequester(focus)) {`
- [ ] L358 `TmButton(onClick = onAction, modifier = Modifier.focusRequester(focus)) {`
- [ ] L366 `TmButton(onClick = onSecondAction) { Text(secondLabel) }`
- [ ] L432 `TmButton(`
- [ ] L433 `onClick = onRetry,`
- [ ] L451 `SendLogsButton()`
- [ ] L453 `com.tmplayer.ui.settings.SendToDeveloperButton()`

## app/src/main/java/com/tmplayer/ui/components/TitleReportTvPanel.kt (4)
- [ ] L58 `TmSecondaryButton(onClick = actions.onDecline, modifier = Modifier.focusRequester(quiet)) { Text(s.titlesNoTha`
- [ ] L59 `TmButton(onClick = actions.onSend, enabled = review.canSend) { Text(s.titlesSend) }`
- [ ] L61 `state is State.Sending -> TmSecondaryButton(onClick = actions.onClose, modifier = Modifier.focusRequester(quie`
- [ ] L62 `else -> TmButton(onClick = actions.onClose, modifier = Modifier.focusRequester(quiet)) { Text(s.commonClose) }`

## app/src/main/java/com/tmplayer/ui/components/TmButton.kt (10)
- [ ] L45 `fun TmButton(`
- [ ] L57 `TvButton(`
- [ ] L58 `onClick = onClick,`
- [ ] L71 `TouchButton(`
- [ ] L72 `onClick = onClick,`
- [ ] L110 `fun TmSecondaryButton(`
- [ ] L119 `TvButton(`
- [ ] L120 `onClick = onClick,`
- [ ] L132 `TouchTonalButton(`
- [ ] L133 `onClick = onClick,`

## app/src/main/java/com/tmplayer/ui/components/TvConfirm.kt (8)
- [ ] L70 `onDismissRequest = onDismiss,`
- [ ] L108 `TextButton(`
- [ ] L109 `onClick = onConfirm,`
- [ ] L115 `Button(onClick = onConfirm) { M3Text(confirmLabel) }`
- [ ] L119 `TextButton(onClick = onDismiss) { M3Text(cancelLabel) }`
- [ ] L165 `TmSecondaryButton(`
- [ ] L166 `onClick = onDismiss,`
- [ ] L171 `TmButton(onClick = onConfirm, destructive = destructive, modifier = Modifier.focusRequester(confirmFocus)) {`

## app/src/main/java/com/tmplayer/ui/components/TvMenu.kt (5)
- [ ] L159 `if (onClose != null) SheetCloseButton(onClose)`
- [ ] L185 `PhoneSheet(onDismissRequest = onDismiss) {`
- [ ] L232 `modifier = modifier.clickable(onClick = action.onSelect),`
- [ ] L279 `.clickable(`
- [ ] L284 `onClick = action.onSelect,`

## app/src/main/java/com/tmplayer/ui/components/TvSearchField.kt (4)
- [ ] L125 `.clickable(`
- [ ] L149 `BasicTextField(`
- [ ] L173 `.onPreviewKeyEvent { event ->`
- [ ] L222 `BackHandler { stopEditing() }`

## ui/src/androidMain/kotlin/com/tmplayer/ui/components/Toast.kt (2)
- [ ] L165 `androidx.compose.material3.TextButton(`
- [ ] L166 `onClick = {`

## ui/src/androidMain/kotlin/com/tmplayer/ui/nav/BackHandler.kt (2)
- [ ] L14 `fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {`
- [ ] L15 `ActivityBackHandler(enabled = enabled, onBack = onBack)`

## ui/src/androidMain/kotlin/com/tmplayer/ui/online/TrailerPlayer.kt (5)
- [ ] L108 `onDismissRequest = onClose,`
- [ ] L185 `OverlayButton(TmIcons.OpenExternal, s.metadataTrailerOnYoutube, onOpenElsewhere)`
- [ ] L186 `OverlayButton(TmIcons.Close, s.commonClose, onClose, Modifier.focusRequester(closeFocus))`
- [ ] L202 `private fun OverlayButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier`
- [ ] L211 `.clickable(interactionSource = interactions, indication = if (tv) null else androidx.compose.foundation.LocalI`

## ui/src/jvmMain/kotlin/com/tmplayer/ui/nav/BackHandler.kt (3)
- [ ] L30 `* [BackHandler] exactly as they would on Android, and the order they were composed in is the order`
- [ ] L66 `fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {`
- [ ] L101 `.onKeyEvent { event -> isBackKey(event) && stack.handleBack() }`

## ui/src/shared/kotlin/com/tmplayer/ui/components/AccentSwatches.kt (3)
- [ ] L137 `.clickable(`
- [ ] L141 `role = Role.RadioButton,`
- [ ] L142 `onClick = onClick,`

## ui/src/shared/kotlin/com/tmplayer/ui/components/AddTvDialog.kt (5)
- [ ] L92 `onDismissRequest = { if (!working) onDismiss() },`
- [ ] L99 `OutlinedTextField(`
- [ ] L129 `IconButton(onClick = { onRemove(tv.key) }, modifier = Modifier.semantics { contentDescription = label }) {`
- [ ] L137 `confirmButton = { TextButton(onClick = submit, enabled = !working && text.isNotBlank()) { Text(s.castAddConfir`
- [ ] L138 `dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text(s.commonCancel) } },`

## ui/src/shared/kotlin/com/tmplayer/ui/components/ChoicePopover.kt (5)
- [ ] L126 `group.choices.forEach { choice -> ChoiceRow(choice.label, choice.icon, chosen = choice.chosen, onClick = choic`
- [ ] L150 `BackHandler(enabled = open, onBack = onDismiss)`
- [ ] L160 `.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss`
- [ ] L208 `.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}`
- [ ] L235 `.selectable(selected = chosen, interactionSource = press, indication = ripple(), role = Role.Button, onClick =`

## ui/src/shared/kotlin/com/tmplayer/ui/components/ChoiceRow.kt (3)
- [ ] L69 `Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = { if (!selected`
- [ ] L71 `Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)`
- [ ] L83 `selected != null -> RadioButton(selected = selected, onClick = null, enabled = enabled)`

## ui/src/shared/kotlin/com/tmplayer/ui/components/ChooseChatsButton.kt (2)
- [ ] L55 `fun ChooseChatsButton(`
- [ ] L102 `.clickable(interactionSource = interactions, indication = androidx.compose.foundation.LocalIndication.current,`

## ui/src/shared/kotlin/com/tmplayer/ui/components/ClearButton.kt (2)
- [ ] L32 `fun ClearButton(`
- [ ] L46 `.clickable(interactionSource = interactions, indication = null, role = Role.Button, onClickLabel = label, onCl`

## ui/src/shared/kotlin/com/tmplayer/ui/components/CustomAccentPicker.kt (5)
- [ ] L59 `onDismissRequest = onDismiss,`
- [ ] L87 `Slider(`
- [ ] L99 `Slider(`
- [ ] L108 `confirmButton = { TextButton(onClick = { onPick(seed.toArgb()) }) { Text(s.commonDone) } },`
- [ ] L109 `dismissButton = { TextButton(onClick = onDismiss) { Text(s.commonCancel) } },`

## ui/src/shared/kotlin/com/tmplayer/ui/components/Floating.kt (9)
- [ ] L30 `onDismissRequest: () -> Unit,`
- [ ] L43 `onDismissRequest = onDismissRequest,`
- [ ] L45 `{ DialogRemotePart(onDismissRequest, takeFocus = dismissButton == null, content = confirmButton) }`
- [ ] L53 `{ DialogRemotePart(onDismissRequest, takeFocus = true, content = dismissButton) }`
- [ ] L60 `{ DialogRemotePart(onDismissRequest, takeFocus = false, content = text) }`
- [ ] L88 `.onKeyEvent { event ->`
- [ ] L89 `if (event.type != KeyEventType.KeyDown) return@onKeyEvent false`
- [ ] L115 `onDismissRequest: () -> Unit,`
- [ ] L122 `onDismissRequest = onDismissRequest,`

## ui/src/shared/kotlin/com/tmplayer/ui/components/FloatingChrome.kt (9)
- [ ] L115 `fun FloatingRoundButton(`
- [ ] L142 `.clickable(interactionSource = interactions, indication = ripple(), role = Role.Button, onClick = onClick)`
- [ ] L157 `fun FloatingBackButton(`
- [ ] L165 `FloatingRoundButton(`
- [ ] L168 `onClick = onClick,`
- [ ] L182 `fun FloatingEndButton(`
- [ ] L191 `FloatingRoundButton(`
- [ ] L194 `onClick = onClick,`
- [ ] L328 `.clickable(interactionSource = interactions, indication = ripple(), enabled = enabled, role = Role.Button, onC`

## ui/src/shared/kotlin/com/tmplayer/ui/components/FolderCopyDialogs.kt (11)
- [ ] L54 `onDismissRequest = onCancel,`
- [ ] L85 `.toggleable(value = remove, role = Role.Checkbox, onValueChange = onRemove),`
- [ ] L88 `Checkbox(checked = remove, onCheckedChange = null, modifier = Modifier.height(24.dp))`
- [ ] L101 `plan.fits -> TextButton(onClick = { onSave(plan.items) }) { Text(s.downloadsSaveToFolderStart) }`
- [ ] L102 `someFit -> TextButton(onClick = { onSave(plan.fitting) }) { Text(s.downloadsSaveToFolderFitSome(count = plan.f`
- [ ] L106 `dismissButton = { TextButton(onClick = onCancel) { Text(s.commonCancel) } },`
- [ ] L120 `onDismissRequest = {},`
- [ ] L145 `confirmButton = { TextButton(onClick = onCancel) { Text(s.commonCancel) } },`
- [ ] L159 `onDismissRequest = onClose,`
- [ ] L169 `confirmButton = { TextButton(onClick = onClose) { Text(s.commonClose) } },`
- [ ] L182 `onDismissRequest = {},`

## ui/src/shared/kotlin/com/tmplayer/ui/components/FreeUpSpace.kt (14)
- [ ] L119 `ReviewButton(`
- [ ] L122 `onClick = onFreeUp,`
- [ ] L167 `onDismissRequest = { if (confirming) confirming = false else onDismiss() },`
- [ ] L265 `ReviewButton(s.downloadsKeepThem, null, { onConfirming(false) }, ButtonKind.Quiet, large = tv, modifier = Modi`
- [ ] L266 `ReviewButton(`
- [ ] L339 `ReviewButton(if (nothing) s.commonClose else s.commonCancel, null, onDismiss, ButtonKind.Quiet, large = tv, mo`
- [ ] L341 `ReviewButton(`
- [ ] L342 `s.downloadsReviewButton(size = f.bytes(total)),`
- [ ] L518 `role = Role.Checkbox,`
- [ ] L519 `onClick = {`
- [ ] L528 `TriStateCheckbox(state = state, onClick = null, enabled = enabled)`
- [ ] L557 `ReviewButton(`
- [ ] L577 `internal fun ReviewButton(`
- [ ] L612 `.clickable(interactionSource = interactions, indication = LocalIndication.current, enabled = enabled, role = R`

## ui/src/shared/kotlin/com/tmplayer/ui/components/LoadAhead.kt (1)
- [ ] L123 `OutlinedButton(onClick = onRetry) { Text(LocalStrings.current.commonTryAgain) }`

## ui/src/shared/kotlin/com/tmplayer/ui/components/MiniPlayerBar.kt (7)
- [ ] L90 `.clickable(`
- [ ] L95 `onClick = onExpand,`
- [ ] L109 `BarButton(`
- [ ] L112 `onClick = onTogglePlay,`
- [ ] L114 `BarButton(icon = TmIcons.Close, label = s.playerMiniClose, onClick = onDismiss)`
- [ ] L121 `private fun BarButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Uni`
- [ ] L128 `.clickable(interactionSource = interactions, indication = androidx.compose.foundation.LocalIndication.current,`

## ui/src/shared/kotlin/com/tmplayer/ui/components/ScrollToTop.kt (1)
- [ ] L201 `.clickable(role = Role.Button) {`

## ui/src/shared/kotlin/com/tmplayer/ui/components/TitleReportDialog.kt (7)
- [ ] L69 `onDismissRequest = { if (review != null) onDecline() else onClose() },`
- [ ] L81 `review != null -> TextButton(onClick = onSend, enabled = review.canSend) { Text(s.titlesSend) }`
- [ ] L83 `else -> TextButton(onClick = onClose, modifier = Modifier.focusRequester(quiet)) { Text(s.commonClose) }`
- [ ] L88 `review != null -> TextButton(onClick = onDecline, modifier = Modifier.focusRequester(quiet)) { Text(s.titlesNo`
- [ ] L89 `state is State.Sending -> TextButton(onClick = onClose, modifier = Modifier.focusRequester(quiet)) { Text(s.co`
- [ ] L223 `role = Role.Checkbox,`
- [ ] L229 `Checkbox(checked = checked, onCheckedChange = null)`

## ui/src/shared/kotlin/com/tmplayer/ui/components/TvReach.kt (8)
- [ ] L150 `return onKeyEvent { event ->`
- [ ] L151 `if (event.type != KeyEventType.KeyDown) return@onKeyEvent false`
- [ ] L155 `else -> return@onKeyEvent false`
- [ ] L157 `if (!TvReach.shown(edge)) return@onKeyEvent false`
- [ ] L195 `BackHandler(enabled = held, onBack = leave)`
- [ ] L205 `.onKeyEvent { event -> reachableKey(event, edge, manager, gate, leave) }`
- [ ] L240 `.onKeyEvent { event ->`
- [ ] L241 `val dir = arrow(event) ?: return@onKeyEvent false`

## ui/src/shared/kotlin/com/tmplayer/ui/components/UpdateCard.kt (2)
- [ ] L236 `.onPreviewKeyEvent { event ->`
- [ ] L237 `if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false`

## ui/src/shared/kotlin/com/tmplayer/ui/components/ViewingSummary.kt (1)
- [ ] L81 `.clickable(interactionSource = interactions, indication = androidx.compose.foundation.LocalIndication.current,`
