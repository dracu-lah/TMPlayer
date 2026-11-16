# web-1: 247 controls in 18 files

Tick a line only after every dimension in private/UX-INTERACTION-PLAN.md was checked for it.

## webapp/src/App.tsx (5)
- [ ] L134 `<button class="primary" onClick={() => window.location.reload()}>{t('common.try_again')}</button>`
- [ ] L144 `<button class="primary" onClick={reset}>Try again</button>`
- [ ] L218 `<button class="primary" type="button" onClick={useHere}>{t('web.use_here')}</button>`
- [ ] L243 `<button class="link" type="button" onClick={() => setLater(true)}>{t('update.later_short')}</button>`
- [ ] L244 `<button class="primary" type="button" onClick={applyUpdate}>{t('player.reload')}</button>`

## webapp/src/player/Chrome.tsx (84)
- [ ] L1335 `<button`
- [ ] L1343 `onClick={(e) => (p.onClick(e), poke())}`
- [ ] L1358 `onPointerDown={onDown}`
- [ ] L1380 `<Btn icon={ArrowLeft02Icon} label={plain() ? t('common.back') : t('player.back_hint')} onClick={() => c.close(`
- [ ] L1385 `<Btn icon={MoreVerticalIcon} label={phone() ? t('player.more_options') : t('common.more')} onClick={menuAt('mo`
- [ ] L1400 `onClick={() => go(info()?.previous ?? null)}`
- [ ] L1404 `<button`
- [ ] L1410 `onClick={() => (togglePlay(), poke())}`
- [ ] L1425 `onClick={() => go(info()?.next ?? null)}`
- [ ] L1442 `<button type="button" class="pc-total" onClick={flipRemaining} aria-label={remaining() ? t('player.show_total_`
- [ ] L1472 `<Btn icon={SubtitleIcon} label={plain() ? t('tracks.subtitles') : t('player.subtitles_hint')} onClick={menuAt(`
- [ ] L1473 `<Btn icon={HeadphonesIcon} label={plain() ? t('player.audio') : t('player.audio_hint')} onClick={menuAt('audio`
- [ ] L1477 `<button type="button" class="pc-btn pc-speed-text" data-tip={t('player.speed_hint')} data-shot="speed" aria-la`
- [ ] L1482 `<Btn icon={DashboardSpeed02Icon} label={t('player.playback_speed')} onClick={menuAt('speed')} shot="speed" />`
- [ ] L1485 `<Btn icon={shapeIcon(shape())} label={plain() ? t('player.picture_shape_now', { shape: shapeName(shape()) }) :`
- [ ] L1492 `onClick={() => void toggleFullscreen()}`
- [ ] L1497 `<Btn icon={SquareLock02Icon} label={t('player.lock_screen')} onClick={lock} shot="lock" />`
- [ ] L1500 `<Btn icon={MoreVerticalIcon} label={t('common.more')} onClick={menuAt('more')} shot="more" pressed={sheet() ==`
- [ ] L1511 `onClick={toggleFullscreen}`
- [ ] L1517 `<button type="button" class="pc-chip-button" data-shot="copies" onClick={menuAt('copies')} aria-label={t('play`
- [ ] L1523 `<button type="button" class="pc-chip-button" data-shot="episodes" onClick={menuAt('episodes')}>`
- [ ] L1530 `<button type="button" class="pc-readout" onClick={flipRemaining} aria-label={remaining() ? t('player.show_tota`
- [ ] L1545 `<button type="button" class="pc-chipline loop" onClick={() => (setLoop(null), flash(t('player.repeat_off')))} `
- [ ] L1556 `<button type="button" class="pc-corner back" tabIndex={tv() ? -1 : 0} onClick={takeBack}>`
- [ ] L1562 `<button type="button" class="pc-corner skip" tabIndex={tv() ? -1 : 0} onClick={skipIt}>`
- [ ] L1606 `<button type="button" onClick={() => (c.startOver(), setOffer(false))}>{t('player.start_over')}</button>`
- [ ] L1617 `<button type="button" onClick={() => (stopCount(), setNextHidden(true))}>{counting() !== null ? t('common.canc`
- [ ] L1618 `<button type="button" onClick={() => go(info()!.next)}>{t('player.play_now')}</button>`
- [ ] L1635 `<Entry icon={SquareLock02Icon} text={t('player.lock_screen')} onClick={lock} />`
- [ ] L1638 `<Entry icon={ScreenRotationIcon} text={t('player.screen_orientation')} onClick={() => (setSheet(null), rotate(`
- [ ] L1644 `<Entry text={t('player.copies')} trailing={copyLabel()} onClick={() => setSheet('copies')} />`
- [ ] L1647 `<Entry icon={PictureInPictureOnIcon} text={t('player.picture_in_picture')} onClick={() => (setSheet(null), tog`
- [ ] L1651 `<Entry text={t('player.subtitles')} trailing={subOn() ? subLabel(subOn()!, subs().indexOf(subOn()!)) : t('comm`
- [ ] L1652 `<Entry text={t('player.audio')} trailing={c.audio() ? c.trackLabel(c.audio()!, c.audios().indexOf(c.audio()!))`
- [ ] L1653 `<Entry text={t('player.playback_speed')} trailing={speedLabel(rate())} onClick={() => setSheet('speed')} />`
- [ ] L1655 `<Entry text={t('player.playback_options')} trailing={sleepAt() !== null || sleepEnd() ? sleepSummary() : undef`
- [ ] L1656 `<Entry text={t('player.picture_shape')} trailing={shapeName(shape())} onClick={() => setSheet('shape')} />`
- [ ] L1657 `<Entry text={t('player.subtitle_style_and_timing')} onClick={() => setSheet('timing')} />`
- [ ] L1658 `<Entry text={t('player.load_subtitle_file')} onClick={() => (setSheet(null), c.loadSubtitleFile())} />`
- [ ] L1660 `<Entry icon={CheckmarkCircle02Icon} text={c.watched() ? t('player.mark_unwatched') : t('player.mark_watched')}`
- [ ] L1661 `<Entry icon={Bookmark02Icon} text={t('player.add_bookmark')} trailing={plain() ? undefined : 'B'} onClick={() `
- [ ] L1662 `<Entry text={t('player.bookmarks')} trailing={bookmarks().length ? String(bookmarks().length) : undefined} onC`
- [ ] L1665 `<Entry icon={KeyboardIcon} text={t('player.shortcuts')} trailing="?" onClick={() => setSheet('shortcuts')} />`
- [ ] L1667 `<Entry icon={InformationCircleIcon} text={t('player.playback_details')} trailing={phone() ? undefined : 'I'} o`
- [ ] L1672 `<Entry icon={Moon02Icon} text={t('player.sleep_timer')} trailing={sleepSummary()} onClick={() => setSheet('sle`
- [ ] L1673 `<Entry icon={RepeatIcon} text={repeatText()} trailing={plain() ? undefined : 'R'} onClick={() => (setSheet(nul`
- [ ] L1675 `<Entry icon={RepeatIcon} text={t('player.repeat_video')} trailing={repeatOn() ? t('common.on') : t('common.off`
- [ ] L1676 `<Entry text={t('settings.keep_repeat')} trailing={keepRepeat() ? t('common.on') : t('common.off')} onClick={()`
- [ ] L1677 `<Entry text={t('settings.play_chat_order')} trailing={chatOrder() ? t('common.on') : t('common.off')} onClick=`
- [ ] L1679 `<Entry text={t('player.chapter_next')} trailing={plain() ? undefined : '${t(/Mac/.test(navigator.platform) ? '`
- [ ] L1680 `<Entry text={t('player.chapter_previous')} trailing={plain() ? undefined : '${t(/Mac/.test(navigator.platform)`
- [ ] L1687 `<Choice icon={Cancel01Icon} text={t('player.sleep_turn_off')} phone={phone()} onClick={() => (setSleep(null), `
- [ ] L1690 `{(m) => <Choice text={sleepLabel(m)} on={sleepPick() === m && (sleepAt() !== null || sleepEnd())} phone={phone`
- [ ] L1726 `<button type="button" class="pc-sheet-button" onClick={() => c.close()}>{t('player.go_back')}</button>`
- [ ] L1727 `<button type="button" class="pc-sheet-button primary" onClick={keepWatching}>{t('player.keep_watching')}</butt`
- [ ] L1739 `<Choice text={shapeName(s)} on={shape() === s} phone={phone()} onClick={() => (pickShape(s), phone() && setShe`
- [ ] L1747 `<Choice text={t('common.off')} on={!subOn()} phone={phone()} onClick={() => (pickSub(null), setSheet(null))} /`
- [ ] L1748 `<For each={subs()}>{(tt, i) => <Choice text={subLabel(tt, i())} on={subOn() === tt} phone={phone()} onClick={(`
- [ ] L1751 `<Choice icon={Add01Icon} text={t('tracks.in_chat')} detail={t('tracks.in_chat_detail')} phone={phone()} onClic`
- [ ] L1753 `<Choice icon={Add01Icon} text={t('player.load_subtitle_file')} phone={phone()} onClick={() => (setSheet(null),`
- [ ] L1755 `<Choice icon={TranslateIcon} text={t('msone.search')} detail={t('msone.search_detail')} phone={phone()} onClic`
- [ ] L1762 `{(sub) => <Choice text={sub.name} detail={chatDetail(sub, chatBusy() === sub.key)} phone={phone()} disabled={c`
- [ ] L1771 `<Show when={c.audios().length} fallback={<Choice text={t('player.no_audio_tracks')} phone={phone()} onClick={(`
- [ ] L1779 `onClick={() => (c.pickAudio(i()), setSheet(null))}`
- [ ] L1791 `<For each={SPEEDS}>{(s) => <Choice text={speedLabel(s)} on={Math.abs(rate() - s) < 0.001} phone={false} onClic`
- [ ] L1801 `{(copy) => <Choice text={c.qualityLabel(copy.height)} on={c.sourceKey() === copy.key} phone={phone()} onClick=`
- [ ] L1812 `<button type="button" class="pc-btn" aria-label={t('common.close')} onClick={() => setSheet(null)}>`
- [ ] L1827 `<div class="pc-shield" onClick={showUnlock}>`
- [ ] L1828 `<button type="button" class="pc-unlock" classList={{ shown: unlockShown() }} onClick={(e) => (e.stopPropagatio`
- [ ] L1978 `onPointerDown={down}`
- [ ] L1983 `onKeyDown={(e) => {`
- [ ] L2151 `<input`
- [ ] L2159 `onInput={(e) => props.onVolume(Number(e.currentTarget.value) / 100)}`
- [ ] L2161 `<button type="button" class="pc-btn" aria-label={props.muted() ? t('player.unmute_hint') : t('player.mute_hint`
- [ ] L2180 `<button type="button" class="pc-tile" role="radio" aria-checked={Math.abs(props.rate() - s) < 0.001} onClick={`
- [ ] L2190 `<button type="button" class="pc-round" aria-label={t('player.speed_slower')} disabled={props.rate() <= SPEED_M`
- [ ] L2194 `<button type="button" class="pc-round" aria-label={t('player.speed_faster')} disabled={props.rate() >= SPEED_M`
- [ ] L2217 `<div class="pc-sheet-scrim episodes" onClick={(e) => e.target === e.currentTarget && props.onClose()}>`
- [ ] L2224 `<button type="button" class="pc-sheet-close" aria-label={t('common.close')} onClick={props.onClose}>`
- [ ] L2230 `<button type="button" class="pc-step-line pc-switch" role="switch" aria-checked={props.autoplay} onClick={() =`
- [ ] L2235 `<button type="button" class="pc-step-line" onClick={() => props.onPlay(props.info.previous!)}>`
- [ ] L2241 `<button type="button" class="pc-step-line" onClick={() => props.onPlay(props.info.next!)}>`
- [ ] L2252 `<button type="button" role="tab" class="pc-season" aria-selected={s.number === season()?.number} onClick={() =`
- [ ] L2266 `<button type="button" class="pc-episode" classList={{ playing: playing() }} aria-current={playing() ? 'true' :`

