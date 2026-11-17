// Skip times from the databases (the apps' SkipTimesApi, an anime's by AniSkip), laid with the file's
// own chapters into the one list the player's pill works from: core's SkipMerge (SkipPolicy.kt), in
// seconds. The worker (`/tmdb/skip`) reads the databases; this file reads its answer, which is outside
// data like any other: a wrong type, a time out of range or a kind it does not know skips that entry,
// and nothing here throws (skipOnline.test.ts feeds it garbage).
import type { SkipKind, SkipSegment } from './chapters.ts'

/** The kinds an anime's databases give. */
const KINDS = new Set<string>(['opening', 'ending', 'recap'])
/** A day: a longer time is no episode's. */
const MAX_SECONDS = 86_400
/** At most this many stretches are taken from one answer. */
const MAX_SEGMENTS = 20

/** The segments an answer of `/tmdb/skip` holds; empty for anything that is not one. */
export function readSkips(json: unknown): SkipSegment[] {
    const list = json && typeof json === 'object' ? (json as { segments?: unknown }).segments : null
    if (!Array.isArray(list)) return []
    const out: SkipSegment[] = []
    for (const raw of list.slice(0, MAX_SEGMENTS)) {
        const o = raw && typeof raw === 'object' ? (raw as Record<string, unknown>) : null
        const { kind, start, end } = o ?? {}
        if (typeof kind !== 'string' || !KINDS.has(kind)) continue
        if (typeof start !== 'number' || typeof end !== 'number' || !Number.isFinite(start) || !Number.isFinite(end)) continue
        if (start < 0 || end <= start || end > MAX_SECONDS) continue
        out.push({ kind: kind as SkipKind, start, end })
    }
    return out
}

/** SkipGroup.of: the opening of an anime and the intro of a show are one choice, as are credits and an ending. */
const group = (kind: SkipKind): string => (kind === 'intro' || kind === 'opening' ? 'intro' : kind === 'credits' || kind === 'ending' ? 'credits' : kind)

/** SkipMerge's OVERSHOOT_MS and END_MARGIN_MS, in seconds. */
const OVERSHOOT = 2
const END_MARGIN = 1

/** SkipMerge.fit: a stretch that cannot be right for a file of `duration` seconds is dropped, one that runs a little past the end is cut to it. */
function fit(s: SkipSegment, duration: number): SkipSegment | null {
    if (!(s.start >= 0) || !(s.end > s.start)) return null
    if (duration > 0) {
        if (s.start >= duration - END_MARGIN) return null
        if (s.end > duration + OVERSHOOT) return null
        const end = Math.min(s.end, duration)
        if (end - s.start > (duration * 3) / 5) return null
        return end === s.end ? s : { ...s, end }
    }
    return s
}

/**
 * SkipMerge.merge for the two sources the web has: the file's own `chapters` first, the databases'
 * `online` after. A stretch that overlaps one already kept is dropped, and so is one of the same
 * group from the other source (chapters say where the intro is: a database's idea of a second intro
 * is not wanted beside it). Within one source, stretches that do not overlap all stay.
 */
export function mergeSkips(chapters: readonly SkipSegment[], online: readonly SkipSegment[], duration: number): SkipSegment[] {
    const tagged = [
        ...chapters.map((s) => ({ s, from: 0 })),
        ...online.map((s) => ({ s, from: 1 })),
    ]
        .map(({ s, from }) => ({ s: fit(s, duration), from }))
        .filter((x): x is { s: SkipSegment; from: number } => x.s !== null)
        .sort((a, b) => a.from - b.from || a.s.start - b.s.start)
    const kept: { s: SkipSegment; from: number }[] = []
    for (const c of tagged) {
        const cEnd = c.s.end
        const clash = kept.some((k) => (c.s.start < k.s.end && k.s.start < cEnd) || (k.from !== c.from && group(k.s.kind) === group(c.s.kind)))
        if (!clash) kept.push(c)
    }
    return kept.map((k) => k.s).sort((a, b) => a.start - b.start)
}
