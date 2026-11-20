// The page's side of the Telegram worker: calls by name, answers by message. Only types come from
// the worker file, so mtcute never lands in the page's bundle.
import { createSignal } from 'solid-js'
import type { Api, Ctx } from './worker'
import type { RpcError } from './types'
import { failure } from '../errors'
import { showCrashCard } from '../crash'
import { createFloodClock, floodSeconds } from './flood'
import { noteNames } from '../logs'
import { names, picker } from '../accounts'

type Out =
    | { id: number; value: unknown }
    | { id: number; error: RpcError }
    | { id: number; event: unknown; ask?: number }
    | { link: string }

interface Pending {
    method: string
    resolve: (v: unknown) => void
    reject: (e: unknown) => void
    onEvent?: (data: unknown) => unknown
}

const pending = new Map<number, Pending>()
let nextId = 0
let worker: Worker | null = null
let started!: (w: Worker) => void
/** The worker once this tab holds the session; calls made before wait for it. */
const ready = new Promise<Worker>((r) => (started = r))

function start() {
    // Its name tells the worker whose storage to open (HX88): `telegram` is the adopted profile's.
    const w = new Worker(new URL('./worker.ts', import.meta.url), { type: 'module', name: names.worker })
    w.onmessage = async (ev: MessageEvent<Out>) => {
        const msg = ev.data
        // The line to Telegram as mtcute reports it: offline, connecting, updating or connected.
        if ('link' in msg) return void (worker === w && typeof msg.link === 'string' && setLink(msg.link.slice(0, 16)))
        const call = pending.get(msg.id)
        if (!call) return
        if ('event' in msg) {
            // Partial lists come as events (the chats a page at a time): their titles are learned the same way as an answer's.
            noteNames(msg.event)
            const answer = await call.onEvent?.(msg.event)
            if (msg.ask != null) w.postMessage({ reply: msg.id, ask: msg.ask, value: answer })
            return
        }
        pending.delete(msg.id)
        if ('error' in msg) {
            if (msg.error.code === 401) onUnauthorized?.()
            // A read Telegram asked to hold back: the player says for how long (the read waits it out itself, stream/reader.ts).
            if (call.method === 'chunk') noteFlood(floodSeconds(msg.error))
            call.reject(Object.assign(new Error(msg.error.message), msg.error))
        } else {
            // Every chat, video and topic title the page shows comes through here: the log's scrubber learns them (WB18).
            noteNames(msg.value)
            call.resolve(msg.value)
        }
    }
    // The worker failed to load or died of an error it did not catch (HX10): the calls in flight
    // fail with "no answer" (their screens offer Try again) and a new worker takes over, from the
    // session it saved. Three times in five minutes is past helping: the page's error card says so.
    w.onerror = (ev) => {
        ev.preventDefault()
        console.error('[rpc] the Telegram worker failed', ev.message)
        if (worker !== w || stopped) return
        w.terminate()
        worker = null
        setLink(null)
        for (const [, p] of pending) p.reject(Object.assign(new Error('Telegram worker restarted'), { code: -1 }))
        pending.clear()
        const now = Date.now()
        restarts = restarts.filter((at) => now - at < RESTART_WINDOW_MS)
        if (restarts.length >= MAX_RESTARTS) return void showCrashCard()
        restarts.push(now)
        start()
        // What the old worker knew (the rows a page said were on screen) is gone: screens say it again.
        for (const fn of [...restartListeners]) fn()
    }
    worker = w
    started(w)
}

const restartListeners = new Set<() => void>()
/** Called after a crashed Telegram worker is replaced; returns the way to stop listening. */
export function onWorkerRestart(fn: () => void): () => void {
    restartListeners.add(fn)
    return () => void restartListeners.delete(fn)
}

/**
 * The worker's line to Telegram ('offline', 'connecting', 'updating', 'connected'), null while the
 * worker has not said: the banner (net.ts) and the player's "why" (player/openStall.ts) read it.
 */
const [link, setLink] = createSignal<string | null>(null)
export { link }

const flood = createFloodClock()
const noteFlood = flood.note
/** Seconds left of the flood wait Telegram asked a file read for; 0 when there is none. */
export const floodLeft = flood.left
/** A new video opens: a wait that belonged to the last one is not told for it. */
export const clearFlood = flood.clear

let restarts: number[] = []
const MAX_RESTARTS = 3
const RESTART_WINDOW_MS = 5 * 60_000

/**
 * Whether this tab uses the Telegram session: 'waiting' while it asks, 'elsewhere' while another
 * tab of TMPlayer holds it. Two tabs on one session would write over each other's state, so only
 * one runs the worker (the Web Locks API; a browser without it just runs).
 */
