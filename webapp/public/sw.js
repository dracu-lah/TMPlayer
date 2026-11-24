// Two jobs. Streams Telegram files to the <video> element: /stream/<key>?size=&mime= answers range
// requests with a real 206, at most 2 MiB at a time, from the page that holds the Telegram
// connection. And keeps the app's own files, so it opens without a connection and at once: the
// build writes VERSION and SHELL (vite.config.ts, offlineShell), so every release is a new worker.
// A new worker waits until the page says the user chose Reload (src/install.ts), so nothing changes
// under a video that is playing.
const VERSION = 'dev'
const SHELL = []
const MAX_ANSWER = 2 * 1024 * 1024
const READ_TIMEOUT = 30_000
const CACHE = `tmplayer-shell-${VERSION}`

// The web app this one replaced at the same address (web/, Kotlin and tdweb) left its own worker,
// caches and storage. Its worker answers /stream/ another way, so this one takes over at once.
const OLD_APP_CACHES = ['tm-shell-v1', 'tm-runtime-v1']

self.addEventListener('install', (event) => {
    if (VERSION === 'dev') {
        self.skipWaiting()
        return
    }
    event.waitUntil(
        (async () => {
            const cache = await caches.open(CACHE)
            // Save-Data or a 2g line (ui/saveData.ts): the first page only; the rest is kept as it is first fetched (fromCache).
            const conn = self.navigator && self.navigator.connection
            const lean = !!conn && (conn.saveData === true || conn.effectiveType === 'slow-2g' || conn.effectiveType === '2g')
            await cache.addAll((lean ? SHELL.filter((path) => path === '/') : SHELL).map((path) => new Request(path, { cache: 'reload' })))
            if (await oldAppWasHere()) await self.skipWaiting()
        })(),
    )
})

self.addEventListener('activate', (event) => {
    event.waitUntil(
        (async () => {
            // Older versions' files go; the thumbnails' cache (src/store.ts) stays.
            for (const name of await caches.keys()) if (name.startsWith('tmplayer-shell-') && name !== CACHE) await caches.delete(name)
            if (await oldAppWasHere()) await clearOldApp().catch((e) => console.warn('[sw] old app', e))
            await self.clients.claim()
        })(),
    )
})

// Saves to the device that are running (WP-86): id to { cancelled }. A cancel from the page ends the stream with an error, which the browser answers by dropping the download.
const saves = new Map()

self.addEventListener('message', (event) => {
    if (event.data?.type === 'SKIP_WAITING') self.skipWaiting()
    if (event.data?.type === 'save-cancel' && typeof event.data.id === 'string') {
        const run = saves.get(event.data.id)
        if (run) run.cancelled = true
    }
})

self.addEventListener('fetch', (event) => {
    const request = event.request
    const url = new URL(request.url)
    if (url.origin !== self.location.origin || request.method !== 'GET') return
    // A request for a download (no range, a name to save as) is answered whole, a piece at a time (saveStream); the player's reads are ranges.
    if (url.pathname.startsWith('/stream/') && url.searchParams.has('save') && !request.headers.get('range')) return event.respondWith(saveStream(event, url, request))
    if (url.pathname.startsWith('/stream/')) return event.respondWith(stream(event, url))
    if (VERSION === 'dev') return
    // libav.js and its sources (/libav/sources/, linked from /licenses.txt) come from the network as they are.
    if (url.pathname.startsWith('/libav/')) return
    // Every address is the same page, the router picks the screen; /licenses.txt is its own.
    if (request.mode === 'navigate' && !SHELL.includes(url.pathname)) return event.respondWith(fromCache('/', request))
    if (SHELL.includes(url.pathname) || url.pathname.startsWith('/assets/')) event.respondWith(fromCache(url.pathname, request))
})

/**
 * The kept copy, else the network. Built files have the hash of their content in their name, so a
 * copy never goes stale; files left out of SHELL (the sound decoders, loaded only for some videos)
 * are kept the first time they are fetched.
 */
