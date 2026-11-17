// A long anime's episodes, numbered the way its files are: straight through ("Show - 123", the
// absolute number), laid over the places TMDB gives them (season 6, episode 12) and the titles,
// stills and dates the providers hold. core's AnimeEpisodes (core/.../online/AnimeEpisodes.kt),
// ported for the poster worker (it runs on Cloudflare and cannot load the Kotlin module) and for
// the page, which rebuilds the guide from the worker's answer. Pure: the providers' answers go in
// as source lists, one guide comes out (`merge`). A change to the Kotlin rules is a change here;
// tmdb/animeRules.test.ts holds the cases of the apps' AnimeEpisodesTest.
//
// The sources, in the order they are asked and the order a title is taken in: TMDB's episode group
// of type "Absolute order", TMDB's seasons counted in order, Kitsu's episodes (by the MyAnimeList
// id), Jikan's (MyAnimeList's) titles and dates, AniList's streaming list. The first source with a
// real title gives the title; the picture, the overview and the date each come from the first
// source that has one.
//
// One difference from the apps, on purpose: a picture counts only when it is a TMDB still ("/abc.jpg"),
// the one kind of picture the worker serves (`/tmdb/img/...`); a Kitsu or AniList picture is never
// drawn by the page, so it is never taken as "the picture" in place of a later source's own.
import type { SeasonStop } from '../tmdb/extras.ts'
import { plain } from './plain.ts'

export type Source = 'group' | 'seasons' | 'kitsu' | 'jikan' | 'anilist'

/** AnimeEpisodes.Source's label: whose words an episode carries. */
export const LABEL: Record<Source, string> = { group: 'TMDB', seasons: 'TMDB', kitsu: 'Kitsu', jikan: 'MyAnimeList', anilist: 'AniList' }

/** More seasons than any show has; the rest of a garbage answer is dropped. */
export const MAX_SEASONS = 200
/** The longest list taken from one source: One Piece has about a thousand and five hundred. */
export const MAX_EPISODES = 3000
/** The largest episode number taken; an absurd one is dropped. */
export const MAX_NUMBER = 9999
const MAX_NAME = 300
const MAX_OVERVIEW = 4000

/** One source's word on one episode, numbered absolutely; `season` and `episode` are TMDB's place for it, where known. */
export interface Piece {
    absolute: number
    name?: string
    overview?: string
    still?: string | null
    airDate?: string | null
    runtime?: number | null
    season?: number | null
    episode?: number | null
}

/** What one source knows, in the order the source gave it. */
export interface SourceList {
    source: Source
    pieces: Piece[]
}

/** One episode of a season as a provider lists it (MetaEpisode): the number is the season's own. */
export interface ListedEpisode {
    number: number
    name: string
    overview: string
    still: string | null
    airDate: string | null
    runtime: number | null
}

/** One episode as the guide answers it. `source` gave the name, `stillSource` the picture. */
export interface Entry {
    absolute: number
    /** TMDB's season and the episode's number in it, null where TMDB has no place for it. */
    season: number | null
    episode: number | null
    name: string
    overview: string
    still: string | null
    airDate: string | null
    runtime: number | null
    source: Source | null
    stillSource: Source | null
}

export interface Next {
    season: number
    episode: number
    airDate: string
}

const place = (season: number, episode: number) => season * 100_000 + episode

/**
 * The merged answer for one show. `layout` is TMDB's seasons counted in order (empty where TMDB
 * does not know the show); `next` is the next episode to air, numbered absolutely; `declared` is
 * how many episodes AniList says the run has (zero where it says none yet).
 */
export class Guide {
    readonly entries: Entry[]
    readonly layout: SeasonStop[]
    readonly next: Next | null
    readonly declared: number
    private readonly byAbsolute: Map<number, Entry>
    private readonly byPlace: Map<number, number>

