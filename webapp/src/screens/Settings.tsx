import { createEffect, For, Match, Show, Switch } from 'solid-js'
import { A, useLocation, useNavigate, useParams } from '@solidjs/router'
import { ArrowLeft02Icon, HardDriveIcon, Home03Icon, InformationCircleIcon, LockKeyholeIcon, PaintBoardIcon, PlayIcon } from '@hugeicons/core-free-icons'
import { OLD_PAGES, pageFor, type PageId, type RowContext } from '../ui/settingsMap'
import { rowDomId, SettingsSearch, showingResults, useRowTarget } from './SettingsFind'
import { settings } from '../store'
import { back, me } from '../session'
import { language, languageName, t } from '../i18n'
import { form, layout, layoutFixed } from '../form'
import { Icon, type IconData } from '../ui/Icon'
import { Initial } from '../ui/PageBar'
import { installed, installPrompt, iosBrowser } from '../install'
import { msoneOn } from '../msoneFlag'
import { syncHasFile, syncOn } from '../sync'
import { Chevron, RowBody } from '../ui/SettingsParts'
import { About } from './settings/About'
import { HomePage, Library } from './settings/Home'
import { LanguageBlock } from './settings/Language'
import { Playback } from './settings/Playback'
import { Privacy } from './settings/Privacy'
import { SignOut } from './settings/SignOut'
import { ProfileMenuButton, RemoveAllButton } from '../ui/Profiles'
import { LayoutChoices, ThemeChoices } from './settings/shared'
import { StoragePage } from './settings/Storage'
import { Support } from './settings/Support'
import { TvSettings } from './settings/Tv'
import { dot } from '../ui/dot'
import { VersionFooter } from '../ui/VersionFooter'

/** The app's pages (SettingsPage) that the web has something to put in, in its order. Home and library are one page, as in the apps (11.1). */
const PAGES: { id: PageId; icon: IconData; title: () => string; summary: () => string }[] = [
    {
        id: 'appearance',
        icon: PaintBoardIcon,
        title: () => t('settings.appearance'),
        summary: () => [t(`theme.${settings().theme}`), form() === 'phone' || layoutFixed() ? '' : t(`settings.layout_${layout()}`), languageName(language())].filter(Boolean).join(dot),
    },
    { id: 'home', icon: Home03Icon, title: () => t('settings.page_home_library'), summary: () => t('settings.page_home_library_summary') },
    { id: 'playback', icon: PlayIcon, title: () => t('settings.playback'), summary: () => t('settings.resume_rewind') },
    { id: 'privacy', icon: LockKeyholeIcon, title: () => t('settings.page_privacy_lock'), summary: () => (syncOn() ? t('syncplace.switch_on') : t('syncplace.switch_off')) },
    { id: 'storage', icon: HardDriveIcon, title: () => t('web.settings_storage'), summary: () => t('web.settings_storage_summary') },
    { id: 'about', icon: InformationCircleIcon, title: () => t('settings.page_about'), summary: () => t('about.licence_line') },
]
const PAGE_ICONS = Object.fromEntries(PAGES.map((p) => [p.id, p.icon])) as Record<PageId, IconData>

/** What decides which rows this page draws, for the search over them. */
function rowContext(): RowContext {
    return {
        form: form(),
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
                        <h1>{t('nav.settings')}</h1>
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
                {/* The version, the build and the update state, quietly; a press copies them (WP-104). */}
                <div class="set-version">
                    <VersionFooter look="line" />
                </div>
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
        <Switch>
            <Match when={props.id === 'appearance'}>
                <Appearance />
            </Match>
            <Match when={props.id === 'home'}>
                <HomePage />
                <Library />
            </Match>
            <Match when={props.id === 'playback'}>
                <Playback />
            </Match>
            <Match when={props.id === 'privacy'}>
                <Privacy />
            </Match>
            <Match when={props.id === 'storage'}>
                <StoragePage />
            </Match>
            <Match when={props.id === 'about'}>
                <Support />
                <About />
            </Match>
        </Switch>
    )
}

function Appearance() {
    return (
        <>
            <div class="set-card set-blocks">
                <div class="set-block" id={rowDomId('theme')}>
                    <h3 class="set-block-title" id="theme-label">{t('settings.theme')}</h3>
                    <ThemeChoices />
                </div>
                <Show when={form() !== 'phone' && !layoutFixed()}>
                    <div class="set-block" id={rowDomId('layout')}>
                        <h3 class="set-block-title" id="layout-label">{t('settings.layout')}</h3>
                        <LayoutChoices />
                    </div>
                </Show>
            </div>
            <div class="set-card set-blocks">
                <LanguageBlock />
            </div>
        </>
    )
}
