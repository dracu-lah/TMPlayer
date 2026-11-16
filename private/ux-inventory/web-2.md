# web-2: 107 controls in 13 files

Tick a line only after every dimension in private/UX-INTERACTION-PLAN.md was checked for it.

## webapp/src/screens/Settings.tsx (32)
- [ ] L131 `<button class="round-button" type="button" aria-label={t('common.back')} title={t('common.back')} onClick={() `
- [ ] L152 `function BackButton() {`
- [ ] L155 `<button class="round-button" type="button" aria-label={t('common.back')} title={t('common.back')} onClick={() `
- [ ] L251 `<button type="button" role="switch" class="set-row" aria-checked={props.on} onClick={() => props.onChange(!pro`
- [ ] L328 `<button type="button" class="set-row" aria-busy={m.checking()} onClick={() => void m.check(true)}>`
- [ ] L342 `<button class="outline-button" style={{ 'align-self': 'start' }} type="button" aria-labelledby="msone-label" o`
- [ ] L437 `draggable={form() === 'desktop'}`
- [ ] L452 `<button type="button" class="order-move" aria-label={'${t('settings.home_order_up')}: ${homeOrderTitle(row)}'}`
- [ ] L455 `<button type="button" class="order-move" aria-label={'${t('settings.home_order_down')}: ${homeOrderTitle(row)}`
- [ ] L464 `<button class="outline-button order-reset" type="button" onClick={() => put([])}>`
- [ ] L473 `function TvSwitch(props: { id: string; title: string; on: boolean; onText: string; offText: string; onPick: (o`
- [ ] L521 `<button type="button" class="set-row" onClick={() => void clearHistoryWithUndo()}>`
- [ ] L596 `<button class="set-row" type="button" onClick={open}>`
- [ ] L614 `onClick={(e) => { if (e.target === dialog) dialog.close() }}`
- [ ] L622 `<button class="link" type="button" onClick={copy}>{copied() ? t('support.upi_copied') : t('support.upi_copy')}`
- [ ] L623 `<button class="primary" type="button" onClick={() => dialog.close()}>{t('common.close')}</button>`
- [ ] L638 `<input type="radio" name={props.name} checked={props.value === o.value} onChange={() => props.onPick(o.value)}`
- [ ] L696 `<input`
- [ ] L705 `onInput={(e) => pick('min', nearest(Number(e.currentTarget.value) / 1000))}`
- [ ] L706 `onKeyDown={(e) => keys('min', e)}`
- [ ] L708 `<input`
- [ ] L715 `onInput={(e) => pick('max', nearest(Number(e.currentTarget.value) / 1000))}`
- [ ] L716 `onKeyDown={(e) => keys('max', e)}`
- [ ] L722 `<button`
- [ ] L725 `onClick={() => {`
- [ ] L780 `<button class="primary" style={{ 'align-self': 'start' }} type="button" onClick={install}>{t('web.install_butt`
- [ ] L824 `<button class="link" style={{ 'align-self': 'start' }} type="button" disabled={clearing()} onClick={clear}>{t(`
- [ ] L833 `<button class="link" style={{ 'align-self': 'start' }} type="button" onClick={keepData}>{t('web.keep_storage')`
- [ ] L850 `<button class="danger-button set-signout" type="button" onClick={() => dialog.showModal()}>`
- [ ] L858 `<button class="link" type="button" disabled={leaving()} onClick={() => dialog.close()}>{t('common.cancel')}</b`
- [ ] L859 `<button class="primary danger" type="button" disabled={leaving()} onClick={confirm}>`
- [ ] L910 `<button class="outline-button" style={{ 'align-self': 'start' }} type="button" aria-labelledby="history-label"`

