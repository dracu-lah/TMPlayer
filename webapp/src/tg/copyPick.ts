// One answer for a video posted more than once (HX38): the copy a card plays and badges, its
// "N versions" count and the order its page lists the copies in, all from the rules (VideoCopies,
// GlobalSearch.copyOrder through WebRules), with what only the page knows (which copies this browser
// cannot play natively, the viewer's own choice, the quality they usually play) handed in.
// Pure over the rules, so copyPick.test.ts runs it on the real Kotlin answers.
import type { Rules, Video } from '../rules'
import { copyFit, slowHere, type CopyContext } from './copyFit.ts'

type CopyRules = Pick<Rules, 'copyOrder' | 'bestCopy' | 'pickCopy' | 'versionCount' | 'qualityRead'>

/** The ids of `copies` the browser cannot play natively, by their names (CopyContext.slow). */
export function slowIds(copies: readonly Video[], ctx: CopyContext): string[] {
    if (!ctx.slow.length) return []
    return copies.filter((v) => slowHere(v.fileName, v.mimeType, ctx.slow)).map((v) => v.id)
}

/**
 * The ids the one choice nothing else settles passes over while another copy is left: those the
 * browser cannot play natively, and a cam, a telesync or a pre-DVD rip (QualityRead.lowGrade), which
 * the list puts last, so the card's copy is never one its own list ranks under a better one.
 */
function passOver(r: CopyRules, copies: readonly Video[], ctx: CopyContext): string[] {
    const slow = new Set(slowIds(copies, ctx))
    for (const v of copies) if (r.qualityRead(v).lowGrade) slow.add(v.id)
    return [...slow]
}

/** The copy the viewer chose among `copies`, the newest choice first; null when they chose none of them. */
export function chosenAmong(copies: readonly Video[], ctx: CopyContext): string | null {
    if (!ctx.chosen.length) return null
    let rank = ranks.get(ctx)
    if (!rank) ranks.set(ctx, (rank = new Map(ctx.chosen.map((id, i) => [id, i]))))
    let found: string | null = null
    let at = Infinity
    for (const v of copies) {
        const i = rank.get(v.id)
        if (i !== undefined && i < at) (found = v.id), (at = i)
    }
    return found
}

/** Each context's choices by id, read once however many cards ask. */
const ranks = new WeakMap<CopyContext, Map<string, number>>()

/**
 * The copy a card of `copies` plays and wears the badge of (VideoCopies.pick): the viewer's own
 * choice, else the one they started, else the quality they usually play, else the best this device
 * suits among the copies it plays natively. A cam is passed over only when nothing the viewer did
 * settles it, so a started cam copy resumes in its own file, as the apps' pick keeps it. Null for
 * no copies. `started` is the view's set of started ids (one per list, not read again per card).
 */
export function playCopy(r: CopyRules, copies: readonly Video[], ctx: CopyContext, started: ReadonlySet<string>): string | null {
    if (!copies.length) return null
    if (copies.length === 1) return copies[0].id
    const going = copies.flatMap((v) => (started.has(v.id) ? [v.id] : []))
    const chosen = chosenAmong(copies, ctx)
    const skip = chosen || going.length ? slowIds(copies, ctx) : passOver(r, copies, ctx)
    return r.pickCopy([...copies], chosen, going, ctx.habit, null, skip) ?? copies[0].id
}

/** One row of a copies list, as the worker hands it to the page. */
export interface CopyRow {
    video: Video
    /** "Best for this device": the copy nothing else would settle, only where the copies differ in picture. */
    best: boolean
    fit: 'smooth' | 'stutter' | null
    started: boolean
    /** The name says one picture over a file stated as another (the size is right and heads the row). */
    mismatch: boolean
}

/**
 * Every file of a group of copies, the same file posted twice included: the list above shows such a
 * pair once, but a rename (or any correction) belongs to all of them so no hidden post keeps the old title.
 */
export const groupRefs = (copies: readonly Video[]): { chatId: number; messageId: number }[] => copies.map((v) => ({ chatId: v.chatId, messageId: v.messageId }))

/**
 * `copies` as a list of copies shows them: the same file posted twice once (the post `current` is
 * about standing for it), recommended first (GlobalSearch.copyOrder: not a cam, one the browser plays
 * natively, the picture the device suits, the newest), each with what its row says. Its length is
 * the "N versions" count, so the count and the list never differ; fewer than two files is no list.
 */
export function copyRows(r: CopyRules, copies: readonly Video[], current: string, ctx: CopyContext, started: Iterable<string>): CopyRow[] {
    if (copies.length < 2) return []
    const slow = slowIds(copies, ctx)
    const byId = new Map(copies.map((v) => [v.id, v]))
    const ordered = r.copyOrder([...copies], null, slow).flatMap((id) => byId.get(id) ?? [])
    // The same file posted twice (forwarded, or sent again: the same name and size) is one choice.
    const fileOf = (v: Video) => (v.fileName && v.size > 0 ? `${v.size}:${v.fileName}` : v.id)
    const lead = new Map<string, Video>()
    for (const v of ordered) {
        const key = fileOf(v)
        if (!lead.has(key) || v.id === current) lead.set(key, v)
    }
    const shown = ordered.filter((v) => lead.get(fileOf(v)) === v)
    if (shown.length < 2) return []
    const read = new Map(shown.map((v) => [v.id, r.qualityRead(v)]))
    const heights = new Set([...read.values()].map((q) => q.height).filter((h) => h != null))
    const best = heights.size > 1 ? r.bestCopy(shown, passOver(r, shown, ctx)) : null
    const slowSet = new Set(slow)
    const anySlow = shown.some((v) => slowSet.has(v.id))
    const going = new Set(started)
    return shown.map((video) => ({
        video,
        best: video.id === best,
        fit: copyFit(slowSet.has(video.id), anySlow),
        started: going.has(video.id),
        mismatch: read.get(video.id)?.mismatch ?? false,
    }))
}
