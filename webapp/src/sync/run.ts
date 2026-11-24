// One sync, as core's ResumeSync.run does it: read every copy of the file, merge it with this
// device's lists, take in what is newer, put the merge back in the same message. Pure over the
// things it needs (the file in Saved Messages, the stores, the clock), so the rules are tested
// with two fake devices; index.ts hands it the real ones.
//
// Opt in and nothing before: with the switch off a sync does nothing at all, it neither reads nor
// writes Saved Messages. A file this build cannot read, or that a newer TMPlayer wrote, is left
// alone and this device's lists are kept. More than one copy (two devices started at once) is read
// and merged in, but only the apps put it right: they merge what the web does not read (the named
// lists) before one message is left.
import { MAX_READ_BYTES, apply, decode, droppedTotal, emptyDoc, encode, mergeCounting, sameLists, type SyncDoc } from './format.ts'
import { applyLocal, collect, earlyBase, nextBase, noBase, type Base, type Lists, type Writer } from './local.ts'

/** What a sync keeps between runs. */
export interface State {
    on: boolean
    /** The account the file belongs to; 0 before the first sync. */
    account: number
    /** The message the last sync used. */
    file: number
    /** When a new file was last sent: another is not sent within RESEND_GUARD_MS (Telegram may not have shown it yet). */
    sentAt: number
    base: Base
}
export const freshState = (): State => ({ on: false, account: 0, file: 0, sentAt: 0, base: noBase() })

/** What a sync did, for the Settings row (ResumeSync.Outcome). */
export type Outcome =
    | { kind: 'off' }
    | { kind: 'noConnection' }
    | { kind: 'otherAccount' }
    | { kind: 'tooNew'; version: number }
    | { kind: 'unreadable' }
    | { kind: 'waitingForFile' }
    | { kind: 'twoCopies' }
    | { kind: 'done'; received: number; wrote: boolean; dropped: number }
    | { kind: 'failed'; reason: string }

export interface SyncFile {
    id: number
    date: number
    bytes: ArrayBuffer | null
    failed?: 'large' | 'download'
}

export interface Transport {
    files(known: number): Promise<SyncFile[]>
    write(target: number | null, bytes: Uint8Array, caption: string): Promise<{ id: number } | { gone: true }>
    remove(ids: number[]): Promise<void>
}

export interface Deps {
    now(): number
    /** This session's Telegram account. */
    account: number
    appVersion: string
    /** The words the file's caption carries after its tag. */
    caption: string
    state: { get(): Promise<State>; set(patch: Partial<State>): Promise<void> }
    transport: Transport
    lists(): Promise<Lists>
    writer: Writer
    /** True when the browser knows it has no connection. */
    offline(): boolean
    /** A failure in plain words. */
    humanise(e: unknown): string
}

/** A file sent within this long and not shown yet is waited for, never sent twice (ResumeSync.RESEND_GUARD_MS). */
export const RESEND_GUARD_MS = 15 * 60 * 1000
const FILE_TAG = '#TMPlayerSyncFile'

