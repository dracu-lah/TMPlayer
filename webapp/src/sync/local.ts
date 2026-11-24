// This device's lists as a sync file, and a merged file written into this device's lists
// (SyncDocuments.collect and the apply half of ResumeSync.run). Pure over plain inputs and a
// writer, so the rules are tested without a browser; run.ts hands it the real stores.
//
// What the web keeps differently from the apps, and how that is told here:
// - Message ids: the file holds TDLib's (the web's shifted left by 20 bits), see format.ts.
// - A place has no title here (Continue watching reads its video from Telegram): the title the
//   file already holds for that video is kept, else "Video". The apps read the video from
//   Telegram too and take its own title, so the placeholder is never what a person sees.
// - Watch later lines for a whole show, bookmarks, the named lists and every correction line but a
//   poster pick and a rename are the apps' own: they travel through the file untouched (a merge
//   never drops an entry for being absent) and the web neither shows nor changes them.
// - A removal is told from what this device noted when the viewer took the entry off (store.ts
//   `xw:`, `xl:`, `xfix:`, `xtitle:`), except places and favourite chats, which are told from what the last
//   sync left, as the apps do.
import { decodePick, type Pick } from '../pick.ts'
import { cleanTitle, titleLine, titleOf } from '../rename.ts'
import type { ListsDoc } from '../ui/lists.ts'
import { toFile } from './listsFile.ts'
import {
    REMOVAL_DAYS,
    TITLE_MAX,
    emptyDoc,
    keyOf,
    tdlibMessageId,
    validKey,
    webMessageId,
    type CorrectionRec,
    type LaterRec,
    type Removal,
    type ResumeRec,
    type SyncApply,
    type SyncDoc,
    type WatchedRec,
} from './format.ts'

/** What this device syncs last, for telling what it removed since (SyncBase). */
export interface Base {
    /** The place keys (apps' form) this device held after its last sync. */
    resumeKeys: string[]
    favourites: number[]
    at: number
}
export const noBase = (): Base => ({ resumeKeys: [], favourites: [], at: 0 })

/** The lists as the web stores hold them, in the web's own ids and units. */
export interface Lists {
    /** Seconds, as the player saves them. */
    resumes: { chatId: number; messageId: number; position: number; length: number; time: number }[]
    watched: { chatId: number; messageId: number; time: number }[]
    /** Single videos only: a whole show saved is not synced. */
    later: { chatId: number; messageId: number; time: number; title: string; size: number; duration: number }[]
    stars: { chatId: number; time: number }[]
    picks: { chatId: number; messageId: number; text: string; at: number }[]
    /** The titles the viewer gave (a rename, with when it was set): the other line of a correction the web writes. */
    titles?: { chatId: number; messageId: number; title: string; at: number }[]
    /** What the viewer took off, by the apps' key. */
    tombs: { list: 'watched' | 'later' | 'correction'; key: string; at: number }[]
    /** The renames the viewer reset (Use the file name), by the apps' key, with when. */
    titleTombs?: { key: string; at: number }[]
    /** The named lists (WP-56): written to the file as the apps write theirs. */
    named?: ListsDoc
}

const DAY_MS = 24 * 60 * 60 * 1000
const PLACEHOLDER = 'Video'

const cut = (title: string) => title.trim().slice(0, TITLE_MAX).trim()

const PICK_LINE = /^tmdb=(film|show):\d{1,9}$/
/** The lines VideoCorrection.encode writes: everything else on a correction is another version's. */
const KNOWN_LINE = /^(separate|wrong|title=.*|tmdb=(film|show):\d{1,9})$/
/** How much of another version's lines is carried along (the file's own limit for a correction is 1,000 characters). */
const UNKNOWN_LINES = 8
const UNKNOWN_CHARS = 120

/**
 * VideoCorrection.encode's lines of `text` with the lines `change` sets: `pick` (a `tmdb=` line, null
 * for none, undefined to keep the one the file has) and `title` (a rename, the same three ways). A
 * pick lifts a "wrong match" as the apps' withPick does. A line it does not know (a newer app's, such
 * as `year=2019`) is kept verbatim after the others: core's decode and encode would drop it, but the
 * web has no reason to, and it never makes a correction look empty. "" when nothing is left.
 */
