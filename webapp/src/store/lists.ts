// The viewer's named lists (HX43): one document under `lists`, the apps' rules (ui/lists.ts), and the
// merge of another device's lists into it.
import { createSignal } from 'solid-js'
import { get } from 'idb-keyval'
import type { VideoItem } from '../tg/types'
import * as Lists from '../ui/lists'
import { fromFile, mergeInput, missingLines } from '../sync/listsFile'
import { db, raw, read, write } from './kv'

const LISTS = 'lists'
const [listsDoc, setListsDoc] = createSignal<Lists.ListsDoc>(Lists.emptyLists())
export { listsDoc, setListsDoc }
/** The lists as the page draws them: in the order made, each line in the order added. */
export const namedLists = () => Lists.visible(listsDoc())

export async function loadLists() {
    setListsDoc(Lists.readLists(await read<unknown>(LISTS), Date.now()))
}

/**
 * One edit, made over what is stored now and merged with it, so another tab's edit made meanwhile
 * is kept (the newest write wins per list and per line, as the apps merge them).
 */
function editLists(change: (doc: Lists.ListsDoc, now: number) => Lists.ListsDoc) {
    const now = Date.now()
    const next = change(listsDoc(), now)
    if (next === listsDoc()) return
    setListsDoc(next)
    write(LISTS, next)
    if (!db) return
    void raw((st) => get<unknown>(LISTS, st)).then((there) => {
        const merged = Lists.merge(Lists.readLists(there, now), listsDoc(), now)
        if (JSON.stringify(merged) === JSON.stringify(listsDoc())) return
        setListsDoc(merged)
        write(LISTS, merged)
    }, () => {})
}

/** A new list; null for an empty name or once the most lists are made. */
export function createList(name: string): Lists.ListInfo | null {
    let made: Lists.ListInfo | null = null
    editLists((doc, now) => {
        const r = Lists.create(doc, name, now)
        if (!r) return doc
        made = r[1]
        return r[0]
    })
    return made
}

export const renameList = (id: string, name: string) => editLists((doc, now) => Lists.rename(doc, id, name, now))
export const setListHome = (id: string, home: boolean) => editLists((doc, now) => Lists.setHome(doc, id, home, now))
export const deleteList = (id: string) => editLists((doc, now) => Lists.deleteList(doc, id, now))

/** Puts a video (or, with `show`, the whole show it is an episode of) in list `id`. */
export function addToList(id: string, item: VideoItem, show?: string): Lists.AddResult {
    let result: Lists.AddResult = 'no-list'
    const line = show ? { item: { ...item, title: show, name: show }, show: show.trim().toLowerCase() } : { item }
    editLists((doc, now) => {
        const [next, r] = Lists.add(doc, id, line, now)
        result = r
        return next
    })
    return result
}

export const removeFromList = (id: string, keys: readonly string[]) => editLists((doc, now) => Lists.removeLines(doc, id, new Set(keys), now))

/** Videos gone from their chats (HX21) out of every list: the ones of `keys` that were in one. */
export function forgetListVideos(keys: readonly string[]): string[] {
    const wanted = new Set(keys)
    const listed = new Set(listsDoc().lines.filter((l) => !l.show && wanted.has(Lists.lineKey(l))).map(Lists.lineKey))
    if (listed.size) editLists((doc, now) => Lists.forgetVideos(doc, listed, now)[0])
    return [...listed]
}

/** The lists as stored, for the sync and the backup. */
export const namedListsDoc = () => listsDoc()

/** How many lines of other devices' lists one sync reads from Telegram, the newest first (the rest wait for the next). */
export const LISTS_BUDGET = 20

/**
 * The lists a file holds (the apps' or another tab's) joined into this device's: the newest write
 * wins per list and per line, a removal beats an older write. A line this device lacks is read
 * from Telegram first (its card draws from the video), within a budget; one that is gone from its
 * chat is left out. Returns how many lines came in. A restore passes `removals` false: the file's
 * removals still keep the lines they name out, but never take a list or line away here.
 */
/** Lines whose video could not be read this page life (gone from its chat): not asked again, so they never starve the rest of the budget. */
const deadLines = new Set<string>()

export async function syncMergeLists(raw: unknown, video: (chatId: number, messageId: number) => Promise<VideoItem | null>, budget = LISTS_BUDGET, removals = true): Promise<number> {
    const file = fromFile(raw)
    if (!file.lists.length && !file.entries.length && (!removals || !file.removed.length)) return 0
    const lines: Lists.ListLine[] = []
    for (const e of missingLines(file, listsDoc())) {
        const dead = `${e.chat}:${e.msg}`
        if (deadLines.has(dead)) continue
        if (budget-- <= 0) break
        const item = await video(e.chat, e.msg).catch(() => null)
        if (!item) {
            deadLines.add(dead)
            continue
        }
        lines.push({ list: e.list, item: e.show ? { ...item, title: e.title || item.title, name: e.title || item.name } : item, at: e.at, ...(e.show ? { show: e.show } : {}) })
    }
    const before = listsDoc()
    editLists((doc, now) => Lists.merge(doc, mergeInput(file, lines, removals), now))
    return Math.max(0, listsDoc().lines.length - before.lines.length)
}