## webapp/src/player/Player.tsx (7)
- [ ] L937 `<button type="button" onClick={() => setNotice('')} aria-label={t('common.close')}>`
- [ ] L962 `<button type="button" class="pc-sheet-button primary" onClick={() => reload()}>`
- [ ] L972 `<button`
- [ ] L975 `onClick={() => {`
- [ ] L985 `<button type="button" class="pc-sheet-button" onClick={copyFailure}>`
- [ ] L989 `<button type="button" class="pc-sheet-button" onClick={close}>{t('player.go_back')}</button>`
- [ ] L993 `<input`

## webapp/src/player/extra-sheets.tsx (13)
- [ ] L32 `<button type="button" class="pc-choice" onClick={props.onHere}>`
- [ ] L45 `<button type="button" class="pc-bookmark" onClick={() => props.onPlay(b)} aria-label={'${t('player.bookmark_pl`
- [ ] L49 `<button type="button" class="pc-round small" aria-label={b.note ? t('player.bookmark_edit_note') : t('player.b`
- [ ] L52 `<button type="button" class="pc-round small" aria-label={t('player.bookmark_delete')} onClick={() => props.onD`
- [ ] L67 `<input ref={(el) => (input = el, queueMicrotask(() => el.focus()))} type="text" maxLength={MAX_NOTE} value={b.`
- [ ] L69 `<button type="submit" class="pc-sheet-button primary">{t('player.bookmark_save_note')}</button>`
- [ ] L98 `<button type="button" class="pc-tile" role="radio" aria-checked={props.current === v} onClick={() => props.onP`
- [ ] L124 `<button type="button" class="pc-round" aria-label={t('tracks.subtitles_earlier')} onClick={() => step(false)}>`
- [ ] L128 `<button type="button" class="pc-round" aria-label={t('tracks.subtitles_later')} onClick={() => step(true)}>`
- [ ] L133 `<button type="button" class="pc-sheet-button" onClick={() => props.onDelay(0)}>{t('tracks.reset_timing')}</but`
- [ ] L145 `<button type="button" class="pc-sheet-button" onClick={() => props.onLook(DEFAULT_LOOK)}>{t('subtitles.default`
- [ ] L211 `<div class="pc-sheet-scrim shortcuts" onClick={(e) => e.target === e.currentTarget && props.onClose()}>`
- [ ] L215 `<button type="button" class="pc-sheet-close" aria-label={t('common.close')} onClick={props.onClose}>`

