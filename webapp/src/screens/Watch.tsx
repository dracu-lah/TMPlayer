import { createResource, lazy, Match, Switch } from 'solid-js'
import { useLocation, useNavigate, useParams } from '@solidjs/router'
import { api, errorText } from '../tg/rpc'
import { back } from '../session'
import { t } from '../i18n'
import { progress as readProgress, settings } from '../store'
import { chatGone, forgetGone } from '../goneVideos'
import { watchPath } from './VideoList'
import { warmFile } from '../stream/reader'
import type { ChatView, VideoItem } from '../tg/types'

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
    const [video] = createResource(ids, async ({ chatId, messageId }) => {
        const passed = location.state?.video
        let found: VideoItem | null
        try {
            found = passed?.chatId === chatId && passed.messageId === messageId && (await api.known(passed.key)) ? passed : await api.video(chatId, messageId)
        } catch (e) {
            // Its chat closed to this account: the page says so, and the lists let it go (HX21).
            if (chatGone(e)) void forgetGone([{ chatId, messageId }], false)
            throw e
        }
        // Gone from the chat: it leaves Continue watching, Watched and Watch later (HX21), which
        // the page need not say on top of saying the video is gone.
        if (found === null) void forgetGone([{ chatId, messageId }], false)
        // The file's head and tail start arriving while the player's code loads; the player takes them over.
        else warmFile(found)
        return found
    })
    const [progress] = createResource(readProgress)
    const view = (): ChatView => ({
        sort: settings().sort,
        minBytes: settings().sizeMin,
        maxBytes: settings().sizeMax,
        watched: progress()?.watched ?? [],
        started: progress()?.started ?? [],
        query: '',
    })
    // The episodes and the next one wait for the chat's videos; the player does not wait for them.
    const [info] = createResource(
        () => (progress.state === 'ready' ? { ...ids(), view: view() } : null),
        ({ chatId, messageId, view }) => api.watch(chatId, messageId, view).catch((e) => (console.warn('[watch]', e), null)),
    )
    const close = () => back(navigate)
    const play = (next: VideoItem) => navigate(watchPath(next), { state: { video: next }, replace: true })

    return (
        <div class="watch">
            <Switch fallback={<div class="player"><div class="spinner" aria-label={t('web.loading_player')} /></div>}>
                <Match when={video.error}>
                    <div class="boot">
                        <p class="error">{errorText(video.error)}</p>
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
                    {(item) => <Player item={item} info={info() ?? null} onClose={close} onPlay={play} />}
                </Match>
            </Switch>
        </div>
    )
}
