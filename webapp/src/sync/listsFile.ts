// The named lists (ui/lists.ts) as the sync file and the backup file hold them (core's
// NamedListsJson): `{ lists: [{id, name, at, home}], entries: [one Watch later record + list],
// removed: [{list, at, entry?}] }`, with the apps' message ids (TDLib's, shifted) and entry names
// (`<chat>_<message>` for a video, `<chat>_s:<show>` for a show). Pure: no stores, no Telegram.
// Until WP-56 the web carried this part of a file through untouched and never read it, so a list
// made on the web did not reach the apps and the apps' lists did not show here.
import { cleanName, isId, MAX_LINES, MAX_LISTS, MAX_REMOVALS, type ListGone, type ListInfo, type ListLine, type ListsDoc } from '../ui/lists.ts'
import { tdlibMessageId, webMessageId } from './format.ts'

type Json = Record<string, unknown>
const obj = (v: unknown): Json | null => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Json) : null)
const arr = (v: unknown, cap: number): Json[] => (Array.isArray(v) ? v.slice(0, cap).map(obj).filter((o): o is Json => o !== null) : [])
const int = (v: unknown): number | null => (typeof v === 'number' && Number.isFinite(v) ? Math.trunc(v) : null)
const text = (v: unknown, max: number): string => (typeof v === 'string' ? v.slice(0, max) : '')
const TEXT_MAX = 500
const ENTRY_ID_MAX = 300

/** A line the file holds, before its video is read from Telegram. */
export interface FileEntry {
    list: string
    chat: number
    /** The web's message id. */
    msg: number
    title: string
    at: number
    /** The show's key for a show saved whole. */
    show?: string
}

export interface FromFile {
    lists: ListInfo[]
    entries: FileEntry[]
    removed: ListGone[]
}

/** A web line's name (`lists.lineKey`) as the apps name an entry. */
export function entryId(key: string): string | null {
    const show = /^show:(-?\d+):(.*)$/s.exec(key)
    if (show) return `${show[1]}_s:${show[2]}`
    const video = /^(-?\d+):(\d+)$/.exec(key)
    return video ? `${video[1]}_${tdlibMessageId(Number(video[2]))}` : null
}

/** The apps' entry name as the web names a line; null for one whose message is not a server message's. */
export function lineKeyOf(entry: string): string | null {
    const show = /^(-?\d+)_s:(.*)$/s.exec(entry)
    if (show) return `show:${show[1]}:${show[2]}`
    const video = /^(-?\d+)_(\d+)$/.exec(entry)
    if (!video) return null
    const msg = webMessageId(Number(video[2]))
    return msg === null ? null : `${video[1]}:${msg}`
}

/** The web's lists as the file writes them. `removals` is false for a backup (a restore adds and never takes away). */
export function toFile(doc: ListsDoc, removals = true): Json {
    const out: Json = {
        lists: doc.lists.map((l) => ({ id: l.id, name: l.name, at: l.at, home: l.home })).sort((a, b) => (a.id < b.id ? -1 : 1)),
        entries: doc.lines
            .map((l) => ({
                chat: l.item.chatId,
                message: tdlibMessageId(l.item.messageId),
                file: 0,
                title: (l.show ? l.item.title : l.item.title || l.item.name || '').slice(0, TEXT_MAX),
                fileName: (l.item.fileName ?? '').slice(0, TEXT_MAX),
                chatTitle: (l.item.chat ?? '').slice(0, TEXT_MAX),
                size: l.item.size ?? 0,
                durationSec: l.item.duration ?? 0,
                mime: '',
                addedAt: l.at,
                ...(l.show ? { series: l.show } : {}),
                list: l.list,
            }))
            .sort((a, b) => (a.list < b.list ? -1 : a.list > b.list ? 1 : a.addedAt - b.addedAt)),
    }
    if (removals) {
        out.removed = doc.removed
            .map((r) => ({ r, entry: r.entry ? entryId(r.entry) : '' }))
            .filter((x) => x.entry !== null)
            .map(({ r, entry }) => ({ list: r.list, at: r.at, ...(entry ? { entry } : {}) }))
    }
    return out
}

/** Reads the file's lists: whatever does not read as a list, a line or a removal is skipped, and the counts are capped. */
export function fromFile(raw: unknown): FromFile {
    const root = obj(raw)
    const lists: ListInfo[] = []
    const entries: FileEntry[] = []
    const removed: ListGone[] = []
    if (!root) return { lists, entries, removed }
    for (const o of arr(root.lists, MAX_LISTS * 2)) {
        const id = o.id
        const name = cleanName(o.name)
        const at = int(o.at)
        if (!isId(id) || !name || at === null) continue
        lists.push({ id, name, at, home: o.home !== false })
    }
    for (const o of arr(root.entries, MAX_LINES * 2)) {
        const list = o.list
        const chat = int(o.chat)
        const message = int(o.message)
        const at = int(o.addedAt)
        if (!isId(list) || chat === null || chat === 0 || message === null || message <= 0 || at === null) continue
        const msg = webMessageId(message)
        if (msg === null) continue
        const series = text(o.series, 400).trim()
        entries.push({ list, chat, msg, title: text(o.title, TEXT_MAX), at, ...(series ? { show: series } : {}) })
    }
    for (const o of arr(root.removed, MAX_REMOVALS * 2)) {
        const list = o.list
        const at = int(o.at)
        const entry = typeof o.entry === 'string' ? o.entry : ''
        if (!isId(list) || at === null || entry.length > ENTRY_ID_MAX) continue
        if (!entry) {
            removed.push({ list, entry: '', at })
            continue
        }
        const key = lineKeyOf(entry)
        if (key) removed.push({ list, entry: key, at })
    }
    return { lists, entries, removed }
}

/**
 * What a merge takes from `file`: its lists, the `lines` read from Telegram and, for a sync only,
 * its removals. A restore (`removals` false) adds and never takes away, so a backup that carries
 * removals (the apps', or a crafted one) cannot delete this viewer's lists or lines.
 */
export function mergeInput(file: FromFile, lines: ListLine[], removals: boolean): ListsDoc {
    return { lists: file.lists, lines, removed: removals ? file.removed : [] }
}

/** The lines of `file` that this device's `doc` does not have, newest first (the ones to read from Telegram). */
export function missingLines(file: FromFile, doc: ListsDoc): FileEntry[] {
    // The same name ui/lists.ts's lineKey gives.
    const have = new Set(doc.lines.map((l) => `${l.list}\u0000${l.show ? `show:${l.item.chatId}:${l.show}` : `${l.item.chatId}:${l.item.messageId}`}`))
    const gone = new Map(file.removed.map((r) => [`${r.list}\u0000${r.entry}`, r.at] as const))
    return file.entries
        .filter((e) => {
            const key = e.show ? `show:${e.chat}:${e.show}` : `${e.chat}:${e.msg}`
            if (have.has(`${e.list}\u0000${key}`)) return false
            const listGone = gone.get(`${e.list}\u0000`) ?? -Infinity
            return e.at >= Math.max(listGone, gone.get(`${e.list}\u0000${key}`) ?? -Infinity)
        })
        .sort((a, b) => b.at - a.at)
}