## webapp/src/player/loader.tsx (1)
- [ ] L81 `<button type="button" class="pc-loader-back" aria-label={t('common.back')} onClick={() => props.onClose()}>`

## webapp/src/player/msone-sheet.tsx (7)
- [ ] L159 `<button type="button" class="pc-msone-pick" aria-busy={running() === hit.id} onClick={() => void pick(hit)}>`
- [ ] L189 `<button type="button" class="pc-sheet-button primary" onClick={() => void pick(hit)}>{t('common.try_again')}</`
- [ ] L190 `<button type="button" class="pc-sheet-button" onClick={props.onPickFile}>{t('msone.pick_file')}</button>`
- [ ] L218 `<button type="button" class="pc-sheet-button primary" onClick={props.onPickFile}>{t('msone.pick_file')}</butto`
- [ ] L219 `<button type="button" class="pc-sheet-button" onClick={() => void run(true)}>{t('msone.check_again')}</button>`
- [ ] L231 `<button type="button" class="pc-sheet-button primary" onClick={() => void run(false)}>{t('common.try_again')}<`
- [ ] L232 `<button type="button" class="pc-sheet-button" onClick={props.onPickFile}>{t('msone.pick_file')}</button>`

## webapp/src/player/sheets.tsx (13)
- [ ] L32 `<div class="pc-dismiss" onClick={props.onClose} />`
- [ ] L33 `<div ref={el} class="pc-menu" role="menu" style={place()} onKeyDown={(e) => menuKeys(e, el)}>`
- [ ] L69 `<div class="pc-dismiss" onClick={props.onClose} onContextMenu={(e) => (e.preventDefault(), props.onClose())} /`
- [ ] L70 `<div ref={el} class="pc-panel" classList={{ opening }} role="dialog" aria-label={props.title} data-shot="panel`
- [ ] L73 `<button type="button" class="pc-panel-back" aria-label={t('common.back')} onClick={props.onBack!}>`
- [ ] L78 `<button type="button" class="pc-sheet-close" aria-label={t('common.close')} onClick={props.onClose}>`
- [ ] L125 `<button type="button" role={props.checked !== undefined ? 'menuitemradio' : 'menuitem'} aria-checked={props.ch`
- [ ] L156 `<Entry text={t('common.back')} onClick={props.onBack!} />`
- [ ] L183 `<div class="pc-sheet-scrim" onClick={(e) => e.target === e.currentTarget && props.onClose()}>`
- [ ] L187 `<button type="button" class="pc-sheet-close" aria-label={t('common.back')} onClick={props.onBack!}>`
- [ ] L195 `<button type="button" class="pc-sheet-close" aria-label={t('common.close')} onClick={props.onClose}>`
- [ ] L208 `<Show when={props.phone} fallback={<Entry text={props.text} detail={props.detail} checked={props.icon ? undefi`
- [ ] L209 `<button type="button" class="pc-choice" role={props.icon ? 'button' : 'radio'} aria-checked={props.icon ? unde`