async function fromCache(path, request) {
    const cache = await caches.open(CACHE)
    const hit = await cache.match(path)
    if (hit) return hit
    const response = await fetch(request)
    if (response.ok && path.startsWith('/assets/')) cache.put(path, response.clone()).catch(() => {})
    return response
}

async function oldAppWasHere() {
    for (const name of OLD_APP_CACHES) if (await caches.has(name)) return true
    return false
}

/**
 * What the old web app kept: its caches, TDLib's database (an old sign in, of no use to this app),
 * tdweb's compiled module cache, its finished small files and its video parts. The "tmplayer"
 * database is mtcute's now too, so only the old app's store in it is emptied. Its tm.* settings in
 * localStorage, a few KB a worker cannot reach, stay.
 */
async function clearOldApp() {
    const known = indexedDB.databases ? new Set((await indexedDB.databases()).map((d) => d.name)) : null
    const gone = (name) =>
        new Promise((resolve) => {
            const request = indexedDB.deleteDatabase(name)
            request.onsuccess = request.onerror = request.onblocked = () => resolve()
        })
    for (const name of ['/tmplayer/dbfs', 'wasm-cache']) if (!known || known.has(name)) await gone(name)
    if (!known || known.has('tmplayer')) await emptyOldFiles()
    try {
        await (await navigator.storage.getDirectory()).removeEntry('tdweb-files', { recursive: true })
    } catch {
        // Never written, or no OPFS here.
    }
    for (const name of OLD_APP_CACHES) await caches.delete(name)
}

/** Empties tdweb's "keyvaluepairs" store, when there is one, and closes at once so mtcute can upgrade the database. */
function emptyOldFiles() {
    return new Promise((resolve) => {
        const request = indexedDB.open('tmplayer')
        request.onerror = () => resolve()
        request.onsuccess = () => {
            const db = request.result
            if (!db.objectStoreNames.contains('keyvaluepairs')) {
                db.close()
                return resolve()
            }
            const tx = db.transaction('keyvaluepairs', 'readwrite')
            tx.objectStore('keyvaluepairs').clear()
            tx.oncomplete = tx.onerror = tx.onabort = () => {
                db.close()
                resolve()
            }
        }
    })
}

async function stream(event, url) {
    const key = decodeURIComponent(url.pathname.slice('/stream/'.length))
    const size = Number(url.searchParams.get('size'))
    const mime = url.searchParams.get('mime') || 'video/mp4'
    const range = /bytes=(\d*)-(\d*)/.exec(event.request.headers.get('range') || '')
    let start = range && range[1] ? Number(range[1]) : 0
    let end = range && range[2] ? Number(range[2]) : size - 1
    if (range && !range[1] && range[2]) {
        // A suffix range: the last N bytes.
        start = Math.max(0, size - Number(range[2]))
        end = size - 1
    }
    if (!(size > 0) || start >= size) {
        return new Response(null, { status: 416, headers: { 'Content-Range': `bytes */${size}` } })
    }
    end = Math.min(end, size - 1, start + MAX_ANSWER - 1)

    // The profile the page that asked runs (HX88): only a page of that profile answers, never another's.
    const profile = url.searchParams.get('p')
    const answer = await readFrom(event, { type: 'read', key, start, end, p: profile })
    if (answer === 'none') return new Response(null, { status: 503 })
    if (answer === 'timeout') return new Response(null, { status: 504 })
    if (answer === 'other') return new Response(null, { status: 404 })
    if (answer.error) return new Response(null, { status: answer.error === 'gone' ? 410 : 502 })
    const bytes = answer.bytes
    return new Response(bytes, {
        status: 206,
        headers: {
            'Content-Type': mime,
            'Content-Length': String(bytes.byteLength),
            'Content-Range': `bytes ${start}-${start + bytes.byteLength - 1}/${size}`,
            'Accept-Ranges': 'bytes',
            'Cache-Control': 'no-store',
        },
    })
}

/**
 * Save to device (WP-86): the file as an attachment, streamed. The body pulls the next piece from the page
 * only when the browser has taken the last, so nothing is held but one piece; a failed read or a cancel
 * ends the stream with an error, and the browser drops what it had (no partial file is kept by the app).
 * The page is told how far it has got, and how it ended, to show the bar and the summary.
 */
