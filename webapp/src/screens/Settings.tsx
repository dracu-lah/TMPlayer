import { createEffect, createResource, createSignal, For, Match, Show, Switch, type JSX } from 'solid-js'
import { A, useNavigate, useParams } from '@solidjs/router'
import {
    ArrowLeft02Icon,
    ArrowRight01Icon,
    Delete02Icon,
    Download04Icon,
    GridViewIcon,
    HardDriveIcon,
    File01Icon,
    Home03Icon,
    Image01Icon,
    InformationCircleIcon,
    LegalDocument01Icon,
    Logout05Icon,
    PaintBoardIcon,
    PlayIcon,
} from '@hugeicons/core-free-icons'
import { settings, setSetting, SIZE_DEFAULTS, storageWorks, type Theme } from '../store'
import { back, me, signOut } from '../session'
import { api } from '../tg/rpc'
import { t } from '../i18n'
import { sizeScale } from './sizes'
import { clearHistoryWithUndo } from '../clearHistory'
import { size } from './VideoList'
import { form, layout, setLayout, type Layout } from '../form'
import { Icon, type IconData } from '../ui/Icon'
import { TmdbMark } from '../ui/TmdbMark'
import { initialOf } from '../ui/initial'
import { install, installed, installPrompt, iosBrowser, keepData, kept, refused } from '../install'

const THEMES: Theme[] = ['system', 'dark', 'light', 'black']
const LAYOUTS: Layout[] = ['desktop', 'tv']

type PageId = 'appearance' | 'playback' | 'library' | 'storage' | 'about'

/** The app's pages (SettingsPage) that the web has something to put in, in its order. */
const PAGES: { id: PageId; icon: IconData; title: () => string; summary: () => string }[] = [
    {
        id: 'appearance',
        icon: PaintBoardIcon,
        title: () => t('settings.appearance'),
        summary: () => (form() === 'phone' ? t(`theme.${settings().theme}`) : `${t(`theme.${settings().theme}`)}${dot}${t(`settings.layout_${layout()}`)}`),
    },
    { id: 'playback', icon: PlayIcon, title: () => t('settings.playback'), summary: () => t('settings.resume_rewind') },
    { id: 'library', icon: GridViewIcon, title: () => t('settings.library'), summary: () => t('settings.page_library_summary') },
    { id: 'storage', icon: HardDriveIcon, title: () => t('web.settings_storage'), summary: () => t('web.settings_storage_summary') },
    { id: 'about', icon: InformationCircleIcon, title: () => t('settings.page_about'), summary: () => t('about.licence_line') },
]
const dot = '  ·  '

/**
 * Settings, as the app lays it out: a list of pages (Appearance, Playback, Library, Storage, About)
 * with the account over it and Sign out under it, each page its own address under `/settings`. A
 * phone takes the list and a page in turn, the desktop shows the list beside the page (the apps'
 * two panes); the TV keeps the one long page of choices its remote walks.
 */
export function Settings() {
    const params = useParams<{ page?: string }>()
    const navigate = useNavigate()
    // The desktop shows a page beside the list: its address says which, so a change of layout keeps the viewer on it.
    // The TV has one page and no sub pages: it drops the address and remembers the page for the way back out of it.
    createEffect(() => {
        const f = form()
        if (f === 'tv') {
            if (params.page) {
                leftFor = PAGES.find((p) => p.id === params.page)?.id ?? null
                navigate('/settings', { replace: true })
            }
        } else if (!PAGES.some((p) => p.id === params.page)) {
            const back = leftFor
            leftFor = null
            if (f === 'desktop' || back) navigate(`/settings/${back ?? PAGES[0].id}`, { replace: true })
        }
    })
    return (
        <Show when={form() !== 'tv'} fallback={<TvSettings />}>
            <Pages />
        </Show>
    )
}

/** The page the viewer was on when the TV layout took Settings over. */
let leftFor: PageId | null = null