    constructor(entries: Entry[], layout: SeasonStop[], next: Next | null, declared = 0) {
        this.entries = entries
        this.layout = layout
        this.next = next
        this.declared = declared
        this.byAbsolute = new Map(entries.map((e) => [e.absolute, e]))
        this.byPlace = new Map(entries.filter((e) => e.season !== null && e.episode !== null).map((e) => [place(e.season!, e.episode!), e.absolute]))
    }

    /** The last absolute number: the last listed, the run's declared count, or the one before the next to air. */
    get total(): number {
        let top = 0
        for (const e of this.entries) if (e.absolute > top) top = e.absolute
        return Math.max(top, Math.min(Math.max(this.declared, 0), MAX_EPISODES), (this.next?.episode ?? 1) - 1)
    }

    get isEmpty(): boolean {
        return !this.entries.length && !this.layout.length && this.total <= 0
    }

    entry(absolute: number): Entry | undefined {
        return this.byAbsolute.get(absolute)
    }

    /**
     * The absolute number of episode `episode` of `season`, however the file counted: where TMDB
     * places it (a file "S06E12"), else by counting `layout`'s seasons, else the number itself as
     * one counted straight through ("S01E123" in a season of 32 is episode 123). Null when nothing
     * says: a second season of a show no list breaks into seasons, or a number past the end.
     */
    absoluteOf(season: number, episode: number): number | null {
        if (!Number.isInteger(season) || !Number.isInteger(episode) || season < 1 || episode < 1 || episode > MAX_NUMBER) return null
        const placed = this.byPlace.get(place(season, episode))
        if (placed !== undefined) return placed
        const count = this.layout.find((s) => s.number === season)?.episodes ?? 0
        if (count > 0 && episode <= count) return offset(this.layout, season) + episode
        const straight = season === 1 || count > 0
        if (!straight) return null
        return episode <= Math.max(this.total, 1) || this.total === 0 ? episode : null
    }

    /** TMDB's season and episode for `absolute`: its own place, else counted through `layout`; null when it has none. */
    locate(absolute: number): [number, number] | null {
        const e = this.byAbsolute.get(absolute)
        if (e && e.season !== null && e.episode !== null) return [e.season, e.episode]
        return locateIn(this.layout, absolute)
    }

    /** The numbers `season` holds, absolute counted: used to list a season whose files are numbered per season. */
    seasonEntries(season: number): Entry[] {
        return this.entries.filter((e) => e.season === season)
    }
}

/** TMDB's seasons, specials left out, in order, each with a count: the layout episodes are counted through. */
export function layout(seasons: readonly SeasonStop[]): SeasonStop[] {
    const seen = new Set<number>()
    const kept: SeasonStop[] = []
    for (const s of seasons.slice(0, MAX_SEASONS * 20)) {
        if (!s || !Number.isInteger(s.number) || s.number < 1 || s.number > MAX_SEASONS) continue
        if (!Number.isInteger(s.episodes) || s.episodes < 1 || s.episodes > MAX_EPISODES) continue
        if (seen.has(s.number)) continue
        seen.add(s.number)
        kept.push(s)
        if (kept.length >= MAX_SEASONS * 2) break
    }
    return kept.sort((a, b) => a.number - b.number).slice(0, MAX_SEASONS)
}

function offset(list: readonly SeasonStop[], season: number): number {
    let sum = 0
    for (const s of list) {
        if (s.number >= season) break
        sum += s.episodes
    }
    return sum
}

function locateIn(list: readonly SeasonStop[], absolute: number): [number, number] | null {
    if (absolute < 1) return null
    let left = absolute
    for (const s of list) {
        if (left <= s.episodes) return [s.number, left]
        left -= s.episodes
    }
    return null
}

// ---- the sources, each turned into pieces ------------------------------------------------------

/** One episode of TMDB's "Absolute order" group: its group's order, its own, and the place TMDB files it under. */
export interface GroupEpisode {
    groupOrder: number
    order: number
    season: number
    episode: number
    piece: Piece
}