async function saveStream(event, url, request) {
    const key = decodeURIComponent(url.pathname.slice('/stream/'.length))
    // Only a save the page started (saveRuntime.ts registers it under `save:<id>` after checking that the
    // chat allows saving), and only as its hidden frame asks: never the player's own key of a restricted
    // video, and never a top-level navigation from another site (SEC2-05).
    if (!key.startsWith('save:') || request.mode !== 'navigate' || request.destination !== 'iframe') return new Response(null, { status: 403 })
    const size = Number(url.searchParams.get('size'))
    const name = url.searchParams.get('save') || 'video'
    const id = url.searchParams.get('id') || key
    const profile = url.searchParams.get('p')
    if (!(size > 0)) return new Response(null, { status: 400 })
    const run = { cancelled: false }
    saves.set(id, run)
    let at = 0
    const tell = async (state, extra) => {
        for (const client of await self.clients.matchAll({ type: 'window', includeUncontrolled: true })) client.postMessage({ type: 'save', id, state, ...extra })
    }
    const end = (state, extra) => {
        saves.delete(id)
        return tell(state, extra)
    }
    const body = new ReadableStream({
        async pull(controller) {
            if (run.cancelled) {
                controller.error(new Error('cancelled'))
                return end('cancelled')
            }
            if (at >= size) {
                controller.close()
                return end('done', { loaded: size })
            }
            const last = Math.min(size - 1, at + MAX_ANSWER - 1)
            const answer = await readFrom(event, { type: 'read', key, start: at, end: last, p: profile })
            if (run.cancelled) {
                controller.error(new Error('cancelled'))
                return end('cancelled')
            }
            if (!answer || typeof answer === 'string' || answer.error || !answer.bytes || !answer.bytes.byteLength) {
                controller.error(new Error('read'))
                return end('failed', { why: typeof answer === 'string' ? answer : (answer && answer.error) || 'read' })
            }
            at += answer.bytes.byteLength
            controller.enqueue(new Uint8Array(answer.bytes))
            tell('progress', { loaded: at })
        },
        cancel() {
            // The browser's own download list stopped it.
            end('cancelled')
        },
    })
    return new Response(body, {
        status: 200,
        headers: {
            'Content-Type': 'application/octet-stream',
            'Content-Length': String(size),
            'Content-Disposition': `attachment; filename*=UTF-8''${encodeURIComponent(name)}`,
            'Cache-Control': 'no-store',
            'X-Content-Type-Options': 'nosniff',
        },
    })
}

/**
 * Asks the page that made the request; a request with no page of its own (some engines) goes to the
 * windows, focused first, and only an answer from a page of the same profile counts: a page of
 * another profile says 'other' and the next is asked. None of that profile: 'other' (a 404), never
 * another profile's bytes. Before profiles a page answers without checking, as before.
 */
async function readFrom(event, message) {
    const candidates = []
    if (event.clientId) {
        const client = await self.clients.get(event.clientId)
        if (client) candidates.push(client)
    }
    if (!candidates.length) {
        const all = await self.clients.matchAll({ type: 'window' })
        candidates.push(...all.filter((c) => c.focused), ...all.filter((c) => !c.focused))
    }
    if (!candidates.length) return 'none'
    let timedOut = false
    for (const client of candidates) {
        let answer
        try {
            answer = await ask(client, message)
        } catch {
            timedOut = true
            continue
        }
        if (answer && answer.error === 'other') continue
        return answer
    }
    return timedOut ? 'timeout' : 'other'
}

function ask(client, message) {
    return new Promise((resolve, reject) => {
        const channel = new MessageChannel()
        const timer = setTimeout(() => (channel.port1.close(), reject(new Error('timeout'))), READ_TIMEOUT)
        channel.port1.onmessage = (ev) => {
            clearTimeout(timer)
            // One answer per ask: the port is let go, so a page asked in turn leaves nothing open.
            channel.port1.close()
            resolve(ev.data)
        }
        client.postMessage(message, [channel.port2])
    })
}
