import { createEffect, createMemo, createResource, createSignal, For, Match, on, onMount, Show, Switch, type JSX } from 'solid-js'
import { A, useNavigate, useParams } from '@solidjs/router'
import { renderSVG } from 'uqr'
import {
    ArrowDown01Icon,
    ArrowLeft02Icon,
    ArrowRight01Icon,
    ArrowUp01Icon,
    ClapperboardIcon,
    CloudSyncIcon,
    LockKeyholeIcon,
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
    Message01Icon,
    PaintBoardIcon,
    PlayIcon,
    StarIcon,
    TranslateIcon,
    Clock01Icon,
    VideoReplayIcon,
    FavouriteIcon,
} from '@hugeicons/core-free-icons'
import { addedIds, addedMode, homeChatRows, namedLists, settings, setSetting, starredIds, storageWorks, type Theme } from '../store'
import { editorRows, encodeOrder, moveKey, parseOrder, type OrderEntry } from '../ui/homeOrder'
import { back, me, signOut } from '../session'
import { api } from '../tg/rpc'
import { t } from '../i18n'
import { clearHistoryWithUndo } from '../clearHistory'
import { askConfirm } from '../ui/Confirm'
import { size } from './VideoList'
import { form, layout, layoutFixed, setLayout, type Layout } from '../form'
import { Icon, type IconData } from '../ui/Icon'
import { TmdbMark } from '../ui/TmdbMark'
import { Initial } from '../ui/PageBar'
import { checkKept, install, installed, installPrompt, iosBrowser, keepData, kept, refused } from '../install'
import { cacheSizes, clearCaches } from '../cacheBounds'
import { msoneOn, stateLine } from '../msoneFlag'
import { health, knownHealth, type Health } from '../player/msone'
import { setSyncOn, statusLine, syncHasFile, syncNow, syncOn, syncRunning, syncStatus, turnOffAndDelete } from '../sync'
import { showToast } from '../ui/Toast'

const THEMES: Theme[] = ['system', 'dark', 'light', 'black']
const LAYOUTS: Layout[] = ['desktop', 'tv']
/** The build's own notices: the TV build's (under /tv/) also name core-js. */
const LICENCES = `${import.meta.env.BASE_URL}licenses.txt`
// The same UPI details the apps and the site carry (ui About.kt, site/src/lib/support.ts).
const UPI_ID = 'nevilnicks4321@okaxis'
const UPI_LINK = `upi://pay?pa=${UPI_ID}&pn=TMPlayer&cu=INR&tn=${encodeURIComponent('TMPlayer support')}`

type PageId = 'appearance' | 'home' | 'playback' | 'library' | 'privacy' | 'storage' | 'about'

/** The app's pages (SettingsPage) that the web has something to put in, in its order. */
const PAGES: { id: PageId; icon: IconData; title: () => string; summary: () => string }[] = [
    {
        id: 'appearance',
        icon: PaintBoardIcon,
        title: () => t('settings.appearance'),
        summary: () => (form() === 'phone' || layoutFixed() ? t(`theme.${settings().theme}`) : `${t(`theme.${settings().theme}`)}${dot}${t(`settings.layout_${layout()}`)}`),
    },
    { id: 'home', icon: Home03Icon, title: () => t('settings.page_home'), summary: () => t('settings.page_home_rows_summary') },
    { id: 'playback', icon: PlayIcon, title: () => t('settings.playback'), summary: () => t('settings.resume_rewind') },
    { id: 'library', icon: GridViewIcon, title: () => t('settings.library'), summary: () => t('settings.page_library_summary') },
    { id: 'privacy', icon: LockKeyholeIcon, title: () => t('settings.page_privacy_lock'), summary: () => (syncOn() ? t('syncplace.switch_on') : t('syncplace.switch_off')) },
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
                <Initial name={me()!.name} />
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
            <Match when={props.id === 'home'}>
                <HomePage />
            </Match>
            <Match when={props.id === 'playback'}>
                <Playback />
            </Match>
            <Match when={props.id === 'library'}>
                <Library />
            </Match>
            <Match when={props.id === 'privacy'}>
                <Privacy />
            </Match>
            <Match when={props.id === 'storage'}>
                <StoragePage />
            </Match>
            <Match when={props.id === 'about'}>
                <Chip />
                <About />
            </Match>
        </Switch>
    )
}

