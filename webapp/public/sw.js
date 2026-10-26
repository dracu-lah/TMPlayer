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
            await cache.addAll(SHELL.map((path) => new Request(path, { cache: 'reload' })))
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

self.addEventListener('message', (event) => {
    if (event.data?.type === 'SKIP_WAITING') self.skipWaiting()
})

self.addEventListener('fetch', (event) => {
    const request = event.request
    const url = new URL(request.url)
    if (url.origin !== self.location.origin || request.method !== 'GET') return
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

    const client = await pickClient(event)
    if (!client) return new Response(null, { status: 503 })
    let answer
    try {
        answer = await ask(client, { type: 'read', key, start, end })
    } catch {
        return new Response(null, { status: 504 })
    }
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

async function pickClient(event) {
    if (event.clientId) {
        const client = await self.clients.get(event.clientId)
        if (client) return client
    }
    const all = await self.clients.matchAll({ type: 'window' })
    return all.find((c) => c.focused) || all[0] || null
}

function ask(client, message) {
    return new Promise((resolve, reject) => {
        const channel = new MessageChannel()
        const timer = setTimeout(() => reject(new Error('timeout')), READ_TIMEOUT)
        channel.port1.onmessage = (ev) => {
            clearTimeout(timer)
            resolve(ev.data)
        }
        client.postMessage(message, [channel.port2])
    })
}
