import { createEffect, createMemo, createResource, createSignal, lazy, Match, onCleanup, Show, Switch } from 'solid-js'
import { setPageName } from '../ui/pageTitle'
import { useLocation, useNavigate, useParams } from '@solidjs/router'
import { api, errorText } from '../tg/rpc'
import { back } from '../session'
import { askKeepOnce } from '../install'
import { t } from '../i18n'
import { locked } from '../accounts'
import { progress as readProgress, settings } from '../store'
import { chatGone, forgetGone } from '../goneVideos'
import { watchPath } from './VideoList'
import { ensureTouches } from './touches'
import { warmFile } from '../stream/reader'
import { looksSplit } from '../stream/joined'
import { readChatOrder } from '../player/keep'
import { notePlayed } from '../copies'
import { activeRun, around, belongs, endRun, nearest } from './playAllRun'
import type { ChatView, VideoItem, WatchInfo } from '../tg/types'

/** The player's code (the engines and the controls), also fetched while idle once signed in (App.tsx). */
export const loadPlayer = () => import('../player/Player').then((m) => ({ default: m.Player }))
const Player = lazy(loadPlayer)

/**
 * One video playing, at `/watch/<chat>/<message>`: the apps' player screen, the picture across the
 * whole window with the controls over it. The video's details are its page before this one
 * (Detail.tsx). Opened from a page it starts at once with the item passed along; opened by its
 * address (or after the worker restarted) it asks Telegram for the message first. Back, Escape
 * and the iPhone's swipe back all close it; the next episode replaces this page, so Back still
 * goes to the page it was opened from.
 */