// What a row is made of

function RowBody(props: { icon: IconData; title: string; subtitle?: string; note?: string }) {
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
                <Show when={props.note}>
                    <span class="set-sub">{props.note}</span>
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
                <Show when={form() !== 'phone' && !layoutFixed()}>
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
        <>
            <div class="set-card set-blocks">
                <Rewind />
            </div>
            <Show when={msoneOn()}>
                <div class="set-card">
                    <MsoneRow />
                </div>
            </Show>
        </>
    )
}

/**
 * MSone subtitles, behind the hidden flag (msoneFlag.ts): the state the gateway last gave, with
 * its message, and a press checks again (at most once a minute, msone.ts holds that).
 */
function useMsoneHealth() {
    const [state, setState] = createSignal<Health>(knownHealth())
    const [checking, setChecking] = createSignal(false)
    const check = async (force: boolean) => {
        if (checking()) return
        setChecking(true)
        // health() never throws: a failed check keeps the last state.
        setState(await health(force))
        setChecking(false)
    }
    onMount(() => void check(false))
    return { state, checking, check }
}

function MsoneRow() {
    const m = useMsoneHealth()
    return (
        <button type="button" class="set-row" aria-busy={m.checking()} onClick={() => void m.check(true)}>
            <RowBody icon={TranslateIcon} title={t('msone.title')} subtitle={m.checking() ? t('msone.checking') : stateLine(m.state().state)} note={m.checking() ? undefined : m.state().message ?? undefined} />
        </button>
    )
}

function TvMsone() {
    const m = useMsoneHealth()
    return (
        <TvPanel title={t('msone.title')} id="msone-label">
            <p class="muted small" role="status">{m.checking() ? t('msone.checking') : stateLine(m.state().state)}</p>
            <Show when={!m.checking() && m.state().message}>
                <p class="muted small">{m.state().message}</p>
            </Show>
            <button class="outline-button" style={{ 'align-self': 'start' }} type="button" aria-labelledby="msone-label" onClick={() => void m.check(true)}>
                {t('msone.check_again')}
            </button>
        </TvPanel>
    )
}

/**
 * Settings > Home (the apps' Home page, 8f55cf2): a switch for each of Home's rows, and a note that
 * Recently added (and the carousel drawn from it) follows the chats added in Add chats. Its own
 * "Chosen chats" picker repeated Add chats and is gone (2026-11-01).
 */
function HomePage() {
    return (
        <>
            <div class="set-card">
                <h3 class="set-block-title set-card-title">{t('settings.home_rows')}</h3>
                <ToggleRow
                    icon={Home03Icon}
                    title={t('settings.home_carousel')}
                    subtitle={settings().carousel ? t('settings.home_carousel_on') : t('settings.home_carousel_off')}
                    on={settings().carousel}
                    onChange={(on) => setSetting('carousel', on)}
                />
                <ToggleRow
                    icon={VideoReplayIcon}
                    title={t('settings.home_continue')}
                    subtitle={settings().homeContinue ? t('settings.home_continue_on') : t('settings.home_continue_off')}
                    on={settings().homeContinue}
                    onChange={(on) => setSetting('homeContinue', on)}
                />
                <ToggleRow
                    icon={Clock01Icon}
                    title={t('settings.home_recent')}
                    subtitle={settings().homeRecent ? t('settings.home_recent_on') : t('settings.home_recent_off')}
                    on={settings().homeRecent}
                    onChange={(on) => setSetting('homeRecent', on)}
                />
                <ToggleRow
                    icon={StarIcon}
                    title={t('settings.home_starred_rows')}
                    subtitle={settings().homeStarred ? t('settings.home_starred_rows_on') : t('settings.home_starred_rows_off')}
                    on={settings().homeStarred}
                    onChange={(on) => setSetting('homeStarred', on)}
                />
            </div>
            <p class="muted small">{t('settings.home_follows_added')}</p>
            <HomeOrder />
        </>
    )
}