function Pages() {
    const params = useParams<{ page?: string }>()
    const navigate = useNavigate()
    const current = () => PAGES.find((p) => p.id === params.page) ?? null
    const wide = () => form() === 'desktop'
    const shown = () => current() ?? (wide() ? PAGES[0] : null)
    return (
        <section class="set" classList={{ wide: wide() }}>
            <Switch>
                <Match when={wide()}>
                    <header class="set-head">
                        <h1 class="page-title">{t('nav.settings')}</h1>
                        <p class="muted">{t('settings.saves_as_you_go')}</p>
                    </header>
                    <div class="set-panes">
                        <PageList selected={shown()?.id ?? null} />
                        <div class="set-page" id="set-page">
                            <h2 class="set-page-title">{shown()!.title()}</h2>
                            <PageBody id={shown()!.id} />
                        </div>
                    </div>
                </Match>
                <Match when={current()}>
                    {(page) => (
                        <>
                            <header class="set-bar">
                                <button class="round-button" type="button" aria-label={t('common.back')} title={t('common.back')} onClick={() => navigate('/settings', { replace: true })}>
                                    <Icon icon={ArrowLeft02Icon} size={22} />
                                </button>
                                <h1>{page().title()}</h1>
                            </header>
                            <PageBody id={page().id} />
                        </>
                    )}
                </Match>
                <Match when={true}>
                    <header class="set-bar">
                        <BackButton />
                        <h1>{t('nav.settings')}</h1>
                    </header>
                    <PageList selected={null} />
                </Match>
            </Switch>
        </section>
    )
}

function BackButton() {
    const navigate = useNavigate()
    return (
        <button class="round-button" type="button" aria-label={t('common.back')} title={t('common.back')} onClick={() => back(navigate)}>
            <Icon icon={ArrowLeft02Icon} size={22} />
        </button>
    )
}

/** The account, one row for each page, and Sign out last: under the card, as the apps have it. */
function PageList(props: { selected: PageId | null }) {
    return (
        <nav class="set-list" aria-label={t('nav.settings')}>
            <AccountHeader />
            <div class="set-card">
                <For each={PAGES}>
                    {(p) => (
                        <A class="set-row" href={`/settings/${p.id}`} aria-current={props.selected === p.id ? 'page' : undefined}>
                            <RowBody icon={p.icon} title={p.title()} subtitle={p.summary()} />
                            <Show when={props.selected === null}>
                                <Icon icon={ArrowRight01Icon} size={20} class="set-chevron" />
                            </Show>
                        </A>
                    )}
                </For>
            </div>
            <SignOut />
            <p class="muted small set-foot">{t('auth.unofficial')}</p>
        </nav>
    )
}

/** The signed in account over the list: a picture (the first letter), the name and @username, on the page's own ground. */
function AccountHeader() {
    return (
        <Show when={me()?.name}>
            <div class="set-account">
                <span class="initial" aria-hidden="true">{initialOf(me()!.name)}</span>
                <span class="set-text">
                    <span class="set-title">{me()!.name}</span>
                    <Show when={me()?.username}>
                        <span class="set-sub" dir="ltr">@{me()!.username}</span>
                    </Show>
                </span>
            </div>
        </Show>
    )
}

function PageBody(props: { id: PageId }) {
    return (
        <Switch>
            <Match when={props.id === 'appearance'}>
                <Appearance />
            </Match>
            <Match when={props.id === 'playback'}>
                <Playback />
            </Match>
            <Match when={props.id === 'library'}>
                <Library />
            </Match>
            <Match when={props.id === 'storage'}>
                <StoragePage />
            </Match>
            <Match when={props.id === 'about'}>
                <About />
            </Match>
        </Switch>
    )
}

// What a row is made of

function RowBody(props: { icon: IconData; title: string; subtitle?: string }) {
    return (
        <>
            <span class="set-icon">
                <Icon icon={props.icon} size={22} />
            </span>
            <span class="set-text">
                <span class="set-title">{props.title}</span>
                <Show when={props.subtitle}>
                    <span class="set-sub">{props.subtitle}</span>
                </Show>
            </span>
        </>
    )
}

/** An on or off row: the title, what it does, and the app's switch at the end. */
function ToggleRow(props: { icon: IconData; title: string; subtitle: string; on: boolean; onChange: (on: boolean) => void }) {
    return (
        <button type="button" role="switch" class="set-row" aria-checked={props.on} onClick={() => props.onChange(!props.on)}>
            <RowBody icon={props.icon} title={props.title} subtitle={props.subtitle} />
            <span class="switch" aria-hidden="true" />
        </button>
    )
}

