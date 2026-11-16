# flows: 228 controls in 21 files

Tick a line only after every dimension in private/UX-INTERACTION-PLAN.md was checked for it.

## app/src/main/java/com/tmplayer/MainActivity.kt (21)
- [ ] L544 `override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {`
- [ ] L549 `return super.onKeyDown(keyCode, event)`
- [ ] L1545 `.onPreviewKeyEvent {`
- [ ] L1573 `sendLogs = { com.tmplayer.ui.settings.SendLogsButton(Modifier.padding(top = 8.dp)) },`
- [ ] L1668 `com.tmplayer.ui.components.TmSecondaryButton(onClick = openProxies) {`
- [ ] L1671 `com.tmplayer.ui.components.TmSecondaryButton(onClick = sendLogs) {`
- [ ] L1681 `androidx.compose.material3.IconButton(onClick = { moreOpen = true }) {`
- [ ] L1688 `com.tmplayer.ui.components.TmDropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {`
- [ ] L1689 `androidx.compose.material3.DropdownMenuItem(`
- [ ] L1691 `onClick = { moreOpen = false; openProxies() },`
- [ ] L1693 `androidx.compose.material3.DropdownMenuItem(`
- [ ] L1695 `onClick = { moreOpen = false; sendLogs() },`
- [ ] L1888 `BackHandler {`
- [ ] L2155 `BackHandler(onBack = leaveChat)`
- [ ] L2194 `BackHandler(`
- [ ] L2239 `BackHandler(onBack = leaveSettings)`
- [ ] L2283 `BackHandler(onBack = leaveAbout)`
- [ ] L2698 `com.tmplayer.ui.settings.SendLogsButton()`
- [ ] L2733 `com.tmplayer.ui.components.TmSecondaryButton(`
- [ ] L2734 `onClick = {`
- [ ] L2739 `com.tmplayer.ui.settings.SendLogsButton()`

## app/src/main/java/com/tmplayer/ui/auth/AuthFields.kt (5)
- [ ] L78 `OutlinedTextField(`
- [ ] L114 `BasicTextField(`
- [ ] L155 `IconButton(onClick = onBack, modifier = Modifier.size(BACK_TARGET)) {`
- [ ] L203 `.clickable(enabled = pressable, role = Role.Button, onClick = onClick)`
- [ ] L215 `.clickable(enabled = pressable, role = Role.Button, onClick = onClick)`