/** The rows Settings > Home's order lists: Continue watching, Recently added and each starred chat Home drew, in the stored order. */
function homeOrderRows(): OrderEntry[] {
    const chats = homeChatRows().filter((c) => starredIds().has(c.chatId))
    const lists = namedLists().filter((l) => l.info.home && l.lines.length).map((l) => ({ id: l.info.id, name: l.info.name }))
    return editorRows(chats, parseOrder(settings().homeOrder), lists)
}

function homeOrderTitle(row: OrderEntry): string {
    return row.key === 'continue' ? t('settings.home_continue') : row.key === 'recent' ? t('settings.home_recent') : (row.listName ?? row.chatTitle ?? row.key)
}

function homeOrderOff(row: OrderEntry): boolean {
    if (row.listName !== undefined) return false
    return row.key === 'continue' ? !settings().homeContinue : row.key === 'recent' ? !settings().homeRecent : !settings().homeStarred
}

/**
 * Settings > Home, Order of rows (EG-F7, EG-F23): Move up and Move down on every row, and a row can
 * be dragged by a mouse. On a TV the buttons are what the remote reaches (OK presses one). The order
 * is stored as the apps' HomeOrder text and applied by Home's own build in the worker.
 */
function HomeOrder() {
    const rows = createMemo(homeOrderRows)
    const [dragging, setDragging] = createSignal<string | null>(null)
    const put = (keys: string[]) => setSetting('homeOrder', encodeOrder(keys))
    // The rows are drawn again after a move: the keyboard follows the row (its same button, or the other where this one has reached the end).
    let list!: HTMLOListElement
    let follow: { key: string; by: number } | null = null
    createEffect(
        on(
            rows,
            () => {
                const f = follow
                follow = null
                if (!f) return
                queueMicrotask(() => {
                    const buttons = [...(list.querySelectorAll<HTMLElement>(`[data-key="${CSS.escape(f.key)}"] .order-move`) ?? [])]
                    const same = buttons[f.by > 0 ? 1 : 0]
                    ;((same as HTMLButtonElement | undefined)?.disabled ? buttons[f.by > 0 ? 0 : 1] : same)?.focus()
                })
            },
            { defer: true },
        ),
    )
    const move = (key: string, by: number) => {
        follow = { key, by }
        put(moveKey(rows().map((r) => r.key), key, by))
    }
    // A row dropped on another takes that row's place.
    const drop = (onto: string) => {
        const from = dragging()
        setDragging(null)
        if (!from || from === onto) return
        const keys = rows().map((r) => r.key)
        put(moveKey(keys, from, keys.indexOf(onto) - keys.indexOf(from)))
    }
    return (
        <div class="set-card">
            <h3 class="set-block-title set-card-title">{t('settings.home_order')}</h3>
            <ol class="order-list" ref={list}>
                <For each={rows()}>
                    {(row, i) => (
                        <li
                            class="order-row"
                            data-key={row.key}
                            classList={{ 'order-dragging': dragging() === row.key }}
                            draggable={form() === 'desktop'}
                            onDragStart={() => setDragging(row.key)}
                            onDragEnd={() => setDragging(null)}
                            onDragOver={(e) => dragging() && e.preventDefault()}
                            onDrop={(e) => {
                                e.preventDefault()
                                drop(row.key)
                            }}
                        >
                            <span class="set-text">
                                <span class="set-title">{homeOrderTitle(row)}</span>
                                <Show when={homeOrderOff(row)}>
                                    <span class="set-sub">{t('common.off')}</span>
                                </Show>
                            </span>
                            <button type="button" class="order-move" aria-label={`${t('settings.home_order_up')}: ${homeOrderTitle(row)}`} disabled={i() === 0} onClick={() => move(row.key, -1)}>
                                <Icon icon={ArrowUp01Icon} size={20} />
                            </button>
                            <button type="button" class="order-move" aria-label={`${t('settings.home_order_down')}: ${homeOrderTitle(row)}`} disabled={i() === rows().length - 1} onClick={() => move(row.key, 1)}>
                                <Icon icon={ArrowDown01Icon} size={20} />
                            </button>
                        </li>
                    )}
                </For>
            </ol>
            <p class="muted small order-hint">{form() === 'desktop' ? t('settings.home_order_hint_web') : t('settings.home_order_hint_buttons')}</p>
            <Show when={settings().homeOrder !== ''}>
                <button class="outline-button order-reset" type="button" onClick={() => put([])}>
                    {t('settings.home_order_reset')}
                </button>
            </Show>
        </div>
    )
}

