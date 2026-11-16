# phone-browse-2: 117 controls in 18 files

Tick a line only after every dimension in private/UX-INTERACTION-PLAN.md was checked for it.

## ui/src/shared/kotlin/com/tmplayer/ui/browse/AddChatsScreen.kt (32)
- [ ] L438 `.onPreviewKeyEvent { rowKey(it, id) }`
- [ ] L505 `BackHandler(onBack = leave)`
- [ ] L524 `.onPreviewKeyEvent { event ->`
- [ ] L540 `OutlinedTextField(`
- [ ] L581 `if (!required) QuietButton(s.commonBack, icon = true, onClick = leave)`
- [ ] L595 `QuietButton(s.commonBack, icon = true, onClick = leave)`
- [ ] L622 `onClick = { model.scope(scope) },`
- [ ] L645 `SetupButton(s.commonTryAgain, primary = true, onClick = onRetry)`
- [ ] L685 `SetupButton(`
- [ ] L691 `.onPreviewKeyEvent { event ->`
- [ ] L701 `onClick = {`
- [ ] L808 `SetupButton(s.commonTryAgain, primary = true, onClick = { model.done(chats) })`
- [ ] L821 `SetupButton(`
- [ ] L825 `onClick = { model.done(chats) },`
- [ ] L832 `QuietButton(s.pickerContinueWithout, modifier = Modifier.focusRequester(escapeFocus), onClick = go)`
- [ ] L862 `doneButton()`
- [ ] L900 `onClick = { intoList(sc) },`
- [ ] L903 `.onPreviewKeyEvent { event ->`
- [ ] L919 `onClick = { intoSearch() },`
- [ ] L922 `.onPreviewKeyEvent { event ->`
- [ ] L941 `.onPreviewKeyEvent { event ->`
- [ ] L969 `doneButton()`
- [ ] L1042 `.clickable(interactionSource = interactions, indication = null, role = Role.Tab, onClick = onClick)`
- [ ] L1081 `.clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onOpen)`
- [ ] L1159 `.onPreviewKeyEvent { event ->`
- [ ] L1160 `if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false`
- [ ] L1190 `.combinedClickable(`
- [ ] L1193 `role = Role.Checkbox,`
- [ ] L1195 `onClick = onToggle,`
- [ ] L1228 `Checkbox(checked = ticked, onCheckedChange = null)`
- [ ] L1260 `* The tick box [PickRow] draws on TV instead of Material3's animated [Checkbox] (HX76): one node`
- [ ] L1379 `QuietButton(s.pickerRemove, onClick = onRemove)`

## ui/src/shared/kotlin/com/tmplayer/ui/browse/ChatsGateHost.kt (1)
- [ ] L126 `QuietButton(s.pickerContinueWithout, modifier = Modifier.focusRequester(focus), onClick = ChatsGate::waive)`

## ui/src/shared/kotlin/com/tmplayer/ui/browse/Copies.kt (11)
- [ ] L192 `onClick = { open = !open },`
- [ ] L246 `onDismissRequest = onClose,`
- [ ] L301 `PageDiscButton(`
- [ ] L308 `com.tmplayer.ui.components.FloatingRoundButton(`
- [ ] L450 `onClick = { choose(copy) },`
- [ ] L494 `.clickable(interactionSource = interactions, indication = if (tv) null else androidx.compose.foundation.LocalI`
- [ ] L573 `.clickable(interactionSource = interactions, indication = if (tv) null else androidx.compose.foundation.LocalI`
- [ ] L626 `.clickable(interactionSource = interactions, indication = if (tv) null else androidx.compose.foundation.LocalI`
- [ ] L691 `onClick = { open = !open },`
- [ ] L703 `onClick = { pick(0) },`
- [ ] L716 `onClick = { pick(q.height) },`

## ui/src/shared/kotlin/com/tmplayer/ui/browse/Course.kt (2)
- [ ] L65 `PlayButton(`
- [ ] L71 `onClick = { onPlay(next) },`

