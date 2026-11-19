// Search by topic name (WB21, the apps' U14): "Chat > Topic" for the forum chats among the added ones.
// The rules (which groups, how a name matches, the order) are core's TopicSearch through the rules
// module; this file is the asking, inside the same budget: at most twelve groups a search, three at a
// time, twenty topics each, nothing before the viewer types, nothing after a new letter (the call is
// aborted), a flood wait waited out by the call that got it while the others go on. It takes its
// Telegram calls as plain functions, so `node --test` runs the budget against a fake account.
import type { TelegramClient } from '@mtcute/web'
import type { Rules } from '../rules'
import type { TopicHit, TopicsFound } from './types'
import { floodSeconds } from './flood.ts'

/** The longest flood wait a topic call sits out, once (the apps' TOPIC_FLOOD_WAIT_S). */
export const TOPIC_FLOOD_MAX_S = 30
/** Hits whose "holds videos" is read (two calls at most each): the best few, never the whole list. */
export const COUNTED_HITS = 12
/** The words are cut here: a search of a pasted page asks nothing absurd. */
const QUERY_MAX = 80
/** A topic's answer is kept this long, so Back to a search does not ask again. */
const KEEP_MS = 120_000
const KEEP_MAX = 30

/** A group as the chat list knows it. Only the ones with topics turned on are given. */
export interface ForumChat {
    id: number
    title: string
    archived: boolean
}

/** One topic as Telegram listed it; `title` is empty for one without a title. */
export interface RawTopic {
    id: number
    title: string
    pinned: boolean
    closed: boolean
}

/** What a search asks of Telegram; the real ones are `telegramTopics`, a test gives its own. */
export interface TopicAsk {
    /** Telegram's topics of `chatId` whose names answer `query`, at most `limit`. Throws when refused. */
    topicsOf(chatId: number, query: string, limit: number, signal: AbortSignal): Promise<RawTopic[]>
    /** Whether the topic holds a video or a file (a count, never the messages). Throws when refused. */
    hasVideos(chatId: number, topicId: number, signal: AbortSignal): Promise<boolean>
}

export interface TopicSearchInput {
    rules: Pick<Rules, 'topicBudget' | 'topicCandidates' | 'topicHits' | 'topicRank'>
    ask: TopicAsk
    /** Every group with topics the page knows (the added ones only are asked). */
    forums: readonly ForumChat[]
    /** The chats' order in the chat list, by id: ties between hits go to the chat used most recently. */
    order: readonly number[]
    /** The viewer's added chats, null for every chat. */
    added: readonly number[] | null
    includeArchived: boolean
    query: string
    signal: AbortSignal
    /** Called with what has come so far, after each group answers. */
    onPartial?: (found: TopicsFound) => void
    /** Waits `ms`, rejecting when `signal` aborts; replaced in tests. */
    sleep?: (ms: number, signal: AbortSignal) => Promise<void>
}

export const sleepFor = (ms: number, signal: AbortSignal) =>
    new Promise<void>((resolve, reject) => {
        if (signal.aborted) return reject(new DOMException('aborted', 'AbortError'))
        const timer = setTimeout(() => (signal.removeEventListener('abort', stop), resolve()), ms)
        const stop = () => (clearTimeout(timer), reject(new DOMException('aborted', 'AbortError')))
        signal.addEventListener('abort', stop, { once: true })
    })

/** The words with nothing a name could answer taken out: none left (only emoji, blanks) asks nothing. */
export function topicWords(query: unknown): string {
    if (typeof query !== 'string') return ''
    const words = query.replace(/\s+/g, ' ').trim().slice(0, QUERY_MAX)
    return /[\p{L}\p{N}]/u.test(words) ? words : ''
}

/**
 * The topics whose names answer `query`, best first. A group Telegram refuses is skipped and the
 * rest are listed; only when every group asked refused does the search fail (the last error is thrown),
 * so the page can offer Try again.
 */
