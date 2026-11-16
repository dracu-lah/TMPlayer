// Where the sync file lives (core's ResumeSync, "the file" half): one document in the viewer's own
// Saved Messages, found by its caption, read, and replaced in place so messages never pile up. The
// worker only moves bytes: the format, the merge and every decision are the page's (src/sync/).
// Nothing here runs before the viewer turned the sync on; the page asks, this answers.
import type { Message, TelegramClient } from '@mtcute/web'

/** The caption the file is found by; a hashtag, so Telegram's search finds it at once (ResumeSync.FILE_TAG). */
export const FILE_TAG = '#TMPlayerSyncFile'
/** What an older TMPlayer's text message starts with; found only to be deleted with the file. */
const LEGACY_TAG = '#TMPlayerSync'
const STEM = 'tmplayer-sync'
const SEARCH_LIMIT = 10
const HISTORY_LIMIT = 30
/** A file larger than this, packed or unpacked, is not read at all (SyncDocuments.MAX_READ_BYTES). */
const MAX_READ_BYTES = 2 * 1024 * 1024

const SELF = 'self'
const DOC_FILTER = { _: 'inputMessagesFilterDocument' } as const

/** One sync file found: its message, and its bytes, or why there are none. */
export interface FoundFile {
    id: number
    /** Seconds since 1970, newest first in the list. */
    date: number
    bytes: ArrayBuffer | null
    /** Set when the file could not be read: "large" (past the limit) or "download". */
    failed?: 'large' | 'download'
}

export const isFile = (m: Message | null | undefined): m is Message => {
    const media = m?.media
    return !!m && media?.type === 'document' && (media.fileName ?? '').startsWith(STEM) && m.text.trimStart().startsWith(FILE_TAG)
}

const isLegacy = (m: Message | null | undefined): m is Message => !!m && !m.media && m.text.trimStart().startsWith(LEGACY_TAG) && !m.text.trimStart().startsWith(FILE_TAG)

/** Every sync file in Saved Messages: the one remembered, by its caption, or the newest messages. Newest first. */
export async function findFiles(tg: TelegramClient, known: number): Promise<Message[]> {
    const found = new Map<number, Message>()
    const keep = (m: Message | null | undefined) => {
        if (isFile(m)) found.set(m.id, m)
    }
    if (known > 0) keep((await tg.getMessages(SELF, known).catch(() => []))[0])
    const hits = await tg.searchMessages({ chatId: SELF, query: FILE_TAG, filter: DOC_FILTER, limit: SEARCH_LIMIT }).catch(() => [] as Message[])
    for (const m of hits) keep(m)
    // Not indexed yet, or search not answering: the newest messages themselves.
    if (!found.size) for (const m of await tg.getHistory(SELF, { limit: HISTORY_LIMIT })) keep(m)
    return [...found.values()].sort((a, b) => b.date.getTime() - a.date.getTime() || b.id - a.id)
}

/** Whether a file of this stated size may be downloaded: a known size within the limit; an unknown, zero or negative one may not. */
export const isReadableSize = (size: number | null | undefined): boolean => typeof size === 'number' && Number.isFinite(size) && size > 0 && size <= MAX_READ_BYTES

/** The files found, each downloaded (a large one is named, not read). */
export async function readFiles(tg: TelegramClient, known: number): Promise<FoundFile[]> {
    const out: FoundFile[] = []
    for (const m of await findFiles(tg, known)) {
        const file: FoundFile = { id: m.id, date: Math.floor(m.date.getTime() / 1000), bytes: null }
        const media = m.media
        if (media?.type !== 'document') continue
        // A size Telegram does not state is not trusted: the download cannot be bounded, so it is not made.
        if (!isReadableSize(media.fileSize)) file.failed = 'large'
        else {
            try {
                const bytes = await tg.downloadAsBuffer(media)
                file.bytes = bytes.byteOffset === 0 && bytes.byteLength === bytes.buffer.byteLength ? (bytes.buffer as ArrayBuffer) : (bytes.slice().buffer as ArrayBuffer)
            } catch {
                file.failed = 'download'
            }
        }
        out.push(file)
    }
    return out
}

/**
 * The file put in place: `target` edited when there is one, else sent once and pinned. `gone` is
 * Telegram's own word that the message was deleted since it was read, so the page sends it anew.
 */
export async function writeFile(tg: TelegramClient, target: number | null, bytes: Uint8Array, caption: string): Promise<{ id: number } | { gone: true }> {
    const media = { type: 'document', file: bytes, fileName: `${STEM}.json.gz`, fileMime: 'application/gzip' } as const
    if (target) {
        try {
            const edited = await tg.editMessage({ chatId: SELF, message: target, text: caption, media })
            return { id: edited.id }
        } catch (e) {
            const text = String((e as { text?: string })?.text ?? e)
            if (/MESSAGE_NOT_MODIFIED/.test(text)) return { id: target }
            if (/MESSAGE_ID_INVALID|MESSAGE_EMPTY/.test(text)) return { gone: true }
            throw e
        }
    }
    const sent = await tg.sendMedia(SELF, { ...media, caption }, { silent: true })
    // Pinned so the viewer sees what it is; not worth failing the sync for.
    await tg.pinMessage({ chatId: SELF, message: sent.id, notify: false }).catch(() => {})
    return { id: sent.id }
}

/** The files with these ids deleted (every other copy, once merged in). */
export async function deleteMessages(tg: TelegramClient, ids: number[]): Promise<void> {
    if (ids.length) await tg.deleteMessagesById(SELF, ids, { revoke: true })
}

/** The sync file, every copy, and an older TMPlayer's text message deleted: "Turn off and delete the sync file". True when none is left. */
export async function deleteEverything(tg: TelegramClient, known: number): Promise<boolean> {
    const ids = new Set((await findFiles(tg, known)).map((m) => m.id))
    const legacy = await tg.searchMessages({ chatId: SELF, query: LEGACY_TAG, limit: SEARCH_LIMIT }).catch(() => [] as Message[])
    for (const m of legacy) if (isLegacy(m)) ids.add(m.id)
    await deleteMessages(tg, [...ids])
    return true
}