## webapp/src/screens/AddChats.tsx (15)
- [ ] L322 `<input type="search" placeholder={t('chats.search')} aria-label={t('chats.search')} value={text()} onInput={(e`
- [ ] L328 `<button class="history-tab" type="button" aria-pressed={scope() === s} onClick={() => setScope(s)}>`
- [ ] L334 `<button class="link" type="button" onClick={() => (allOn() ? setTicked(clearVisible(ticked(), visible())) : se`
- [ ] L341 `<button class="link" type="button" onClick={retry}>{t('common.try_again')}</button>`
- [ ] L363 `<button class="link" type="button" onClick={() => toggle(item.row.id)}>`
- [ ] L373 `<button class="primary" type="button" disabled={saving() || blocked()} onClick={done}>{doneLabel()}</button>`
- [ ] L379 `<button class="link" type="button" onClick={() => confirmAll.close()}>{t('common.cancel')}</button>`
- [ ] L380 `<button`
- [ ] L383 `onClick={() => {`
- [ ] L396 `<button class="link" type="button" onClick={() => discard.close()}>{t('common.cancel')}</button>`
- [ ] L397 `<button`
- [ ] L400 `onClick={() => {`
- [ ] L429 `<input type="checkbox" class="chat-tick" checked={props.on} onChange={props.onToggle} aria-label={chatName(pro`
- [ ] L446 `<button class="icon-button star" classList={{ on: on() }} type="button" aria-pressed={on()} aria-label={label(`
- [ ] L458 `<button class="link" type="button" onClick={(e) => { e.preventDefault(); props.onRetry() }}>`