// Pages

function Appearance() {
    return (
        <>
            <div class="set-card set-blocks">
                <div class="set-block">
                    <h3 class="set-block-title" id="theme-label">{t('settings.theme')}</h3>
                    <Choices
                        label="theme-label"
                        name="theme"
                        options={THEMES.map((v) => ({ value: v, label: t(`theme.${v}`) }))}
                        value={settings().theme}
                        onPick={(v) => setSetting('theme', v)}
                    />
                </div>
                <Show when={form() !== 'phone'}>
                <div class="set-block">
                    <h3 class="set-block-title" id="layout-label">{t('settings.layout')}</h3>
                    <Choices
                        label="layout-label"
                        name="layout"
                        options={LAYOUTS.map((v) => ({ value: v, label: t(`settings.layout_${v}`) }))}
                        value={layout()}
                        onPick={setLayout}
                    />
                    <p class="muted small">{t('settings.layout_detail_desktop')}</p>
                </div>
                </Show>
            </div>
        </>
    )
}

function Playback() {
    return (
        <div class="set-card set-blocks">
            <Rewind />
        </div>
    )
}

function Library() {
    return (
        <>
            <div class="set-card">
                <ToggleRow
                    icon={Home03Icon}
                    title={t('settings.home_carousel')}
                    subtitle={settings().carousel ? t('settings.home_carousel_on') : t('settings.home_carousel_off')}
                    on={settings().carousel}
                    onChange={(on) => setSetting('carousel', on)}
                />
                <ToggleRow
                    icon={Image01Icon}
                    title={t('web.posters_title')}
                    subtitle={settings().posters ? t('web.posters_on_detail') : t('metadata.toggle_off')}
                    on={settings().posters}
                    onChange={(on) => setSetting('posters', on)}
                />
                <div class="set-block set-credit">
                    <a class="tmdb-credit" href="https://www.themoviedb.org/" target="_blank" rel="noopener" aria-label="TMDB">
                        <TmdbMark height={14} />
                    </a>
                    <p class="muted small">{t('metadata.about_notice')}</p>
                </div>
            </div>
            <div class="set-card set-blocks" id="size">
                <SizeLimits />
            </div>
            <div class="set-card">
                {/* One row for Continue watching and Watched together; Watch later is kept. Done at once, with Undo. */}
                <button type="button" class="set-row" onClick={() => void clearHistoryWithUndo()}>
                    <RowBody icon={Delete02Icon} title={t('browse.clear_history')} subtitle={t('browse.clear_history_detail')} />
                </button>
            </div>
        </>
    )
}

function StoragePage() {
    return (
        <>
            <Install />
            <div class="set-card set-blocks">
                <StorageBlock />
            </div>
        </>
    )
}

function About() {
    return (
        <div class="set-card">
            <a class="set-row" href="/licenses.txt" target="_blank" rel="noopener">
                <RowBody icon={File01Icon} title={t('about.notices_title')} subtitle={t('about.notices_detail')} />
                <Icon icon={ArrowRight01Icon} size={20} class="set-chevron" />
            </a>
            <a class="set-row" href="https://tmplayer.org/legal" target="_blank" rel="noopener">
                <RowBody icon={LegalDocument01Icon} title={t('login.legal')} subtitle={t('auth.unofficial')} />
                <Icon icon={ArrowRight01Icon} size={20} class="set-chevron" />
            </a>
            <div class="set-block">
                <p class="muted small">{t('about.licence_line')}</p>
                <a class="link" href="/licenses.txt" target="_blank" rel="noopener">{t('about.read')}</a>
            </div>
        </div>
    )
}

// Blocks

function Choices<T extends string | number>(props: { label: string; name: string; options: { value: T; label: string }[]; value: T; onPick: (v: T) => void }) {
    return (
        <div class="segmented" role="radiogroup" aria-labelledby={props.label} style={{ 'grid-template-columns': `repeat(${props.options.length}, 1fr)` }}>
            <For each={props.options}>
                {(o) => (
                    <label classList={{ on: props.value === o.value }}>
                        <input type="radio" name={props.name} checked={props.value === o.value} onChange={() => props.onPick(o.value)} />
                        {o.label}
                    </label>
                )}
            </For>
        </div>
    )
}