export function withLines(text: string | undefined, change: { pick?: string | null; title?: string | null }): string {
    const lines = (text ?? '').split(/\r\n|\r|\n/)
    const separate = lines.includes('separate')
    const kept = lines.map((l) => l.trim()).find((l) => PICK_LINE.test(l)) ?? null
    const pick = change.pick === undefined ? kept : change.pick
    const wrong = lines.includes('wrong') && !pick
    const title = change.title === undefined ? titleOf(text, TITLE_MAX) : change.title
    const unknown = lines.filter((l) => l.trim() && !KNOWN_LINE.test(l.trim())).slice(0, UNKNOWN_LINES).map((l) => l.slice(0, UNKNOWN_CHARS))
    return [separate ? 'separate' : null, wrong ? 'wrong' : null, pick, title ? titleLine(title) : null, ...unknown].filter((l): l is string => !!l).join('\n')
}

/** `text` with the pick line set to `pick` (null: none), the rename and the rest kept. */
export const withPick = (text: string | undefined, pick: string | null): string => withLines(text, { pick })

/** The correction's lines without its pick: what is left of a correction once the pick is taken off. */
export const withoutPick = (text: string) => withPick(text, null)

/**
 * This device's lists as a sync file. `remote` is the file as just read (its titles and the other
 * lines of a correction are kept), `remoteFavourites` its favourites with their times.
 */
export function collect(lists: Lists, base: Base, remote: SyncDoc, account: number, now: number, appVersion: string): SyncDoc {
    const doc = emptyDoc(account)
    doc.updatedAt = now
    doc.appVersion = appVersion
    const titles = new Map<string, string>()
    // The tracks and speed a place was left with on another device stay with it (ResumeState).
    const states = new Map<string, string | undefined>()
    for (const r of remote.resume) (titles.set(keyOf(r.chat, r.msg), r.title), states.set(keyOf(r.chat, r.msg), r.state))
    for (const w of remote.watched) if (w.title) titles.set(keyOf(w.chat, w.msg), w.title)
    for (const l of remote.later) if (l.title && !l.series) titles.set(keyOf(l.chat, l.msg), l.title)
    const title = (key: string) => cut(titles.get(key) ?? '') || PLACEHOLDER

    for (const r of lists.resumes) {
        const msg = tdlibMessageId(r.messageId)
        const key = keyOf(r.chatId, msg)
        if (!(r.position > 0) || !Number.isFinite(r.time)) continue
        doc.resume.push({
            chat: r.chatId,
            msg,
            // The file id is the writing device's own and the apps replace it with theirs; the file's check wants a positive one.
            file: 1,
            title: title(key),
            chatTitle: '',
            size: 0,
            durationSec: Math.max(0, Math.round(r.length)),
            positionMs: Math.round(r.position * 1000),
            durationMs: Math.max(0, Math.round(r.length * 1000)),
            updatedAt: r.time,
            ...(states.get(key) ? { state: states.get(key) } : {}),
        } satisfies ResumeRec)
    }
    for (const w of lists.watched) {
        const msg = tdlibMessageId(w.messageId)
        doc.watched.push({ chat: w.chatId, msg, file: 0, title: title(keyOf(w.chatId, msg)), chatTitle: '', size: 0, durationSec: 0, watchedAt: w.time, manual: false } satisfies WatchedRec)
    }
    for (const l of lists.later) {
        doc.later.push({ chat: l.chatId, msg: tdlibMessageId(l.messageId), file: 0, title: cut(l.title) || PLACEHOLDER, fileName: '', chatTitle: '', size: Math.max(0, Math.round(l.size)), durationSec: Math.max(0, Math.round(l.duration)), mime: 'video/mp4', addedAt: l.time, series: '' } satisfies LaterRec)
    }
    // A favourite's time is the file's own for it, the last sync's for one this device already had then, and now for one starred since.
    const heldThen = new Set(base.favourites)
    const starred = new Set(lists.stars.map((s) => s.chatId))
    for (const s of lists.stars) doc.favourites.set(s.chatId, remote.favourites.get(s.chatId) ?? (heldThen.has(s.chatId) && base.at > 0 ? base.at : now))

    const removed: Removal[] = []
    const held = new Set(doc.resume.map((r) => keyOf(r.chat, r.msg)))
    for (const key of base.resumeKeys) if (!held.has(key)) removed.push({ list: 'resume', key, at: now })
    for (const id of base.favourites) if (!starred.has(id)) removed.push({ list: 'favourite', key: String(id), at: now })

    // Poster picks and renames are the corrections the web has: each joined to the other lines the file holds for that video.
    const others = new Map(remote.corrections.map((c) => [keyOf(c.chat, c.msg), c] as const))
    // What this device says of a video's correction: a line to set, null to take it off, absent to leave the file's.
    const says = new Map<string, { pick?: string | null; title?: string | null; at: number }>()
    const slot = (key: string) => {
        let s = says.get(key)
        if (!s) says.set(key, (s = { at: 0 }))
        return s
    }
    for (const p of lists.picks) {
        const pickLine = p.text.split('\n').map((l) => l.trim()).find((l) => PICK_LINE.test(l))
        if (!pickLine) continue
        const s = slot(keyOf(p.chatId, tdlibMessageId(p.messageId)))
        s.pick = pickLine
        s.at = Math.max(s.at, p.at)
    }
    for (const t of lists.titles ?? []) {
        const title = cleanTitle(t.title, TITLE_MAX)
        if (!title) continue
        const s = slot(keyOf(t.chatId, tdlibMessageId(t.messageId)))
        s.title = title
        s.at = Math.max(s.at, t.at)
    }
    const oldest = now - REMOVAL_DAYS * DAY_MS
    for (const t of lists.tombs) {
        if (t.at < oldest || !validKey(t.list, t.key)) continue
        if (t.list !== 'correction') {
            removed.push({ list: t.list, key: t.key, at: t.at })
            continue
        }
        // A pick taken off leaves the rename and "Not the same video" the apps' correction also holds.
        const s = slot(t.key)
        if (s.pick === undefined) ((s.pick = null), (s.at = Math.max(s.at, t.at)))
    }
    for (const t of lists.titleTombs ?? []) {
        if (t.at < oldest || !validKey('correction', t.key)) continue
        // A rename reset leaves the pick and the rest likewise.
        const s = slot(t.key)
        if (s.title === undefined) ((s.title = null), (s.at = Math.max(s.at, t.at)))
    }
    for (const [key, s] of says) {
        const [chat, msg] = key.split('_').map(Number)
        const text = withLines(others.get(key)?.text, s)
        // With nothing else on the correction, taking the pick or the rename off is a removal.
        if (text) doc.corrections.push({ chat, msg, text, at: s.at } satisfies CorrectionRec)
        else removed.push({ list: 'correction', key, at: s.at })
    }
    doc.removed = removed
    const named = lists.named
    // Nothing to say (no list made, none taken off): the file's own lists stay as they are.
    if (named && (named.lists.length || named.lines.length || named.removed.length)) doc.lists = toFile(named)
    return doc
}

