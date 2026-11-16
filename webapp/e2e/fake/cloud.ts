// The fake account's Saved Messages for the sync file (src/tg/syncfile.ts): kept by the test server
// (e2e/vite.e2e.ts, /__e2e/cloud), so a test reads what the page put there and a second browser
// context is a second device on the same account. One cloud per test: the `cloud:<key>` flag.

interface Stored {
    id: number
    date: number
    text: string
    fileName: string
    bytes: string
}

const toBase64 = (b: Uint8Array) => btoa(Array.from(b, (c) => String.fromCharCode(c)).join(''))
const fromBase64 = (s: string) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0))

export function cloudFor(key: string) {
    async function call(op: string, body?: unknown): Promise<Stored | Stored[]> {
        const r = await fetch(`/__e2e/cloud?key=${encodeURIComponent(key)}&op=${op}`, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) })
        return r.json()
    }

    /** A stored message as the client hands it back: a document with its caption as `text`. */
    const message = (s: Stored, chat: unknown) => ({
        id: s.id,
        chat,
        date: new Date(s.date),
        text: s.text,
        groupedId: null,
        media: { type: 'document', fileName: s.fileName, fileSize: fromBase64(s.bytes).length, uniqueFileId: `cloud${s.id}`, bytes: fromBase64(s.bytes) },
    })

    return {
        async all(chat: unknown) {
            return ((await call('list')) as Stored[]).map((s) => message(s, chat)).sort((a, b) => b.id - a.id)
        },
        async put(chat: unknown, p: { file: Uint8Array; fileName: string; caption: string }) {
            const s = (await call('put', { date: Date.now(), text: p.caption, fileName: p.fileName, bytes: toBase64(p.file) })) as Stored
            return message(s, chat)
        },
        async edit(chat: unknown, id: number, p: { file: Uint8Array; fileName: string; caption: string }) {
            const list = (await call('list')) as Stored[]
            if (!list.some((s) => s.id === id)) throw Object.assign(new Error('MESSAGE_ID_INVALID'), { text: 'MESSAGE_ID_INVALID', code: 400 })
            const s = { id, date: Date.now(), text: p.caption, fileName: p.fileName, bytes: toBase64(p.file) }
            await call('edit', s)
            return message(s, chat)
        },
        async remove(ids: number[]) {
            await call('delete', { ids })
        },
    }
}