## app/src/main/java/com/tmplayer/ui/auth/LoginScreen.kt (49)
- [ ] L302 `BackHandler {`
- [ ] L333 `if (onBack == null) ExitOnBack() else BackHandler(onBack = onBack)`
- [ ] L400 `TmSecondaryButton(onClick = { picking = true }, enabled = !detecting) { Text(s.loginChooseCountry) }`
- [ ] L404 `if (onBack != null) TmSecondaryButton(onClick = onUseQr) { Text(s.loginUseQr) }`
- [ ] L495 `onClick = { picking = true },`
- [ ] L542 `.onPreviewKeyEvent { event ->`
- [ ] L556 `TmButton(`
- [ ] L557 `onClick = submit,`
- [ ] L568 `TmSecondaryButton(onClick = onUseQr, modifier = Modifier.paneAction()) {`
- [ ] L588 `androidx.compose.material3.TextButton(onClick = open, modifier = Modifier.paneAction()) {`
- [ ] L592 `TmSecondaryButton(onClick = open) { Text(s.loginProxySettings) }`
- [ ] L606 `BackHandler(onBack = onStartOver)`
- [ ] L658 `TmSecondaryButton(`
- [ ] L659 `onClick = {`
- [ ] L673 `TmSecondaryButton(onClick = onChangeNumber) { Text(s.loginWrongNumber) }`
- [ ] L762 `TmButton(`
- [ ] L763 `onClick = submit,`
- [ ] L774 `TmSecondaryButton(`
- [ ] L775 `onClick = {`
- [ ] L791 `TmSecondaryButton(onClick = onChangeNumber, modifier = Modifier.paneAction()) {`
- [ ] L794 `TmSecondaryButton(onClick = onStartOver, modifier = Modifier.paneAction()) {`
- [ ] L816 `BasicTextField(`
- [ ] L909 `if (isTouch()) BackHandler(onBack = onUsePhone) else ExitOnBack()`
- [ ] L981 `TmSecondaryButton(onClick = onUsePhone, modifier = Modifier.paneAction()) {`
- [ ] L984 `QrRefreshButton(onRefresh)`
- [ ] L1008 `TmSecondaryButton(onClick = onUsePhone, modifier = Modifier.focusRequester(first)) { Text(s.loginUsePhone) }`
- [ ] L1009 `QrRefreshButton(onRefresh)`
- [ ] L1066 `BackHandler { if (confirming) confirming = false else forgot = false }`
- [ ] L1078 `BackHandler(onBack = onStartOver)`
- [ ] L1091 `androidx.compose.material3.IconButton(onClick = { shown = !shown }) {`
- [ ] L1106 `if (!isTouch()) TmSecondaryButton(onClick = { shown = !shown }) { Text(reveal) }`
- [ ] L1107 `TmSecondaryButton(`
- [ ] L1108 `onClick = {`
- [ ] L1141 `TmButton(onClick = onBack, modifier = (if (touch) Modifier.paneAction() else Modifier).focusRequester(back)) {`
- [ ] L1144 `TmSecondaryButton(`
- [ ] L1145 `onClick = onReset,`
- [ ] L1167 `BackHandler(onBack = back)`
- [ ] L1206 `androidx.compose.material3.IconButton(onClick = { shown = !shown }) {`
- [ ] L1229 `extras = { if (!isTouch()) TmSecondaryButton(onClick = { shown = !shown }) { Text(reveal) } },`
- [ ] L1244 `BackHandler(onBack = onStartOver)`
- [ ] L1285 `BackHandler(onBack = onStartOver)`
- [ ] L1311 `TmSecondaryButton(`
- [ ] L1312 `onClick = { asking.start { submitted = null; onRun { Td.resendCode() } } },`
- [ ] L1317 `TmSecondaryButton(onClick = { asking.start { onRun { Td.resetEmailAddress() } } }) { Text(s.loginEmailReset) }`
- [ ] L1416 `TmButton(`
- [ ] L1417 `onClick = onSubmit,`
- [ ] L1425 `TmSecondaryButton(onClick = onBack, modifier = Modifier.paneAction()) {`
- [ ] L1435 `TmButton(onClick = onSubmit, enabled = canSubmit, loading = busy, busyLabel = busyLabel) {`
- [ ] L1438 `TmSecondaryButton(onClick = onBack, modifier = Modifier.focusRequester(waitFocus)) {`