/** What a merged file writes into this device's lists. Every method is quiet (no removal noted) and keeps the times the file gave. */
export interface Writer {
    putResume(r: { chatId: number; messageId: number; position: number; length: number; time: number }): void
    dropResume(chatId: number, messageId: number): void
    putWatched(chatId: number, messageId: number, time: number): Promise<void> | void
    dropWatched(chatId: number, messageId: number): void
    /** The line's video, read from Telegram; null when it is gone or not a video. */
    video(chatId: number, messageId: number): Promise<{ chatId: number; messageId: number } | null>
    putLater(item: { chatId: number; messageId: number }, time: number): void
    dropLater(chatId: number, messageId: number): void
    putStar(chatId: number, time: number): void
    dropStar(chatId: number): void
    putPick(chatId: number, messageId: number, pick: Pick | null, at: number): void
    /** The video's rename from the file (null: none, the file name reads again), cleaned already. */
    putTitle(chatId: number, messageId: number, title: string | null, at: number): void
    /** The merged file's named lists (raw): joined into this device's, the lines it lacks read from Telegram. */
    mergeLists?(raw: unknown): Promise<number> | number
}

export interface Applied {
    /** Changes made here, as the Settings row counts them. */
    changes: number
    /** The place keys written, for the base. */
    resumed: Set<string>
    /** The Watch later lines the budget left for the next sync. */
    left: number
}

/** How many Watch later videos one sync reads from Telegram, the newest first. */
export const LATER_BUDGET = 20

