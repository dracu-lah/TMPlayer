import { createSignal, For, Match, onCleanup, Show, Switch } from 'solid-js'
import { A, useLocation } from '@solidjs/router'
import { Clock01Icon, StarIcon } from '@hugeicons/core-free-icons'
import { settings, setSetting } from '../../store'
import { me } from '../../session'
import { t } from '../../i18n'
import { clearHistoryWithUndo } from '../../clearHistory'
import { layoutFixed } from '../../form'
import { msoneOn } from '../../msoneFlag'
import { setSyncOn, statusLine, syncNow, syncOn, syncRunning, syncStatus } from '../../sync'
import { openFeedback } from '../../ui/FeedbackForm'
import { TmdbCredit, TvPanel, TvSwitch } from '../../ui/SettingsParts'
import { rowFor, type PageId } from '../../ui/settingsMap'
import { AboutNews, rowDomId } from '../SettingsFind'
import { HOME_SWITCHES, HomeOrder } from './Home'
import { LanguageBlock } from './Language'
import { Rewind, TvMsone } from './Playback'
import { SignOut } from './SignOut'
import { VersionFooter } from '../../ui/VersionFooter'
import { ProfileMenuButton, RemoveAllButton } from '../../ui/Profiles'
import { Install, StorageBlock } from './Storage'
import { chosenDetail, LayoutChoices, LICENCES, ThemeChoices } from './shared'

/** The pages of the TV's Settings, in the apps' order (ui/settingsMap.ts PAGE_IDS): the ones the web has rows for. */
const RAIL: { id: PageId; title: () => string }[] = [
    { id: 'account', title: () => t('settings.group_account') },
    { id: 'playback', title: () => t('settings.playback') },
    { id: 'subtitles', title: () => t('settings.page_subtitles_audio') },
    { id: 'storage', title: () => t('settings.page_downloads_data') },
    { id: 'appearance', title: () => t('settings.page_appearance') },
    { id: 'home', title: () => t('settings.page_home_library') },
    { id: 'help', title: () => t('settings.help') },
    { id: 'about', title: () => t('settings.group_about') },
]

/** The pane follows the rail's focus after this long, so a remote passing down the rail does not draw every page (the TV app's rail, WP-07). */
export const FOLLOW_MS = 150

/** The row a hash names (an address from a search, an old link) says which page opens first. */
export function firstPage(hash: string): PageId {
    let name = hash.replace(/^#/, '')
    try {
        name = decodeURIComponent(name)
    } catch {}
    const page = rowFor(name.replace(/^row-/, ''))?.page
    return page && RAIL.some((r) => r.id === page) ? page : 'account'
}

const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]), [tabindex]:not([tabindex="-1"])'

/**
 * The TV layout (WP-88): a rail of the apps' pages on the left and the page's panels in the pane beside
 * it, as the Android TV app has them (WP-07). The remote walks the rail with Up and Down and the pane
 * follows after FOLLOW_MS; Right enters the pane, Left on the pane's first column goes back to the rail's
 * chosen page, which stays marked, and Back from the pane lands on the rail before it leaves Settings
 * (tv/focus.ts reads `data-tv-back-to`). A header with the breadcrumb stays at the top.
 */
