// The anime skip-time sources the poster worker reads: AniSkip (core's SkipTimesApi.aniSkip) by
// MyAnimeList id, and the id map (core's IdMapApi) that names the MyAnimeList id when AniList has
// none. Neither wants a key. anime-skip.com (core's AnimeSkipApi) wants a client id this build does
// not hold, so it is not read here. Nothing a provider sends is trusted: a wrong type, a missing
// field or an absurd time skips that entry and no reader throws (skiptimes.test.ts feeds both
// truncated, wrong-typed, huge and random input).
export const ANISKIP = 'https://api.aniskip.com/v2'
/** IdMapApi.BASE. */
export const IDMAP = 'https://arm.haglund.dev/api/v2'

/** What a stretch is, as the player's pill words it ("Skip opening", "Skip recap"). */
export type Kind = 'opening' | 'ending' | 'recap'

/** One stretch to jump over, in seconds from the start of the file. */
export interface Segment {
    kind: Kind
    start: number
    end: number
}

type Json = Record<string, any>
const object = (v: unknown): Json | null => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Json) : null)

/** The largest time taken: a day. A longer one is no episode's. */
const MAX_SECONDS = 86_400

/** Seconds to the millisecond, or null for anything that is not a time. */
function seconds(v: unknown): number | null {
    return typeof v === 'number' && Number.isFinite(v) && v >= 0 && v <= MAX_SECONDS ? Math.round(v * 1000) / 1000 : null
}

/**
 * AniSkip's answer: `results` with an `interval` (`startTime`, `endTime`, seconds) and a `skipType`
 * ("op", "mixed-op" are an opening; "ed", "mixed-ed" an ending; "recap"). A stretch with no start,
 * or ending before it starts, is dropped.
 */
export function aniSkipOf(json: unknown): Segment[] {
    const results = object(json)?.results
    if (!Array.isArray(results)) return []
    const out: Segment[] = []
    for (const raw of results.slice(0, 50)) {
        const result = object(raw)
        const interval = object(result?.interval)
        if (!result || !interval) continue
        const kind: Kind | null = result.skipType === 'op' || result.skipType === 'mixed-op' ? 'opening' : result.skipType === 'ed' || result.skipType === 'mixed-ed' ? 'ending' : result.skipType === 'recap' ? 'recap' : null
        const start = seconds(interval.startTime)
        const end = seconds(interval.endTime)
        if (kind && start !== null && end !== null && end > start) out.push({ kind, start, end })
    }
    return out
}

/** IdMapApi.forAniList's MyAnimeList id: a number or a string of digits, 1 to 999999999; null for anything else. */
export function malOfIdMap(json: unknown): string | null {
    const v = object(json)?.myanimelist
    if (typeof v === 'number') return Number.isInteger(v) && v >= 1 && v <= 999_999_999 ? String(v) : null
    if (typeof v === 'string') {
        const s = v.trim()
        return /^\d{1,9}$/.test(s) && s !== '0' ? s : null
    }
    return null
}