export async function findTopics(input: TopicSearchInput): Promise<TopicsFound> {
    const { rules, ask, signal } = input
    const query = topicWords(input.query)
    const idle: TopicsFound = { hits: [], asked: 0, refused: 0, partial: false, searching: false }
    if (!query) return idle
    const budget = rules.topicBudget()
    const sleep = input.sleep ?? sleepFor
    const asking = rules.topicCandidates(input.forums.map((f) => ({ id: f.id, title: f.title, archived: f.archived })), query, input.added ? [...input.added] : null, input.includeArchived)
    const titles = new Map(input.forums.map((f) => [f.id, f.title] as const))
    // More groups could have topics than one search asks: said in the heading.
    const allowed = input.added ? new Set(input.added) : null
    const eligible = input.forums.filter((f) => (input.includeArchived || !f.archived) && (!allowed || allowed.has(f.id))).length
    const partial = eligible > asking.length
    if (!asking.length) return { ...idle, partial }
    const hits: TopicHit[] = []
    let refused = 0
    let answered = 0
    let lastError: unknown = null
    const ranked = (): TopicHit[] => rules.topicRank(hits.map(toWeb), [...input.order]).map(fromWeb)
    const askOne = async (chatId: number): Promise<void> => {
        let topics: RawTopic[]
        try {
            topics = await topicsWithFlood(ask, chatId, query, budget.topicsPerChat, sleep, signal)
        } catch (e) {
            if (signal.aborted) throw e
            refused++
            lastError = e
            return
        }
        // Answered after the search was given up (a new letter): nothing of it is used.
        signal.throwIfAborted()
        answered++
        const found = rules.topicHits(chatId, titles.get(chatId) ?? '', topics.slice(0, budget.topicsPerChat).map(toTopic), query, true)
        hits.push(...found.map(fromWeb))
        input.onPartial?.({ hits: ranked(), asked: asking.length, refused, partial, searching: true })
    }
    // `atOnce` of them side by side, the rest as one finishes.
    let next = 0
    const lanes = Array.from({ length: Math.max(1, Math.min(budget.atOnce, asking.length)) }, async () => {
        while (next < asking.length) {
            signal.throwIfAborted()
            await askOne(asking[next++])
            signal.throwIfAborted()
        }
    })
    await Promise.all(lanes)
    signal.throwIfAborted()
    if (!answered && refused) throw lastError ?? new Error('topics')
    // Which of the best hits hold videos: read after the topic calls, one call each (a second only when it found none),
    // from what an earlier search already learned where there is such a thing, and never again once Telegram refuses a
    // count: the rest of the search lists its topics as they are, not dimmed. A count is never waited out.
    const top = ranked()
    const toCount = top.slice(0, COUNTED_HITS)
    next = 0
    let countsRefused = false
    await Promise.all(
        Array.from({ length: Math.min(budget.atOnce, toCount.length) }, async () => {
            while (next < toCount.length && !countsRefused) {
                signal.throwIfAborted()
                const hit = toCount[next++]
                const key = `${hit.chatId}:${hit.topicId}`
                const known = counted.get(key)
                if (known !== undefined) {
                    hit.hasVideos = known
                    continue
                }
                try {
                    hit.hasVideos = await ask.hasVideos(hit.chatId, hit.topicId, signal)
                    if (!signal.aborted) rememberCount(key, hit.hasVideos)
                } catch {
                    if (signal.aborted) return
                    countsRefused = true
                }
            }
        }),
    )
    signal.throwIfAborted()
    const final = rules.topicRank(top.map(toWeb), [...input.order]).map(fromWeb)
    return { hits: final, asked: asking.length, refused, partial, searching: false }
}