## app/src/main/java/com/tmplayer/ui/downloads/DownloadsScreen.kt (46)
- [ ] L432 `BackHandler(enabled = picking) { leavePicking() }`
- [ ] L1319 `IconButton(`
- [ ] L1320 `onClick = { if (picking) leavePicking() else onBack() },`
- [ ] L1364 `onClick = { confirmingDeleteMany = true },`
- [ ] L1528 `* [TextButton], with the ring a remote needs.`
- [ ] L1538 `TvPill(label, icon = null, enabled = enabled, onClick = onClick)`
- [ ] L1542 `TextButton(`
- [ ] L1543 `onClick = onClick,`
- [ ] L1584 `TextAction(bulk, onClick = onBulk)`
- [ ] L1651 `onClick = { onPick(sort) },`
- [ ] L1709 `.clickable(interactionSource = interactions, indication = LocalIndication.current, role = Role.Button, onClick`
- [ ] L1757 `if (onClick == null && onHold == null) {`
- [ ] L1762 `.combinedClickable(`
- [ ] L1765 `onClick = { onClick?.invoke() ?: onHold?.invoke() },`
- [ ] L1811 `TvPill(label, icon, modifier = Modifier.weight(1f), onClick = onClick)`
- [ ] L1815 `FilledTonalButton(`
- [ ] L1816 `onClick = onClick,`
- [ ] L1834 `TvPill(label, icon, danger = danger, modifier = Modifier.weight(1f), onClick = onClick)`
- [ ] L1839 `OutlinedButton(`
- [ ] L1840 `onClick = onClick,`
- [ ] L1866 `TvPill(label, icon, danger = danger, showLabel = false, onClick = onClick)`
- [ ] L1871 `OutlinedButton(`
- [ ] L1872 `onClick = onClick,`
- [ ] L1944 `onClick = when {`
- [ ] L1953 `Checkbox(checked = checked, onCheckedChange = { pickTick(!checked); onToggle() })`
- [ ] L1999 `IconButton(onClick = { menuOpen = true }) {`
- [ ] L2002 `com.tmplayer.ui.components.TmDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {`
- [ ] L2003 `androidx.compose.material3.DropdownMenuItem(`
- [ ] L2006 `onClick = {`
- [ ] L2012 `androidx.compose.material3.DropdownMenuItem(`
- [ ] L2015 `onClick = {`
- [ ] L2022 `androidx.compose.material3.DropdownMenuItem(`
- [ ] L2025 `onClick = {`
- [ ] L2032 `androidx.compose.material3.DropdownMenuItem(`
- [ ] L2035 `onClick = {`
- [ ] L2236 `onClick = onCancel,`
- [ ] L2357 `FloatingBackButton(`
- [ ] L2362 `onClick = if (picking) onLeavePicking else onBack,`
- [ ] L2388 `TextButton(onClick = onSelectAll, enabled = !allChosen) { Text(s.downloadsSelectAll, maxLines = 1) }`
- [ ] L2473 `onClick = if (picking) onLeavePicking else onBack,`
- [ ] L2493 `TvPill(s.downloadsSelectAll, TmIcons.Checklist, enabled = !allChosen, onClick = onSelectAll)`
- [ ] L2495 `TvPill(s.commonShare, TmIcons.Share, onClick = onShareChosen)`
- [ ] L2496 `TvPill(s.downloadsSaveToFolder, TmIcons.Folder, onClick = onSaveChosen)`
- [ ] L2497 `TvPill(s.commonDelete, TmIcons.Delete, danger = true, onClick = onDeleteChosen)`
- [ ] L2500 `canSelect -> TvPill(s.downloadsSelect, TmIcons.Checklist, onClick = onSelect)`
- [ ] L2590 `.clickable(interactionSource = interactions, indication = null, role = Role.Button) { if (enabled) onClick() }`

## app/src/main/java/com/tmplayer/ui/update/ReleasePage.kt (1)
- [ ] L132 `TmButton(onClick = onClose, modifier = Modifier.focusRequester(close)) { Text(s.commonClose) }`

## app/src/main/java/com/tmplayer/ui/update/UpdateDialog.kt (16)
- [ ] L200 `TmButton(`
- [ ] L201 `onClick = primary,`
- [ ] L206 `TmSecondaryButton(onClick = releasePage, modifier = Modifier.paneAction()) {`
- [ ] L210 `TmSecondaryButton(onClick = later, modifier = Modifier.paneAction()) {`
- [ ] L213 `TmSecondaryButton(onClick = skip, modifier = Modifier.paneAction()) {`
- [ ] L220 `TmButton(`
- [ ] L221 `onClick = primary,`
- [ ] L227 `TmSecondaryButton(onClick = releasePage, modifier = Modifier.paneAction()) {`
- [ ] L231 `TmSecondaryButton(onClick = onDismiss, modifier = Modifier.paneAction()) {`
- [ ] L292 `onDismissRequest = { if (!downloading) onDismiss() },`
- [ ] L314 `Button(`
- [ ] L315 `onClick = onPrimary,`
- [ ] L328 `TextButton(onClick = onReleasePage, modifier = Modifier.fillMaxWidth()) {`
- [ ] L334 `TextButton(onClick = onSkip, modifier = Modifier.weight(1f)) {`
- [ ] L337 `TextButton(onClick = onLater, modifier = Modifier.weight(1f)) {`
- [ ] L341 `TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {`

## ui/src/shared/kotlin/com/tmplayer/ui/auth/CountryPicker.kt (5)
- [ ] L81 `BackHandler(onBack = onDismiss)`
- [ ] L91 `IconButton(onClick = onDismiss) {`
- [ ] L109 `OutlinedTextField(`
- [ ] L120 `IconButton(onClick = { query = "" }) {`
- [ ] L158 `.clickable(interactionSource = interactions, indication = androidx.compose.foundation.LocalIndication.current,`

