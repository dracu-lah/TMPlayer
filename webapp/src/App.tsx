import { createEffect, createSignal, ErrorBoundary, lazy, Match, onMount, Show, Switch, type ParentProps } from 'solid-js'
import { A, Route, Router, useLocation, useNavigate } from '@solidjs/router'
import { api, errorText, sessionHere, useHere } from './tg/rpc'
import { online } from './net'
import { entryState, me, setMe, signOut } from './session'
import { loadSettings } from './store'
import { form, holdForm } from './form'
import { Home } from './screens/Home'
import { Nav } from './ui/Nav'
import { applyUpdate, updateReady } from './install'
import { t } from './i18n'

// Home comes with the first load. Sign in (with its QR code), the watch page (with the player and
// its remuxer) and the other pages load when needed; once signed in they are fetched while idle.
const SignIn = lazy(() => import('./screens/SignIn').then((m) => ({ default: m.SignIn })))
// The watch page brings the player's code with it, so the first Play waits for neither.
const loadWatch = () => import('./screens/Watch').then((m) => (m.loadPlayer().catch(() => {}), { default: m.Watch }))
const Watch = lazy(loadWatch)
const loadDetail = () => import('./screens/Detail').then((m) => ({ default: m.Detail }))
const loadChats = () => import('./screens/Chats').then((m) => ({ default: m.Chats }))
const loadChat = () => import('./screens/Chat').then((m) => ({ default: m.Chat }))
const loadSearch = () => import('./screens/Search').then((m) => ({ default: m.Search }))
const loadSettingsPage = () => import('./screens/Settings').then((m) => ({ default: m.Settings }))
const loadLibrary = () => import('./screens/Library')
const Chats = lazy(loadChats)
const Chat = lazy(loadChat)
const Search = lazy(loadSearch)
const Settings = lazy(loadSettingsPage)
const Detail = lazy(loadDetail)
const Favourites = lazy(() => loadLibrary().then((m) => ({ default: m.Favourites })))
const HistoryPage = lazy(() => loadLibrary().then((m) => ({ default: m.HistoryPage })))
const later = [loadChats, loadChat, loadSearch, loadSettingsPage, loadDetail, loadWatch]
// The TV layout's remote, bar and carousel (plan 8.5): only fetched in the TV form.
const loadTv = () => import('./tv')
const TvKeys = lazy(() => loadTv().then((m) => ({ default: m.TvKeys })))
const TvBar = lazy(() => loadTv().then((m) => ({ default: m.TvBar })))

// Safari has no requestIdleCallback.
const idle = (fn: () => void) => ('requestIdleCallback' in window ? requestIdleCallback(fn) : setTimeout(fn, 1500))

/** Every page: waits for the session, sends a signed out visitor to sign in and back again after. */
function Shell(props: ParentProps) {
    const location = useLocation()
    const navigate = useNavigate()
    const [error, setError] = createSignal<string | null>(null)

    onMount(async () => {
        try {
            const [user] = await Promise.all([api.me(), loadSettings()])
            setMe(user)
            // The saved session is used at once; Telegram's answer follows, and says when it was ended elsewhere.
            if (user) api.whoami().then((u) => void (u ? setMe(u) : signOut()), () => {})
        } catch (e) {
            setError(errorText(e))
        }
    })

    createEffect(() => {
        const user = me()
        if (user === undefined) return
        const onSignIn = location.pathname === '/signin'
        if (!user && !onSignIn) {
            const next = location.pathname + location.search
            navigate(next === '/' ? '/signin' : `/signin?next=${encodeURIComponent(next)}`, { replace: true, state: entryState() })
        } else if (user && onSignIn) {
            const next = new URLSearchParams(location.search).get('next')
            navigate(next?.startsWith('/') ? next : '/', { replace: true, state: entryState() })
        }
        // Fetched ahead only: one that fails now (offline) is fetched again when its page opens.
        if (user) idle(() => later.forEach((load) => load().catch(() => {})))
    })

    const signedIn = () => me() && location.pathname !== '/signin'
    const playing = () => location.pathname.startsWith('/watch/')
    // A video's page is a page of its own over everything, as the apps' detail is: no bar under it.
    const detail = () => location.pathname.startsWith('/video/')
    // A resize in Auto layout waits while a video plays.
    createEffect(() => holdForm(playing()))

    return (
        <>
            <Switch fallback={<Boot />}>
                <Match when={error()}>
                    <div class="boot">
                        <p class="error">{error()}</p>
                        <button class="primary" onClick={() => window.location.reload()}>{t('common.try_again')}</button>
                    </div>
                </Match>
                <Match when={me() === null && location.pathname === '/signin'}>{props.children}</Match>
                <Match when={signedIn()}>
                    <main class="page" classList={{ playing: playing(), bleed: detail() }}>
                        <ErrorBoundary
                            fallback={(e, reset) => (
                                <div class="boot">
                                    <p class="error">{errorText(e)}</p>
                                    <button class="primary" onClick={reset}>Try again</button>
                                </div>
                            )}
                        >
                            {props.children}
                        </ErrorBoundary>
                    </main>
                    <Show when={!playing() && !detail()}>
                        <Show when={form() === 'tv'} fallback={<Nav />}>
                            <TvBar />
                        </Show>
                    </Show>
                </Match>
            </Switch>
            <Show when={!playing()}>
                {/* Reload cannot fetch a new version offline: the notice waits for the connection. */}
                <Show when={!online()} fallback={<UpdateNotice />}>
                    <p class="offline-line" role="status">{t('web.offline')}</p>
                </Show>
            </Show>
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
            <Route path="/search" component={Search} />
            <Route path="/video/:chatId/:messageId" component={Detail} />
            <Route path="/watch/:chatId/:messageId" component={Watch} />
            <Route path="/settings" component={Settings} />
            <Route path="/favourites" component={Favourites} />
            <Route path="/history/:tab?" component={HistoryPage} />
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
            <button class="primary" type="button" onClick={useHere}>{t('web.use_here')}</button>
        </div>
    )
}

/** The first frame while the session is checked: the logo mark, as index.html paints it. */
function Boot() {
    return (
        <div class="boot">
            <img class="boot-mark" src="/icon.svg" alt="TMPlayer" width="64" height="64" />
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
            <div class="update-notice" role="status">
                <p>{t('web.update_ready')}</p>
                <button class="link" type="button" onClick={() => setLater(true)}>{t('update.later_short')}</button>
                <button class="primary" type="button" onClick={applyUpdate}>{t('player.reload')}</button>
            </div>
        </Show>
    )
}