/**
 * The app's two thumb size slider: No minimum, 50 MB, then every 100 MB up to No limit, on its
 * uneven scale (the first gigabyte takes almost half the track). The arrow keys move one stop,
 * Page Up and Down ten; the thumbs never cross.
 */
function SizeLimits() {
    const stops = () => sizeScale()?.stops ?? []
    const indexOf = (bytes: number) => {
        const s = stops()
        let best = 0
        for (let i = 1; i < s.length; i++) if (Math.abs(s[i].bytes - bytes) < Math.abs(s[best].bytes - bytes)) best = i
        return best
    }
    const minI = () => indexOf(settings().sizeMin)
    const maxI = () => indexOf(settings().sizeMax)
    const nearest = (at: number) => {
        const s = stops()
        let best = 0
        for (let i = 1; i < s.length; i++) if (Math.abs(s[i].at - at) < Math.abs(s[best].at - at)) best = i
        return best
    }
    const pick = (which: 'min' | 'max', i: number) => {
        const last = stops().length - 1
        if (which === 'min') setSetting('sizeMin', stops()[Math.max(0, Math.min(i, maxI() - 1))].bytes)
        else setSetting('sizeMax', stops()[Math.min(last, Math.max(i, minI() + 1))].bytes)
    }
    const keys = (which: 'min' | 'max', e: KeyboardEvent) => {
        const at = which === 'min' ? minI() : maxI()
        const step = { ArrowLeft: -1, ArrowDown: -1, ArrowRight: 1, ArrowUp: 1, PageDown: -10, PageUp: 10, Home: -1e4, End: 1e4 }[e.key]
        if (step === undefined) return
        e.preventDefault()
        pick(which, at + step)
    }
    const [range] = createResource(
        () => [settings().sizeMin, settings().sizeMax] as const,
        ([min, max]) => api.sizeRange(min, max),
    )
    const isDefault = () => settings().sizeMin === SIZE_DEFAULTS.sizeMin && settings().sizeMax >= SIZE_DEFAULTS.sizeMax
    const at = (i: number) => (stops()[i]?.at ?? 0) * 100
    return (
        <div class="set-block">
            <h3 class="set-block-title" id="size-label">{t('settings.size_limits')}</h3>
            <Show when={stops().length}>
                <div class="size-readout" aria-hidden="true">
                    <span>{stops()[minI()].label}</span>
                    <span>{stops()[maxI()].label}</span>
                </div>
                <div class="dual-range" style={{ '--from': `${at(minI())}%`, '--to': `${at(maxI())}%` }}>
                    <div class="dual-track" />
                    <input
                        type="range"
                        min="0"
                        max="1000"
                        // Above the top thumb once past half way, so two thumbs side by side can both be taken.
                        classList={{ above: at(minI()) > 50 }}
                        value={Math.round(at(minI()) * 10)}
                        aria-label={t('size.smallest')}
                        aria-valuetext={stops()[minI()].label}
                        onInput={(e) => pick('min', nearest(Number(e.currentTarget.value) / 1000))}
                        onKeyDown={(e) => keys('min', e)}
                    />
                    <input
                        type="range"
                        min="0"
                        max="1000"
                        value={Math.round(at(maxI()) * 10)}
                        aria-label={t('size.largest')}
                        aria-valuetext={stops()[maxI()].label}
                        onInput={(e) => pick('max', nearest(Number(e.currentTarget.value) / 1000))}
                        onKeyDown={(e) => keys('max', e)}
                    />
                </div>
            </Show>
            <p class="muted" aria-live="polite">{range.latest ? t('settings.size_limits_note', { range: range.latest }) : ''}</p>
            <Show when={!isDefault()}>
                <button
                    class="link"
                    type="button"
                    onClick={() => {
                        setSetting('sizeMin', SIZE_DEFAULTS.sizeMin)
                        setSetting('sizeMax', SIZE_DEFAULTS.sizeMax)
                    }}
                >
                    {t('common.reset')}
                </button>
            </Show>
        </div>
    )
}

