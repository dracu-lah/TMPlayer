// Backing up and restoring in the browser (WP-56): the file is made and read by backup.ts, and
// merged into the stores the way a sync merges a file (format.ts merge and apply, local.ts
// applyLocal), so a restore adds and never takes away: lists are joined, the newest place wins, what
// this device removed stays removed. Browser glue over those: nothing here is a rule.
import { api } from '../tg/rpc'
import { me } from '../session'
import { applyCount, apply, emptyDoc, merge, type SyncApply } from './format'
import { applyLocal, collect, noBase } from './local'
import { accountTagOf, backupFileName, backupText, decodeBackup, otherAccount, type BackupRead } from './backup'
import { fromFile, missingLines } from './listsFile'
import { localLists, localWriter } from './index'
import { settings } from '../store'
import { namedListsDoc, recentSearches, restoreSearches, syncMergeLists } from '../store'
import { emptyLists } from '../ui/lists'

/** How many lines of a restored backup's lists are read from Telegram at once. */
const RESTORE_LINES = 200

/** The whole file as text, ready to save. */
export async function backupNow(now = Date.now()): Promise<{ name: string; text: string }> {
    const account = me()?.id ?? 0
    const lists = await localLists()
    const doc = collect(lists, noBase(), emptyDoc(account), account, now, 'web')
    const text = backupText({ doc, lists: lists.named ?? emptyLists(), recentSearches: recentSearches() }, { now, appVersion: 'web', accountTag: await accountTagOf(account) })
    return { name: backupFileName(now), text }
}

/** Hands the file to the browser as a download. */
export function saveFile(name: string, text: string) {
    const url = URL.createObjectURL(new Blob([text], { type: 'application/json' }))
    const a = document.createElement('a')
    a.href = url
    a.download = name
    a.rel = 'noopener'
    document.body.append(a)
    a.click()
    a.remove()
    setTimeout(() => URL.revokeObjectURL(url), 30_000)
}

export interface RestorePlan {
    read: Extract<BackupRead, { kind: 'ok' }>
    change: SyncApply
    /** Lines of the backup's lists this device lacks. */
    lines: number
    /** Recent searches of the backup this device lacks. */
    searches: number
    /** True when the file says it was made for another Telegram account. */
    otherAccount: boolean
    /** Nothing in the file would change anything here. */
    nothing: boolean
}

/** What restoring `text` would change, counted before anything is written; or why it cannot be read. */
export async function planRestore(text: string, now = Date.now()): Promise<RestorePlan | Exclude<BackupRead, { kind: 'ok' }>> {
    const read = decodeBackup(text)
    if (read.kind !== 'ok') return read
    const account = me()?.id ?? 0
    const lists = await localLists()
    const local = collect(lists, noBase(), emptyDoc(account), account, now, 'web')
    const merged = merge(local, read.doc, now)
    const change = apply(local, merged)
    const lines = missingLines(fromFile(read.lists), namedListsDoc()).length
    const have = new Set(recentSearches().map((s) => s.toLowerCase()))
    const searches = settings().rememberSearches ? read.searches.filter((s) => !have.has(s.toLowerCase())).slice(0, Math.max(0, 10 - recentSearches().length)).length : 0
    const listsNew = lines > 0 || fromFile(read.lists).lists.some((l) => !namedListsDoc().lists.some((m) => m.id === l.id))
    return { read, change, lines, searches, otherAccount: await otherAccount(read, account), nothing: applyCount(change) === 0 && !listsNew && searches === 0 }
}

/** Writes the plan into the stores. Returns how many places and lines changed. */
export async function restore(plan: RestorePlan): Promise<number> {
    const applied = await applyLocal(plan.change, localWriter)
    const lines = plan.read.lists === undefined ? 0 : await syncMergeLists(plan.read.lists, (c, m) => api.video(c, m), RESTORE_LINES, false)
    return applied.changes + lines + restoreSearches(plan.read.searches)
}