export function fromGroup(episodes: readonly GroupEpisode[]): SourceList {
    const sorted = episodes.slice(0, MAX_EPISODES * 2).sort((a, b) => a.groupOrder - b.groupOrder || a.order - b.order)
    return { source: 'group', pieces: sorted.slice(0, MAX_EPISODES).map((e, i) => ({ ...e.piece, absolute: i + 1, season: e.season, episode: e.episode })) }
}

/**
 * TMDB's seasons counted in order: season 1's episodes numbered from 1, season 2's carrying on
 * after the last number of season 1 (its count, else its highest episode). `seasons` is each
 * season's episodes, `counts` the seasons' counts from the show's own page.
 */
export function fromSeasons(seasons: ReadonlyMap<number, readonly ListedEpisode[]>, counts: readonly SeasonStop[]): SourceList {
    const fallback = [...seasons].map(([number, list]): SeasonStop => ({ number, episodes: list.reduce((top, e) => Math.max(top, e.number), 0), airDate: null }))
    const run = layout(counts.length ? counts : fallback)
    const pieces: Piece[] = []
    let start = 0
    for (const s of run) {
        const list = seasons.get(s.number) ?? []
        for (const e of list.slice(0, MAX_EPISODES)) {
            if (!(e.number >= 1 && e.number <= Math.max(s.episodes, e.number))) continue
            pieces.push({ absolute: start + e.number, name: e.name, overview: e.overview, still: e.still, airDate: e.airDate, runtime: e.runtime, season: s.number, episode: e.number })
        }
        start += s.episodes
        if (start > MAX_EPISODES) break
    }
    return { source: 'seasons', pieces }
}

/** A source that numbers its episodes straight through (Kitsu, Jikan): each number is the absolute one. */
export function fromNumbered(source: Source, episodes: readonly ListedEpisode[]): SourceList {
    return {
        source,
        pieces: episodes.slice(0, MAX_EPISODES * 2).map((e) => ({ absolute: e.number, name: e.name, overview: e.overview, still: e.still, airDate: e.airDate, runtime: e.runtime })),
    }
}

const STREAM_TITLE = /^\s*(?:episode|ep\.?|e|folge|épisode|episodio|ep)\s*(\d{1,4})\s*[-:\u2013\u2014.]\s*(.+)$/i

/**
 * An AniList streaming episode ("Episode 12 - The Title", a picture): the number is in the
 * title's words. Null for a title that does not start with one, which is not trusted with a place.
 * The picture is dropped (AniList's host is not one the page draws from).
 */
export function streamingPiece(title: string): Piece | null {
    const m = STREAM_TITLE.exec(title.slice(0, MAX_NAME * 2))
    if (!m) return null
    const number = Number(m[1])
    if (!Number.isInteger(number) || number < 1 || number > MAX_NUMBER) return null
    return { absolute: number, name: m[2].trim() }
}

// ---- the merge ---------------------------------------------------------------------------------

/**
 * Whether `list` describes the show a chat's entry of `expected` episodes is. A list well short
 * of the entry (the other show of a similar name, "Naruto" for "Naruto Shippuden") or far past
 * it is a wrong match, and its titles would be wrong ones, so it is left out. With nothing to
 * compare (`expected` null or zero) it stands.
 */
export function plausible(expected: number | null, list: SourceList): boolean {
    const total = list.pieces.reduce((top, p) => Math.max(top, p.absolute), 0)
    if (!list.pieces.length) return false
    if (expected === null || expected <= 0) return true
    return total * 10 >= expected * 9 && total <= expected * 6 + 50
}

const DIGITS = /\p{Nd}+/gu
const SEPARATORS = /[\s\p{P}\p{S}]+/u
/** The zero of each script's digits (phone.ts' list): a digit of another script is read as no number. */
const ZEROS = [0x30, 0x660, 0x6f0, 0x966, 0x9e6, 0xa66, 0xae6, 0xb66, 0xbe6, 0xc66, 0xce6, 0xd66, 0xe50, 0xed0, 0xf20, 0x1040, 0x17e0, 0x1810, 0xff10]
const PLACEHOLDER_WORD = 10