## ui/src/shared/kotlin/com/tmplayer/ui/auth/QrScanned.kt (3)
- [ ] L241 `QrRefreshButton(onRefresh, Modifier.focusRequester(focus), onWhite = true)`
- [ ] L253 `fun QrRefreshButton(onRefresh: () -> Unit, modifier: Modifier = Modifier, onWhite: Boolean = false) {`
- [ ] L279 `.clickable(interactionSource = interactions, indication = androidx.compose.foundation.LocalIndication.current,`

## ui/src/shared/kotlin/com/tmplayer/ui/connect/ConnectHelp.kt (4)
- [ ] L202 `SetupButton(s.commonRetry, primary = true, modifier = Modifier.focusRequester(focus), onClick = onRetry)`
- [ ] L203 `SetupButton(if (proxyOn) s.connectProxyOn else s.connectUseProxy, onClick = onOpenProxies)`
- [ ] L264 `QuietButton(s.commonRetry, onClick = onRetry)`
- [ ] L265 `QuietButton(if (proxyOn) s.connectProxyOn else s.connectUseProxy, onClick = onOpenProxies)`

## ui/src/shared/kotlin/com/tmplayer/ui/connect/ProxyScreen.kt (12)
- [ ] L75 `BackHandler(onBack = onClose)`
- [ ] L126 `QuietButton(s.commonBack, icon = true, onClick = onClose)`
- [ ] L139 `OutlinedTextField(`
- [ ] L152 `SetupButton(if (busy) s.proxyPinging else s.proxyAdd, primary = true, onClick = submitLink)`
- [ ] L153 `QuietButton(s.proxyByHand, onClick = { manual = !manual })`
- [ ] L175 `QuietButton(s.proxyStop, onClick = { scope.launch { Td.disableProxy(); reload() } })`
- [ ] L210 `if (!entry.enabled) QuietButton(s.proxyUse, onClick = onUse)`
- [ ] L211 `QuietButton(s.proxyTest, onClick = onTest)`
- [ ] L212 `QuietButton(s.commonRemove, onClick = onRemove)`
- [ ] L230 `SetupButton(kindLabel(k, s), primary = k == kind, onClick = { kind = k; error = null })`
- [ ] L234 `OutlinedTextField(`
- [ ] L253 `SetupButton(if (busy) s.proxyPinging else s.proxyAdd, primary = true, onClick = {`

## ui/src/shared/kotlin/com/tmplayer/ui/lock/PinPad.kt (8)
- [ ] L211 `if (onCancel != null) BackHandler(onBack = onCancel)`
- [ ] L243 `.onPreviewKeyEvent { event ->`
- [ ] L244 `if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false`
- [ ] L301 `onClick = { press(digit) },`
- [ ] L308 `PadKey(size = key, label = s.commonCancel, small = true, onClick = onCancel)`
- [ ] L312 `PadKey(size = key, label = "0", enabled = enabled, onClick = { press('0') })`
- [ ] L318 `onClick = { delete() },`
- [ ] L454 `.clickable(interactionSource = interactions, indication = null, role = Role.Button) {`

## ui/src/shared/kotlin/com/tmplayer/ui/onboarding/AppearanceStep.kt (4)
- [ ] L266 `.onPreviewKeyEvent { event ->`
- [ ] L279 `.clickable(`
- [ ] L282 `role = Role.RadioButton,`
- [ ] L283 `onClick = onClick,`

## ui/src/shared/kotlin/com/tmplayer/ui/onboarding/CrashAskCard.kt (4)
- [ ] L60 `if (tv) BackHandler { onAnswer(false) }`
- [ ] L88 `CrashAsk.Control.Yes -> SetupButton(s.setupCrashAskYes, modifier = focus.weight(1f)) { onAnswer(true) }`
- [ ] L89 `CrashAsk.Control.No -> SetupButton(s.setupCrashAskNo, modifier = focus.weight(1f)) { onAnswer(false) }`
- [ ] L95 `SetupButton(s.setupCrashPrivacy, modifier = privacyFocus.fillMaxWidth(), onClick = openPrivacy)`

## ui/src/shared/kotlin/com/tmplayer/ui/onboarding/CrashReportsQuestion.kt (2)
- [ ] L69 `onClick = onToggle,`
- [ ] L71 `SetupButton(`

