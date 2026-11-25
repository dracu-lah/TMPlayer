import { onGlobal } from './ui/globalInput'
import { createEffect, createMemo, createSignal, ErrorBoundary, lazy, Match, on, onCleanup, onMount, Show, Switch, type Component, type ParentProps } from 'solid-js'
import { Dynamic } from 'solid-js/web'
import { A, Route, Router, useLocation, useNavigate } from '@solidjs/router'
import { api, errorText, sessionHere, useHere } from './tg/rpc'
import { linkLine, online, unreachable, watchReach } from './net'
import { back, entryState, me, sessionEndedElsewhere, setMe } from './session'
import { closeTopLayer, locked, picker, shieldPage } from './accounts'
import { holdClose, openModal } from './ui/confirmCore'
import { afterSessionRead, attachSignedIn, cleanStaleProfiles, sessionUnreadable, showCarriedNotice } from './accountFlows'
import { loadSubmaker } from './submakerBase'
import { chatsGated as gated, loadSettings, setChatsGateWaived } from './store'
import { openSync, stopSync } from './sync'
import { form, holdForm } from './form'
import { Home } from './screens/Home'
import { Nav } from './ui/Nav'
import { Toaster } from './ui/Toast'
import { SaveStatus } from './ui/SaveStatus'
import { ReachBanner } from './ui/ReachBanner'
import { LinkStatusLine } from './ui/WorkStatus'
import { CardMenu } from './ui/CardMenu'
import { installShellKeys } from './ui/shellKeys'
import { ListPicker, ListsPage } from './screens/Lists'
import { PosterPicker } from './ui/PosterPicker'
import { TrailerSheet } from './ui/TrailerSheet'
import { applyUpdate, inAppIos, updateReady } from './install'
import { InAppCard } from './screens/signin/InAppCard'
import { scheduleTrim } from './cacheBounds'
import { t } from './i18n'
import { saveData } from './ui/saveData'
import { focusTarget, pageName, pageTitle } from './ui/pageTitle'
import { PAGE_TITLES, pageFor } from './ui/settingsMap'

// Home comes with the first load. Sign in (with its QR code), the watch page (with the player and
// its remuxer) and the other pages load when needed; once signed in they are fetched while idle.
const SignIn = lazy(() => import('./screens/SignIn').then((m) => ({ default: m.SignIn })))
// The watch page brings the player's code with it, so the first Play waits for neither.
const loadWatch = () => import('./screens/Watch').then((m) => (m.loadPlayer().catch(() => {}), { default: m.Watch }))
const Watch = lazy(loadWatch)
const loadDetail = () => import('./screens/Detail').then((m) => ({ default: m.Detail }))
const loadChats = () => import('./screens/Chats').then((m) => ({ default: m.Chats }))
const loadChat = () => import('./screens/Chat').then((m) => ({ default: m.Chat }))
const loadChatInfo = () => import('./screens/ChatInfo').then((m) => ({ default: m.ChatInfo }))
const loadRecent = () => import('./screens/Recent').then((m) => ({ default: m.Recent }))
const loadAddChats = () => import('./screens/AddChats').then((m) => ({ default: m.AddChats }))
const SetupChats = lazy(() => import('./screens/SetupChats').then((m) => ({ default: m.SetupChats })))
const loadSearch = () => import('./screens/Search').then((m) => ({ default: m.Search }))
const loadSettingsPage = () => import('./screens/Settings').then((m) => ({ default: m.Settings }))
const loadLibrary = () => import('./screens/Library')
const Chats = lazy(loadChats)
const Chat = lazy(loadChat)
const ChatInfo = lazy(loadChatInfo)
const Search = lazy(loadSearch)
const Recent = lazy(loadRecent)
const Settings = lazy(loadSettingsPage)
const Detail = lazy(loadDetail)
const AddChats = lazy(loadAddChats)
const Downloads = lazy(() => import('./screens/Downloads').then((m) => ({ default: m.Downloads })))
const Favourites = lazy(() => loadLibrary().then((m) => ({ default: m.Favourites })))
const HistoryPage = lazy(() => loadLibrary().then((m) => ({ default: m.HistoryPage })))
const later = [loadChats, loadChat, loadChatInfo, loadSearch, loadRecent, loadSettingsPage, loadDetail, loadWatch, loadAddChats]
// The TV layout's remote, bar and carousel (plan 8.5): only fetched in the TV form.
const loadTv = () => import('./tv')
const TvKeys = lazy(() => loadTv().then((m) => ({ default: m.TvKeys })))
const TvBar = lazy(() => loadTv().then((m) => ({ default: m.TvBar })))
// "Who's watching?" (HX88): only fetched where a browser has profiles to choose from. Until it is in
// (or when it cannot come, offline) an opaque cover stands in its place, so a locked profile's
// screens never show under it.
const [ProfilesView, setProfilesView] = createSignal<Component | null>(null)
const [profilesFailed, setProfilesFailed] = createSignal(false)
let profilesAsked = false
function loadProfiles() {
    if (profilesAsked) return
    profilesAsked = true
    import('./ui/Profiles').then(
        (m) => setProfilesView(() => m.Profiles),
        () => setProfilesFailed(true),
    )
}

