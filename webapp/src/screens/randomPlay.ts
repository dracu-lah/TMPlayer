// Play random (U16) over a list the page already holds: Watch later, a named list, the videos a
// chat has loaded. The choice is core's rule run in the worker (unwatched first, never the video
// this list played last while another is there); nothing more is loaded to make it.
import { api } from '../tg/rpc'
import { t } from '../i18n'
import { chatGone, forgetGone } from '../goneVideos'
import { showToast } from '../ui/Toast'
import { lastOf, rememberPlayed } from '../ui/videoFilter'
import { watchPath } from './VideoList'
import type { VideoItem } from '../tg/types'

const idOf = (v: Pick<VideoItem, 'chatId' | 'messageId'>) => `${v.chatId}:${v.messageId}`

let running = false

/** Rolls tried when the one picked turned out to be gone from its chat, before saying so. */
const TRIES = 4

/**
 * Picks one of `items` and opens it in the player. With `verify` (a list that keeps videos after
 * they left Telegram: Watch later, a named list) the pick is first asked of its chat: a video gone
 * for good leaves the lists (HX21, said once) and another is rolled, a few times at most; an answer
 * that failed (offline) is no reason to skip, so the player says what is wrong. Returns false when
 * nothing was played.
 */
export async function playRandomOf(opts: {
    scope: string
    items: readonly VideoItem[]
    watched: readonly string[]
    open: (path: string, item: VideoItem) => void
    verify?: boolean
}): Promise<boolean> {
    // A second press while the first is still asking (a double tap, Enter held) is the same press.
    if (running) return false
    running = true
    let pool = [...opts.items]
    const gone: { chatId: number; messageId: number }[] = []
    try {
        for (let tries = 0; tries < TRIES && pool.length; tries++) {
            const pick = await api.randomPick(pool.map(idOf), [...opts.watched], lastOf(opts.scope))
            const item = pool.find((v) => idOf(v) === pick)
            if (!item) return false
            if (opts.verify) {
                const alive = await api.video(item.chatId, item.messageId).then(
                    (v) => v !== null,
                    (e) => !chatGone(e),
                )
                if (!alive) {
                    gone.push(item)
                    pool = pool.filter((v) => v !== item)
                    continue
                }
            }
            rememberPlayed(opts.scope, idOf(item))
            opts.open(watchPath(item), item)
            return true
        }
    } finally {
        running = false
        if (gone.length) void forgetGone(gone)
    }
    showToast({ kind: 'warning', text: t('web.gone_from_chat') })
    return false
}