async function topicsWithFlood(ask: TopicAsk, chatId: number, query: string, limit: number, sleep: (ms: number, signal: AbortSignal) => Promise<void>, signal: AbortSignal): Promise<RawTopic[]> {
    for (let attempt = 0; ; attempt++) {
        try {
            signal.throwIfAborted()
            return await ask.topicsOf(chatId, query, limit, signal)
        } catch (e) {
            const seconds = floodSeconds(e as { message?: string; text?: string; seconds?: number })
            if (!seconds || attempt > 0 || seconds > TOPIC_FLOOD_MAX_S) throw e
            await sleep(seconds * 1000 + 250, signal)
        }
    }
}

const toTopic = (t: RawTopic) => ({ id: t.id, name: t.title, pinned: t.pinned, closed: t.closed, hasVideos: true })
const toWeb = (h: TopicHit) => ({ ...h })
const fromWeb = (h: TopicHit): TopicHit => ({
    chatId: h.chatId,
    chatTitle: h.chatTitle,
    topicId: h.topicId,
    name: h.name,
    pinned: h.pinned,
    closed: h.closed,
    hasVideos: h.hasVideos,
    score: h.score,
})

/** The real asking, over mtcute. Telegram's own matching by name, and counts through the topic's thread. */
export function telegramTopics(tg: TelegramClient): TopicAsk {
    const filters = [{ _: 'inputMessagesFilterVideo' }, { _: 'inputMessagesFilterDocument' }] as const
    return {
        async topicsOf(chatId, query, limit, signal) {
            // mtcute takes no abort signal for these calls: a call already sent finishes, and nothing more is sent after the abort.
            signal.throwIfAborted()
            const found = await tg.getForumTopics(chatId, { query, limit })
            const out: RawTopic[] = []
            const seen = new Set<number>()
            for (const topic of found) {
                const id = Number(topic.id)
                if (!Number.isInteger(id) || id < 1 || seen.has(id)) continue
                seen.add(id)
                out.push({ id, title: topic.titleMissing ? '' : String(topic.title ?? ''), pinned: !!topic.isPinned, closed: !!topic.isClosed })
            }
            return out
        },
        async hasVideos(chatId, topicId, signal) {
            signal.throwIfAborted()
            // A video count first; files only when it is none (a topic of PDFs is "no videos" once its files are named, which the grid does).
            const videos = await tg.searchMessages({ chatId, threadId: topicId, filter: filters[0], limit: 1 })
            if (videos.total > 0 || videos.length > 0) return true
            signal.throwIfAborted()
            const files = await tg.searchMessages({ chatId, threadId: topicId, filter: filters[1], limit: 1 })
            return files.total > 0 || files.length > 0
        },
    }
}

/** Which topics were found to hold videos (or none), kept for the session so no search counts one twice; bounded. */
const counted = new Map<string, boolean>()
const COUNTED_MAX = 2000

function rememberCount(key: string, has: boolean) {
    counted.delete(key)
    counted.set(key, has)
    while (counted.size > COUNTED_MAX) counted.delete(counted.keys().next().value!)
}

// What the worker keeps: the last answers, so a Back to the same words lists them at once.
const kept = new Map<string, { at: number; found: TopicsFound }>()

export function keptTopics(key: string, now = Date.now()): TopicsFound | null {
    const hit = kept.get(key)
    if (!hit) return null
    if (now - hit.at > KEEP_MS) {
        kept.delete(key)
        return null
    }
    return hit.found
}

export function keepTopics(key: string, found: TopicsFound, now = Date.now()) {
    kept.delete(key)
    kept.set(key, { at: now, found })
    while (kept.size > KEEP_MAX) kept.delete(kept.keys().next().value!)
}

/** Keeps an answer in which every asked group answered; one with a skipped group (a flood wait past thirty seconds, a refusal) is not kept, so asking again asks again. Whether it was kept. */
export function keepWhole(key: string, found: TopicsFound): boolean {
    if (found.refused) return false
    keepTopics(key, found)
    return true
}

export function forgetTopics() {
    kept.clear()
    counted.clear()
}
