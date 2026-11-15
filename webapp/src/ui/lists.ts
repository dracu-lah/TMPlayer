// The viewer's named lists (HX43) as the web app keeps them: the apps' NamedListRules (core
// Lists.kt) in TypeScript, over the page's own record of a video (VideoItem), so a list draws without
// asking Telegram. Every write carries its time and what is taken off is remembered for a while, so
// two tabs (and, once the web app syncs, another device) merge with the newest write winning.
import type { VideoItem } from '../tg/types'

export interface ListInfo {
    /** Lowercase letters and digits: `list-<id>` is a Home order key (homeOrder.ts). */
    id: string
    /** Through cleanName. Two lists may share a name; their ids differ. */
    name: string
    /** When the name or the Home switch was last written. */
    at: number
    home: boolean
}

export interface ListLine {
    list: string
    item: VideoItem
    /** A show saved whole: its name, lower-cased, as Watch later keeps one (store.showLaterId). */
    show?: string
    /** When it was put in the list. */
    at: number
}

/** Something taken off: the whole list when `entry` is empty. */
export interface ListGone {
    list: string
    entry: string
    at: number
}

export interface ListsDoc {
    lists: ListInfo[]
    lines: ListLine[]
    removed: ListGone[]
}

export const NAME_MAX = 60
export const MAX_LISTS = 100
export const MAX_PER_LIST = 5_000
export const MAX_LINES = 20_000
export const MAX_REMOVALS = 2_000
export const REMOVAL_DAYS = 90
const DAY_MS = 24 * 60 * 60 * 1000
const ID_RANDOM = 6
const ALPHABET = 'abcdefghijklmnopqrstuvwxyz0123456789'

export const emptyLists = (): ListsDoc => ({ lists: [], lines: [], removed: [] })

/** A name: control characters out, spaces folded, trimmed, cut to NAME_MAX; null when nothing is left. */
export function cleanName(raw: unknown): string | null {
    if (typeof raw !== 'string' || !raw) return null
    let out = ''
    let space = false
    for (const ch of raw.slice(0, NAME_MAX * 8)) {
        if (/[\s\p{Cc}​﻿]/u.test(ch)) {
            space = out.length > 0
            continue
        }
        const add = (space ? ' ' : '') + ch
        // A character that would cross the cap is left out whole (an emoji is two code units).
        if (out.length + add.length > NAME_MAX) break
        out += add
        space = false
    }
    out = out.trim()
    return out || null
}

export const isId = (id: unknown): id is string => typeof id === 'string' && /^[a-z0-9]{4,20}$/.test(id)

export function newId(now: number, taken: ReadonlySet<string> = new Set(), random: () => number = Math.random): string {
    const stamp = Math.max(0, Math.floor(now)).toString(36)
    for (;;) {
        let id = stamp
        for (let i = 0; i < ID_RANDOM; i++) id += ALPHABET[Math.floor(random() * ALPHABET.length) % ALPHABET.length]
        if (!taken.has(id)) return id
    }
}

export const homeKey = (id: string) => `list-${id}`

/** A line's name in its list: the show's, or the video's, as Watch later names them. */
export const lineKey = (l: Pick<ListLine, 'item' | 'show'>) => (l.show ? `show:${l.item.chatId}:${l.show}` : `${l.item.chatId}:${l.item.messageId}`)

const later = (before: number, now: number) => (now > before ? now : before + 1)

export function create(doc: ListsDoc, name: string, now: number, id = newId(now, new Set(doc.lists.map((l) => l.id)))): [ListsDoc, ListInfo] | null {
    const clean = cleanName(name)
    if (!clean || doc.lists.length >= MAX_LISTS || !isId(id) || doc.lists.some((l) => l.id === id)) return null
    const info: ListInfo = { id, name: clean, at: now, home: true }
    return [{ ...doc, lists: [...doc.lists, info], removed: doc.removed.filter((r) => r.list !== id) }, info]
}

export function rename(doc: ListsDoc, id: string, name: string, now: number): ListsDoc {
    const clean = cleanName(name)
    const info = doc.lists.find((l) => l.id === id)
    if (!clean || !info || info.name === clean) return doc
    return { ...doc, lists: doc.lists.map((l) => (l.id === id ? { ...l, name: clean, at: later(l.at, now) } : l)) }
}