/** [replace] writes this device's lists over a file that could not be read, which only the viewer asks for. */
export async function runSync(d: Deps, o: { push?: boolean; replace?: boolean } = {}): Promise<Outcome> {
    const push = o.push ?? true
    const state = await d.state.get()
    if (!state.on) return { kind: 'off' }
    if (d.offline()) return { kind: 'noConnection' }
    try {
        // Never one account's lists into another's file: off until turned on again here.
        if (state.account !== 0 && d.account !== 0 && state.account !== d.account) {
            await d.state.set({ on: false })
            return { kind: 'otherAccount' }
        }
        const now = d.now()
        const found = await d.transport.files(state.file)
        let remote: SyncDoc = emptyDoc(d.account)
        let unreadable = false
        let newest = 0
        for (const file of found) {
            if (!file.bytes || file.failed || file.bytes.byteLength > MAX_READ_BYTES) {
                if (file.failed === 'download') return { kind: 'failed', reason: d.humanise(new Error('download')) }
                unreadable = true
                continue
            }
            const read = await decode(new Uint8Array(file.bytes), d.account)
            if (read.kind === 'ok') remote = mergeCounting(remote, read.doc, now).doc
            else if (read.kind === 'tooNew') newest = Math.max(newest, read.version)
            else unreadable = true
        }
        if (newest > 0) return { kind: 'tooNew', version: newest }
        if (unreadable && !o.replace) return { kind: 'unreadable' }

        const lists = await d.lists()
        const local = collect(lists, state.base, remote, d.account, now, d.appVersion)
        const { doc: merged, dropped } = mergeCounting(remote, local, now)
        const change = apply(local, merged)
        const applied = await applyLocal(change, d.writer)
        // The named lists the file holds (the apps' and other tabs'), joined into this device's.
        if (merged.lists !== undefined && d.writer.mergeLists) applied.changes += await d.writer.mergeLists(merged.lists)
        // What changed here because of the file, and nothing else, until the write below goes through:
        // a removal made here since the last push is still told then.
        await d.state.set({ base: earlyBase(state.base, change, applied.resumed) })
        const received = applied.changes
        if (!push) return { kind: 'done', received, wrote: false, dropped: 0 }

        const encoded = await encode({ ...merged, account: d.account, updatedAt: now, appVersion: d.appVersion })
        // One copy is the apps' to tidy up; a file that reads and says what it said is not written again.
        const changed = found.length === 0 || unreadable || !sameLists(remote, encoded.doc)
        const caption = `${FILE_TAG}\n${d.caption}`
        let wrote = false
        // The switch may have been turned off (or the file deleted) since this sync began: nothing is written then.
        if (changed && !(await d.state.get()).on) return { kind: 'off' }
        if (found.length > 1 && !o.replace) {
            // Taken in, but not written: the apps merge what the web does not read before one copy is left.
            await d.state.set({ base: nextBase(local, change, applied.resumed, now), account: d.account })
            return { kind: 'twoCopies' }
        }
        if (changed && found.length <= 1) {
            const target = found[0]?.id ?? null
            let id: number | null = null
            if (target !== null) {
                const edited = await d.transport.write(target, encoded.bytes, caption)
                if ('id' in edited) id = edited.id
            }
            // No file yet, or deleted since it was read: sent once, and not again until Telegram has shown it.
            if (id === null) {
                const fresh = await d.state.get()
                if (!fresh.on) return { kind: 'off' }
                if (now - fresh.sentAt >= 0 && now - state.sentAt < RESEND_GUARD_MS) return { kind: 'waitingForFile' }
                await d.state.set({ sentAt: now, file: 0 })
                const sent = await d.transport.write(null, encoded.bytes, caption)
                if ('id' in sent) id = sent.id
            }
            if (id !== null) {
                await d.state.set({ file: id })
                wrote = true
            }
        } else if (changed && o.replace) {
            // The viewer asked to replace what could not be read: this device's lists go over the first copy, the rest go.
            await d.transport.write(found[0].id, encoded.bytes, `${FILE_TAG}\n${d.caption}`)
            await d.transport.remove(found.slice(1).map((f) => f.id))
            await d.state.set({ file: found[0].id })
            wrote = true
        }
        // What this device held as the file was read, as changed by the file.
        await d.state.set({ base: nextBase(local, change, applied.resumed, now), account: d.account })
        return { kind: 'done', received, wrote, dropped: droppedTotal(dropped) + droppedTotal(encoded.dropped) }
    } catch (e) {
        return d.offline() ? { kind: 'noConnection' } : { kind: 'failed', reason: d.humanise(e) }
    }
}

/** A state as kept: the switch is on only when it says `true`, anything missing or of another type reads as the default. */
export function parseState(saved: unknown): State | null {
    if (!saved || typeof saved !== 'object') return null
    const s = saved as Partial<State>
    const fresh = freshState()
    const nums = (v: unknown) => (Array.isArray(v) ? v.filter((x): x is number => typeof x === 'number') : [])
    const keys = (v: unknown) => (Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : [])
    return {
        on: s.on === true,
        account: typeof s.account === 'number' ? s.account : fresh.account,
        file: typeof s.file === 'number' ? s.file : fresh.file,
        sentAt: typeof s.sentAt === 'number' ? s.sentAt : fresh.sentAt,
        base: { resumeKeys: keys(s.base?.resumeKeys), favourites: nums(s.base?.favourites), at: typeof s.base?.at === 'number' ? s.base.at : 0 },
    }
}