## ui/src/shared/kotlin/com/tmplayer/ui/browse/DetailHero.kt (4)
- [ ] L197 `.clickable(interactionSource = interactions, indication = if (tv) null else LocalIndication.current, onClick =`
- [ ] L297 `.clickable(interactionSource = interactions, indication = null, enabled = !ghost, onClick = press)`
- [ ] L319 `.clickable(interactionSource = interactions, indication = if (enabled) LocalIndication.current else null, enab`
- [ ] L417 `Modifier.clickable(interactionSource = interactions, indication = if (tv) null else LocalIndication.current) {`

## ui/src/shared/kotlin/com/tmplayer/ui/browse/FeaturedHero.kt (3)
- [ ] L161 `onClick = onPick?.let { pick -> { pick(page) } },`
- [ ] L194 `if (onClick == null) {`
- [ ] L200 `.clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onClick)`

## ui/src/shared/kotlin/com/tmplayer/ui/browse/HeroPill.kt (1)
- [ ] L93 `.clickable(interactionSource = interactions, indication = if (isTouch()) androidx.compose.foundation.LocalIndi`

## ui/src/shared/kotlin/com/tmplayer/ui/browse/HomeEmpty.kt (3)
- [ ] L82 `onClick = onButton,`
- [ ] L200 `HeroPill(label = s.homeOpenSettings, onClick = onSettings, focus = focus, icon = TmIcons.Settings, modifier = `
- [ ] L232 `HeroPill(label = s.pickerChooseChats, onClick = onAddChats, focus = focus, icon = TmIcons.PersonAdd)`

## ui/src/shared/kotlin/com/tmplayer/ui/browse/ListPageScaffold.kt (5)
- [ ] L107 `onClick = { onSelect(list.info.id) },`
- [ ] L116 `onClick = { naming = true },`
- [ ] L124 `CopyChip(s.listsRename, chosen = false, onClick = { renaming = shown })`
- [ ] L125 `CopyChip(s.listsOnHome, chosen = shown.info.home, onClick = { actions.setHome(shown.info.id, !shown.info.home)`
- [ ] L126 `CopyChip(s.listsDelete, chosen = false, onClick = { deleting = shown })`

## ui/src/shared/kotlin/com/tmplayer/ui/browse/ListsUi.kt (7)
- [ ] L200 `onDismissRequest = onDismiss,`
- [ ] L204 `OutlinedTextField(`
- [ ] L217 `confirmButton = { TextButton(onClick = { save() }, enabled = clean != null) { Text(confirm) } },`
- [ ] L218 `dismissButton = { TextButton(onClick = onDismiss) { Text(s.commonCancel) } },`
- [ ] L227 `onDismissRequest = onDismiss,`
- [ ] L230 `confirmButton = { TextButton(onClick = { onDismiss(); onDelete() }) { Text(s.commonDelete) } },`
- [ ] L231 `dismissButton = { TextButton(onClick = onDismiss) { Text(s.commonCancel) } },`

## ui/src/shared/kotlin/com/tmplayer/ui/browse/MediaDetail.kt (1)
- [ ] L560 `.clickable(interactionSource = interactions, indication = if (touch) androidx.compose.foundation.LocalIndicati`

## ui/src/shared/kotlin/com/tmplayer/ui/browse/MediaDetailScreen.kt (10)
- [ ] L190 `com.tmplayer.ui.components.FloatingBackButton(`
- [ ] L216 `CloseButton(onClose)`
- [ ] L343 `Actions(page, holds, onEpisodes = episodesButton(page.item, page.meta, episodes), headlineOnly = tv)`
- [ ] L413 `Actions(page, holds, primaryFullWidth = true, onEpisodes = episodesButton(page.item, page.meta, episodes), hea`
- [ ] L722 `private fun CloseButton(onClose: () -> Unit, modifier: Modifier = Modifier) {`
- [ ] L723 `PageDiscButton(TmIcons.Close, LocalStrings.current.commonClose, onClose, modifier)`
- [ ] L731 `fun PageDiscButton(`
- [ ] L744 `com.tmplayer.ui.components.FloatingRoundButton(`
- [ ] L776 `.clickable(interactionSource = interactions, indication = if (tv) null else androidx.compose.foundation.LocalI`
- [ ] L791 `internal fun episodesButton(item: MediaItem, meta: MetaInfo?, target: BringIntoViewRequester): (() -> Unit)? {`

