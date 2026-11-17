// The rules of an in-app trailer (private/TRAILERS-PLAN.md): a port of core's TrailerEmbed and
// TrailerRules (core/src/pureMain/kotlin/com/tmplayer/online), kept apart from the Telegram worker's
// compiled rules (src/rules) because the page itself needs them, and loading that module on the
// page would weigh on the first load. trailer.test.ts mirrors core's TrailerEmbedTest and
// TrailerRulesTest cases, so the two cannot drift apart silently. YouTube's own embed
// (youtube-nocookie.com) is the only way a trailer plays here, and nothing from Google is
// requested until the viewer has chosen the trailer.

/** Where the embed comes from: YouTube's privacy enhanced host. */
export const HOST = 'https://www.youtube-nocookie.com'

/** No `ready` within this long and the sheet falls back to the link (core's TrailerRules.READY_TIMEOUT_MS). */
export const READY_TIMEOUT_MS = 12_000

/** YouTube's video ids are exactly eleven of `A-Z a-z 0-9 _ -`. TMDB's key is outside data: nothing else is put in a URL. */
export function isValidKey(key: unknown): key is string {
    return typeof key === 'string' && /^[A-Za-z0-9_-]{11}$/.test(key)
}

/** `https://host` or `http://host:port`, nothing after it: what YouTube's `origin` takes. */
function isOrigin(origin: string): boolean {
    return origin.length <= 200 && /^https?:\/\/[A-Za-z0-9.-]+(:[0-9]{1,5})?$/.test(origin)
}

/** The embed's address for [key], or null when it is not a video id or [origin] not a web origin. */
export function embedUrl(key: unknown, origin: string = HOST): string | null {
    if (!isValidKey(key) || !isOrigin(origin)) return null
    return `${HOST}/embed/${key}?autoplay=1&playsinline=1&rel=0&enablejsapi=1&origin=${encodeURIComponent(origin)}`
}

/** The external link, as before: the YouTube watch page. Null for anything that is not a video id. */
export function watchUrl(key: unknown): string | null {
    return isValidKey(key) ? `https://www.youtube.com/watch?v=${key}` : null
}

/** One message from the player (core's TrailerMessage). */
export type Message = { kind: 'ready' } | { kind: 'state'; code: number } | { kind: 'error'; code: number }

const MAX_MESSAGE = 32

/** `-1` to `9999` in ASCII digits only (a regex `\d` would not read other scripts' digits, but say so). */
function asciiInt(text: string): number | null {
    if (!/^-?[0-9]{1,4}$/.test(text)) return null
    return Number(text)
}

/** What the player said as `ready`, `state:<n>` or `error:<n>`; null for anything else (core's TrailerEmbed.parseMessage). */
export function parseMessage(raw: unknown): Message | null {
    if (typeof raw !== 'string' || raw.length > MAX_MESSAGE) return null
    const text = raw.trim()
    if (text === 'ready') return { kind: 'ready' }
    const at = text.indexOf(':')
    if (at < 0) return null
    const code = asciiInt(text.slice(at + 1))
    if (code === null) return null
    const name = text.slice(0, at)
    if (name === 'state') return code >= -1 && code <= 5 ? { kind: 'state', code } : null
    if (name === 'error') return code >= -1 && code <= 999 ? { kind: 'error', code } : null
    return null
}

/**
 * What YouTube's embed posts to the page that holds it (the messages its IFrame Player API reads):
 * a JSON string `{"event":"onReady"}`, `{"event":"onStateChange","info":1}` or
 * `{"event":"onError","info":150}`. Read as outside data: short, one of those three, numbers in
 * range; it is turned into the bridge's own shapes and goes through [parseMessage]. Null for
 * anything else (YouTube posts others, such as `infoDelivery`, that mean nothing here).
 */
export function parseWidgetMessage(data: unknown): Message | null {
    if (typeof data !== 'string' || data.length > 2_000) return null
    let json: unknown
    try {
        json = JSON.parse(data)
    } catch {
        return null
    }
    if (typeof json !== 'object' || json === null || Array.isArray(json)) return null
    const { event, info } = json as { event?: unknown; info?: unknown }
    if (event === 'onReady') return { kind: 'ready' }
    if (typeof info !== 'number' || !Number.isInteger(info)) return null
    if (event === 'onStateChange') return parseMessage(`state:${info}`)
    if (event === 'onError') return parseMessage(`error:${info}`)
    return null
}

export type Phase = 'loading' | 'ready' | 'playing' | 'paused' | 'buffering' | 'ended' | 'failed'
export type Failure = 'notEmbeddable' | 'notFound' | 'broken' | 'unreachable' | 'timedOut'
export type State = { phase: Phase; failure: Failure | null }

export const LOADING: State = { phase: 'loading', failure: null }

/** What a YouTube error [code] means here. Every code falls back (core's TrailerRules.failure). */
export function failure(code: number): Failure {
    if (code === -1) return 'unreachable'
    if (code === 100) return 'notFound'
    if (code === 101 || code === 150 || code === 152 || code === 153) return 'notEmbeddable'
    return 'broken'
}

/** The state after the player said [message]. A failed trailer stays failed; null changes nothing (core's TrailerRules.next). */
export function next(state: State, message: Message | null): State {
    if (state.phase === 'failed' || message === null) return state
    if (message.kind === 'ready') return state.phase === 'loading' ? { phase: 'ready', failure: null } : state
    if (message.kind === 'error') return { phase: 'failed', failure: failure(message.code) }
    switch (message.code) {
        case 1:
            return { phase: 'playing', failure: null }
        case 2:
            return { phase: 'paused', failure: null }
        case 3:
            return { phase: 'buffering', failure: null }
        case 0:
            return { phase: 'ended', failure: null }
        // Unstarted and cued: ready once the player is there, still loading before.
        default:
            return state.phase === 'loading' ? state : { phase: 'ready', failure: null }
    }
}

/** The state when [READY_TIMEOUT_MS] has passed since the embed was added: still loading falls back. */
export function timedOut(state: State): State {
    return state.phase === 'loading' ? { phase: 'failed', failure: 'timedOut' } : state
}

/** The sheet falls back to the link. */
export const fallsBack = (state: State): boolean => state.phase === 'failed'

/** In the app when the viewer's setting `trailers_in_app` is on and [key] is a YouTube id (core's TrailerRules.route; a page is always online enough to try, and the wait covers the rest). */
export const inApp = (enabled: boolean, key: unknown): boolean => enabled && isValidKey(key)
