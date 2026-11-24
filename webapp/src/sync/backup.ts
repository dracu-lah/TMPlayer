// The backup file (core's Backups, "tmplayer-backup", versions 1 and 2) as the web writes and reads
// it: favourites, resume points, watched videos, Watch later, bookmarks and the named lists, in the
// apps' own field names and message ids, so a file made here restores on Android, desktop and iOS and
// the other way round. The apps' settings (Android preference names) are not read and the web writes
// none: its settings are its own. Never in it: the sign in, any key or token.
//
// The web reads and writes this file through the sync document (format.ts) it already keeps in step
// with core, so one record means the same thing in both. A file is read like the apps read one: a
// record that does not read is skipped on its own and the rest restores; a file that is not a
// backup, or comes from a newer TMPlayer, is refused with a reason.
import {
    MAX_BOOKMARKS,
    MAX_FAVOURITES,
    MAX_RESUME,
    MAX_TEXT_BYTES,
    MAX_WATCHED,
    MAX_WATCH_LATER,
    TITLE_MAX,
    emptyDoc,
    type SyncDoc,
} from './format.ts'
import { toFile } from './listsFile.ts'
import type { ListsDoc } from '../ui/lists.ts'

export const BACKUP_FORMAT = 'tmplayer-backup'
/** Raised whenever a file this build writes could not be read by an older one (Backups.VERSION). */
export const BACKUP_VERSION = 2
export const LISTLESS_VERSION = 1
/** A file larger than this is not read at all. */
export const MAX_BACKUP_BYTES = MAX_TEXT_BYTES
const MAX_SEARCHES = 50

type Json = Record<string, unknown>
const obj = (v: unknown): Json | null => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Json) : null)
const objs = (v: unknown, cap: number): Json[] => (Array.isArray(v) ? v.slice(0, cap).map(obj).filter((o): o is Json => o !== null) : [])
const int = (v: unknown): number | null => (typeof v === 'number' && Number.isFinite(v) ? Math.trunc(v) : null)
const str = (v: unknown, max = 2_000): string => (typeof v === 'string' ? v.slice(0, max) : '')
const STATE = /^[^,]*,[^,]*,[^,]*,[^,]*,[^,]*$/
const clean = (t: string) => t.trim().slice(0, TITLE_MAX).trim()

export interface BackupParts {
    /** What this device holds, as the sync document (format.ts). */
    doc: SyncDoc
    /** The named lists, without their removals. */
    lists: ListsDoc
    recentSearches?: string[]
}

/** What the writer is told: `accountTag` is `accountTagOf` the signed in account ('' when unknown). */
export interface BackupOut {
    now: number
    appVersion: string
    accountTag: string
}

/** `<salt>:<check>`: 16 hex of random salt, 4 hex of check. */
const TAG = /^([0-9a-f]{16}):([0-9a-f]{4})$/

const hex = (bytes: Uint8Array) => [...bytes].map((b) => b.toString(16).padStart(2, '0')).join('')

/**
 * A tag for the Telegram account a file was made for (SEC2-10): enough to warn when another account
 * restores it, without naming the account. A plain hash of the user id would not do: the ids are few
 * enough to try them all. So the tag is a random salt of its own and only 16 bits of
 * SHA-256(salt, id): trying every id leaves tens of thousands that fit, so the file does not say which
 * account it is, and another account is warned 65,535 times in 65,536. The apps write and read no
 * such field (core's Backups ignores it), so the file restores the same there. '' for no account.
 */
export async function accountTagOf(id: number, salt?: string): Promise<string> {
    if (!Number.isFinite(id) || id === 0) return ''
    const s = salt ?? hex(crypto.getRandomValues(new Uint8Array(8)))
    const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(`tmplayer-backup:${s}:${id}`)))
    return `${s}:${hex(digest.slice(0, 2))}`
}

/** Whether a file says it was made for another Telegram account than `account`: by its tag, or by the user id a file from before SEC2-10 holds. */
export async function otherAccount(read: { account: number | null; accountTag: string | null }, account: number): Promise<boolean> {
    if (account === 0) return false
    const salt = read.accountTag === null ? null : TAG.exec(read.accountTag)?.[1]
    if (salt) return read.accountTag !== (await accountTagOf(account, salt))
    return read.account !== null && read.account !== 0 && read.account !== account
}

/** The file's JSON, as `Backups.encode` writes it. */
export function toBackupJson(p: BackupParts, o: BackupOut): Json {
    const { doc } = p
    const hasLists = p.lists.lists.length > 0 || p.lists.lines.length > 0
    return {
        format: BACKUP_FORMAT,
        version: hasLists ? BACKUP_VERSION : LISTLESS_VERSION,
        createdAt: o.now,
        app: o.appVersion,
        // Not the apps' own field: a tag of the Telegram account the file was made for, so another one is warned (the apps ignore it).
        ...(TAG.test(o.accountTag) ? { accountTag: o.accountTag } : {}),
        settings: {},
        metadata: {},
        favourites: [...doc.favourites.keys()].sort((a, b) => a - b),
        recentSearches: (p.recentSearches ?? []).slice(0, MAX_SEARCHES),
        memory: {},
        resume: doc.resume.map((r) => ({ chat: r.chat, message: r.msg, file: r.file, title: r.title, chatTitle: r.chatTitle, size: r.size, durationSec: r.durationSec, positionMs: r.positionMs, durationMs: r.durationMs, updatedAt: r.updatedAt, ...(r.state ? { state: r.state } : {}) })),
        watched: doc.watched.map((w) => ({ chat: w.chat, message: w.msg, file: w.file, title: w.title, chatTitle: w.chatTitle, size: w.size, durationSec: w.durationSec, watchedAt: w.watchedAt, manual: w.manual })),
        watchLater: doc.later.map((l) => ({ chat: l.chat, message: l.msg, file: l.file, title: l.title, fileName: l.fileName, chatTitle: l.chatTitle, size: l.size, durationSec: l.durationSec, mime: l.mime, addedAt: l.addedAt, ...(l.series ? { series: l.series } : {}) })),
        bookmarks: doc.bookmarks.map((m) => ({ chat: m.chat, message: m.msg, id: m.id, positionMs: m.positionMs, note: m.note })),
        ...(hasLists ? { lists: toFile(p.lists, false) } : {}),
    }
}