export function Watch() {
    const params = useParams<{ chatId: string; messageId: string }>()
    const location = useLocation<{ video?: VideoItem }>()
    const navigate = useNavigate()
    const ids = () => ({ chatId: Number(params.chatId), messageId: Number(params.messageId) })
    // While locked nothing of the profile starts: no lookup, no warm-up download, no play note.
    const [video] = createResource(() => (locked() ? null : ids()), async ({ chatId, messageId }) => {
        const passed = location.state?.video
        let found: VideoItem | null
        try {
            found = passed?.chatId === chatId && passed.messageId === messageId && (await api.known(passed.key)) ? passed : await api.video(chatId, messageId)
        } catch (e) {
            // Its chat closed to this account: the page says so, and the lists let it go (HX21).
            if (chatGone(e)) void forgetGone([{ chatId, messageId }], false)
            throw e
        }
        // Opened on one piece of a video cut into pieces (Continue watching, an address): the rest of its pieces are found in the chat.
        if (found && !found.joined && found.fileName && looksSplit(found.fileName)) found = (await api.joined(chatId, messageId).catch(() => null)) ?? found
        // Gone from the chat: it leaves Continue watching, Watched and Watch later (HX21), which
        // the page need not say on top of saying the video is gone.
        if (found === null) void forgetGone([{ chatId, messageId }], false)
        // The file's head and tail start arriving while the player's code loads; the player takes them over.
        else warmFile(found)
        return found
    })
    // The browser's one ask to keep the data comes with the first video watched, not at sign in,
    // where Firefox's prompt landed over the setup loader and the chats gate before any value (ON-14).
    createEffect(() => {
        if (video()) askKeepOnce().catch(() => {})
    })
    // The tab's title names the video while it plays (ui/pageTitle.ts).
    createEffect(() => setPageName(video()?.title))
    onCleanup(() => setPageName(null))
    const [progress] = createResource(readProgress)
    // Play all (WP-32): a run started in a grid or a list goes on to the next video by itself. It ends when this page
    // closes or a video that is not part of it opens (an address, Continue watching), never carried to a stranger.
    const [run, setRun] = createSignal(activeRun())
    createEffect(() => {
        const r = run()
        if (r && !belongs(r, ids())) (endRun(), setRun(null))
    })
    onCleanup(endRun)
    // "After a video that is not an episode": the chat's next video in the grid's order (EG-F30).
    const [chatOrder] = createResource(() => readChatOrder().catch(() => false))
    // The Last played order needs the times the worker holds, so Next and autoplay run in the grid's order; a failed send is no times.
    const lastPlayed = () => settings().sort === 'LastPlayed'
    const [touchVer] = createResource(lastPlayed, (on) => (on ? ensureTouches().catch(() => 0) : 0))
    const view = (): ChatView => ({
        // A chat's run follows the grid's order whatever the setting says.
        chatOrder: (chatOrder() ?? false) || run()?.kind === 'chat',
        sort: settings().sort,
        minBytes: settings().sizeMin,
        maxBytes: settings().sizeMax,
        watched: progress()?.watched ?? [],
        started: progress()?.started ?? [],
        query: '',
        ...(lastPlayed() && (touchVer() ?? 0) > 0 ? { touches: touchVer() } : {}),
    })
    // The episodes and the next one wait for the chat's videos; the player does not wait for them.
    const [info] = createResource(
        () => (!locked() && progress.state === 'ready' && chatOrder.state === 'ready' && (!lastPlayed() || touchVer.state === 'ready') ? { ...ids(), view: view() } : null),
        ({ chatId, messageId, view }) => api.watch(chatId, messageId, view).catch((e) => (console.warn('[watch]', e), null)),
    )
    // A list's run: the video after and before this one are the list's, not the chat's (a list spans chats).
    const [neighbours] = createResource(
        () => {
            const r = run()
            return r?.kind === 'list' ? { keys: r.keys, key: `${ids().chatId}:${ids().messageId}` } : null
        },
        async ({ keys, key }) => {
            const { next, previous } = around(keys, key)
            const get = (k: string) => {
                const [chatId, messageId] = k.split(':').map(Number)
                return api.video(chatId!, messageId!).catch((e) => (chatGone(e) ? null : Promise.reject(e)))
            }
            const [after, before] = await Promise.all([nearest(next, get), nearest(previous, get)])
            return { next: after, previous: before }
        },
    )
    const shown = createMemo((): WatchInfo | null => {
        const base = info() ?? null
        if (!base || run()?.kind !== 'list') return base
        const n = neighbours.state === 'ready' ? neighbours() : undefined
        return { ...base, next: n?.next ?? null, nextCode: null, previous: n?.previous ?? null, previousCode: null }
    })
    // A play noted in the quality the viewer usually plays (QualityHabit), once per video opened: a
    // card of a video posted more than once then plays the copy nearest it (HX38).
    let noted = ''
    createEffect(() => {
        const now = info()
        const key = `${params.chatId}:${params.messageId}`
        if (!now || noted === key) return
        noted = key
        notePlayed(now.qualityHeight)
    })
    const close = () => back(navigate)
    const play = (next: VideoItem) => navigate(watchPath(next), { state: { video: next }, replace: true })

    // Locked: nothing mounts (a browser Forward to /watch must not load the player or set the Media Session before the redirect).
    return (
        <Show when={!locked()}>
            <div class="watch">
                <Switch fallback={<div class="player"><div class="spinner" aria-label={t('web.loading_player')} /></div>}>
                    <Match when={video.error}>
                        <div class="boot">
                            <p class="error" role="alert">{errorText(video.error)}</p>
                            <button class="primary" onClick={close}>{t('common.back')}</button>
                        </div>
                    </Match>
                    <Match when={video.state === 'ready' && video() === null}>
                        <div class="boot">
                            <p>{t('web.gone_from_chat')}</p>
                            <button class="primary" onClick={close}>{t('common.back')}</button>
                        </div>
                    </Match>
                    <Match when={video()} keyed>
                        {(item) => <Player item={item} info={shown()} onClose={close} onPlay={play} playAll={run() !== null} />}
                    </Match>
                </Switch>
            </div>
        </Show>
    )
}