## ui/src/shared/kotlin/com/tmplayer/ui/browse/NavGroupHeading.kt (1)
- [ ] L74 `.clickable(role = Role.Button, onClickLabel = if (open) s.navFold else s.navUnfold, onClick = onToggle)`

## ui/src/shared/kotlin/com/tmplayer/ui/browse/SeasonsSection.kt (3)
- [ ] L228 `.then(if (usable) Modifier.clickable(interactionSource = interactions, indication = null, onClick = onSelect) `
- [ ] L293 `Modifier.clickable(`
- [ ] L296 `onClick = onOpen,`

## ui/src/shared/kotlin/com/tmplayer/ui/browse/Series.kt (18)
- [ ] L305 `.clickable(interactionSource = interactions, indication = null) { onSelect(season.number) }`
- [ ] L437 `.clickable(interactionSource = interactions, indication = if (tv) null else androidx.compose.foundation.LocalI`
- [ ] L459 `CopyChip(label, chosen = height == selected, onClick = { onSelect(height) }, description = s.seriesQualityPick`
- [ ] L515 `Modifier.combinedClickable(interactionSource = interactions, indication = androidx.compose.foundation.LocalInd`
- [ ] L517 `Modifier.clickable(interactionSource = interactions, indication = if (tv) null else androidx.compose.foundatio`
- [ ] L946 `Modifier.onPreviewKeyEvent { event ->`
- [ ] L947 `if (event.key != Key.DirectionDown) return@onPreviewKeyEvent false`
- [ ] L963 `.onPreviewKeyEvent { event ->`
- [ ] L1134 `onClick = { onPlay(copyOf(target, next?.item ?: watch.pick(target, progress))) },`
- [ ] L1148 `onClick = { onPlay(copyOf(target, next?.item ?: watch.pick(target, progress))) },`
- [ ] L1242 `onClick = {`
- [ ] L1531 `PageDiscButton(TmIcons.ArrowBack, s.seriesBack, onBack)`
- [ ] L1593 `.clickable(interactionSource = interactions, indication = null) { onSelect(index) }`
- [ ] L1621 `internal fun PlayButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {`
- [ ] L1631 `.clickable(interactionSource = interactions, indication = if (tv) null else androidx.compose.foundation.LocalI`
- [ ] L1709 `* a D-pad hold does not reach 'combinedClickable' on a television. A hold is a repeat of the key`
- [ ] L1728 `.onPreviewKeyEvent { event ->`
- [ ] L1729 `if (event.key !in OK_KEYS) return@onPreviewKeyEvent false`

## ui/src/shared/kotlin/com/tmplayer/ui/browse/ShowMenu.kt (2)
- [ ] L148 `androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {`
- [ ] L203 `.clickable(interactionSource = interactions, indication = androidx.compose.material3.ripple(), role = Role.But`

## ui/src/shared/kotlin/com/tmplayer/ui/browse/TopicTabs.kt (9)
- [ ] L117 `onClick = { onSelect(0) },`
- [ ] L128 `onClick = { onSelect(topic.id) },`
- [ ] L173 `.clickable(interactionSource = interactions, indication = null) { editing = true }`
- [ ] L186 `BasicTextField(`
- [ ] L196 `.onPreviewKeyEvent { event ->`
- [ ] L197 `if (!tv || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false`
- [ ] L204 `else -> return@onPreviewKeyEvent false`
- [ ] L222 `modifier = Modifier.size(if (tv) 18.dp else 16.dp).clickable(onClickLabel = s.commonClear) { onValue("") },`
- [ ] L264 `.clickable(interactionSource = interactions, indication = null, onClick = onClick)`

## ui/src/shared/kotlin/com/tmplayer/ui/browse/WideDetail.kt (4)
- [ ] L152 `val onEpisodes = episodesButton(item, meta, episodesAt)`
- [ ] L417 `PageDiscButton(TmIcons.ArrowBack, s.commonBack, onClose, Modifier.align(Alignment.TopStart).padding(16.dp))`
- [ ] L491 `.clickable(interactionSource = interactions, indication = if (enabled) LocalIndication.current else null, onCl`
- [ ] L531 `.clickable(interactionSource = interactions, indication = LocalIndication.current, onClick = onClick)`
