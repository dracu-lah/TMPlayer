// The work status (WP-98, the web half of the 3.10 spec's shared status line): what a wait that is
// not a first full page load says. Pure, so `node --test src/ui/workStatus.test.ts` (pnpm test:ui)
// can drive it without a page; ui/WorkStatus.tsx draws it. The states and keys are the apps' (core's
// WorkStatus, WP-91): `state.updating`, `state.updated`, `state.failed_kept`, `state.rate_limited`.

export type WorkStatus =
    | { kind: 'idle' }
    /** A first load under a skeleton: `what` is the label the screen already says. */
    | { kind: 'loading'; what: string }
    /** A refresh with content, a revalidate, a list that grows: `what` is the words (empty: "Updating…"); `total` only when it is really known. */
    | { kind: 'syncing'; what: string; done?: number; total?: number }
    /** The refresh the viewer asked for ended; shown 2 s by its owner. */
    | { kind: 'updated'; at: number }
    /** Some sources failed: `what` is the sentence, with its own count. */
    | { kind: 'partial'; okCount: number; failedCount: number; what: string }
    /** A refresh failed with content kept from `keptAt` (null: nothing is kept, `message` says it). */
    | { kind: 'failed'; message: string; keptAt: number | null }
    /** Settled offline: the offline line says it, this one stays quiet. */
    | { kind: 'offline'; keptAt: number | null }
    /** A flood wait: counts down to `untilMs`. */
    | { kind: 'rateLimited'; untilMs: number }

export const idle: WorkStatus = { kind: 'idle' }

/** What shows on a line: the words, whether it is busy, which buttons, and the bar. */
export interface StatusWords {
    text: string
    busy: boolean
    /** Try again and Send logs. */
    actions: boolean
    /** A determinate bar, only with a real total. */
    progress: { done: number; total: number } | null
    /** The text changes every second: shown aria-hidden beside a fixed spoken sentence. */
    ticking: boolean
}

export type Say = (
    key: 'state.updating' | 'state.updated' | 'state.failed_kept' | 'state.rate_limited',
    args?: Record<string, string | number>,
) => string

/** Whole seconds left of a flood wait, never under 0. */
export const secondsLeft = (untilMs: number, now: number) => Math.max(0, Math.ceil((untilMs - now) / 1000))

/** The clock time a kept page is from, in the viewer's language ("14:05"). */
export function clockTime(at: number, language?: string): string {
    try {
        return new Intl.DateTimeFormat(language, { hour: 'numeric', minute: '2-digit' }).format(new Date(at))
    } catch {
        return ''
    }
}

/** Null when the status draws nothing: Idle, Offline (the offline line speaks), a flood wait that ended, an empty sentence. */
export function statusWords(status: WorkStatus, now: number, say: Say, time: (at: number) => string = clockTime): StatusWords | null {
    const plain = (text: string, busy = false, actions = false): StatusWords | null =>
        text ? { text, busy, actions, progress: null, ticking: false } : null
    switch (status.kind) {
        case 'idle':
        case 'offline':
            return null
        case 'loading':
            return plain(status.what, true)
        case 'syncing': {
            const words = plain(status.what || say('state.updating'), true)
            const { done, total } = status
            if (words && typeof done === 'number' && typeof total === 'number' && total > 0 && done >= 0) {
                words.progress = { done: Math.min(done, total), total }
            }
            return words
        }
        case 'updated':
            return plain(say('state.updated'))
        case 'partial':
            return plain(status.what, false, true)
        case 'failed':
            return plain(status.keptAt === null ? status.message : say('state.failed_kept', { time: time(status.keptAt) }), false, true)
        case 'rateLimited': {
            const seconds = secondsLeft(status.untilMs, now)
            if (seconds <= 0) return null
            return { text: say('state.rate_limited', { seconds }), busy: false, actions: false, progress: null, ticking: true }
        }
    }
}

/** Failed beats RateLimited beats Offline beats Partial beats Syncing beats Updated beats Idle; Loading ranks with Syncing. */
const RANK: Record<WorkStatus['kind'], number> = { failed: 6, rateLimited: 5, offline: 4, partial: 3, syncing: 2, loading: 2, updated: 1, idle: 0 }

/** The one status for a page with two sources (Home's rows and the chat list); a tie keeps `a`. */
export const mergeStatus = (a: WorkStatus, b: WorkStatus): WorkStatus => (RANK[b.kind] > RANK[a.kind] ? b : a)