## ui/src/shared/kotlin/com/tmplayer/ui/onboarding/DeviceFitUi.kt (4)
- [ ] L123 `SetupButton(s.deviceApplyAll, primary = true, modifier = Modifier.focusRequester(apply), onClick = onApply)`
- [ ] L124 `SetupButton(s.deviceNotNow, onClick = onDismiss)`
- [ ] L151 `SetupButton(s.deviceNotNow, onClick = onNotNow)`
- [ ] L152 `SetupButton(s.deviceApply, primary = true, onClick = onApply)`

## ui/src/shared/kotlin/com/tmplayer/ui/onboarding/LanguageList.kt (1)
- [ ] L89 `TextButton(onClick = { runCatching { uri.openUri(Onboarding.TRANSLATE_URL) } }) { Text(s.onboardingHelpTransla`

## ui/src/shared/kotlin/com/tmplayer/ui/onboarding/SetupChoices.kt (12)
- [ ] L160 `SetupButton(`
- [ ] L163 `onClick = onRunSetupAgain,`
- [ ] L227 `SetupButton(`
- [ ] L234 `ChoiceSwitch(`
- [ ] L251 `private fun ChoiceSwitch(`
- [ ] L273 `.onKeyEvent { event ->`
- [ ] L274 `if (event.type != KeyEventType.KeyDown) return@onKeyEvent false`
- [ ] L280 `else -> return@onKeyEvent false`
- [ ] L299 `role = Role.RadioButton`
- [ ] L306 `.pointerInput(choice) { detectTapGestures { picked(choice) } }`
- [ ] L340 `.clickable(`
- [ ] L344 `onClick = onClick,`

## ui/src/shared/kotlin/com/tmplayer/ui/onboarding/SetupScreen.kt (13)
- [ ] L208 `BackHandler(enabled = back != null) { back?.invoke() }`
- [ ] L392 `role: Role = Role.RadioButton,`
- [ ] L408 `.clickable(`
- [ ] L412 `onClick = onClick,`
- [ ] L488 `.clickable(`
- [ ] L491 `role = Role.RadioButton,`
- [ ] L492 `onClick = onPick,`
- [ ] L560 `internal fun SetupButton(`
- [ ] L588 `.clickable(`
- [ ] L592 `onClick = onClick,`
- [ ] L711 `return onPreviewKeyEvent { event ->`
- [ ] L712 `if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false`
- [ ] L718 `else -> return@onPreviewKeyEvent false`

## ui/src/shared/kotlin/com/tmplayer/ui/onboarding/StepChrome.kt (9)
- [ ] L143 `QuietButton(s.setupSkipAll, modifier = Modifier.arrowsMoveFocus(), onClick = onSkipAll)`
- [ ] L155 `if (onBack != null) QuietButton(s.setupBack, icon = true, modifier = Modifier.arrowsMoveFocus(), onClick = onB`
- [ ] L160 `SetupButton(`
- [ ] L164 `onClick = onUseRecommended,`
- [ ] L168 `SetupButton(`
- [ ] L175 `onClick = onPrimary,`
- [ ] L242 `internal fun QuietButton(label: String, icon: Boolean = false, modifier: Modifier = Modifier, onClick: () -> U`
- [ ] L254 `.clickable(`
- [ ] L258 `onClick = onClick,`

## ui/src/shared/kotlin/com/tmplayer/ui/onboarding/TourScreen.kt (1)
- [ ] L86 `BackHandler { backward?.invoke() ?: onDone() }`

## ui/src/shared/kotlin/com/tmplayer/ui/online/MetaExtrasUi.kt (8)
- [ ] L409 `.clickable(interactionSource = interactions, indication = if (tv) null else androidx.compose.foundation.LocalI`
- [ ] L493 `.clickable(`
- [ ] L496 `onClick = { enlarged = true },`
- [ ] L543 `onDismissRequest = onClose,`
- [ ] L550 `.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),`
- [ ] L584 `.clickable(interactionSource = interactions, indication = if (tv) null else androidx.compose.foundation.LocalI`
- [ ] L670 `onClick = { onOpen(known.item) },`
- [ ] L697 `.clickable(interactionSource = interactions, indication = if (tv) null else androidx.compose.foundation.LocalI`