## webapp/src/screens/Chat.tsx (25)
- [ ] L157 `<button`
- [ ] L162 `onClick={() => sizeDialog.showModal()}`
- [ ] L183 `<button type="button" class="nav-cell" aria-pressed={searching()} title={t('browse.search_chat')} onClick={() `
- [ ] L189 `<button`
- [ ] L195 `onClick={() => setStarred(chatId(), !starredIds().has(chatId()))}`
- [ ] L215 `<button`
- [ ] L221 `onClick={() => (searching() ? closeSearch() : setSearching(true))}`
- [ ] L225 `<button`
- [ ] L232 `onClick={() => run('refresh')}`
- [ ] L244 `<button`
- [ ] L251 `onClick={() => run('refresh')}`
- [ ] L276 `<input`
- [ ] L281 `onInput={(e) => onType(e.currentTarget.value)}`
- [ ] L282 `onKeyDown={(e) => e.key === 'Escape' && closeSearch()}`
- [ ] L296 `<button class="link" type="button" onClick={() => run(page() ? 'refresh' : 'open')}>{t('common.try_again')}</b`
- [ ] L299 `<dialog class="confirm size-picker" ref={sizeDialog} aria-labelledby="size-picker-title" onClick={(e) => e.tar`
- [ ] L304 `<button type="button" role="radio" aria-checked={settings().sizeMin === bytes} classList={{ on: settings().siz`
- [ ] L318 `<button class="link" type="button" onClick={() => sizeDialog.close()}>{t('common.cancel')}</button>`
- [ ] L337 `<button class="link" type="button" onClick={() => run('more')}>{t('common.try_again')}</button>`
- [ ] L341 `<button`
- [ ] L344 `onClick={() => {`
- [ ] L383 `<button`
- [ ] L390 `onClick={() => setOpen(!open())}`
- [ ] L402 `<button`
- [ ] L407 `onClick={() => {`

## webapp/src/screens/ChatInfo.tsx (4)
- [ ] L58 `<button type="button" class="chat-info-username" title={t('chats.info_copy_username')} onClick={() => copy('@$`
- [ ] L67 `<button type="button" class="outline-button" onClick={() => share(info()!.link!)}>`
- [ ] L71 `<button type="button" class="outline-button" onClick={() => copy(info()!.link!, t('common.link_copied'))}>{t('`
- [ ] L77 `<button type="button" class="outline-button" onClick={() => refetch()}>{t('common.try_again')}</button>`