const [sessionHere, setSessionHere] = createSignal<'waiting' | 'here' | 'elsewhere'>('waiting')
export { sessionHere }

/** One lock per profile (HX88): two tabs never run one profile's client, two profiles may run in two tabs. */
const LOCK = names.lock
const STEAL = 'tmplayer-use-here'

let claimed = false
/** Whether this tab started its profile's client (false on the picker at launch, before a choice). */
export const sessionOpen = () => claimed && !stopped
/** Set once the page let its client go (a sign out of this profile): nothing starts it again. */
let stopped = false

/**
 * Starts this tab's Telegram client for its profile. At once when there is nothing to choose; after
 * the picker when the tab starts on it, so a profile nobody chose never opens a client.
 */
export function openSession() {
    if (claimed || stopped) return
    claimed = true
    const locks = navigator.locks
    if (!locks) {
        start()
        return setSessionHere('here')
    }
    let steal = false
    try {
        steal = sessionStorage.getItem(STEAL) === '1'
        sessionStorage.removeItem(STEAL)
    } catch {}
    const hold = () => {
        if (!worker) start()
        setSessionHere('here')
        // Held for as long as the tab is open, unless another tab takes it.
        return new Promise<void>(() => {})
    }
    locks
        .request(LOCK, steal ? { steal: true } : { ifAvailable: true }, (lock) => {
            if (lock) return hold()
            setSessionHere('elsewhere')
            // Taken as soon as the other tab closes; the calls made meanwhile then run.
            locks.request(LOCK, hold)
            return undefined
        })
        .catch(() => {
            // "Use it here" in another tab took the session: this one stops at once.
            worker?.terminate()
            worker = null
            setLink(null)
            for (const [, p] of pending) p.reject(new DOMException('session moved to another tab', 'AbortError'))
            pending.clear()
            document.querySelectorAll('video').forEach((v) => v.pause())
            setSessionHere('elsewhere')
            locks.request(LOCK, () => (location.reload(), new Promise<void>(() => {})))
        })
}
if (!picker()) openSession()

/**
 * Stops this tab's client for good (the page is about to leave its profile): the worker goes, so
 * its storage can be deleted, and calls still waiting fail.
 */
export function stopSession() {
    stopped = true
    worker?.terminate()
    worker = null
    setLink(null)
    for (const [, p] of pending) p.reject(new DOMException('session closed', 'AbortError'))
    pending.clear()
}

/** "Use it here": this tab takes the session from the other one, which stops. */
export function useHere() {
    try {
        sessionStorage.setItem(STEAL, '1')
    } catch {}
    location.reload()
}

let onUnauthorized: (() => void) | null = null
/** Called when Telegram says the session is gone (signed out on another device, for one). */
export function whenUnauthorized(fn: () => void) {
    onUnauthorized = fn
}

type Args<F> = F extends (ctx: Ctx, ...a: infer A) => unknown ? A : never
type Result<F> = F extends (...a: never[]) => Promise<infer R> ? R : never

export interface CallOptions {
    signal?: AbortSignal
    /** Events the call sends while it runs; what this returns answers the ones that ask. */
    onEvent?: (data: unknown) => unknown
}

export function call<M extends keyof Api>(method: M, args: Args<Api[M]>, opts: CallOptions = {}): Promise<Result<Api[M]>> {
    const { signal, onEvent } = opts
    if (signal?.aborted) return Promise.reject(new DOMException('aborted', 'AbortError'))
    const id = ++nextId
    return new Promise((resolve, reject) => {
        pending.set(id, { method, resolve: resolve as (v: unknown) => void, reject, onEvent })
        signal?.addEventListener(
            'abort',
            () => {
                if (!pending.delete(id)) return
                worker?.postMessage({ abort: id })
                reject(new DOMException('aborted', 'AbortError'))
            },
            { once: true },
        )
        // The worker running now, which after a restart is not the one `ready` first gave.
        ready.then((first) => pending.has(id) && (worker ?? first).postMessage({ id, method, args }))
    })
}

/** The worker's calls as plain functions: `api.chats(60)`. */
export const api = new Proxy({} as { [M in keyof Api]: (...args: Args<Api[M]>) => Promise<Result<Api[M]>> }, {
    get: (_, method: string) => (...args: unknown[]) => call(method as keyof Api, args as never),
})

// Local testing only: the calls in the console.
if (location.hostname === 'localhost') (window as unknown as { api: typeof api }).api = api

/** An error in the app's words: Telegram's flood waits and a lost connection among them. */
export function errorText(e: unknown): string {
    return failure(e)?.text ?? ''
}