/** What covers the page while the picker loads, or when it could not: nothing of the profile, and Try again. */
function LockCover() {
    // A modal dialog in the top layer too, closed by nothing: the picker takes its place, or a reload.
    const open = (d: HTMLDialogElement) =>
        queueMicrotask(() => {
            closeTopLayer(d)
            holdClose(d, () => true)
            openModal(d)
        })
    return (
        <dialog class="lock-cover" ref={open} aria-label={t('accounts.cover_loading')} onCancel={(e) => e.preventDefault()}>
            <Show when={profilesFailed()} fallback={<div class="loader" role="progressbar" aria-label={t('accounts.cover_loading')} />}>
                <p role="alert">{t('accounts.cover_failed')}</p>
                {/* A reload starts on the picker again: nothing unlocked rides along. */}
                <button class="primary" type="button" ref={(el) => queueMicrotask(() => el.focus())} onClick={() => location.reload()}>
                    {t('common.try_again')}
                </button>
            </Show>
        </dialog>
    )
}

/** The pages the chats gate leaves alone: signing in, and the gate's own two. */
const GATE_FREE = ['/signin', '/setup/chats', '/added-chats']

/**
 * The chats gate (the apps' ChatsGate): a signed in visitor who has never chosen which chats to
 * watch is sent to choose first, whatever page they asked for, then on to it. An answer on file
 * ('picked', or 'all' from before choosing was required) never sees it again.
 */
const toGate = (next: string) => `/setup/chats?next=${encodeURIComponent(next)}`

// Safari has no requestIdleCallback.
const idle = (fn: () => void) => ('requestIdleCallback' in window ? requestIdleCallback(fn) : setTimeout(fn, 1500))

const [inAppStay, setInAppStay] = createSignal(false)

