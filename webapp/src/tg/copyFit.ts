// Which copies of a video this browser plays natively, as far as a copy's name and type say (HX38
// on the web, decision D5): the page tells the worker which picture kinds its browser cannot decode
// (copies.ts reads that from the browser), and the worker hands the rules the ids of the copies of
// those kinds as "too slow", so a copy the browser can play leads the list and is the one Play opens.
// Pure and outside data safe: names and types come from Telegram, the kinds from the page.

import { cleanTitle, RENAME_MAX } from '../rename.ts'

/** A kind of picture the name can name that some browsers cannot decode themselves. */
export type PictureKind = 'hevc' | 'hevc10' | 'avc10' | 'av1' | 'vp9' | 'legacy'

export const PICTURE_KINDS: readonly PictureKind[] = ['hevc', 'hevc10', 'avc10', 'av1', 'vp9', 'legacy']

/** The device word the rules recommend copies for (VideoCopies.deviceWord). */
export type CopyDevice = 'phone' | 'tv' | 'computer'

/**
 * What the page says of the viewer's copies: the device the copies are recommended for, the
 * picture kinds this browser cannot play natively, the files kept apart ("Not the same video"),
 * the copies the viewer chose themselves and the qualities they usually play (QualityHabit's line).
 */
export interface CopyContext {
    device: CopyDevice
    slow: PictureKind[]
    /** "chatId:messageId" of each file kept apart from the copies it was grouped with. */
    apart: string[]
    /** "chatId:messageId" of each copy the viewer chose in a list of copies, newest first. */
    chosen: string[]
    /** The habit line ("1080:12,720:3"), or null. */
    habit: string | null
    /** The titles the viewer gave ("Rename", the apps' VideoCorrection.title) by "chatId:messageId", read by the grouping and every tile's title. */
    titles: { id: string; title: string }[]
}

export const NO_COPY_CONTEXT: CopyContext = { device: 'phone', slow: [], apart: [], chosen: [], habit: null, titles: [] }

/**
 * The corrections the rules group by (`setCorrections`: ids and stored lines) for a context: a file
 * kept apart reads `separate`, a renamed one `title=...` (cut and cleaned by the rules' own
 * `correctionSet`, so the worker and the apps read one title), both when both.
 */
export function correctionLines(ctx: Pick<CopyContext, 'apart' | 'titles'>, set: (text: string | null, field: string, value: string | null) => string): { ids: string[]; texts: string[] } {
    const lines = new Map<string, string>()
    for (const key of ctx.apart) lines.set(key, 'separate')
    for (const t of ctx.titles) {
        const before = lines.get(t.id) ?? null
        // The rules cut a title at 120 UTF-16 units; the page's cap is 120 whole characters, so a longer one is written as it is.
        lines.set(t.id, t.title.length <= RENAME_MAX ? set(before, 'title', t.title) : [before, `title=${t.title}`].filter(Boolean).join('\n'))
    }
    return { ids: [...lines.keys()], texts: [...lines.values()] }
}

const MAX_IDS = 5_000
const ID = /^-?\d{1,20}:\d{1,12}$/
const HABIT = /^[\d:,]{0,400}$/

/** `raw` as a context, anything it holds that is not one dropped (it crosses from the page to the worker). */
export function cleanContext(raw: unknown): CopyContext {
    const c = (raw && typeof raw === 'object' ? raw : {}) as Record<string, unknown>
    const device: CopyDevice = c.device === 'tv' || c.device === 'computer' ? c.device : 'phone'
    const ids = (v: unknown) => (Array.isArray(v) ? [...new Set(v.slice(0, MAX_IDS).filter((x): x is string => typeof x === 'string' && ID.test(x)))] : [])
    const slow = Array.isArray(c.slow) ? PICTURE_KINDS.filter((k) => (c.slow as unknown[]).includes(k)) : []
    const habit = typeof c.habit === 'string' && HABIT.test(c.habit) && c.habit ? c.habit : null
    return { device, slow, apart: ids(c.apart), chosen: ids(c.chosen), habit, titles: renames(c.titles) }
}

/** The renames a context holds: each id checked, each title cleaned, the newest of an id kept, bounded. */
function renames(v: unknown): CopyContext['titles'] {
    if (!Array.isArray(v)) return []
    const out = new Map<string, string>()
    for (const e of v.slice(0, MAX_IDS)) {
        const x = (e && typeof e === 'object' ? e : {}) as { id?: unknown; title?: unknown }
        const title = cleanTitle(x.title)
        if (typeof x.id === 'string' && ID.test(x.id) && title) out.set(x.id, title)
    }
    return [...out].map(([id, title]) => ({ id, title }))
}

