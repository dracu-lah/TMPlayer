// Worker only: turns Telegram messages into the page's plain items and remembers where each file's
// bytes are, so the page can ask for them by key.
import type { FileLocation, Message, tl } from '@mtcute/web'
import type { PosterAsk, RawVideo, Video } from '../rules'
import type { FileRef, Storyboard, VideoCopy, VideoItem } from './types'

/** Where a Telegram document's bytes are. */
export interface FileLoc {
    location: tl.TypeInputFileLocation
    dcId: number
    size: number
    /** The message it came in, to ask for it again when Telegram says its file reference expired. */
    chatId: number
    messageId: number
}

/** Every file the page has been told about, by key; refreshed each time a list names it again. */
export const files = new Map<string, FileLoc>()
/** Video thumbnails, chat pictures and sponsored messages' pictures, by key. */
export const thumbnails = new Map<string, FileLocation>()

function docRef(d: tl.RawDocument, msg: Message): FileRef {
    const key = `doc${d.id.toString()}`
    files.set(key, {
        location: { _: 'inputDocumentFileLocation', id: d.id, accessHash: d.accessHash, fileReference: d.fileReference, thumbSize: '' },
        dcId: d.dcId,
        size: d.size,
        chatId: msg.chat.id,
        messageId: msg.id,
    })
    return { key, size: d.size }
}

/** The copies and storyboard Telegram made for a video posted as a video (its `alt_documents`). */
function alternatives(msg: Message, media: tl.TypeMessageMedia | undefined): { copies: VideoCopy[]; storyboard: Storyboard | null } {
    const alts = media?._ === 'messageMediaDocument' ? (media.altDocuments ?? []) : []
    const copies: VideoCopy[] = []
    let sprite: FileRef | null = null
    let map: FileRef | null = null
    for (const a of alts) {
        if (a._ !== 'document') continue
        if (a.mimeType === 'video/mp4') {
            const v = a.attributes.find((t): t is tl.RawDocumentAttributeVideo => t._ === 'documentAttributeVideo')
            copies.push({ ...docRef(a, msg), width: v?.w ?? 0, height: v?.h ?? 0, codec: v?.videoCodec ?? null })
        } else if (a.mimeType === 'application/x-tgstoryboard') sprite = docRef(a, msg)
        else if (a.mimeType === 'application/x-tgstoryboardmap') map = docRef(a, msg)
    }
    copies.sort((a, b) => b.height - a.height || (a.codec === 'avc1' ? -1 : 1))
    return { copies, storyboard: sprite && map ? { sprite, map } : null }
}

const VIDEO_NAME = /\.(mkv|mp4|m4v|mov|webm|avi|ts|m2ts|wmv|flv|3gp)$/i

/** A message's video, posted as a video or as a file, or null. Restricted messages are skipped. */
export function toItem(msg: Message): VideoItem | null {
    if ((msg.raw as { restrictionReason?: unknown[] }).restrictionReason?.length) return null
    const m = msg.media
    if (!m || (m.type !== 'video' && m.type !== 'document')) return null
    if (m.type === 'video' && (m.isRound || m.isAnimation)) return null
    if (m.type === 'document' && !m.mimeType.startsWith('video/') && !VIDEO_NAME.test(m.fileName ?? '')) return null
    const name = m.fileName?.replace(/\.[^.]+$/, '').replace(/[._]+/g, ' ').trim()
    const caption = msg.text.split('\n')[0]?.trim()
    return itemOf(msg, name || caption || 'Video', null)
}

/** The page's item for a message already known to hold a video, under [title], its file noted. */
export function itemOf(msg: Message, title: string, poster: PosterAsk | null | undefined): VideoItem | null {
    const m = msg.media
    if (!m || (m.type !== 'video' && m.type !== 'document')) return null
    const key = m.uniqueFileId
    const size = m.fileSize ?? 0
    files.set(key, {
        location: (typeof m.location === 'function' ? m.location() : m.location) as tl.TypeInputFileLocation,
        dcId: m.dcId ?? 2,
        size,
        chatId: msg.chat.id,
        messageId: msg.id,
    })
    const thumb = m.getThumbnail('m') ?? m.thumbnails[0] ?? null
    if (thumb) thumbnails.set(thumb.uniqueFileId, thumb)
    const raw = msg.raw._ === 'message' ? msg.raw : null
    return {
        key,
        size,
        chatId: msg.chat.id,
        messageId: msg.id,
        duration: m.type === 'video' ? m.duration : 0,
        title,
        chat: msg.chat.displayName,
        date: msg.date.getTime(),
        thumb: thumb?.uniqueFileId ?? null,
        ...alternatives(msg, raw?.media),
        canBeSaved: !raw?.noforwards && !chatProtects(msg),
        poster: poster ?? null,
    }
}

/**
 * A message as the app's rules read one (WebRules' RawVideo), or null when it holds no video or
 * file. `chatProtected` is the chat's "restrict saving content".
 */
export function rawOf(msg: Message, chatProtected: boolean): RawVideo | null {
    const m = msg.media
    if (!m || (m.type !== 'video' && m.type !== 'document')) return null
    const raw = msg.raw._ === 'message' ? msg.raw : null
    const media = raw?.media?._ === 'messageMediaDocument' ? raw.media : null
    const reply = raw?.replyTo?._ === 'messageReplyHeader' ? raw.replyTo : null
    return {
        chatId: msg.chat.id,
        messageId: msg.id,
        kind: m.type === 'video' ? (m.isRound ? 'round' : m.isAnimation ? 'animation' : 'video') : 'document',
        fileName: m.fileName ?? null,
        mimeType: m.mimeType ?? null,
        caption: msg.text || null,
        size: m.fileSize ?? 0,
        duration: m.type === 'video' ? m.duration : 0,
        width: m.type === 'video' ? m.width : 0,
        height: m.type === 'video' ? m.height : 0,
        date: Math.floor(msg.date.getTime() / 1000),
        topicId: reply?.forumTopic ? (reply.replyToTopId ?? reply.replyToMsgId ?? 0) : 0,
        albumId: msg.groupedId ? msg.groupedId.toString() : null,
        canBeSaved: !chatProtected && !raw?.noforwards,
        selfDestructs: !!media?.ttlSeconds,
        withheld: !!raw?.restrictionReason?.length || !!raw?.fromScheduled,
    }
}

/** The message's chat has "restrict saving content" on. */
export const chatProtects = (msg: Message) => !!(msg.chat.raw as { noforwards?: boolean }).noforwards

/** Two videos are the same file listed twice: the page keys its items by message. */
export const videoId = (v: Pick<Video, 'chatId' | 'messageId'>) => `${v.chatId}:${v.messageId}`
