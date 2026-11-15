// A subtitle file posted in the video's chat (EG-F48), read whole through the worker's file key
// (chatsubs.ts turns the bytes into the File the player takes).
import { call } from '../tg/rpc'
import type { ChatSubtitle } from '../tg/types'
import { ChatSubtitleError, chatSubtitleFile, MAX_BYTES, MAX_ZIP_BYTES } from './chatsubs'

/** What `chunk` reads at a time: a power of two that does not cross 1 MiB. */
const CHUNK = 1 << 20

/** The file's bytes, read in pieces; a message that is gone says so, any other failure is "offline". */
async function readWhole(key: string, size: number, signal?: AbortSignal): Promise<Uint8Array> {
    const out = new Uint8Array(size)
    for (let at = 0; at < size; at += CHUNK) {
        let piece: ArrayBuffer
        try {
            piece = await call('chunk', [key, at, CHUNK], { signal })
        } catch (e) {
            if ((e as DOMException)?.name === 'AbortError') throw e
            throw new ChatSubtitleError((e as Error)?.message === 'gone' ? 'gone' : 'offline')
        }
        out.set(new Uint8Array(piece), at)
    }
    return out
}

/**
 * The listed file as a File for the player, or an error saying why not: gone from the chat, too big,
 * compressed beyond what this browser can open, not a subtitle, or no connection. [video] is the
 * playing video's season and episode, which pick the entry of a zip.
 */
export async function readChatSubtitle(sub: ChatSubtitle, video: { season: number | null; episode: number } | null, signal?: AbortSignal): Promise<File> {
    if (sub.size <= 0) throw new ChatSubtitleError('unreadable')
    if (sub.size > (sub.archive ? MAX_ZIP_BYTES : MAX_BYTES)) throw new ChatSubtitleError('too-big')
    return chatSubtitleFile(sub.name, sub.archive, await readWhole(sub.key, sub.size, signal), video)
}
