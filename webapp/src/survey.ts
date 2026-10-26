// Local testing only (session 15): probes every video of the signed in account's chats the way the
// player does and records why each would say "needs the app". Run `await survey()` in the console.
import { api } from './tg/rpc'
import { openFile } from './stream/reader'
import { codecName, playableSound, probe, route } from './player/engines'
import { LIBAV_FORMATS, LibavEngine } from './player/libav'
import type { ChatView } from './tg/types'

const VIEW: ChatView = { sort: 'Newest', minBytes: 0, maxBytes: Number.MAX_SAFE_INTEGER, watched: [], started: [], query: '' }

export interface Row {
    chat: string
    title: string
    size: number
    duration: number
    container: string
    picture: string
    sound: string
    verdict: string
}

async function one(chat: string, t: { title: string; item: { key: string; size: number; duration: number } }): Promise<Row> {
    const row: Row = { chat, title: t.title, size: t.item.size, duration: t.item.duration, container: '?', picture: '?', sound: '?', verdict: '' }
    const f = openFile(t.item as never)
    try {
        const p = await Promise.race([probe(f, t.item.duration), new Promise<never>((_, r) => setTimeout(() => r(new Error('probe timeout')), 40_000))])
        row.container = p.container
        row.picture = p.videoCodec
        row.sound = p.audios.map((a) => codecName(a)).join('+') || 'none'
        try {
            const how = route(p)
            const snd = p.audios.length ? await playableSound(p.audios) : null
            row.verdict = how + (p.audios.length && !snd ? ' / no playable sound' : '')
        } catch (e) {
            row.verdict = 'needs app ' + JSON.stringify((e as { why?: unknown }).why ?? String(e))
        }
        p.input.dispose()
    } catch (e) {
        const why = (e as { why?: { kind: string; format?: string } }).why
        row.verdict = why ? `needs app ${JSON.stringify(why)}` : 'error ' + String(e)
        if (why?.kind === 'format') row.container = why.format ?? '?'
        if (why?.kind === 'format' && LIBAV_FORMATS.has(why.format ?? '')) await libav(row, f, why.format!, t.item.duration)
    } finally {
        f.close()
    }
    return row
}

/** A file the libav.js engine opens: its header only, nothing played. */
async function libav(row: Row, f: ReturnType<typeof openFile>, container: string, duration: number) {
    try {
        const e = await LibavEngine.open(f, document.createElement('video'), container, duration, null, {})
        row.picture = e.videoCodec
        row.sound = e.sounds.map((s) => codecName(s)).join('+') || 'none'
        row.verdict = 'libav' + (e.sounds.length && !e.sounds.some((s) => s.decodable) ? ' / no playable sound' : '')
        e.destroy()
    } catch (e) {
        const why = (e as { why?: unknown }).why
        row.verdict = why ? `needs app ${JSON.stringify(why)}` : 'error ' + String(e)
    }
}

export async function survey(perChat = 40, skip: string[] = [], onRow?: (r: Row) => void): Promise<Row[]> {
    const out: Row[] = []
    const chats = await api.chats(false)
    for (const c of chats) {
        if (c.kind === 'bot') continue
        let page = await api.chat(c.id, VIEW, 'open').catch(() => null)
        while (page && !page.done && page.tiles.length < perChat) page = await api.chat(c.id, VIEW, 'more').catch(() => null)
        if (!page || skip.includes(page.title)) continue
        for (const t of page.tiles.slice(0, perChat)) {
            const r = await one(page.title, t)
            out.push(r)
            onRow?.(r)
            console.debug('[survey]', JSON.stringify(r))
        }
    }
    return out
}

;(window as unknown as { survey: typeof survey }).survey = survey