/** One of Settings > Home's switches on a TV: its words and an On or Off choice made with OK. */
function TvSwitch(props: { id: string; title: string; on: boolean; onText: string; offText: string; onPick: (on: boolean) => void }) {
    return (
        <TvPanel title={props.title} id={`${props.id}-label`}>
            <p class="muted small">{props.on ? props.onText : props.offText}</p>
            <Choices
                label={`${props.id}-label`}
                name={props.id}
                options={[
                    { value: 'on', label: t('common.on') },
                    { value: 'off', label: t('common.off') },
                ]}
                value={props.on ? 'on' : 'off'}
                onPick={(v) => props.onPick(v === 'on')}
            />
        </TvPanel>
    )
}

/** What the chosen chats row says: how many, or, for an answer from before choosing was required, every chat. */
const chosenDetail = () => (addedMode() === 'all' ? t('picker.row_detail_all') : t('picker.row_detail', { count: addedMode() === 'picked' ? (addedIds()?.size ?? 0) : 0 }))

function Library() {
    return (
        <>
            <div class="set-card">
                <A class="set-row" href="/added-chats">
                    <RowBody icon={Message01Icon} title={t('picker.row_title')} subtitle={chosenDetail()} />
                    <Icon icon={ArrowRight01Icon} size={20} class="set-chevron" />
                </A>
                <ToggleRow
                    icon={Image01Icon}
                    title={t('web.posters_title')}
                    subtitle={settings().posters ? t('web.posters_on_detail') : t('metadata.toggle_off')}
                    on={settings().posters}
                    onChange={(on) => setSetting('posters', on)}
                />
                <ToggleRow
                    icon={ClapperboardIcon}
                    title={t('metadata.trailers_in_app')}
                    subtitle={t('metadata.trailers_in_app_detail')}
                    on={settings().trailersInApp}
                    onChange={(on) => setSetting('trailersInApp', on)}
                />
                <div class="set-block set-credit">
                    <a class="tmdb-credit" href="https://www.themoviedb.org/" target="_blank" rel="noopener" aria-label="TMDB">
                        <TmdbMark height={14} />
                    </a>
                    <p class="muted small">{t('metadata.about_notice')}</p>
                </div>
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

/**
 * Privacy and lock: resume on your other devices (the apps' row of the same name). Off until the
 * viewer turns it on, and turning it on asks first what goes where. While on: the line of the last
 * sync, "Sync now" (or "Replace the sync file" when the file could not be read), and "Turn off and
 * delete the sync file", which takes TMPlayer's file out of Saved Messages.
 */
function Privacy() {
    let enable!: HTMLDialogElement
    const say = (text: string) => showToast({ text })
    const change = (on: boolean) => (on ? enable.showModal() : void setSyncOn(false))
    const turnOn = () => {
        enable.close()
        void setSyncOn(true)
        say(t('settings.resume_sync_started'))
    }
    // Turning off and deleting the file asks first (HX59), and holds its buttons while the file is taken out.
    const erased = async () => {
        let gone = false
        const yes = await askConfirm({
            title: t('settings.resume_sync_delete_title'),
            message: t('settings.resume_sync_delete_message'),
            confirm: t('settings.resume_sync_delete'),
            run: async () => (gone = await turnOffAndDelete()),
        })
        if (yes) say(gone ? t('settings.resume_sync_deleted') : t('settings.resume_sync_delete_failed'))
    }
    const replaced = async () => {
        const yes = await askConfirm({
            title: t('settings.resume_sync_replace_title'),
            message: t('settings.resume_sync_replace_message'),
            confirm: t('settings.resume_sync_replace'),
        })
        if (yes) void syncNow({ replace: true })
    }
    const unreadable = () => syncStatus()?.kind === 'unreadable'
    return (
        <>
            <div class="set-card">
                <ToggleRow icon={CloudSyncIcon} title={t('settings.resume_sync')} subtitle={syncOn() ? t('settings.resume_sync_on') : t('settings.resume_sync_off')} on={syncOn()} onChange={change} />
                <Show when={syncOn()}>
                    <div class="set-block sync-status">
                        <p class="small" role="status" aria-live="polite" aria-busy={syncRunning()}>{statusLine()}</p>
                        <div class="sync-buttons">
                            <Show
                                when={unreadable()}
                                fallback={<button type="button" class="outline-button" disabled={syncRunning()} onClick={() => void syncNow()}>{t('settings.resume_sync_now')}</button>}
                            >
                                <button type="button" class="outline-button" disabled={syncRunning()} onClick={() => void replaced()}>{t('settings.resume_sync_replace')}</button>
                            </Show>
                        </div>
                    </div>
                </Show>
                <Show when={syncOn() || syncHasFile()}>
                    <button type="button" class="set-row" onClick={() => void erased()}>
                        <RowBody icon={Delete02Icon} title={t('settings.resume_sync_delete')} subtitle={t('settings.resume_sync_delete_detail')} />
                    </button>
                </Show>
                <div class="set-block">
                    <p class="muted small">{t('syncplace.privacy')}</p>
                </div>
            </div>
            <dialog class="confirm" ref={enable} aria-labelledby="sync-enable-title">
                <h2 id="sync-enable-title">{t('settings.resume_sync_enable_title')}</h2>
                <p class="muted">{t('settings.resume_sync_enable_message')}</p>
                <div class="confirm-buttons">
                    <button class="link" type="button" onClick={() => enable.close()}>{t('common.cancel')}</button>
                    <button class="primary" type="button" onClick={turnOn}>{t('syncplace.card_yes')}</button>
                </div>
            </dialog>
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
            <a class="set-row" href={LICENCES} target="_blank" rel="noopener">
                <RowBody icon={File01Icon} title={t('about.notices_title')} subtitle={t('about.notices_detail')} />
                <Icon icon={ArrowRight01Icon} size={20} class="set-chevron" />
            </a>
            <a class="set-row" href="https://tmplayer.org/legal" target="_blank" rel="noopener">
                <RowBody icon={LegalDocument01Icon} title={t('login.legal')} subtitle={t('auth.unofficial')} />
                <Icon icon={ArrowRight01Icon} size={20} class="set-chevron" />
            </a>
            <div class="set-block">
                <p class="muted small">{t('about.licence_line')}</p>
                <a class="link" href={LICENCES} target="_blank" rel="noopener">{t('about.read')}</a>
            </div>
        </div>
    )
}

/**
 * Chipping in (user, 2026-11-01; HX75, 2026-11-02). Buy Me a Coffee takes cards and PayPal, which
 * most people here do not use: UPI stands beside it, with Google Pay's own mark, since that is the
 * app most of them pay with. The UPI row always opens a window with the code, the ID and Copy. On
 * a phone it also follows the real `upi://` link, which GPay, PhonePe or Paytm answers; the window
 * is there for when the wrong app opened or none did. A computer has nothing to answer the link,
 * so the window is all it gets. Nothing here is checked afterwards and nothing is unlocked.
 */
function Chip() {
    const touch = typeof matchMedia === 'function' && matchMedia('(pointer: coarse)').matches
    let dialog!: HTMLDialogElement
    const [copied, setCopied] = createSignal(false)
    const qr = renderSVG(UPI_LINK, { pixelSize: 6, border: 2, whiteColor: '#fff', blackColor: '#0e0f13' })
    const open = () => {
        setCopied(false)
        if (!dialog.open) dialog.showModal()
        // A phone hands the link to its UPI app; a refused or unanswered link leaves the window up.
        if (touch) {
            try { location.href = UPI_LINK } catch { /* the window shows the code and the ID */ }
        }
    }
    const copy = async () => {
        // A refused or missing clipboard leaves the ID on screen to copy by hand.
        try {
            await navigator.clipboard.writeText(UPI_ID)
            setCopied(true)
        } catch {
            setCopied(false)
        }
    }
    return (
        <>
        <div class="set-card">
            <a class="set-row" href="https://buymeacoffee.com/nevil.dev" target="_blank" rel="noopener">
                <RowBody icon={FavouriteIcon} title={t('support.buy_coffee')} />
                <Icon icon={ArrowRight01Icon} size={20} class="set-chevron" />
            </a>
            <button class="set-row" type="button" onClick={open}>
                <span class="set-icon set-icon-plain">
                    <img src="/gpay-mark.png" alt="" width="24" height="24" />
                </span>
                <span class="set-text">
                    <span class="set-title">{t('support.pay_upi')}</span>
                    <span class="set-sub">{UPI_ID}</span>
                </span>
                <Icon icon={ArrowRight01Icon} size={20} class="set-chevron" />
            </button>
            <div class="set-block">
                <p class="muted small">{t('support.any_amount')}</p>
            </div>
        </div>
        <dialog
            class="confirm upi-dialog"
            ref={dialog}
            aria-labelledby="upi-title"
            onClick={(e) => { if (e.target === dialog) dialog.close() }}
        >
            <h2 id="upi-title">{t('support.pay_upi')}</h2>
            <p class="muted">{t('support.upi_note')}</p>
            <div class="qr"><div class="qr-code" role="img" aria-label={t('support.scan_upi')} innerHTML={qr} /></div>
            <p class="muted small">{t('support.scan_upi')}</p>
            <p class="upi-id"><code>{UPI_ID}</code></p>
            <div class="confirm-buttons">
                <button class="link" type="button" onClick={copy}>{copied() ? t('support.upi_copied') : t('support.upi_copy')}</button>
                <button class="primary" type="button" onClick={() => dialog.close()}>{t('common.close')}</button>
            </div>
        </dialog>
        </>
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
const small = (bytes: number) => (bytes < 500_000 ? '0 MB' : size(bytes))

function StorageBlock() {
    const [version, setVersion] = createSignal(0)
    const [estimate] = createResource(version, () => navigator.storage?.estimate?.().catch(() => null) ?? null)
    const [sizes] = createResource(version, () => cacheSizes())
    const [clearing, setClearing] = createSignal(false)
    onMount(() => void checkKept())
    // Thumbnails and posters only: the sign in, settings, places and watched stay.
    const clear = async () => {
        if (clearing()) return
        const held = (sizes()?.thumbs ?? 0) + (sizes()?.posters ?? 0)
        const yes = await askConfirm({
            title: t('settings.clear_pictures_title'),
            message: t('settings.clear_pictures_message', { size: size(held) }),
            detail: t('settings.clear_pictures_untouched'),
            confirm: t('web.storage_clear_cache'),
        })
        if (!yes) return
        setClearing(true)
        try {
            await clearCaches()
        } catch {}
        setClearing(false)
        setVersion((v) => v + 1)
    }
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
            <Show when={estimate()?.usage !== undefined && sizes()}>
                {(sz) => {
                    const app = () => Math.max(0, (estimate()!.usage ?? 0) - sz().thumbs - sz().posters)
                    return (
                        <>
                            <p class="muted small">
                                {t('web.storage_breakdown', { thumbs: small(sz().thumbs), posters: small(sz().posters), app: small(app()) })}
                            </p>
                            <button class="link" style={{ 'align-self': 'start' }} type="button" disabled={clearing()} onClick={clear}>{t('web.storage_clear_cache')}</button>
                            <p class="muted small">{t('web.storage_clear_cache_detail')}</p>
                        </>
                    )
                }}
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

/** Sign out at the end of the list: a quiet outlined row with the danger colour in its words only, and a question first that names what goes with it. */
function SignOut() {
    const ask = () =>
        void askConfirm({
            title: t('settings.sign_out_title'),
            message: t('settings.sign_out_message'),
            confirm: t('settings.sign_out_confirm'),
            busy: t('settings.signing_out'),
            run: signOut,
        })
    return (
        <>
            <button class="danger-button set-signout" type="button" onClick={ask}>
                <Icon icon={Logout05Icon} size={20} />
                {t('settings.sign_out')}
            </button>
        </>
    )
}

// The TV layout: one long page of choices, as session 20 styled it (src/tv/tv.css) and its remote walks.

function TvSettings() {
    return (
        <section class="settings">
            <h1 class="page-title">{t('nav.settings')}</h1>
            <TvPanel title={t('picker.row_title')} id="add-chats-label">
                <p class="muted small">{chosenDetail()}</p>
                <A class="outline-button" style={{ 'align-self': 'start' }} href="/added-chats">{t('picker.choose_chats')}</A>
            </TvPanel>
            <TvPanel title={t('settings.theme')} id="theme-label">
                <Choices
                    label="theme-label"
                    name="theme"
                    options={THEMES.map((v) => ({ value: v, label: t(`theme.${v}`) }))}
                    value={settings().theme}
                    onPick={(v) => setSetting('theme', v)}
                />
            </TvPanel>
            <Show when={!layoutFixed()}>
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
            </Show>
            <TvSwitch id="carousel" title={t('settings.home_carousel')} on={settings().carousel} onText={t('settings.home_carousel_on')} offText={t('settings.home_carousel_off')} onPick={(on) => setSetting('carousel', on)} />
            <TvSwitch id="home-continue" title={t('settings.home_continue')} on={settings().homeContinue} onText={t('settings.home_continue_on')} offText={t('settings.home_continue_off')} onPick={(on) => setSetting('homeContinue', on)} />
            <TvSwitch id="home-recent" title={t('settings.home_recent')} on={settings().homeRecent} onText={t('settings.home_recent_on')} offText={t('settings.home_recent_off')} onPick={(on) => setSetting('homeRecent', on)} />
            <TvSwitch id="home-starred" title={t('settings.home_starred_rows')} on={settings().homeStarred} onText={t('settings.home_starred_rows_on')} offText={t('settings.home_starred_rows_off')} onPick={(on) => setSetting('homeStarred', on)} />
            <p class="muted small">{t('settings.home_follows_added')}</p>
            <HomeOrder />
            <TvSwitch id="sync" title={t('settings.resume_sync')} on={syncOn()} onText={t('settings.resume_sync_on')} offText={t('settings.resume_sync_off')} onPick={(on) => void setSyncOn(on)} />
            <p class="muted small">{t('syncplace.privacy')}</p>
            <Show when={syncOn()}>
                <p class="muted small" role="status">{statusLine()}</p>
                <button class="outline-button" style={{ 'align-self': 'start' }} type="button" disabled={syncRunning()} onClick={() => void syncNow()}>{t('settings.resume_sync_now')}</button>
            </Show>
            <TvPanel title={t('browse.clear_history')} id="history-label">
                <p class="muted small">{t('browse.clear_history_detail')}</p>
                <button class="outline-button" style={{ 'align-self': 'start' }} type="button" aria-labelledby="history-label" onClick={() => void clearHistoryWithUndo()}>
                    {t('browse.clear_history')}
                </button>
            </TvPanel>
            <TvPanel title="" id="rewind-panel">
                <Rewind />
            </TvPanel>
            <Show when={msoneOn()}>
                <TvMsone />
            </Show>
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
            <TvPanel title={t('metadata.trailers_in_app')} id="trailers-label">
                <p class="muted small">{t('metadata.trailers_in_app_detail')}</p>
                <Choices
                    label="trailers-label"
                    name="trailers"
                    options={[
                        { value: 'on', label: t('common.on') },
                        { value: 'off', label: t('common.off') },
                    ]}
                    value={settings().trailersInApp ? 'on' : 'off'}
                    onPick={(v) => setSetting('trailersInApp', v === 'on')}
                />
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
                <a class="link" href={LICENCES} target="_blank" rel="noopener">{t('about.read')}</a>
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