export function setHome(doc: ListsDoc, id: string, home: boolean, now: number): ListsDoc {
    const info = doc.lists.find((l) => l.id === id)
    if (!info || info.home === home) return doc
    return { ...doc, lists: doc.lists.map((l) => (l.id === id ? { ...l, home, at: later(l.at, now) } : l)) }
}

export function deleteList(doc: ListsDoc, id: string, now: number): ListsDoc {
    const info = doc.lists.find((l) => l.id === id)
    const lines = doc.lines.filter((l) => l.list === id)
    if (!info && !lines.length) return doc
    // After every write of the list, so none of them outlives the removal in a merge.
    const at = Math.max(now, (info?.at ?? 0) + 1, ...lines.map((l) => l.at + 1))
    return prune({ lists: doc.lists.filter((l) => l.id !== id), lines: doc.lines.filter((l) => l.list !== id), removed: [...doc.removed.filter((r) => r.list !== id), { list: id, entry: '', at }] }, now)
}

export type AddResult = 'added' | 'already' | 'full' | 'no-list'

/** The same video (or show) twice is one line, which keeps its first time and place. */
export function add(doc: ListsDoc, list: string, line: Omit<ListLine, 'list' | 'at'>, now: number): [ListsDoc, AddResult] {
    if (!doc.lists.some((l) => l.id === list)) return [doc, 'no-list']
    const key = lineKey(line)
    if (doc.lines.some((l) => l.list === list && lineKey(l) === key)) return [doc, 'already']
    if (doc.lines.length >= MAX_LINES || doc.lines.filter((l) => l.list === list).length >= MAX_PER_LIST) return [doc, 'full']
    const next: ListLine = { list, item: line.item, at: now, ...(line.show ? { show: line.show } : {}) }
    return [{ ...doc, lines: [...doc.lines, next], removed: doc.removed.filter((r) => !(r.list === list && r.entry === key)) }, 'added']
}

export function removeLines(doc: ListsDoc, list: string, keys: ReadonlySet<string>, now: number): ListsDoc {
    const doomed = doc.lines.filter((l) => l.list === list && keys.has(lineKey(l)))
    if (!doomed.length) return doc
    const gone = new Set(doomed.map(lineKey))
    return prune(
        {
            ...doc,
            lines: doc.lines.filter((l) => !(l.list === list && gone.has(lineKey(l)))),
            removed: [...doc.removed.filter((r) => !(r.list === list && gone.has(r.entry))), ...doomed.map((l) => ({ list, entry: lineKey(l), at: Math.max(now, l.at + 1) }))],
        },
        now,
    )
}

/** Videos gone from their chats (HX21) out of every list; a show's line stays. */
export function forgetVideos(doc: ListsDoc, keys: ReadonlySet<string>, now: number): [ListsDoc, number] {
    const doomed = doc.lines.filter((l) => !l.show && keys.has(lineKey(l)))
    let next = doc
    for (const list of new Set(doomed.map((l) => l.list))) next = removeLines(next, list, new Set(doomed.filter((l) => l.list === list).map(lineKey)), now)
    return [next, doomed.length]
}

export function prune(doc: ListsDoc, now: number): ListsDoc {
    const oldest = now - REMOVAL_DAYS * DAY_MS
    const kept = doc.removed.filter((r) => r.at >= oldest)
    const bounded = kept.length <= MAX_REMOVALS ? kept : [...kept].sort((a, b) => b.at - a.at).slice(0, MAX_REMOVALS)
    return bounded.length === doc.removed.length ? doc : { ...doc, removed: bounded }
}

/** Of two writes of one thing at one time, the larger by its text, so both sides agree. */
const tie = (a: unknown, b: unknown) => JSON.stringify(a) > JSON.stringify(b)

/**
 * Two copies as one, as the apps merge them: the newest write wins per list and per line, a removal
 * beats a write of the same age or older, a whole list's removal takes its lines older than it, and
 * a line added after it brings the list back with that line alone. Anything that does not read as
 * a list, a line or a removal is dropped on the way.
 */
