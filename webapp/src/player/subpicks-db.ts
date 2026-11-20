// Where the remembered online subtitles live (subpicks.ts) and the rules every writer and the wipe share:
// the database, one change at a time (this tab's queue, then the Web Lock across tabs), and a
// generation that a wipe bumps, so a write queued or in flight before a sign out never lands after it.
// Plain and small (idb-keyval is in the main chunk already), so the sign out, the session end and the
// MSone "stopped" wipe can all reach it without loading the player.
import { createStore, delMany, get, keys as storeKeys, set, type UseStore } from 'idb-keyval'
import type { KeepStore } from './msone.ts'

export const PICKS_DB = 'tmplayer-subpicks'
/** The database of the profile this page runs (HX88): the adopted profile's is PICKS_DB, set once at start (main.tsx). */
let picksDb = PICKS_DB
export function usePicksDatabase(name: string) {
    picksDb = name
}
export const STATE = 'state'
export const COPY = 'copy|'
/** A store that does not answer (some private windows) is given up on rather than waited for. */
const STORE_MS = 2000
/** A wipe is given longer: it matters more than a pick does. */
const WIPE_MS = 10_000
const LOCK = 'tmplayer-subpicks'

function idbStore(): KeepStore {
    let db: UseStore | null = null
    const store = () => (db ??= createStore(picksDb, 'kv'))
    return {
        get: (key) => get(key, store()),
        set: (key, value) => set(key, value, store()),
        del: (keys) => delMany(keys, store()),
        keys: async () => (await storeKeys(store())).filter((k): k is string => typeof k === 'string'),
    }
}

let current: KeepStore = idbStore()
let generation = 0
let queue: Promise<unknown> = Promise.resolve()
const wiped: Array<() => void> = []

/** The store in use. */
export const pickStore = (): KeepStore => current

/** For the tests: the store to keep in (a Map, or one that fails). */
export function setPickStore(next: KeepStore) {
    current = next
    generation++
    queue = Promise.resolve()
}

/** Runs `fn` whenever the picks are wiped: what the player holds in memory (a restored file) goes too. */
export function onPicksWiped(fn: () => void) {
    wiped.push(fn)
}

type Locks = { request: <R>(name: string, fn: () => Promise<R>) => Promise<R> }

function exclusiveFor<T>(ms: number, job: (live: () => boolean) => Promise<T>, fallback: T): Promise<T> {
    const gen = generation
    let timedOut = false
    // False once a wipe has come after this job was asked for, or once its time is up: the job stops before its next write.
    const live = () => gen === generation && !timedOut
    const run = async (): Promise<T> => {
        if (!live()) return fallback
        let timer: ReturnType<typeof setTimeout> | undefined
        try {
            return await Promise.race([
                job(live),
                new Promise<T>((resolve) => (timer = setTimeout(() => ((timedOut = true), resolve(fallback)), ms))),
            ])
        } catch {
            return fallback
        } finally {
            clearTimeout(timer)
        }
    }
    const locks = (globalThis as { navigator?: { locks?: Locks } }).navigator?.locks
    const next = queue.then(() => (locks ? locks.request(LOCK, run).catch(() => fallback) : run()))
    queue = next.catch(() => {})
    return next
}

/**
 * One change at a time: after the ones before it in this tab and, where the browser has Web Locks,
 * before another tab's, so a read, change and write is never torn. `job` checks `live()` before every
 * write; a store that fails or hangs gives `fallback`.
 */
export const exclusive = <T>(job: (live: () => boolean) => Promise<T>, fallback: T) => exclusiveFor(STORE_MS, job, fallback)

/**
 * Every pick and every copy kept with them (sign out, a session that ended, MSone stopping for good).
 * Bumps the generation first, so what is queued or running stops before its next write, then empties
 * the store behind them under the lock. Never throws.
 */
export function wipeAllPicks(): Promise<void> {
    generation++
    for (const fn of wiped) {
        try {
            fn()
        } catch {}
    }
    return exclusiveFor(
        WIPE_MS,
        async () => {
            const all = await current.keys()
            const mine = all.filter((k) => k === STATE || k.startsWith(COPY))
            if (mine.length) await current.del(mine)
        },
        undefined,
    )
}