/** Rewind when resuming: the app's choices, Off first. */
function Rewind() {
    const [choices] = createResource(() => api.rewindChoices())
    const current = () => settings().rewindMs ?? choices()?.fallback ?? 0
    return (
        <div class="set-block">
            <h3 class="set-block-title" id="rewind-label">{t('settings.resume_rewind')}</h3>
            <p class="muted small">{t('settings.resume_rewind_detail')}</p>
            <Show when={choices()}>
                <Choices
                    label="rewind-label"
                    name="rewind"
                    options={choices()!.choices.map((ms) => ({ value: ms, label: ms ? t('format.seconds_short', { seconds: ms / 1000 }) : t('common.off') }))}
                    value={current()}
                    onPick={(v) => setSetting('rewindMs', v)}
                />
            </Show>
        </div>
    )
}

/** Installing: the browser's own prompt where it has one, the Share menu's steps on an iPhone or iPad; gone once installed. */
function Install() {
    return (
        <Show when={!installed() && (installPrompt() || iosBrowser)}>
            <div class="set-card set-blocks">
                <div class="set-block">
                    <Show
                        when={installPrompt()}
                        fallback={
                            <>
                                <h3 class="set-block-title">{t('web.install_ios_title')}</h3>
                                <p class="muted small">{t('web.install_ios_body')}</p>
                                <ol class="install-steps">
                                    <li>{t('web.install_ios_step_share')}</li>
                                    <li>{t('web.install_ios_step_add')}</li>
                                </ol>
                                <p class="muted small">{t('web.install_ios_own_sign_in')}</p>
                            </>
                        }
                    >
                        <h3 class="set-block-title">{t('web.install_title')}</h3>
                        <p class="muted small">{t('web.install_pwa_body')}</p>
                        <button class="primary" style={{ 'align-self': 'start' }} type="button" onClick={install}>{t('web.install_button')}</button>
                    </Show>
                </div>
            </div>
        </Show>
    )
}

/** What the browser holds for TMPlayer, and asking it to keep that when the device runs low on space. */
function StorageBlock() {
    const [estimate] = createResource(() => navigator.storage?.estimate?.().catch(() => null) ?? null)
    return (
        <div class="set-block">
            <h3 class="set-block-title">{t('web.storage_title')}</h3>
            <p class="muted small">{t('web.storage_body_stream')}</p>
            <Show when={!storageWorks()}>
                <p class="small caution-text">{t('web.storage_unavailable')}</p>
            </Show>
            <Show when={estimate()?.quota}>
                <p class="small">{t('web.storage_used', { used: size(estimate()!.usage ?? 0), quota: size(estimate()!.quota!) })}</p>
            </Show>
            <Show when={kept() !== null}>
                <p class="muted small">{kept() ? t('web.keep_storage_on') : refused() ? t('web.keep_storage_refused') : t('web.keep_storage_off')}</p>
                <Show when={!kept()}>
                    <button class="link" style={{ 'align-self': 'start' }} type="button" onClick={keepData}>{t('web.keep_storage')}</button>
                </Show>
            </Show>
        </div>
    )
}

/** Sign out at the end of the list: full width, in the danger colour. It still asks first. */
function SignOut() {
    let dialog!: HTMLDialogElement
    const [leaving, setLeaving] = createSignal(false)
    const confirm = async () => {
        setLeaving(true)
        await signOut()
    }
    return (
        <>
            <button class="danger-button set-signout" type="button" onClick={() => dialog.showModal()}>
                <Icon icon={Logout05Icon} size={20} />
                {t('settings.sign_out')}
            </button>
            <dialog class="confirm" ref={dialog} aria-labelledby="sign-out-title">
                <h2 id="sign-out-title">{t('settings.sign_out_title')}</h2>
                <p class="muted">{t('settings.sign_out_message')}</p>
                <div class="confirm-buttons">
                    <button class="link" type="button" disabled={leaving()} onClick={() => dialog.close()}>{t('common.cancel')}</button>
                    <button class="primary danger" type="button" disabled={leaving()} onClick={confirm}>
                        {leaving() ? t('settings.signing_out') : t('settings.sign_out_confirm')}
                    </button>
                </div>
            </dialog>
        </>
    )
}