export function merge(a: ListsDoc, b: ListsDoc, now: number): ListsDoc {
    const oldest = now - REMOVAL_DAYS * DAY_MS
    const removedAt = new Map<string, number>()
    for (const r of [...(a.removed ?? []), ...(b.removed ?? [])]) {
        if (!r || !isId(r.list) || typeof r.entry !== 'string' || typeof r.at !== 'number' || r.at < oldest || r.entry.length > 300) continue
        const k = `${r.list}\u0000${r.entry}`
        if (!(removedAt.get(k)! >= r.at)) removedAt.set(k, r.at)
    }
    const listGone = (id: string) => removedAt.get(`${id}\u0000`) ?? -Infinity
    const lineGone = (id: string, entry: string) => Math.max(listGone(id), removedAt.get(`${id}\u0000${entry}`) ?? -Infinity)

    const lines = new Map<string, ListLine>()
    for (const l of [...(a.lines ?? []), ...(b.lines ?? [])]) {
        if (!l || !isId(l.list) || !l.item || typeof l.item.chatId !== 'number' || typeof l.item.messageId !== 'number' || typeof l.at !== 'number') continue
        const entry = lineKey(l)
        if (l.at < lineGone(l.list, entry)) continue
        const k = `${l.list}\u0000${entry}`
        const known = lines.get(k)
        if (!known || l.at > known.at || (l.at === known.at && tie(l, known))) lines.set(k, l)
    }
    const byList = new Map<string, ListLine[]>()
    for (const l of lines.values()) {
        const of = byList.get(l.list)
        if (of) of.push(l)
        else byList.set(l.list, [l])
    }

    const infos = new Map<string, ListInfo>()
    for (const info of [...(a.lists ?? []), ...(b.lists ?? [])]) {
        if (!info || !isId(info.id) || typeof info.at !== 'number') continue
        const name = cleanName(info.name)
        if (!name) continue
        const clean: ListInfo = { id: info.id, name, at: info.at, home: info.home !== false }
        const known = infos.get(info.id)
        if (!known || clean.at > known.at || (clean.at === known.at && tie(clean, known))) infos.set(info.id, clean)
    }
    const made = (l: ListInfo) => parseInt(l.id.slice(0, -ID_RANDOM), 36) || l.at
    const listsOut = [...infos.values()]
        .filter((l) => l.at >= listGone(l.id) || (byList.get(l.id)?.length ?? 0) > 0)
        .sort((x, y) => made(x) - made(y) || (x.id < y.id ? -1 : 1))
        .slice(0, MAX_LISTS)
    const live = new Set(listsOut.map((l) => l.id))
    let linesOut = [...byList.entries()].filter(([id]) => live.has(id)).flatMap(([, ls]) => [...ls].sort(byAdded).slice(0, MAX_PER_LIST))
    if (linesOut.length > MAX_LINES) linesOut = [...linesOut].sort((x, y) => y.at - x.at).slice(0, MAX_LINES)
    linesOut.sort((x, y) => (x.list < y.list ? -1 : x.list > y.list ? 1 : byAdded(x, y)))
    const liveAt = new Map(linesOut.map((l) => [`${l.list}\u0000${lineKey(l)}`, l.at]))
    const removed: ListGone[] = []
    for (const [k, at] of removedAt) {
        const [list, entry] = k.split('\u0000')
        // A line's removal a newer write of the line outlived is done; a whole list's stays until it expires.
        if (entry && (liveAt.get(k) ?? -Infinity) >= at) continue
        removed.push({ list, entry, at })
    }
    removed.sort((x, y) => y.at - x.at || (x.list < y.list ? -1 : x.list > y.list ? 1 : x.entry < y.entry ? -1 : x.entry > y.entry ? 1 : 0))
    return { lists: listsOut, lines: linesOut, removed: removed.slice(0, MAX_REMOVALS) }
}

/** The order a list's lines show in: the order they were added. */
export const byAdded = (x: ListLine, y: ListLine) => x.at - y.at || (lineKey(x) < lineKey(y) ? -1 : lineKey(x) > lineKey(y) ? 1 : 0)

/** Each list with its lines in the order added, the lists in the order made. */
export function visible(doc: ListsDoc): { info: ListInfo; lines: ListLine[] }[] {
    const tidy = merge(doc, emptyLists(), 0)
    return tidy.lists.map((info) => ({ info, lines: tidy.lines.filter((l) => l.list === info.id).sort(byAdded) }))
}

/** What the store read back, checked: anything else is an empty set of lists. */
export function readLists(raw: unknown, now: number): ListsDoc {
    if (!raw || typeof raw !== 'object') return emptyLists()
    const r = raw as Partial<ListsDoc>
    const arr = <T>(x: unknown): T[] => (Array.isArray(x) ? (x.slice(0, MAX_LINES * 2) as T[]) : [])
    return merge({ lists: arr<ListInfo>(r.lists), lines: arr<ListLine>(r.lines), removed: arr<ListGone>(r.removed) }, emptyLists(), now)
}
