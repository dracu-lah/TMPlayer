import { createEffect, For, Match, Show, Switch } from 'solid-js'
import { A, useLocation, useNavigate, useParams } from '@solidjs/router'
import { ArrowLeft02Icon, HardDriveIcon, HelpCircleIcon, Home03Icon, InformationCircleIcon, PaintBoardIcon, PlayIcon, SubtitleIcon as SubtitlesIcon, UserIcon } from '@hugeicons/core-free-icons'
import { MOVED, OLD_PAGES, pageFor, PAGE_TITLES, rowFor, type PageId, type RowContext } from '../ui/settingsMap'
import { rowDomId, SettingsSearch, showingResults, useRowTarget } from './SettingsFind'
import { settings } from '../store'
import { back, me } from '../session'
import { language, languageName, t } from '../i18n'
import { form, layout, layoutFixed, tabletClass } from '../form'
import { Icon, type IconData } from '../ui/Icon'
import { Initial } from '../ui/PageBar'
import { installed, installPrompt, iosBrowser } from '../install'
import { msoneOn } from '../msoneFlag'
import { syncHasFile, syncOn } from '../sync'
import { Chevron, RowBody } from '../ui/SettingsParts'
import { About, Help } from './settings/About'
import { HomePage, Library } from './settings/Home'
import { LanguageBlock } from './settings/Language'
import { Playback } from './settings/Playback'
import { SubtitlesPage } from './settings/Subtitles'
import { Privacy } from './settings/Privacy'
import { SignOut } from './settings/SignOut'
import { ProfileMenuButton, RemoveAllButton } from '../ui/Profiles'
import { LayoutChoices, LookChoices, ThemeChoices } from './settings/shared'
import { StoragePage } from './settings/Storage'
import { Support } from './settings/Support'
import { TvSettings } from './settings/Tv'
import { dot } from '../ui/dot'
import { VersionFooter, VersionMark } from '../ui/VersionFooter'

/** The app's pages (SettingsGroup, WP-08) that the web has something to put in, in the apps' order (ui/settingsMap.ts PAGE_IDS). */
const PAGES: { id: PageId; icon: IconData; title: () => string; summary: () => string }[] = [
    { id: 'account', icon: UserIcon, title: () => t('settings.group_account'), summary: () => (syncOn() ? t('syncplace.switch_on') : t('syncplace.switch_off')) },
    { id: 'playback', icon: PlayIcon, title: () => t('settings.playback'), summary: () => t('settings.resume_rewind') },
    { id: 'subtitles', icon: SubtitlesIcon, title: () => t('settings.page_subtitles_audio'), summary: () => t('settings.subtitle_style') },
    { id: 'storage', icon: HardDriveIcon, title: () => t('settings.page_downloads_data'), summary: () => t('web.settings_storage_summary') },
    {
        id: 'appearance',
        icon: PaintBoardIcon,
        title: () => t('settings.page_appearance'),
        // Language first, as on the page.
        summary: () => [languageName(language()), t(`theme.${settings().theme}`), form() === 'phone' || layoutFixed() ? '' : t(`settings.layout_${layout()}`)].filter(Boolean).join(dot),
    },
    { id: 'home', icon: Home03Icon, title: () => t('settings.page_home_library'), summary: () => t('settings.page_home_library_summary') },
    { id: 'help', icon: HelpCircleIcon, title: () => t('settings.help'), summary: () => t('settings.group_help_summary') },
    { id: 'about', icon: InformationCircleIcon, title: () => t('settings.group_about'), summary: () => t('about.licence_line') },
]
const PAGE_ICONS = Object.fromEntries(PAGES.map((p) => [p.id, p.icon])) as Record<PageId, IconData>

/** What decides which rows this page draws, for the search over them. */
function rowContext(): RowContext {
    return {
        form: form(),
        tablet: tabletClass(),
        layoutFixed: layoutFixed(),
        msone: msoneOn(),
        sync: syncOn(),
        syncFile: syncHasFile(),
        installable: !installed() && !!(installPrompt() || iosBrowser),
        storageEstimate: typeof navigator !== 'undefined' && typeof navigator.storage?.estimate === 'function',
    }
}

/**
 * Settings, as the app lays it out: a list of pages (Appearance, Playback, Library, Storage, About)
 * with the account over it and Sign out under it, each page its own address under `/settings`. A
 * phone takes the list and a page in turn, the desktop shows the list beside the page (the apps'
 * two panes); the TV keeps the one long page of choices its remote walks.
 */