/** Writes `apply` into the stores through `writer`; a line whose id is not a server message's, or whose video is gone, is skipped. */
export async function applyLocal(apply: SyncApply, writer: Writer, budget = LATER_BUDGET): Promise<Applied> {
    let changes = 0
    const resumed = new Set<string>()
    for (const r of apply.resumeIn) {
        const messageId = webMessageId(r.msg)
        if (messageId === null || !(r.positionMs > 0)) continue
        writer.putResume({ chatId: r.chat, messageId, position: Math.floor(r.positionMs / 1000), length: Math.round(r.durationMs / 1000), time: r.updatedAt })
        resumed.add(keyOf(r.chat, r.msg))
        changes++
    }
    for (const key of apply.resumeOut.keys()) {
        const [chat, msg] = key.split('_').map(Number)
        const messageId = webMessageId(msg)
        if (messageId === null) continue
        writer.dropResume(chat, messageId)
        changes++
    }
    for (const w of apply.watchedIn) {
        const messageId = webMessageId(w.msg)
        if (messageId === null) continue
        await writer.putWatched(w.chat, messageId, w.watchedAt)
        changes++
    }
    for (const key of apply.watchedOut.keys()) {
        const [chat, msg] = key.split('_').map(Number)
        const messageId = webMessageId(msg)
        if (messageId === null) continue
        writer.dropWatched(chat, messageId)
        changes++
    }
    let left = 0
    for (const l of [...apply.laterIn].filter((x) => !x.series).sort((a, b) => b.addedAt - a.addedAt)) {
        const messageId = webMessageId(l.msg)
        if (messageId === null) continue
        if (budget-- <= 0) {
            left++
            continue
        }
        const item = await writer.video(l.chat, messageId).catch(() => null)
        if (!item) continue
        writer.putLater(item, l.addedAt)
        changes++
    }
    for (const id of apply.laterOut.keys()) {
        const m = /^(-?\d+)_(\d+)$/.exec(id)
        const messageId = m ? webMessageId(Number(m[2])) : null
        if (!m || messageId === null) continue
        writer.dropLater(Number(m[1]), messageId)
        changes++
    }
    for (const id of apply.favouritesIn) (writer.putStar(id, Date.now()), changes++)
    for (const id of apply.favouritesOut) (writer.dropStar(id), changes++)
    for (const c of apply.correctionsIn) {
        const messageId = webMessageId(c.msg)
        if (messageId === null) continue
        writer.putPick(c.chat, messageId, decodePick(c.text), c.at)
        writer.putTitle(c.chat, messageId, titleOf(c.text), c.at)
        changes++
    }
    for (const key of apply.correctionsOut.keys()) {
        const [chat, msg] = key.split('_').map(Number)
        const messageId = webMessageId(msg)
        if (messageId === null) continue
        writer.putPick(chat, messageId, null, 0)
        writer.putTitle(chat, messageId, null, 0)
        changes++
    }
    return { changes, resumed, left }
}

/**
 * The base while the file is not written yet: what changed here because of the file, and nothing
 * else, so a removal made here since the last push is still told when the push goes through.
 */
export function earlyBase(base: Base, apply: SyncApply, resumed: Set<string>): Base {
    const keys = new Set(base.resumeKeys)
    for (const k of apply.resumeOut.keys()) keys.delete(k)
    for (const k of resumed) keys.add(k)
    const favourites = new Set(base.favourites)
    for (const id of apply.favouritesOut) favourites.delete(id)
    for (const id of apply.favouritesIn) favourites.add(id)
    return { resumeKeys: [...keys], favourites: [...favourites], at: base.at }
}

/** The base this device holds after a sync: the places it held, less what went out, plus what came in; likewise the favourites. */
export function nextBase(local: SyncDoc, apply: SyncApply, resumed: Set<string>, now: number): Base {
    const keys = new Set(local.resume.map((r) => keyOf(r.chat, r.msg)))
    for (const k of apply.resumeOut.keys()) keys.delete(k)
    for (const k of resumed) keys.add(k)
    const favourites = new Set(local.favourites.keys())
    for (const id of apply.favouritesOut) favourites.delete(id)
    for (const id of apply.favouritesIn) favourites.add(id)
    return { resumeKeys: [...keys], favourites: [...favourites], at: now }
}