/** Every page: waits for the session, sends a signed out visitor to sign in and back again after. */
function Shell(props: ParentProps) {
    const location = useLocation()
    const navigate = useNavigate()
    const [error, setError] = createSignal<string | null>(null)
    // "Can't reach Telegram" (U2): signed in, holding the session, the browser online and the line down for 20 s.
    watchReach(() => !!me() && sessionHere() === 'here')

    onMount(async () => {
        // A line a reload carried over (a profile signed out, the same account twice), and profiles a closed tab left half added.
        showCarriedNotice()
        void cleanStaleProfiles()
        try {
            // At launch with profiles to choose from, the session is read once one is chosen (rpc.openSession).
            const [user] = await Promise.all([api.me(), loadSettings(), loadSubmaker()])
            const next = await afterSessionRead(user)
            if (next === 'leaving') return
            if (next === 'signedOut') return void setMe(null)
            setMe(user)
            // The saved session is used at once; Telegram's answer follows, and says when it was ended elsewhere.
            if (user)
                api.whoami().then(
                    async (u) => {
                        if (!u) return sessionEndedElsewhere()
                        if (await attachSignedIn(u)) setMe(u)
                    },
                    () => {},
                )
        } catch (e) {
            // With other profiles, this one needs attention and the picker opens over the error.
            sessionUnreadable()
            setError(errorText(e))
        }
    })

    createEffect(() => {
        const user = me()
        if (user === undefined) return
        const onSignIn = location.pathname === '/signin'
        // Signed out: whoever signs in next is weighed by the gate afresh.
        if (!user) (setChatsGateWaived(false), stopSync())
        if (!user && !onSignIn) {
            const next = location.pathname + location.search
            navigate(next === '/' ? '/signin' : `/signin?next=${encodeURIComponent(next)}`, { replace: true, state: entryState() })
        } else if (user && onSignIn) {
            const next = new URLSearchParams(location.search).get('next')
            const to = next?.startsWith('/') ? next : '/'
            // Just signed in and never asked which chats to watch: straight to the gate, not past a page first.
            navigate(gated() ? toGate(to) : to, { replace: true, state: entryState() })
        }
        // Fetched ahead only: one that fails now (offline) is fetched again when its page opens.
        // The other pages are fetched ahead when the line has room; on Save-Data each is fetched when it opens (ui/saveData.ts).
        if (user && !saveData()) idle(() => later.forEach((load) => load().catch(() => {})))
        // Sync across my devices: what was kept is read, and a sync follows if the viewer turned it on.
        if (user) openSync()
    })
    // Every other page, not only the one after signing in: a visitor already signed in who never
    // chose (an answer left open before choosing was required) meets the gate on their next visit.
    createEffect(() => {
        if (!me() || !gated()) return
        const path = location.pathname
        if (GATE_FREE.includes(path)) return
        navigate(toGate(path + location.search), { replace: true })
    })
    // A gate waived while offline asks again once the connection is back.
    createEffect(on(online, (now, was) => {
        if (now && was === false) setChatsGateWaived(false)
    }))
    // Signed in: keep the picture caches within their caps. The browser's "keep the data" ask waits
    // for the first video watched (Watch.tsx), so it never lands on the setup loader or the gate (ON-14).
    let kept = false
    createEffect(() => {
        if (!me() || kept) return
        kept = true
        scheduleTrim()
    })

    const signedIn = () => me() && location.pathname !== '/signin'
    const playing = () => location.pathname.startsWith('/watch/')
    // A video's page is a page of its own over everything, as the apps' detail is: no bar under it.
    const detail = () => location.pathname.startsWith('/video/')
    // The gate stands alone, as the apps' setup does: no bar to wander off through.
    const setup = () => location.pathname.startsWith('/setup/') || (location.pathname === '/added-chats' && new URLSearchParams(location.search).has('setup'))
    // The desktop app's window keys (HX17): search, find, choose chats, Downloads, Home, Chats, a card's menu.
    // Not on the TV skin (its remote walk is tv/focus.ts), in the player (its own keys) or on the chats gate.
    onMount(() => {
        const off = installShellKeys({
            enabled: () => !!me() && form() !== 'tv' && !playing() && !setup() && location.pathname !== '/signin',
            path: () => location.pathname,
            go: (path) => navigate(path),
            back: () => back(navigate),
            searchLabel: () => t('browse.search_chat'),
            listen: (onKey) => onGlobal(window, 'keydown', onKey),
        })
        onCleanup(off)
    })
    // The picker's code, the first time it is wanted (at launch, from the menu, or locking again).
    createEffect(() => picker() && loadProfiles())
    // Locked (HX88): the rest of the page is inert and hidden, and the player closes, so its streams,
    // decoders and sound go (accounts.ts stopped its media and kept its place first).
    createEffect(() => shieldPage(locked()))
    createEffect(() => {
        if (locked() && location.pathname.startsWith('/watch/')) navigate('/', { replace: true })
    })
    // A resize in Auto layout waits while a video plays.
    createEffect(() => holdForm(playing()))

    // The tab, the app switcher and a screen reader name the page (WCAG 2.4.2). A chat's or a video's name
    // is the page's own and never shows while a profile is locked (ui/pageTitle.ts).
    const title = createMemo(() =>
        pageTitle({
            path: location.pathname,
            say: (key) => t(key as never),
            name: pageName(),
            settingsPage: (() => {
                const page = pageFor(location.pathname.split('/')[2])
                return page ? PAGE_TITLES[page] : null
            })(),
            locked: locked(),
        }),
    )
    createEffect(() => {
        document.title = title()
    })
    // After a navigation the keyboard and a screen reader start at the new page: its heading, else the page itself, unless the page took focus on purpose.
    let firstRoute = true
    let settleTimer = 0
    const settleFocus = (tries = 0) => {
        clearTimeout(settleTimer)
        settleTimer = window.setTimeout(() => {
            const page = document.querySelector<HTMLElement>('main.page')
            const heading = page?.querySelector<HTMLElement>('h1') ?? null
            // A page that draws after its data has its heading a moment later.
            if (!heading && tries < 6) return settleFocus(tries + 1)
            const target = focusTarget({ heading, page, active: document.activeElement, body: document.body, first: false })
            if (!target) return
            if (!target.hasAttribute('tabindex')) target.tabIndex = -1
            target.focus({ preventScroll: true })
        }, tries === 0 ? 60 : 150)
    }
    createEffect(
        on(
            () => location.pathname,
            () => {
                if (firstRoute) return void (firstRoute = false)
                // The TV's remote walk is tv/focus.ts; the player has its own keys; a locked profile shows the picker.
                if (form() === 'tv' || playing() || locked() || picker()) return
                settleFocus()
            },
        ),
    )
    onCleanup(() => clearTimeout(settleTimer))
    // The bar is last in the page: a keyboard user's way to it without tabbing through the page.
    const navShown = () => form() !== 'tv' && !playing() && !detail() && !setup() && !location.pathname.startsWith('/chat/')
    const toNav = (e: MouseEvent) => {
        e.preventDefault()
        document.querySelector<HTMLElement>('#main-nav .nav-cell')?.focus()
    }

    return (
        <>
            <Switch fallback={<Boot />}>
                <Match when={error()}>
                    <div class="boot">
                        <p class="error" role="alert">{error()}</p>
                        <button class="primary" onClick={() => window.location.reload()}>{t('common.try_again')}</button>
                    </div>
                </Match>
                <Match when={me() === null && location.pathname === '/signin'}>
                    {/* Inside another app's web view the sign in would be lost: Safari first (WP-58). */}
                    <Show when={inAppIos && !inAppStay()} fallback={props.children}>
                        <InAppCard onContinue={() => setInAppStay(true)} />
                    </Show>
                </Match>
                <Match when={signedIn()}>
                    <Show when={navShown()}>
                        <a class="skip-link" href="#main-nav" onClick={toNav}>{t('web.skip_to_navigation')}</a>
                    </Show>
                    <main class="page" classList={{ playing: playing(), bleed: detail() }} inert={picker() ? true : undefined}>
                        <ErrorBoundary
                            fallback={(e, reset) => (
                                <div class="boot">
                                    <p class="error" role="alert">{errorText(e)}</p>
                                    <button class="primary" onClick={reset}>{t('common.try_again')}</button>
                                </div>
                            )}
                        >
                            {props.children}
                        </ErrorBoundary>
                    </main>
                    <Show when={!playing() && !detail() && !setup()}>
                        {/* A chat has its own bar at the foot on a phone and a desktop (Chat.tsx). */}
                        <Show when={form() === 'tv'} fallback={<Show when={!location.pathname.startsWith('/chat/')}><Nav /></Show>}>
                            <TvBar />
                        </Show>
                    </Show>
                </Match>
            </Switch>
            <Show when={!playing()}>
                {/* Reload cannot fetch a new version offline: the notice waits for the connection. */}
                <Show when={!online()} fallback={<Show when={unreachable()} fallback={<><LinkStatusLine /><Show when={!linkLine()}><UpdateNotice /></Show></>}><ReachBanner /></Show>}>
                    <p class="offline-line" role="status">{t('web.offline')}</p>
                </Show>
            </Show>
            <Show when={picker()}>
                <Show when={ProfilesView()} fallback={<LockCover />}>
                    {(View) => <Dynamic component={View()} />}
                </Show>
            </Show>
            <Toaster />
            <SaveStatus />
            <CardMenu />
            <ListPicker />
            <PosterPicker />
            <TrailerSheet />
            <Show when={sessionHere() === 'elsewhere'}>
                <OtherTab />
            </Show>
            <Show when={form() === 'tv'}>
                <TvKeys />
            </Show>
        </>
    )
}