## webapp/src/screens/Chats.tsx (5)
- [ ] L93 `<input type="search" placeholder={t('chats.search')} aria-label={t('chats.search')} value={text()} onInput={(e`
- [ ] L99 `<button class="link" type="button" onClick={refetch}>{t('common.try_again')}</button>`
- [ ] L145 `export function StarButton(props: { chatId: number; round?: boolean }) {`
- [ ] L149 `<button`
- [ ] L156 `onClick={() => setStarred(props.chatId, !on())}`

## webapp/src/screens/Detail.tsx (21)
- [ ] L76 `<button class="primary" onClick={close}>{t('common.back')}</button>`
- [ ] L82 `<button class="primary" onClick={close}>{t('common.back')}</button>`
- [ ] L269 `<button type="button" class="detail-close" aria-label={t('common.back')} title={t('common.back')} onClick={pro`
- [ ] L327 `<button type="button" class="detail-play" data-tv-first onClick={() => play()}>`
- [ ] L333 `<Round icon={ListVideoIcon} label={t('detail.episodes')} onClick={() => document.getElementById('detail-episod`
- [ ] L336 `<Round icon={RepeatIcon} label={t('detail.start_over')} onClick={() => play('start')} />`
- [ ] L338 `<Round icon={Tick02Icon} label={t('detail.round_watched')} on={!!watched()} onClick={() => setWatched(item.cha`
- [ ] L339 `<Round icon={Clock01Icon} label={t('detail.round_watch_later')} on={later()} onClick={() => setLater(item, !la`
- [ ] L341 `<Round icon={LeftToRightListBulletIcon} label={t('detail.round_list')} on={inAList()} onClick={() => openListP`
- [ ] L344 `<Round icon={Search01Icon} label={t('poster.round')} onClick={() => openPosterPicker(item, props.info?.seasons`
- [ ] L362 `<button type="button" class="link detail-more" onClick={() => setMore(!more())}>{more() ? t('common.less') : t`
- [ ] L400 `<button type="button" class="detail-round" aria-pressed={props.on === undefined ? undefined : props.on} onClic`
- [ ] L435 `<button type="button" class="detail-copy-summary" aria-expanded={open()} onClick={() => setOpen(!open())}>`
- [ ] L447 `<button type="button" role="option" class="detail-copy" aria-selected={c === here()} onClick={() => pick(c)}>`
- [ ] L541 `<Round icon={Tick02Icon} label={t('series.mark_title')} on={choices().length > 0 && choices().every((c) => !c.`
- [ ] L546 `onClick={() => {`
- [ ] L553 `<Round icon={LeftToRightListBulletIcon} label={t('lists.add_to')} onClick={() => openListPicker(props.current,`
- [ ] L556 `<dialog class="confirm card-menu" ref={marks} aria-labelledby="marks-title" onClick={(e) => e.target === marks`
- [ ] L562 `<button class="menu-row" type="button" onClick={() => pick(c)}>`
- [ ] L573 `<button class="link" type="button" onClick={() => marks.close()}>{t('common.cancel')}</button>`
- [ ] L579 `<button role="tab" class="season-chip" classList={{ on: s.number === season()?.number }} aria-selected={s.numb`

## webapp/src/screens/Hero.tsx (8)
- [ ] L220 `onPointerDown={down}`
- [ ] L225 `onKeyDown={key}`
- [ ] L249 `<button type="button" class="round-button hero-arrow" tabindex="-1" aria-label={t('home.row_scroll_back')} tit`
- [ ] L256 `<button`
- [ ] L262 `onClick={() => go(i())}`
- [ ] L268 `<button type="button" class="round-button hero-arrow" tabindex="-1" aria-label={t('home.row_scroll_on')} title`
- [ ] L295 `<img class="hero-fill" src={url} alt="" decoding="async" draggable={false} />`
- [ ] L296 `<img class="hero-frame" src={url} alt="" decoding="async" draggable={false} />`

## webapp/src/screens/Home.tsx (3)
- [ ] L94 `<button class="link" type="button" onClick={load}>{t('common.try_again')}</button>`
- [ ] L226 `<button class="round-button small" type="button" disabled={atStart()} aria-label={t('home.row_scroll_back')} t`
- [ ] L229 `<button class="round-button small" type="button" disabled={atEnd()} aria-label={t('home.row_scroll_on')} title`

