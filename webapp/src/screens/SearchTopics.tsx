import { createEffect, createSignal, Index, onCleanup, Show } from 'solid-js'
import { A } from '@solidjs/router'
import { call, errorText } from '../tg/rpc'
import { onBackOnline } from '../net'
import { t } from '../i18n'
import { addedView } from '../store'
import type { TopicHit, TopicsFound } from '../tg/types'
import './topics.css'

/** Typing settles this long before Telegram is asked (the videos' search starts at once): a word still being typed asks nothing. */
const SETTLE_MS = 250

/** What an event of the worker may be: its own `TopicsFound`, checked as outside data. */
function foundOf(value: unknown): TopicsFound | null {
    const v = value as Partial<TopicsFound> | null
    if (!v || !Array.isArray(v.hits)) return null
    return { hits: v.hits.slice(0, 100) as TopicHit[], asked: Number(v.asked) || 0, refused: Number(v.refused) || 0, partial: !!v.partial, searching: !!v.searching }
}

/** A topic's name as a row reads it: an untitled one says so. */
export const topicName = (hit: Pick<TopicHit, 'name'>) => hit.name.trim() || t('grid.topic_untitled')

/**
 * The topics of the viewer's forum chats whose names answer the words (U14), "Chat > Topic", over the
 * videos. Asked only once the words have settled and only for added chats (every chat on the one-off
 * "Search my other chats"); a new letter or leaving the page cancels the asking. Nothing is drawn
 * while the answer is empty, so an account with no forum chats never sees a heading.
 */
export function SearchTopics(props: { words: string | undefined; everyChat: boolean }) {
    const [found, setFound] = createSignal<TopicsFound | null>(null)
    const [failed, setFailed] = createSignal<unknown>(null)
    const [again, setAgain] = createSignal(0)
    const [busy, setBusy] = createSignal(false)
    createEffect(() => {
        const words = props.words?.trim()
        const added = props.everyChat ? null : addedView()
        again()
        setFailed(null)
        if (!words) {
            setFound(null)
            setBusy(false)
            return
        }
        const controller = new AbortController()
        let timer = 0
        setBusy(true)
        timer = window.setTimeout(async () => {
            try {
                const answer = await call('topics', [words, added], {
                    signal: controller.signal,
                    onEvent: (e) => {
                        const partial = foundOf(e)
                        if (partial && !controller.signal.aborted) setFound(partial)
                    },
                })
                const done = foundOf(answer)
                if (done && !controller.signal.aborted) setFound(done)
            } catch (e) {
                if (controller.signal.aborted) return
                setFailed(e)
            } finally {
                if (!controller.signal.aborted) setBusy(false)
            }
        }, SETTLE_MS)
        onCleanup(() => {
            clearTimeout(timer)
            controller.abort()
        })
    })
    onBackOnline(() => failed() && setAgain(again() + 1))
    const hits = () => (props.words?.trim() ? (found()?.hits ?? []) : [])
    return (
        <Show when={hits().length || failed()}>
            <section class="topic-hits" aria-labelledby="topic-hits-title" aria-busy={busy()}>
                <h2 class="topic-hits-title" id="topic-hits-title">{t('search.topics_heading')}</h2>
                <Show when={failed()}>
                    <div class="notice error-notice">
                        <p class="error" role="alert">{t('search.topics_failed', { reason: errorText(failed()) })}</p>
                        <button class="link" type="button" onClick={() => setAgain(again() + 1)}>{t('common.try_again')}</button>
                    </div>
                </Show>
                {/* Fewer groups than asked answered (one waited too long or refused): said as the apps say fewer groups, with the way to ask again. */}
                <Show when={(found()?.partial || found()?.refused) && found()!.asked && hits().length}>
                    <p class="muted small">{t('search.topics_partial', { count: Math.max(1, found()!.asked - (found()!.refused || 0)) })}</p>
                    <Show when={found()!.refused && !busy()}>
                        <button class="link" type="button" onClick={() => setAgain(again() + 1)}>{t('common.try_again')}</button>
                    </Show>
                </Show>
                <ul class="topic-list">
                    {/* By place, not by object: the answer that follows the first partial one updates the rows in place, so a focused row keeps the focus. */}
                    <Index each={hits()}>
                        {(item) => (
                            <li>
                                <A class="topic-row" classList={{ dim: !item().hasVideos }} href={`/chat/${item().chatId}?topic=${item().topicId}`}>
                                    <span class="topic-label">{t('search.topic_row', { chat: item().chatTitle, topic: topicName(item()) })}</span>
                                    <Show when={!item().hasVideos || item().closed || item().pinned}>
                                        <span class="topic-facts">
                                            {[!item().hasVideos && t('search.topic_no_videos'), item().closed && t('grid.topic_closed'), item().pinned && t('grid.topic_pinned')].filter(Boolean).join(' · ')}
                                        </span>
                                    </Show>
                                </A>
                            </li>
                        )}
                    </Index>
                </ul>
            </section>
        </Show>
    )
}
