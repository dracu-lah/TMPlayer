// The page side of the webOS loopback bridge (tv/webos/service/org.tmplayer.tv.service, private/TV-DIRECT-PLAN.md R4).
// The .ipk wrapper starts a JS service and opens this page with `#tvsvc=<port>.<token>`. The TV's own player
// asks http://127.0.0.1:<port>/stream/<key> for a range; the service holds that request and this page, long-polling
// it, answers with the bytes read through the file (the same read the Service Worker path uses). No import of the
// player or TgFile here, so a Node test can run it against bridge.js.

export interface StreamSource {
    read(start: number, end: number): Promise<Uint8Array>
}
/** The open file for a /stream/ key, or undefined when the player has closed it. */
export type Resolve = (key: string) => StreamSource | undefined

export interface Options {
    /** Defaults to `location.hash`. */
    hash?: string
    /** Removes the fragment from the address bar; defaults to history.replaceState. */
    strip?: () => void
    fetch?: typeof fetch
    /** Pause after a failed poll, doubled up to 5 s; tests shorten it. */
    retryMs?: number
}

/** Failed polls in a row before the service counts as gone and the page goes back to the Service Worker route. */
export const MAX_FAILS = 5

interface Svc {
    base: string
    token: string
    session: string
    stop: () => void
}

let svc: Svc | null = null
const asked = new Set<string>()

/** `port.token` from `#tvsvc=port.token`, or null when the fragment has none or looks wrong. */
export function parseFragment(hash: string): { port: number; token: string } | null {
    const m = /(?:^|[#&])tvsvc=(\d{1,5})\.([A-Za-z0-9_-]{8,128})(?:&|$)/.exec(hash)
    const port = m ? Number(m[1]) : 0
    return m && port > 0 && port < 65536 ? { port, token: m[2] } : null
}

/** Whether the bridge is up and answering. */
export function tvServiceUp(): boolean {
    return svc !== null
}

/** Whether the TV's player has asked the bridge for any of `key`'s bytes since the page loaded. */
export function bridgeAsked(key: string): boolean {
    return asked.has(key)
}

/** The address to give `<video>`, or null when no service is up. */
export function tvServiceUrl(key: string, size: number, mime: string): string | null {
    if (!svc) return null
    const q = `token=${svc.token}&size=${Math.floor(size)}&mime=${encodeURIComponent(mime.split(';')[0])}`
    return `${svc.base}/stream/${encodeURIComponent(key)}?${q}`
}

export function stopTvService() {
    svc?.stop()
    svc = null
}

/**
 * Reads the fragment (and removes it from the address, so the token is not left in history), pings the service
 * and, if it answers, starts the poll loop. Resolves true when the bridge is up; false leaves the page on its
 * other routes. Never throws.
 */
export async function startTvService(resolve: Resolve, opts: Options = {}): Promise<boolean> {
    try {
        const hash = opts.hash ?? (typeof location !== 'undefined' ? location.hash : '')
        const found = parseFragment(hash)
        if (!found) return false
        try {
            if (opts.strip) opts.strip()
            else if (typeof history !== 'undefined') history.replaceState(null, '', location.pathname + location.search)
        } catch {
            // An address that cannot be rewritten still works.
        }
        stopTvService()
        const doFetch = opts.fetch ?? fetch.bind(globalThis)
        const base = `http://127.0.0.1:${found.port}`
        const auth = `token=${found.token}`
        const ping = new AbortController()
        const pingTimer = setTimeout(() => ping.abort(), 3000)
        try {
            const r = await doFetch(`${base}/ping?${auth}`, { signal: ping.signal })
            if (!r.ok) return false
        } finally {
            clearTimeout(pingTimer)
        }
        const ctl = new AbortController()
        const me: Svc = {
            base,
            token: found.token,
            session: Math.random().toString(36).slice(2, 12) + Date.now().toString(36),
            stop: () => ctl.abort(),
        }
        svc = me
        void poll(me, ctl, resolve, doFetch, opts.retryMs ?? 1000)
        return true
    } catch {
        return false
    }
}

interface Req {
    id: number
    key: string
    start: number
    end: number
}

async function poll(me: Svc, ctl: AbortController, resolve: Resolve, doFetch: typeof fetch, retryMs: number) {
    const url = `${me.base}/poll?token=${me.token}&session=${me.session}`
    const cancelled = new Set<number>()
    let fails = 0
    let wait = retryMs
    while (!ctl.signal.aborted) {
        try {
            const r = await doFetch(url, { signal: ctl.signal })
            if (!r.ok) throw new Error(`poll ${r.status}`)
            const body = (await r.json()) as { requests?: unknown; cancel?: unknown }
            fails = 0
            wait = retryMs
            if (Array.isArray(body.cancel)) for (const id of body.cancel) if (typeof id === 'number') cancelled.add(id)
            if (Array.isArray(body.requests)) {
                for (const q of body.requests) {
                    const req = asReq(q)
                    if (req) void answer(me, req, cancelled, resolve, doFetch, ctl.signal)
                }
            }
            // A cancel for a request that already finished would sit here forever.
            if (cancelled.size > 256) cancelled.clear()
        } catch {
            if (ctl.signal.aborted) return
            if (++fails >= MAX_FAILS) {
                if (svc === me) svc = null
                return
            }
            await new Promise((res) => setTimeout(res, wait))
            wait = Math.min(wait * 2, 5000)
        }
    }
}

function asReq(q: unknown): Req | null {
    const o = q as Partial<Req> | null
    if (!o || typeof o.key !== 'string') return null
    const { id, start, end } = o
    if (typeof id !== 'number' || typeof start !== 'number' || typeof end !== 'number') return null
    if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end) || start < 0 || end < start) return null
    return { id, key: o.key, start, end }
}

async function answer(me: Svc, r: Req, cancelled: Set<number>, resolve: Resolve, doFetch: typeof fetch, signal: AbortSignal) {
    const reply = (extra: string, body?: Uint8Array) =>
        doFetch(`${me.base}/reply?token=${me.token}&id=${r.id}${extra}`, { method: 'POST', body: body as BodyInit | undefined, signal }).catch(() => {})
    asked.add(r.key)
    const file = resolve(r.key)
    if (!file) return void (await reply('&error=gone'))
    let bytes: Uint8Array
    try {
        bytes = await file.read(r.start, r.end + 1)
    } catch {
        if (!cancelled.has(r.id)) await reply('&error=fail')
        return
    }
    // The TV closed the request (a seek) while the read ran: nothing to send.
    if (cancelled.delete(r.id)) return
    if (bytes.length !== r.end - r.start + 1) return void (await reply('&error=fail'))
    await reply('', bytes)
}