function numberOfDigits(run: string): number | null {
    let value = 0
    for (const ch of run) {
        const cp = ch.codePointAt(0)!
        const zero = ZEROS.find((z) => cp >= z && cp < z + 10)
        if (zero === undefined) return null
        value = value * 10 + (cp - zero)
        if (value > 1e9) return null
    }
    return value
}

/**
 * EpisodeNames.placeholder: whether `name` only numbers the episode, `number` and at most one short
 * word round it, in any script. "Chapter 3" and "3" are; "The 4th Floor" and "Pilot" are not.
 */
export function placeholder(name: string, number: number): boolean {
    const digits = [...name.matchAll(DIGITS)].map((m) => numberOfDigits(m[0]))
    if (!digits.length) return false
    if (digits.some((d) => d !== number)) return false
    const words = name.replace(DIGITS, ' ').split(SEPARATORS).filter((w) => w.trim())
    // One word, or a few single characters as Chinese writes it: "第 5 集".
    return (words.length <= 1 || words.every((w) => [...w].length === 1)) && words.reduce((n, w) => n + [...w].length, 0) <= PLACEHOLDER_WORD
}

const DATE = /^\d{4}-\d{2}-\d{2}$/
/** A TMDB still as the page asks it by: "/abc.jpg" only. */
const STILL = /^\/[A-Za-z0-9_-]{8,64}\.(jpg|png|webp)$/

const stillOk = (url: string | null | undefined) => typeof url === 'string' && STILL.test(url)
const dateOk = (text: string | null | undefined) => typeof text === 'string' && DATE.test(text)

/**
 * One guide from `lists` in the order they are to be read (the first with a real title wins),
 * `counts` TMDB's seasons (for the layout) and `next`, the next episode to air with its absolute
 * number. A list a wrong match was found in is the caller's to drop (`plausible`).
 */
export function merge(lists: readonly SourceList[], counts: readonly SeasonStop[], next: Next | null, declared = 0): Guide {
    const clean = lists.map((l) => {
        const byNumber = new Map<number, Piece>()
        for (const p of l.pieces.slice(0, MAX_EPISODES * 2)) {
            if (Number.isInteger(p.absolute) && p.absolute >= 1 && p.absolute <= MAX_EPISODES && !byNumber.has(p.absolute)) byNumber.set(p.absolute, p)
        }
        return { source: l.source, byNumber }
    })
    const numbers = [...new Set(clean.flatMap((c) => [...c.byNumber.keys()]))].sort((a, b) => a - b)
    const entries: Entry[] = []
    for (const n of numbers) {
        let name: string | null = null
        let nameSource: Source | null = null
        let overview: string | null = null
        let overviewSource: Source | null = null
        let still: string | null = null
        let stillSource: Source | null = null
        let date: string | null = null
        let runtime: number | null = null
        let season: number | null = null
        let episode: number | null = null
        for (const { source, byNumber } of clean) {
            const piece = byNumber.get(n)
            if (!piece) continue
            if (season === null && typeof piece.season === 'number' && typeof piece.episode === 'number') {
                season = piece.season
                episode = piece.episode
            }
            const words = (typeof piece.name === 'string' ? piece.name : '').trim().slice(0, MAX_NAME)
            if (name === null && words && !placeholder(words, n) && !(typeof piece.episode === 'number' && placeholder(words, piece.episode))) {
                name = words
                nameSource = source
            }
            const about = plain(typeof piece.overview === 'string' ? piece.overview : '').slice(0, MAX_OVERVIEW)
            if (overview === null && about) {
                overview = about
                overviewSource = source
            }
            if (still === null && stillOk(piece.still)) {
                still = piece.still!
                stillSource = source
            }
            if (date === null && dateOk(piece.airDate)) date = piece.airDate!
            if (runtime === null && typeof piece.runtime === 'number' && Number.isInteger(piece.runtime) && piece.runtime >= 1 && piece.runtime <= 1000) runtime = piece.runtime
        }
        entries.push({ absolute: n, season, episode, name: name ?? '', overview: overview ?? '', still, airDate: date, runtime, source: nameSource ?? overviewSource ?? stillSource, stillSource })
    }
    return new Guide(entries, layout(counts), next, declared)
}