// The TV layout: one long page of choices, as session 20 styled it (src/tv/tv.css) and its remote walks.

function TvSettings() {
    return (
        <section class="settings">
            <h1 class="page-title">{t('nav.settings')}</h1>
            <TvPanel title={t('settings.theme')} id="theme-label">
                <Choices
                    label="theme-label"
                    name="theme"
                    options={THEMES.map((v) => ({ value: v, label: t(`theme.${v}`) }))}
                    value={settings().theme}
                    onPick={(v) => setSetting('theme', v)}
                />
            </TvPanel>
            <TvPanel title={t('settings.layout')} id="layout-label">
                <Choices
                    label="layout-label"
                    name="layout"
                    options={LAYOUTS.map((v) => ({ value: v, label: t(`settings.layout_${v}`) }))}
                    value={layout()}
                    onPick={setLayout}
                />
                <p class="muted small">{t('settings.layout_detail_desktop')}</p>
            </TvPanel>
            <TvPanel title={t('settings.home_carousel')} id="carousel-label">
                <p class="muted small">{settings().carousel ? t('settings.home_carousel_on') : t('settings.home_carousel_off')}</p>
                <Choices
                    label="carousel-label"
                    name="carousel"
                    options={[
                        { value: 'on', label: t('common.on') },
                        { value: 'off', label: t('common.off') },
                    ]}
                    value={settings().carousel ? 'on' : 'off'}
                    onPick={(v) => setSetting('carousel', v === 'on')}
                />
            </TvPanel>
            <TvPanel title="" id="size-label" anchor="size">
                <SizeLimits />
            </TvPanel>
            <TvPanel title={t('browse.clear_history')} id="history-label">
                <p class="muted small">{t('browse.clear_history_detail')}</p>
                <button class="outline-button" style={{ 'align-self': 'start' }} type="button" aria-labelledby="history-label" onClick={() => void clearHistoryWithUndo()}>
                    {t('browse.clear_history')}
                </button>
            </TvPanel>
            <TvPanel title="" id="rewind-panel">
                <Rewind />
            </TvPanel>
            <TvPanel title={t('web.posters_title')} id="posters-label">
                <p class="muted small">{settings().posters ? t('web.posters_on_detail') : t('metadata.toggle_off')}</p>
                <Choices
                    label="posters-label"
                    name="posters"
                    options={[
                        { value: 'on', label: t('common.on') },
                        { value: 'off', label: t('common.off') },
                    ]}
                    value={settings().posters ? 'on' : 'off'}
                    onPick={(v) => setSetting('posters', v === 'on')}
                />
                <a class="tmdb-credit" href="https://www.themoviedb.org/" target="_blank" rel="noopener" aria-label="TMDB">
                    <TmdbMark height={14} />
                </a>
                <p class="muted small">{t('metadata.about_notice')}</p>
            </TvPanel>
            <Install />
            <div class="panel">
                <StorageBlock />
            </div>
            <div class="panel">
                <h2 class="panel-title">{t('settings.account')}</h2>
                <Show when={me()?.name}>
                    <p>{t('settings.signed_in_as', { name: me()!.name! })}</p>
                </Show>
                <p class="muted small">{t('auth.unofficial')}</p>
                <a class="link" href="https://tmplayer.org/legal" target="_blank" rel="noopener">{t('login.legal')}</a>
                <SignOut />
            </div>
            <div class="panel">
                <h2 class="panel-title">{t('about.notices_title')}</h2>
                <p class="muted small">{t('about.licence_line')}</p>
                <p class="muted small">{t('about.notices_detail')}</p>
                <a class="link" href="/licenses.txt" target="_blank" rel="noopener">{t('about.read')}</a>
            </div>
        </section>
    )
}

function TvPanel(props: { title: string; id: string; anchor?: string; children: JSX.Element }) {
    return (
        <div class="panel" id={props.anchor}>
            <Show when={props.title}>
                <h2 class="panel-title" id={props.id}>{props.title}</h2>
            </Show>
            {props.children}
        </div>
    )
}
