// The TV's own player for a file, on LG webOS and Samsung Tizen: the TV build registers a
// stream-only Service Worker (install.ts registerStreamWorker), and `<video src="/stream/...">` hands
// the file to the TV's decoders, which play HEVC, AC-3 and E-AC-3 as the IPTV apps do (Jellyfin's
// direct play, private/TV-DIRECT-PLAN.md R3). Untested on a TV when written: if the TV's media
// server fetches the address itself, the worker never sees it, and after two such misses this TV
// stops trying and stays on the Media Source repack.

/** The TV agents the site answers with the TV build (functions/_middleware.ts). */
export const TV_BROWSER = /Web0S|webOS|Tizen|SMART-TV|SmartTV/i

/** Codecs on LG's and Samsung's lists for MP4, MOV and WebM played by the TV itself. */
const TV_CODECS = /^(avc1|avc3|hev1|hvc1|av01|vp09|vp8|vp9|mp4a|ac-3|ec-3|opus|vorbis)(\.|$)/i

/**
 * Whether the TV's player should be given `mime` (as mediabunny names the file, with codecs) even
 * though the browser's `canPlayType` says no to its codec string: the container plays (`container`
 * says so) and every codec is on the TV's list. A file with no codecs named is left to the repack.
 */
export function tvPlaysNatively(mime: string, container: boolean): boolean {
    if (!container) return false
    const list = /codecs="([^"]*)"/.exec(mime)?.[1]
    if (!list) return false
    const codecs = list.split(',').map((c) => c.trim()).filter(Boolean)
    return codecs.length > 0 && codecs.every((c) => TV_CODECS.test(c))
}

const MISSES_KEY = 'tmplayer-tv-native-misses'
/** Misses before the TV's player is not tried again on this TV. */
export const MAX_MISSES = 2

type Store = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>

function store(): Store | null {
    try {
        return localStorage
    } catch {
        return null
    }
}

function misses(s: Store | null): number {
    try {
        const n = Number(s?.getItem(MISSES_KEY))
        return Number.isFinite(n) && n > 0 ? n : 0
    } catch {
        return 0
    }
}

/** The TV's player went around the worker often enough that this TV no longer tries it. */
export function tvNativeOff(s: Store | null = store()): boolean {
    return misses(s) >= MAX_MISSES
}

/**
 * After a native play on a TV: `reached` says whether the worker was asked for the file's bytes.
 * Asked once resets the count (the route works here); never asked counts a miss.
 */
export function noteTvNative(reached: boolean, s: Store | null = store()) {
    try {
        if (reached) s?.removeItem(MISSES_KEY)
        else s?.setItem(MISSES_KEY, String(misses(s) + 1))
    } catch {
        // Storage refused (a private window): the next file tries again, which is still safe.
    }
}