// ---- what the series page reads ----------------------------------------------------------------

/** What an undated episode that has aired is dated, so it never counts as still to come. */
export const DAWN = '1970-01-01'

/** The guide as one season counted straight through, the numbers a file "Show - 123" has: what the series page lists and names. */
export function flat(guide: Guide): ListedEpisode[] {
    let last: string | null = guide.entries.find((e) => e.airDate !== null)?.airDate ?? null
    const nextAt = guide.next?.episode ?? null
    const out: ListedEpisode[] = []
    // Every number up to the run's end, named or not: an episode no source describes is still one.
    for (let n = 1; n <= Math.min(guide.total, MAX_EPISODES); n++) {
        const e = guide.entry(n)
        if (e?.airDate) last = e.airDate
        const aired = nextAt === null || n < nextAt
        out.push({ number: n, name: e?.name ?? '', overview: e?.overview ?? '', still: e?.still ?? null, airDate: e?.airDate ?? (aired ? (last ?? DAWN) : null), runtime: e?.runtime ?? null })
    }
    return out
}

/**
 * `season`'s episodes: season 1 is the guide counted straight through (`flat`), which holds every
 * number a file of season 1 can have; another season is TMDB's own list for it, numbered in the
 * season, for the files that count that way ("S06E12").
 */
export function seasonEpisodes(guide: Guide, season: number): ListedEpisode[] {
    if (season === 1) return flat(guide)
    const out: ListedEpisode[] = []
    for (const e of guide.seasonEntries(season)) {
        if (e.episode === null) continue
        out.push({ number: e.episode, name: e.name, overview: e.overview, still: e.still, airDate: e.airDate, runtime: e.runtime })
    }
    return out
}

/** The season list the series page merges: one season holding the whole run, or none for an empty guide. */
export function seasonList(guide: Guide): SeasonStop[] {
    if (guide.total <= 0) return []
    return [{ number: 1, episodes: Math.min(guide.total, MAX_EPISODES), airDate: guide.entries.find((e) => e.airDate !== null)?.airDate ?? null }]
}

/** The next episode in the season list's numbering: AniList counts it straight through, which is the one season `seasonList` lists. */
export function nextEpisode(guide: Guide): Next | null {
    const n = guide.next
    return n && n.episode >= 1 && n.episode <= MAX_NUMBER ? { ...n, season: 1 } : null
}

/** `extras` with the run's season and next episode in them, for a show whose provider lists none. */
export function overlay<T extends { seasonList?: SeasonStop[]; next?: Next | null }>(extras: T, guide: Guide): T {
    if ((extras.seasonList?.length ?? 0) > 0 || guide.total <= 0) return extras
    return { ...extras, seasonList: seasonList(guide), next: extras.next ?? nextEpisode(guide) }
}

/**
 * Episode `number` of `season` as the guide names it (OnlineMetadata.animeEpisodeFor): its title,
 * overview and picture; null when the guide has nothing to say about it.
 */
export function episodeFor(guide: Guide, season: number, number: number): { name: string; overview: string; still: string | null } | null {
    const absolute = guide.absoluteOf(season, number)
    const entry = absolute === null ? undefined : guide.entry(absolute)
    if (!entry || !(entry.name || entry.overview || entry.still)) return null
    return { name: entry.name, overview: entry.overview, still: entry.still }
}

// ---- dates ------------------------------------------------------------------------------------

/** `2026-10-14` for `epochSeconds` (UTC); null for a date no show has. */
export function dateOf(epochSeconds: number): string | null {
    if (!Number.isFinite(epochSeconds) || epochSeconds < 0 || epochSeconds > 253_402_300_799) return null
    const d = new Date(Math.trunc(epochSeconds) * 1000)
    return d.toISOString().slice(0, 10)
}

