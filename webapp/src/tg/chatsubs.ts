// Worker only: the subtitle files posted in a video's chat (EG-F48), for the player's "In this chat".
// One search of the chat's documents around the video (both sides of it, so the same album, replies
// and neighbours are all in it) and the apps' own matching (WebRules' chatSubtitles, ChatSubtitles.kt),
// answered as a short list; a chosen file is read whole through the worker's file keys. Nothing is
// kept beyond five minutes, and a failure only leaves the entry out.
import type { Message, TelegramClient } from '@mtcute/web'
import { rules } from '../rules'
import { files } from './media'
import type { ChatSubtitle } from './types'

/** Documents asked for in the one search: half older than the video, half newer. */
const WINDOW = 100
/** A subtitle is tens of kilobytes; a zip of a season a few megabytes (ChatSubtitles.MAX_BYTES, MAX_ZIP_BYTES). */
export const MAX_BYTES = 4 * 1024 * 1024
export const MAX_ZIP_BYTES = 8 * 1024 * 1024
const TTL_MS = 5 * 60_000
const KEEP = 32

const kept = new Map<string, { at: number; list: ChatSubtitle[] }>()

/** What a document message says of itself, or null when it is not a file with a name. */
function docOf(msg: Message) {
    const m = msg.media
    if (!m || m.type !== 'document' || !m.fileName) return null
    const raw = msg.raw._ === 'message' ? msg.raw : null
    const reply = raw?.replyTo?._ === 'messageReplyHeader' ? raw.replyTo : null
    return {
        messageId: msg.id,
        fileName: m.fileName,
        size: m.fileSize ?? 0,
        date: Math.floor(msg.date.getTime() / 1000),
        albumId: msg.groupedId ? msg.groupedId.toString() : null,
        replyToMessageId: reply?.replyToMsgId ?? 0,
        withheld: !!raw?.restrictionReason?.length || !!raw?.fromScheduled || !!(raw?.media?._ === 'messageMediaDocument' && raw.media.ttlSeconds),
        canBeSaved: !raw?.noforwards && !(msg.chat.raw as { noforwards?: boolean }).noforwards,
        key: m.uniqueFileId,
        message: msg,
    }
}

/**
 * The files for the video, best first, at most ten. One search (and one read of the video's own
 * message, for its album and what it replies to). Throws when Telegram does not answer: the page
 * hides the entry.
 */
export async function findChatSubtitles(
    tg: TelegramClient,
    chatId: number,
    messageId: number,
    fileName: string,
    caption: string,
    preferred: string[],
): Promise<ChatSubtitle[]> {
    const id = `${chatId}:${messageId}`
    const hit = kept.get(id)
    if (hit && Date.now() - hit.at < TTL_MS) return hit.list
    const [video] = (await tg.getMessages(chatId, messageId)) as (Message | null)[]
    const res = await tg.searchMessages({
        chatId,
        filter: { _: 'inputMessagesFilterDocument' },
        offset: messageId,
        addOffset: -WINDOW / 2,
        limit: WINDOW,
    })
    const docs = (res as Message[]).map(docOf).filter((d): d is NonNullable<ReturnType<typeof docOf>> => !!d && !d.withheld && d.messageId !== messageId)
    const raw = video && video.raw._ === 'message' ? video.raw : null
    const videoReply = raw?.replyTo?._ === 'messageReplyHeader' ? (raw.replyTo.replyToMsgId ?? 0) : 0
    const r = await rules()
    const hits = r.chatSubtitles(
        fileName,
        caption || null,
        messageId,
        video ? Math.floor(video.date.getTime() / 1000) : 0,
        video?.groupedId ? video.groupedId.toString() : null,
        videoReply,
        docs.map((d) => ({ messageId: d.messageId, fileName: d.fileName, size: d.size, date: d.date, albumId: d.albumId, replyToMessageId: d.replyToMessageId })),
        preferred,
    )
    const list: ChatSubtitle[] = []
    for (const h of hits) {
        const d = docs[h.index]
        const m = d.message.media
        if (!m || m.type !== 'document') continue
        // The worker's file key, so the page reads it with `chatSubtitleFile` and nothing else.
        files.set(d.key, {
            location: (typeof m.location === 'function' ? m.location() : m.location) as never,
            dcId: m.dcId ?? 2,
            size: d.size,
            chatId,
            messageId: d.messageId,
        })
        list.push({
            key: d.key,
            name: d.fileName,
            size: d.size,
            relation: h.relation as ChatSubtitle['relation'],
            language: h.language ?? null,
            forced: h.forced,
            hearing: h.hearing,
            archive: h.archive,
            canBeSaved: d.canBeSaved,
        })
    }
    kept.set(id, { at: Date.now(), list })
    while (kept.size > KEEP) kept.delete(kept.keys().next().value!)
    return list
}

/** Forgets what was found for a video, so a Try again asks again. */
export function forgetChatSubtitles(chatId: number, messageId: number) {
    kept.delete(`${chatId}:${messageId}`)
}