export const backupText = (p: BackupParts, o: BackupOut) => JSON.stringify(toBackupJson(p, o), null, 2)

/** `tmplayer-backup-2026-11-24.json`: the date of the device's own clock. */
export function backupFileName(now: number): string {
    const d = new Date(now)
    const two = (n: number) => String(n).padStart(2, '0')
    return `tmplayer-backup-${d.getFullYear()}-${two(d.getMonth() + 1)}-${two(d.getDate())}.json`
}

export type BackupRead =
    /** `account`: the user id a file from before SEC2-10 holds; `accountTag`: what a newer one holds instead. */
    | { kind: 'ok'; doc: SyncDoc; searches: string[]; createdAt: number; app: string; account: number | null; accountTag: string | null; lists: unknown }
    | { kind: 'tooNew'; version: number }
    | { kind: 'notBackup' }
    | { kind: 'tooBig' }

/** Reads a file's text. Never throws: anything unreadable is `notBackup`. */
export function decodeBackup(text: string): BackupRead {
    if (typeof text !== 'string' || text.length > MAX_BACKUP_BYTES) return { kind: 'tooBig' }
    let root: Json | null
    try {
        root = obj(JSON.parse(text))
    } catch {
        return { kind: 'notBackup' }
    }
    if (!root || root.format !== BACKUP_FORMAT) return { kind: 'notBackup' }
    const version = int(root.version)
    if (version === null || version < 1) return { kind: 'notBackup' }
    if (version > BACKUP_VERSION) return { kind: 'tooNew', version: Math.min(version, 2 ** 31 - 1) }
    const createdAt = int(root.createdAt) ?? 0
    const doc = emptyDoc(0)
    doc.updatedAt = createdAt
    doc.appVersion = str(root.app, 40)
    for (const j of objs(root.resume, MAX_RESUME * 2)) {
        const chat = int(j.chat)
        const msg = int(j.message)
        const file = int(j.file)
        const positionMs = int(j.positionMs)
        if (chat === null || msg === null || file === null || file <= 0 || positionMs === null || positionMs < 0) continue
        const state = str(j.state, 200)
        doc.resume.push({ chat, msg, file, title: clean(str(j.title, 400)) || 'Video', chatTitle: '', size: int(j.size) ?? 0, durationSec: int(j.durationSec) ?? 0, positionMs, durationMs: int(j.durationMs) ?? 0, updatedAt: int(j.updatedAt) ?? 0, ...(STATE.test(state) ? { state } : {}) })
    }
    for (const j of objs(root.watched, MAX_WATCHED * 2)) {
        const chat = int(j.chat)
        const msg = int(j.message)
        const watchedAt = int(j.watchedAt)
        if (chat === null || msg === null || watchedAt === null) continue
        doc.watched.push({ chat, msg, file: 0, title: clean(str(j.title, 400)), chatTitle: '', size: int(j.size) ?? 0, durationSec: int(j.durationSec) ?? 0, watchedAt, manual: j.manual === true })
    }
    for (const j of objs(root.watchLater, MAX_WATCH_LATER * 2)) {
        const chat = int(j.chat)
        const msg = int(j.message)
        const addedAt = int(j.addedAt)
        if (chat === null || msg === null || addedAt === null) continue
        doc.later.push({ chat, msg, file: 0, title: clean(str(j.title, 400)), fileName: '', chatTitle: '', size: int(j.size) ?? 0, durationSec: int(j.durationSec) ?? 0, mime: str(j.mime, 100), addedAt, series: str(j.series, 400).trim().slice(0, TITLE_MAX * 2) })
    }
    // The apps keep no time for a star: the file's own date stands for it.
    for (const v of (Array.isArray(root.favourites) ? root.favourites : []).slice(0, MAX_FAVOURITES * 2)) {
        const chat = int(v)
        if (chat !== null) doc.favourites.set(chat, createdAt)
    }
    for (const j of objs(root.bookmarks, MAX_BOOKMARKS * 2)) {
        const chat = int(j.chat)
        const msg = int(j.message)
        const id = int(j.id)
        const positionMs = int(j.positionMs)
        if (chat === null || msg === null || id === null || id <= 0 || positionMs === null || positionMs < 0) continue
        doc.bookmarks.push({ chat, msg, id, positionMs, note: str(j.note, 400).trim(), at: id })
    }
    const lists = obj(root.lists)
    if (lists) doc.lists = lists
    const searches = (Array.isArray(root.recentSearches) ? root.recentSearches : [])
        .slice(0, MAX_SEARCHES * 2)
        .filter((s): s is string => typeof s === 'string')
        .map((s) => s.trim().slice(0, 200))
        .filter(Boolean)
        .slice(0, MAX_SEARCHES)
    return { kind: 'ok', doc, searches, createdAt, app: str(root.app, 40), account: int(root.account), accountTag: typeof root.accountTag === 'string' && TAG.test(root.accountTag) ? root.accountTag : null, lists: lists ?? undefined }
}