## webapp/src/screens/SignIn.tsx (30)
- [ ] L495 `<button type="button" class="qr-refresh on-white" onClick={refreshQr}>`
- [ ] L516 `<button type="button" class="link" onClick={() => (stopQr(), go({ kind: 'phone' }))}>{t('login.use_phone')}</b`
- [ ] L517 `<button type="button" class="qr-refresh" onClick={refreshQr} disabled={refreshing()}>`
- [ ] L531 `<button type="button" class="country-row" classList={{ invalid: !knownCode() && !!dial() }} onClick={() => pic`
- [ ] L538 `<input`
- [ ] L545 `onInput={(e) => onDial(e.currentTarget)}`
- [ ] L548 `<input`
- [ ] L558 `onInput={(e) => onNat(e.currentTarget)}`
- [ ] L565 `<button class="primary" disabled={!ready() || busy() || !!waitLeft()}>{sendWords(t('login.send_code'), t('logi`
- [ ] L568 `<button class="link alt" onClick={() => (go({ kind: 'qr' }), startQr())}>{t('login.use_qr')}</button>`
- [ ] L577 `<input`
- [ ] L589 `onInput={(e) => onCode(e.currentTarget)}`
- [ ] L595 `<button class="primary" disabled={busy() || !codeText().trim() || !!waitLeft()}>{sendWords(t('login.sign_in'),`
- [ ] L599 `<button class="link" disabled={busy() || !!resendLeft()} onClick={resend}>`
- [ ] L604 `<button class="link" onClick={changeNumber}>{t('login.wrong_number')}</button>`
- [ ] L605 `<button class="link" onClick={startOver}>{t('login.start_over')}</button>`
- [ ] L615 `<input`
- [ ] L621 `onInput={(e) => (setEmail(e.currentTarget.value), setError(null))}`
- [ ] L624 `<button class="primary" disabled={busy() || !email().includes('@') || !!waitLeft()}>{sendWords(t('login.email_`
- [ ] L628 `<button class="link" onClick={changeNumber}>{t('login.wrong_number')}</button>`
- [ ] L629 `<button class="link" onClick={startOver}>{t('login.start_over')}</button>`
- [ ] L640 `<input`
- [ ] L646 `onInput={(e) => (setPassword(e.currentTarget.value), setError(null))}`
- [ ] L649 `<button`
- [ ] L653 `onClick={() => setShowPassword(!showPassword())}`
- [ ] L661 `<button class="primary" disabled={busy() || !password() || !!waitLeft()}>{sendWords(t('login.sign_in'), t('log`
- [ ] L664 `<button class="link" onClick={startOver}>{t('login.start_over')}</button>`
- [ ] L707 `<input type="search" aria-label={t('login.country_search')} placeholder={t('login.country_search')} value={que`
- [ ] L708 `<button type="button" class="icon-button" aria-label={t('login.close_country_list')} onClick={() => dialog.clo`
- [ ] L716 `<button type="button" onClick={() => props.onPick(c)}>`

## webapp/src/screens/Sponsored.tsx (5)
- [ ] L92 `<button class="sponsored-art" type="button" tabIndex={-1} aria-hidden="true" onClick={() => open(true)}>`
- [ ] L118 `<button class="tonal" type="button" onClick={() => open(false)}>{props.item.buttonText}</button>`
- [ ] L121 `<button class="link" type="button" disabled={busy()} onClick={() => report()}>{t('sponsored.report')}</button>`
- [ ] L129 `<button type="button" disabled={busy()} onClick={() => report(o.option)}>{o.text}</button>`
- [ ] L134 `<button class="link" type="button" onClick={() => dialog.close()}>{t('common.cancel')}</button>`

## webapp/src/screens/VideoList.tsx (2)
- [ ] L135 `<span class="card-play" title={t('common.play')} onClick={(e) => props.onPlay!(e)}>`
- [ ] L219 `onPointerDown: (e: PointerEvent) => e.pointerType === 'mouse' && warmFile(item),`

## webapp/src/screens/Watch.tsx (2)
- [ ] L82 `<button class="primary" onClick={close}>{t('common.back')}</button>`
- [ ] L88 `<button class="primary" onClick={close}>{t('common.back')}</button>`

## webapp/src/tv/Keypad.tsx (3)
- [ ] L22 `<button type="button" class="key" onClick={() => props.onDigit(d)}>{d}</button>`
- [ ] L25 `<button type="button" class="key" aria-label={t('common.delete')} onClick={props.onDelete}>`
- [ ] L28 `<button type="button" class="key" onClick={() => props.onDigit('0')}>0</button>`

