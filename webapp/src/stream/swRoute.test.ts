// The Service Worker's stream answers (public/sw.js) with profiles (HX88): a read goes to the page that
// asked, else only to a page of the same profile, never to another profile's tab. The worker file is
// run here in a sandbox with the browser's few parts it uses stood in for.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'

const SOURCE = readFileSync(new URL('../../public/sw.js', import.meta.url), 'utf8')

interface FakeClient {
    id: string
    focused: boolean
    profile: string
    keys: Set<string>
    asked: number
    postMessage(message: { key: string; p?: string | null }, ports?: MessagePort[]): void
}

function client(id: string, profile: string, focused: boolean, keys: string[] = []): FakeClient {
    return {
        id,
        focused,
        profile,
        keys: new Set(keys),
        asked: 0,
        postMessage(message, ports) {
            // A save's progress note to the window carries no port.
            const port = ports?.[0]
            if (!port) return
            this.asked++
            // As src/stream/reader.ts answers: another profile's read is refused, a file it does not have is gone.
            if (typeof message.p === 'string' && message.p !== this.profile) return port.postMessage({ error: 'other' })
            if (!this.keys.has(message.key)) return port.postMessage({ error: 'gone' })
            port.postMessage({ bytes: new Uint8Array([1, 2, 3, 4]).buffer })
        },
    }
}

/** Loads the worker with these windows open; returns what a fetch of `url` (from `clientId`) answers. */
async function fetchFrom(windows: FakeClient[], url: string, clientId = '', request?: unknown) {
    const listeners: Record<string, (event: unknown) => void> = {}
    const self = {
        location: { origin: 'https://example.test' },
        addEventListener: (type: string, fn: (event: unknown) => void) => (listeners[type] = fn),
        clients: {
            get: async (id: string) => windows.find((w) => w.id === id) ?? null,
            matchAll: async () => windows,
            claim: async () => {},
        },
        skipWaiting: () => {},
    }
    runInNewContext(SOURCE, { self, URL, Response, ReadableStream, MessageChannel, setTimeout, clearTimeout, caches: {}, fetch, console })
    let answer: Promise<Response> | undefined
    listeners.fetch({
        clientId,
        request: request ?? new Request(`https://example.test${url}`, { headers: { range: 'bytes=0-3' } }),
        respondWith: (p: Promise<Response>) => (answer = p),
    })
    assert.ok(answer, 'the worker answers stream requests')
    return answer!
}

const STREAM = '/stream/f1?size=4&mime=video%2Fmp4&p=abc123'

test('a read with no page of its own skips a focused tab of another profile', async () => {
    const other = client('a', 'main', true, ['f1'])
    const mine = client('b', 'abc123', false, ['f1'])
    const res = await fetchFrom([other, mine], STREAM)
    assert.equal(res.status, 206)
    assert.equal(other.asked, 1)
    assert.equal(mine.asked, 1)
})

test('no tab of that profile: a 404, never another profile\'s bytes', async () => {
    const other = client('a', 'main', true, ['f1'])
    const res = await fetchFrom([other], STREAM)
    assert.equal(res.status, 404)
})

test('the page that asked answers for itself', async () => {
    const other = client('a', 'main', true, ['f1'])
    const mine = client('b', 'abc123', false, ['f1'])
    const res = await fetchFrom([other, mine], STREAM, 'b')
    assert.equal(res.status, 206)
    assert.equal(other.asked, 0)
})

test('a page of the same profile without the file says it is gone; no page at all is a 503', async () => {
    assert.equal((await fetchFrom([client('b', 'abc123', true)], STREAM)).status, 410)
    assert.equal((await fetchFrom([], STREAM)).status, 503)
})

// Node's Request cannot be made with mode 'navigate', so a save's request is stood in for.
const saveRequest = (url: string, mode: string, destination: string) => ({ url: `https://example.test${url}`, method: 'GET', headers: new Headers(), mode, destination })

test('Save to device streams only a save the page registered, asked by its hidden frame (SEC2-05)', async () => {
    const page = () => client('b', 'abc123', true, ['f1', 'save:0f9c'])
    // The player's own key of a playing (maybe restricted) video is never a download.
    const player = '/stream/f1?save=x.mp4&size=4&p=abc123'
    assert.equal((await fetchFrom([page()], player, 'b', saveRequest(player, 'navigate', 'iframe'))).status, 403)
    // A save key asked any other way (a fetch, a top-level navigation from another site) is refused.
    const save = '/stream/save%3A0f9c?save=x.mp4&size=4&p=abc123&id=0f9c'
    assert.equal((await fetchFrom([page()], save, 'b', saveRequest(save, 'cors', ''))).status, 403)
    assert.equal((await fetchFrom([page()], save, 'b', saveRequest(save, 'navigate', 'document'))).status, 403)
    const ok = await fetchFrom([page()], save, 'b', saveRequest(save, 'navigate', 'iframe'))
    assert.equal(ok.status, 200)
    assert.match(ok.headers.get('content-disposition') ?? '', /^attachment/)
    assert.deepEqual([...new Uint8Array(await ok.arrayBuffer())], [1, 2, 3, 4])
})