export function TvSettings() {
    const location = useLocation()
    const [page, setPage] = createSignal<PageId>(firstPage(location.hash))
    let timer = 0
    onCleanup(() => clearTimeout(timer))
    // The remote passing an item selects its page once it rests there; OK selects at once. Focus leaving the item (a row an
    // address names, taking the remote in the first moment) drops the page it was about to show: the address's page stays.
    const follow = (id: PageId, now = false) => {
        clearTimeout(timer)
        if (now) setPage(id)
        else timer = window.setTimeout(() => setPage(id), FOLLOW_MS)
    }
    let pane!: HTMLDivElement
    // Keys the walker offers the item first (focus.ts `tvmove`): Right from the rail enters the pane, Left from the pane's first column returns to the rail.
    const railMove = (e: Event) => {
        if ((e as CustomEvent<string>).detail !== 'right') return
        const id = (e.currentTarget as HTMLElement).dataset.rail as PageId
        follow(id, true)
        e.preventDefault()
        queueMicrotask(() => pane.querySelector<HTMLElement>(FOCUSABLE)?.focus())
    }
    const paneMove = (e: Event) => {
        if ((e as CustomEvent<string>).detail !== 'left') return
        const from = e.target as HTMLElement
        const at = from.getBoundingClientRect()
        const another = [...pane.querySelectorAll<HTMLElement>(FOCUSABLE)].some((el) => {
            if (el === from) return false
            const r = el.getBoundingClientRect()
            return r.width > 0 && r.right <= at.left + 2 && r.bottom > at.top + at.height * 0.2 && r.top < at.bottom - at.height * 0.2
        })
        if (another) return
        e.preventDefault()
        document.querySelector<HTMLElement>('.tv-rail [aria-current=page]')?.focus()
    }
    const title = () => RAIL.find((r) => r.id === page())!.title()
    return (
        <section class="settings tv-set">
            <header class="tv-set-head">
                <h1 class="page-title">{t('nav.settings')}</h1>
                {/* The breadcrumb: where the pane is. */}
                <p class="tv-crumb muted" aria-live="polite">
                    {t('nav.settings')} <span aria-hidden="true">{'›'}</span> {title()}
                </p>
            </header>
            <nav class="tv-rail" aria-label={t('nav.settings')}>
                <For each={RAIL}>
                    {(r) => (
                        <button
                            type="button"
                            class="tv-rail-item"
                            data-rail={r.id}
                            aria-current={page() === r.id ? 'page' : undefined}
                            onFocus={() => follow(r.id)}
                            onBlur={() => clearTimeout(timer)}
                            onClick={() => follow(r.id, true)}
                            ref={(el) => el.addEventListener('tvmove', railMove, true)}
                        >
                            {r.title()}
                        </button>
                    )}
                </For>
            </nav>
            <div class="tv-pane" ref={(el) => ((pane = el), el.addEventListener('tvmove', paneMove, true))} data-tv-back-to=".tv-rail [aria-current=page]" role="region" aria-label={title()}>
                <Switch>
                    <Match when={page() === 'account'}>
                        {/* The version, the build and the update state, read only: the account menu's last row is the TV's copy target (WP-104). */}
                        <VersionFooter look="text" />
                        <div class="panel">
                            <h2 class="panel-title">{t('settings.account')}</h2>
                            <Show when={me()?.name}>
                                <p>{t('settings.signed_in_as', { name: me()!.name! })}</p>
                            </Show>
                            <p class="muted small">{t('auth.unofficial')}</p>
                            <a class="link" href="https://tmplayer.org/legal" target="_blank" rel="noopener">{t('login.legal')}</a>
                            <ProfileMenuButton />
                            <SignOut />
                            <RemoveAllButton />
                        </div>
                        <TvSwitch id="sync" row="resume_sync" title={t('syncplace.switch')} on={syncOn()} onText={t('settings.resume_sync_on')} offText={t('settings.resume_sync_off')} onPick={(on) => void setSyncOn(on)} />
                        <p class="muted small">{t('syncplace.privacy')}</p>
                        <Show when={syncOn()}>
                            <p class="muted small" role="status">{statusLine()}</p>
                            <button class="outline-button" style={{ 'align-self': 'start' }} type="button" disabled={syncRunning()} onClick={() => void syncNow()}>{!syncRunning() && syncStatus()?.kind === 'failed' ? t('common.try_again') : t('settings.resume_sync_now')}</button>
                        </Show>
                    </Match>
                    <Match when={page() === 'playback'}>
                        <TvPanel title="" id="rewind-panel">
                            <Rewind />
                        </TvPanel>
                    </Match>
                    <Match when={page() === 'subtitles'}>
                        <Show when={msoneOn()} fallback={<p class="muted small">{t('settings.subtitle_style')}</p>}>
                            <TvMsone />
                        </Show>
                    </Match>
                    <Match when={page() === 'storage'}>
                        <Install />
                        <div class="panel">
                            <StorageBlock />
                        </div>
                    </Match>
                    <Match when={page() === 'appearance'}>
                        <div class="panel">
                            <LanguageBlock />
                        </div>
                        <TvPanel title={t('settings.theme')} id="theme-label" anchor={rowDomId('theme')}>
                            <ThemeChoices />
                        </TvPanel>
                        <Show when={!layoutFixed()}>
                            <TvPanel title={t('settings.layout')} id="layout-label" anchor={rowDomId('layout')}>
                                <LayoutChoices />
                            </TvPanel>
                        </Show>
                    </Match>
                    <Match when={page() === 'home'}>
                        <TvPanel title={t('picker.row_title')} id="add-chats-label" anchor={rowDomId('chosen_chats')}>
                            <p class="muted small">{chosenDetail()}</p>
                            <A class="outline-button" style={{ 'align-self': 'start' }} href="/added-chats">{t('picker.choose_chats')}</A>
                        </TvPanel>
                        <For each={HOME_SWITCHES}>
                            {(s) => <TvSwitch id={s.id} row={s.row} title={t(s.title)} on={settings()[s.key]} onText={t(s.on)} offText={t(s.off)} onPick={(on) => setSetting(s.key, on)} />}
                        </For>
                        <p class="muted small">{t('settings.home_follows_added')}</p>
                        <HomeOrder />
                        <TvPanel title={t('browse.clear_history')} id="history-label" anchor={rowDomId('clear_history')}>
                            <p class="muted small">{t('browse.clear_history_detail')}</p>
                            <button class="outline-button" style={{ 'align-self': 'start' }} type="button" aria-labelledby="history-label" onClick={() => void clearHistoryWithUndo()}>
                                {t('browse.clear_history')}
                            </button>
                        </TvPanel>
                        <TvSwitch id="posters" row="posters" title={t('web.posters_title')} on={settings().posters} onText={t('web.posters_on_detail')} offText={t('metadata.toggle_off')} onPick={(on) => setSetting('posters', on)}>
                            <TmdbCredit />
                        </TvSwitch>
                        <TvSwitch id="trailers" row="trailers_in_app" title={t('metadata.trailers_in_app')} on={settings().trailersInApp} onText={t('metadata.trailers_in_app_detail')} offText={t('metadata.trailers_in_app_detail')} onPick={(on) => setSetting('trailersInApp', on)} />
                    </Match>
                    <Match when={page() === 'help'}>
                        <TvPanel title={t('feedback.title')} id="feedback-label" anchor={rowDomId('report_problem')}>
                            <p class="muted small">{t('feedback.tv_phone_hint')}</p>
                            <button class="outline-button" style={{ 'align-self': 'start' }} type="button" onClick={(e) => openFeedback('problem', e.currentTarget)}>
                                {t('settings.report_problem')}
                            </button>
                            <button class="outline-button" id={rowDomId('request_feature')} style={{ 'align-self': 'start' }} type="button" onClick={(e) => openFeedback('feature', e.currentTarget)}>
                                {t('web.request_feature')}
                            </button>
                        </TvPanel>
                        <div class="panel">
                            <AboutNews variant="tv" icon={StarIcon} changelogIcon={Clock01Icon} />
                        </div>
                    </Match>
                    <Match when={page() === 'about'}>
                        <div class="panel">
                            <h2 class="panel-title">{t('about.notices_title')}</h2>
                            <VersionFooter look="text" />
                            <p class="muted small">{t('about.licence_line')}</p>
                            <p class="muted small">{t('about.notices_detail')}</p>
                            <a class="link" id={rowDomId('notices')} href={LICENCES} target="_blank" rel="noopener">{t('about.read')}</a>
                        </div>
                    </Match>
                </Switch>
            </div>
        </section>
    )
}