## webapp/src/tv/index.tsx (1)
- [ ] L109 `<button type="button" class="tv-fullscreen" onClick={toggle}>`

## webapp/src/ui/CardMenu.tsx (9)
- [ ] L132 `<dialog class="confirm card-menu" ref={dialog} aria-label={t('detail.open_details')} onClose={close} onClick={`
- [ ] L137 `<button class="menu-row" type="button" onClick={() => go('/watch/${now().item.chatId}/${now().item.messageId}'`
- [ ] L141 `<button class="menu-row" type="button" onClick={() => go('/video/${now().item.chatId}/${now().item.messageId}'`
- [ ] L145 `<button class="menu-row" type="button" onClick={toggleWatched}>`
- [ ] L149 `<button class="menu-row" type="button" onClick={toggleLater}>`
- [ ] L153 `<button class="menu-row" type="button" onClick={pickList}>`
- [ ] L160 `<button class="menu-row" type="button" onClick={choosePoster}>`
- [ ] L168 `<button class="menu-row" type="button" onClick={remove}>`
- [ ] L177 `<button class="link" type="button" onClick={close}>{t('common.cancel')}</button>`

## webapp/src/ui/ChooseChatsButton.tsx (1)
- [ ] L11 `export function ChooseChatsButton(props: { class?: string; size?: number }) {`

## webapp/src/ui/Nav.tsx (12)
- [ ] L68 `<button`
- [ ] L77 `onClick={() => setOpen(!open())}`
- [ ] L151 `<div ref={card} class="you-menu" role="menu" aria-label={t('nav.you')} style={place()} onKeyDown={onKey}>`
- [ ] L155 `<Count value={counts()?.watching ?? 0} label={t('profile.watching')} onClick={() => go('/history/continue')} /`
- [ ] L156 `<Count value={counts()?.watched ?? 0} label={t('profile.watched')} onClick={() => go('/history/watched')} />`
- [ ] L157 `<Count value={starredIds().size} label={t('profile.starred')} onClick={() => go('/favourites')} />`
- [ ] L161 `<Row icon={StarIcon} label={t('browse.tab_favourites')} badge={starredIds().size || undefined} onClick={() => `
- [ ] L162 `<Row icon={History} label={t('browse.tab_history')} onClick={() => go('/history')} />`
- [ ] L163 `<Row icon={Clock01Icon} label={t('browse.tab_watch_later')} onClick={() => go('/history/later')} />`
- [ ] L165 `<Row icon={Settings01Icon} label={t('nav.settings')} shortcut={form() === 'desktop' ? '${command},' : undefine`
- [ ] L172 `<button type="button" class="you-count" role="menuitem" aria-label={'${props.value} ${props.label}'} onClick={`
- [ ] L181 `<button type="button" class="you-row" role="menuitem" onClick={props.onClick}>`

## webapp/src/ui/PageBar.tsx (1)
- [ ] L16 `<button class="round-button" type="button" aria-label="Back" title="Back" onClick={() => back(navigate)}>`

## webapp/src/ui/PosterPicker.tsx (7)
- [ ] L164 `onClick={(e) => e.target === dialog && close()}`
- [ ] L165 `onKeyDown={walk}`
- [ ] L174 `<input type="checkbox" checked={alsoOthers()} onChange={(e) => setAlsoOthers(e.currentTarget.checked)} />`
- [ ] L190 `<button class="link" type="button" onClick={() => setAttempt(attempt() + 1)}>{t('common.try_again')}</button>`
- [ ] L197 `<button class="choice-row" type="button" aria-current={isCurrent(c) ? 'true' : undefined} onClick={() => choos`
- [ ] L219 `<button class="link" type="button" onClick={reset}>{t('poster.reset')}</button>`
- [ ] L221 `<button class="link" type="button" onClick={close}>{t('common.cancel')}</button>`

## webapp/src/ui/Toast.tsx (2)
- [ ] L44 `<button`
- [ ] L47 `onClick={() => {`