export function App() {
    return (
        <Router root={Shell}>
            <Route path="/" component={Home} />
            <Route path="/signin" component={SignIn} />
            <Route path="/chats" component={Chats} />
            <Route path="/chat/:chatId" component={Chat} />
            <Route path="/chat/:chatId/info" component={ChatInfo} />
            <Route path="/search" component={Search} />
            <Route path="/recent" component={Recent} />
            <Route path="/added-chats" component={AddChats} />
            <Route path="/setup/chats" component={SetupChats} />
            <Route path="/video/:chatId/:messageId" component={Detail} />
            <Route path="/watch/:chatId/:messageId" component={Watch} />
            <Route path="/settings/:page?" component={Settings} />
            <Route path="/downloads" component={Downloads} />
            <Route path="/favourites" component={Favourites} />
            <Route path="/history/:tab?" component={HistoryPage} />
            <Route path="/lists/:id?" component={ListsPage} />
            <Route path="*" component={NotFound} />
        </Router>
    )
}

function NotFound() {
    return (
        <div class="boot">
            <p>{t('web.not_found')}</p>
            <A class="link" href="/">{t('browse.tab_home')}</A>
        </div>
    )
}

/** Another tab of TMPlayer holds the Telegram session; this one takes it on "Use it here", or once that tab closes. */
function OtherTab() {
    return (
        <div class="other-tab" role="alertdialog" aria-labelledby="other-tab-title">
            <img class="boot-mark" src="/icon.svg" alt="" width="64" height="64" />
            <h1 id="other-tab-title">{t('web.other_tab_title')}</h1>
            <p class="muted">{t('web.other_tab_body')}</p>
            {/* An alert dialog takes the keyboard: Enter on its one button works at once. */}
            <button class="primary" type="button" ref={(el) => queueMicrotask(() => el.focus())} onClick={useHere}>{t('web.use_here')}</button>
        </div>
    )
}

/** The first frame while the session is checked: the logo mark, as index.html paints it. */
function Boot() {
    return (
        <div class="boot">
            <div class="loader" role="progressbar" aria-label="TMPlayer" />
        </div>
    )
}

/**
 * A new version is ready: Reload takes it, Later hides this until the next start. Never over the
 * player, so a video is not interrupted; it shows again once the watch page closes.
 */
function UpdateNotice() {
    const [later, setLater] = createSignal(false)
    return (
        <Show when={updateReady() && !later()}>
            {/* On a TV, reached from the bar or the page's foot, never taking the remote itself (HX22). */}
            <div class="update-notice" role="status" data-tv-reach="bottom">
                <p>{t('web.update_ready')}</p>
                <button class="link" type="button" onClick={() => setLater(true)}>{t('update.later_short')}</button>
                <button class="primary" type="button" onClick={applyUpdate}>{t('player.reload')}</button>
            </div>
        </Show>
    )
}