const LEGACY_EXT = /\.(avi|divx|wmv|asf|flv|rm|rmvb|vob|mpe?g|3gp|ogm)$/i
const LEGACY_MIME = /^video\/(x-msvideo|avi|msvideo|x-ms-wmv|x-ms-asf|x-flv|vnd\.rn-realvideo|mpeg|3gpp|x-ms-vob)$/i
const LEGACY_WORD = /(^|[^a-z0-9])(xvid|divx|mpeg-?4 ?part ?2|wmv|realvideo)([^a-z0-9]|$)/i
const HEVC_WORD = /(^|[^a-z0-9])(x265|h\.?265|hevc)([^a-z0-9]|$)/i
const TEN_BIT = /(^|[^a-z0-9])(10[ -]?bits?|hi10p?|main ?10)([^a-z0-9]|$)/i
const AVC_WORD = /(^|[^a-z0-9])(x264|h\.?264|avc)([^a-z0-9]|$)/i
/** Hi10P names H.264's High 10 profile whatever else the name says. */
const HI10P = /(^|[^a-z0-9])hi10p?([^a-z0-9]|$)/i
const AV1_WORD = /(^|[^a-z0-9])av1([^a-z0-9]|$)/i
const VP9_WORD = /(^|[^a-z0-9])vp9([^a-z0-9]|$)/i

/**
 * The picture kind a copy's name and type say, or null for H.264 or nothing said (which every
 * browser plays, natively or repacked). An AVI, a WMV or an XviD needs libav.js in any browser.
 */
export function pictureKind(fileName: unknown, mimeType: unknown): PictureKind | null {
    const name = typeof fileName === 'string' ? fileName.slice(0, 1_000) : ''
    const mime = typeof mimeType === 'string' ? mimeType.slice(0, 200).trim() : ''
    if (LEGACY_EXT.test(name) || LEGACY_MIME.test(mime) || LEGACY_WORD.test(name)) return 'legacy'
    if (HEVC_WORD.test(name)) return TEN_BIT.test(name) ? 'hevc10' : 'hevc'
    // 10 bit H.264 (High 10, "x264 Hi10P", anime fansubs): most browsers decode only 8 bit H.264
    // (DecodeJudge.pictureOfName reads the depth for every codec, not HEVC alone).
    if (HI10P.test(name) || (AVC_WORD.test(name) && TEN_BIT.test(name))) return 'avc10'
    if (AV1_WORD.test(name)) return 'av1'
    if (VP9_WORD.test(name)) return 'vp9'
    return null
}

/** Whether the device kind moved (a rotation into the phone form, Settings' Layout): the cards are arranged again then; the first context is no move. */
export const deviceMoved = (before: CopyDevice | null, now: CopyDevice): boolean => before !== null && before !== now

/** Whether a copy of this name and type is one `slow` says the browser cannot play natively. */
export function slowHere(fileName: unknown, mimeType: unknown, slow: readonly PictureKind[]): boolean {
    const kind = pictureKind(fileName, mimeType)
    if (!kind) return false
    // A browser that cannot decode 8 bit HEVC cannot decode 10 bit HEVC either.
    return slow.includes(kind) || (kind === 'hevc10' && slow.includes('hevc'))
}

/**
 * What a copies list says of how a copy plays here ("May stutter on this computer", "Smooth on this
 * computer"), as the apps' copyFit: "stutter" for a copy the browser cannot play natively, "smooth"
 * for the others only where one of `among` may stutter, null where nothing needs saying.
 */
export function copyFit(slow: boolean, anySlow: boolean): 'smooth' | 'stutter' | null {
    if (slow) return 'stutter'
    return anySlow ? 'smooth' : null
}

/**
 * The picture kinds a browser cannot decode itself, from what it says it plays: `supported` is the
 * Media Source's (or the video element's) answer for a codec string. `hevcAnyway` for a TV that
 * decodes HEVC whatever its browser says (webOS, Tizen: hevcnames.tvDecodesHevc).
 */
export function slowKinds(supported: (mime: string) => boolean, hevcAnyway = false): PictureKind[] {
    const ask = (...mimes: string[]) =>
        mimes.some((m) => {
            try {
                return supported(m)
            } catch {
                return false
            }
        })
    const out: PictureKind[] = []
    const hevc = hevcAnyway || ask('video/mp4; codecs="hvc1.1.6.L93.B0"', 'video/mp4; codecs="hev1.1.6.L93.B0"')
    if (!hevc) out.push('hevc')
    if (!hevc || !(hevcAnyway || ask('video/mp4; codecs="hvc1.2.4.L93.B0"', 'video/mp4; codecs="hev1.2.4.L93.B0"'))) out.push('hevc10')
    // H.264 High 10 (profile 110), level 4.0.
    if (!ask('video/mp4; codecs="avc1.6E0028"')) out.push('avc10')
    if (!ask('video/mp4; codecs="av01.0.08M.08"')) out.push('av1')
    if (!ask('video/mp4; codecs="vp09.00.10.08"', 'video/webm; codecs="vp9"')) out.push('vp9')
    out.push('legacy')
    return out
}