// ---- the wire: the worker's answer to /tmdb/anime and the page's reading of it -----------------

/** The answer of /tmdb/anime for one show. `complete` is false while a source was cut short (the page asks again). */
export interface GuideAnswer {
    found: true
    complete: boolean
    total: number
    declared: number
    layout: SeasonStop[]
    next: Next | null
    entries: Entry[]
}

/** The most an overview takes on the wire; the page's About shows it whole. */
const WIRE_OVERVIEW = 1500

export function toAnswer(guide: Guide, complete: boolean): GuideAnswer {
    return {
        found: true,
        complete,
        total: Math.min(guide.total, MAX_EPISODES),
        declared: guide.declared,
        layout: guide.layout,
        next: guide.next,
        entries: guide.entries.map((e) => ({ ...e, overview: e.overview.slice(0, WIRE_OVERVIEW) })),
    }
}

type Json = Record<string, unknown>
const object = (v: unknown): Json | null => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Json) : null)
const int = (v: unknown, lo: number, hi: number): number | null => (typeof v === 'number' && Number.isInteger(v) && v >= lo && v <= hi ? v : null)
const word = (v: unknown, max: number): string => (typeof v === 'string' ? v.slice(0, max) : '')
const SOURCES = new Set<string>(['group', 'seasons', 'kitsu', 'jikan', 'anilist'])
const source = (v: unknown): Source | null => (typeof v === 'string' && SOURCES.has(v) ? (v as Source) : null)

/** The next episode as an answer gives it: a numbered season and episode with a day; anything else is no next episode. */
export function readNext(v: unknown): Next | null {
    const o = object(v)
    const season = int(o?.season, 1, MAX_SEASONS)
    const episode = int(o?.episode, 1, MAX_NUMBER)
    const airDate = o?.airDate
    return season !== null && episode !== null && typeof airDate === 'string' && DATE.test(airDate) ? { season, episode, airDate } : null
}

/** The guide an answer holds, or null for anything that is not one. Never throws, whatever the input. */
export function readAnswer(json: unknown): { guide: Guide; complete: boolean } | null {
    const o = object(json)
    if (!o || o.found !== true || !Array.isArray(o.entries)) return null
    const entries: Entry[] = []
    for (const raw of o.entries.slice(0, MAX_EPISODES)) {
        const e = object(raw)
        const absolute = int(e?.absolute, 1, MAX_EPISODES)
        if (!e || absolute === null) continue
        const season = int(e.season, 0, MAX_SEASONS)
        const episode = int(e.episode, 1, MAX_NUMBER)
        entries.push({
            absolute,
            season: season !== null && episode !== null ? season : null,
            episode: season !== null && episode !== null ? episode : null,
            name: word(e.name, MAX_NAME),
            overview: word(e.overview, MAX_OVERVIEW),
            still: typeof e.still === 'string' && STILL.test(e.still) ? e.still : null,
            airDate: typeof e.airDate === 'string' && DATE.test(e.airDate) ? e.airDate : null,
            runtime: int(e.runtime, 1, 1000),
            source: source(e.source),
            stillSource: source(e.stillSource),
        })
    }
    const counts: SeasonStop[] = []
    for (const raw of (Array.isArray(o.layout) ? o.layout : []).slice(0, MAX_SEASONS * 2)) {
        const s = object(raw)
        const number = int(s?.number, 1, MAX_SEASONS)
        const episodes = int(s?.episodes, 1, MAX_EPISODES)
        if (number !== null && episodes !== null) counts.push({ number, episodes, airDate: typeof s?.airDate === 'string' && DATE.test(s.airDate) ? s.airDate : null })
    }
    return { guide: new Guide(entries, layout(counts), readNext(o.next), int(o.declared, 0, MAX_EPISODES) ?? 0), complete: o.complete === true }
}