export function Settings() {
    const params = useParams<{ page?: string }>()
    const navigate = useNavigate()
    const location = useLocation()
    useRowTarget()
    // The desktop shows a page beside the list: its address says which, so a change of layout keeps the viewer on it.
    // The TV has one page and no sub pages: it drops the address and remembers the page for the way back out of it.
    // An address from before Home and library were one page (/settings/library) lands on the page that took its place; an anchor stays.
    createEffect(() => {
        const f = form()
        const page = pageFor(params.page)
        const hash = location.hash
        if (params.page && page && params.page !== page && Object.hasOwn(OLD_PAGES, params.page)) {
            navigate(`/settings/${page}${hash}`, { replace: true })
        } else if (f === 'tv') {
            if (params.page) {
                leftFor = page
                navigate(`/settings${hash}`, { replace: true })
            }
        } else if (!page) {
            const back = leftFor
            leftFor = null
            if (f === 'desktop' || back) navigate(`/settings/${back ?? PAGES[0].id}${hash}`, { replace: true })
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
                        <h1 class="page-title set-title-mark">
                            <VersionMark />
                            {t('nav.settings')}
                        </h1>
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
                                <BackButton onClick={() => navigate('/settings', { replace: true })} />
                                <h1>{page().title()}</h1>
                            </header>
                            <PageBody id={page().id} />
                        </>
                    )}
                </Match>
                <Match when={true}>
                    <header class="set-bar">
                        <BackButton onClick={() => back(navigate)} />
                        <h1 class="set-title-mark">
                            <VersionMark />
                            {t('nav.settings')}
                        </h1>
                    </header>
                    <PageList selected={null} />
                </Match>
            </Switch>
        </section>
    )
}

function BackButton(props: { onClick: () => void }) {
    return (
        <button class="round-button" type="button" aria-label={t('common.back')} title={t('common.back')} onClick={() => props.onClick()}>
            <Icon icon={ArrowLeft02Icon} size={22} />
        </button>
    )
}

/** The account, one row for each page, and Sign out last: under the card, as the apps have it. */
function PageList(props: { selected: PageId | null }) {
    return (
        <nav class="set-list" aria-label={t('nav.settings')}>
            <AccountHeader />
            <SettingsSearch context={rowContext} icons={PAGE_ICONS} />
            <Show when={!showingResults()}>
                <div class="set-card">
                    <For each={PAGES}>
                        {(p) => (
                            <A class="set-row" href={`/settings/${p.id}`} aria-current={props.selected === p.id ? 'page' : undefined}>
                                <RowBody icon={p.icon} title={p.title()} subtitle={p.summary()} />
                                <Show when={props.selected === null}>
                                    <Chevron />
                                </Show>
                            </A>
                        )}
                    </For>
                </div>
            </Show>
            <SignOut />
            <RemoveAllButton />
            <p class="muted small set-foot">{t('auth.unofficial')}</p>
            {/* The TMPlayer icon and the version, the very last line of the list (landscape and the two panes too); a press opens About, the update state is a chip beside it. */}
            <div class="set-version set-version-foot">
                <VersionFooter look="chip" />
            </div>
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
            <ProfileMenuButton />
        </Show>
    )
}

function PageBody(props: { id: PageId }) {
    return (
        <>
            <Switch>
                <Match when={props.id === 'account'}>
                    <Privacy />
                </Match>
                <Match when={props.id === 'playback'}>
                    <Playback />
                </Match>
                <Match when={props.id === 'subtitles'}>
                    <SubtitlesPage />
                </Match>
                <Match when={props.id === 'storage'}>
                    <StoragePage />
                </Match>
                <Match when={props.id === 'appearance'}>
                    <Appearance />
                </Match>
                <Match when={props.id === 'home'}>
                    <HomePage />
                    <Library />
                </Match>
                <Match when={props.id === 'help'}>
                    <Help />
                </Match>
                <Match when={props.id === 'about'}>
                    <Support />
                    <About />
                </Match>
            </Switch>
            <MovedRows page={props.id} />
        </>
    )
}

/** What left this page in the WP-08 order, for one release: the row's name and where it went (settings.also_in_moved). */
function MovedRows(props: { page: PageId }) {
    const gone = () =>
        MOVED.filter((m) => m.from === props.page)
            .map((m) => ({ ...m, row: rowFor(m.id)! }))
            .filter((m) => m.row && (m.row.shown?.(rowContext()) ?? true))
    return (
        <Show when={gone().length}>
            <div class="set-card moved-rows">
                <For each={gone()}>
                    {(m) => (
                        <A class="set-row" href={`/settings/${m.to}#${rowDomId(m.id)}`}>
                            <RowBody icon={PAGES.find((p) => p.id === m.to)!.icon} title={t(m.row.title as never)} subtitle={t('settings.also_in_moved', { page: t(PAGE_TITLES[m.to] as never) })} />
                            <Chevron />
                        </A>
                    )}
                </For>
            </div>
        </Show>
    )
}

function Appearance() {
    return (
        <>
            <div class="set-card set-blocks">
                <LanguageBlock />
            </div>
            <div class="set-card set-blocks">
                <div class="set-block" id={rowDomId('theme')}>
                    <h3 class="set-block-title" id="theme-label">{t('settings.theme')}</h3>
                    <ThemeChoices />
                </div>
                <Show when={(form() !== 'phone' || tabletClass()) && !layoutFixed()}>
                    <div class="set-block" id={rowDomId('layout')}>
                        <h3 class="set-block-title" id="layout-label">{t('settings.layout')}</h3>
                        <LayoutChoices />
                    </div>
                </Show>
            </div>
            <div class="set-card set-blocks">
                <LookChoices />
            </div>
        </>
    )
}