## webapp/src/screens/Library.tsx (10)
- [ ] L183 `<button class="link" type="button" onClick={() => (selecting() ? leave() : setSelecting(true))}>`
- [ ] L190 `<button class="link" type="button" onClick={() => void clearHistoryWithUndo()}>`
- [ ] L195 `<button class="link" type="button" onClick={() => void removeLaterWithUndo(null)}>`
- [ ] L262 `<button class="hover-tick pick-box" type="button" aria-label={'${t('browse.select')}: ${e.item.name}'} onClick`
- [ ] L270 `<input type="checkbox" checked={picked().has(id)} onClick={(ev) => tick(id, ev.shiftKey)} onChange={(ev) => (e`
- [ ] L287 `<button class="link" type="button" onClick={() => setPicked(toggleAll(picked(), order()))}>`
- [ ] L290 `<button class="primary danger" type="button" disabled={!picked().size} onClick={remove}>`
- [ ] L300 `<button class="link" type="button" onClick={() => confirmDialog.close()}>{t('common.cancel')}</button>`
- [ ] L301 `<button`
- [ ] L304 `onClick={() => {`

## webapp/src/screens/Lists.tsx (20)
- [ ] L72 `<dialog class="confirm card-menu" ref={dialog} aria-label={t('lists.add_to')} onClose={close} onClick={(e) => `
- [ ] L82 `<input type="text" maxLength={NAME_MAX} value={name()} onInput={(e) => setName(e.currentTarget.value)} autofoc`
- [ ] L86 `<button class="link" type="button" onClick={close}>{t('common.cancel')}</button>`
- [ ] L87 `<button class="primary" type="submit" disabled={!cleanName(name())}>{t('lists.create')}</button>`
- [ ] L101 `<button class="menu-row" type="button" onClick={() => pick(list.info.id, list.info.name, inIt())}>`
- [ ] L112 `<button class="menu-row" type="button" onClick={() => setNaming(true)}>`
- [ ] L121 `<button class="link" type="button" onClick={close}>{t('common.cancel')}</button>`
- [ ] L173 `<button class="history-tab" type="button" onClick={() => (setName(''), making.showModal())}>`
- [ ] L183 `<button class="outline-button" type="button" onClick={() => (setName(list().info.name), renaming.showModal())}`
- [ ] L185 `<input type="checkbox" checked={list().info.home} onChange={(e) => setListHome(list().info.id, e.currentTarget`
- [ ] L188 `<button class="outline-button" type="button" onClick={() => deleting.showModal()}>`
- [ ] L199 `<button class="link small" type="button" onClick={() => remove(tile)}>{t('lists.remove')}</button>`
- [ ] L205 `<dialog class="confirm" ref={deleting} aria-labelledby="list-delete-title" onClick={(e) => e.target === deleti`
- [ ] L209 `<button class="link" type="button" onClick={() => deleting.close()}>{t('common.cancel')}</button>`
- [ ] L210 `<button`
- [ ] L213 `onClick={() => {`
- [ ] L254 `<dialog class="confirm" ref={(d) => ((dialog = d), props.ref(d))} onClick={(e) => e.target === dialog && dialo`
- [ ] L266 `<input type="text" maxLength={NAME_MAX} value={props.name} onInput={(e) => props.onName(e.currentTarget.value)`
- [ ] L270 `<button class="link" type="button" onClick={() => dialog.close()}>{t('common.cancel')}</button>`
- [ ] L271 `<button class="primary" type="submit" disabled={!cleanName(props.name)}>{props.confirm}</button>`

## webapp/src/screens/Recent.tsx (2)
- [ ] L110 `<button class="link" type="button" onClick={retry}>{t('common.try_again')}</button>`
- [ ] L124 `<button class="link" type="button" onClick={() => run('more')}>{t('common.try_again')}</button>`

## webapp/src/screens/Search.tsx (4)
- [ ] L89 `<input ref={input} type="search" placeholder={t('search.field_hint')} aria-label={t('search.field_hint')} valu`
- [ ] L91 `<button class="search-clear" type="button" aria-label={t('browse.clear_search')} title={t('browse.clear_search`
- [ ] L99 `<button class="link" type="button" onClick={refetch}>{t('common.try_again')}</button>`
- [ ] L120 `<button class="link" type="button" onClick={() => setEveryChatFor(params.q!.trim())}>{t('search.other_chats')}`
