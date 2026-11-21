// The chat list: every chat outside the archive, the archive, and the forums' topics, read in pages and kept for the session.
// Worker only.
import type { Peer, TelegramClient } from '@mtcute/web'
import { PAGE } from './library'
import { rules } from '../rules'
import { thumbnails } from './media'
import { noteChatTitle } from './hints'
import { forgetTopics, type ForumChat } from './topics'
import type { ChatItem } from './types'

// The chat list

/** A sign out: the kept lists are the account's, and an answer still on its way is no longer wanted (the epoch). */
export function forgetDialogs() {
    topicHead = null
    forgetTopics()
    dialogs = null
    dialogsLoading = null
    dialogsSoFar = []
    dialogsAt = 0
    headLoading = null
    archived = null
    archivedLoading = null
    epoch++
}

let dialogs: ChatItem[] | null = null
/** The chat list as far as it has come while it loads. */
let dialogsSoFar: ChatItem[] = []
let dialogsLoading: Promise<ChatItem[]> | null = null
const dialogListeners = new Set<(chats: ChatItem[]) => void>()

export function chatItem(peer: Peer, pinned: boolean): ChatItem {
    const small = peer.photo?.small ?? null
    if (small) thumbnails.set(small.uniqueFileId, small)
    const self = peer.type === 'user' && peer.isSelf
    noteChatTitle(peer.id, peer.displayName)
    return {
        id: peer.id,
        title: peer.displayName,
        photo: small && !self ? small.uniqueFileId : null,
        pinned,
        kind: peer.type === 'user' ? (peer.isBot ? 'bot' : 'user') : peer.chatType === 'channel' ? 'channel' : 'group',
        forum: peer.type === 'chat' && peer.isForum,
        self,
    }
}

/** When the whole list was read, or its head last read again. */
let dialogsAt = 0
/** A kept list older than this has its head read again before it is handed out (HX44). */
const FRESH_MS = 60_000
let headLoading: Promise<ChatItem[]> | null = null
/** Bumped by a sign out, so an answer that lands after it is not kept for the next account. */
let epoch = 0

/**
 * Every chat outside the archive, pinned first, in Telegram's order. The list arrives in pages
 * of a hundred, each sent on to `onBatch` as it comes; it is kept for the session, and
 * `refresh` asks again. A kept list more than a minute old is handed to `onBatch` at once and
 * its first page then read again (one call), so a chat joined since, or a new private chat,
 * shows up by itself, as the apps' list does without a Refresh button (HX44).
 */
export async function chatList(tg: TelegramClient, refresh: boolean, onBatch: (chats: ChatItem[]) => void): Promise<ChatItem[]> {
    if (dialogs && !refresh) {
        if (Date.now() - dialogsAt < FRESH_MS) return dialogs
        onBatch(dialogs)
        headLoading ??= freshenHead(tg).finally(() => (headLoading = null))
        return await headLoading
    }
    dialogListeners.add(onBatch)
    try {
        dialogsLoading ??= (async () => {
            const mine = epoch
            const out: ChatItem[] = []
            const seen = new Set<number>()
            for await (const d of tg.iterDialogs({ archived: 'exclude', pinned: 'include' })) {
                if (seen.has(d.peer.id)) continue
                seen.add(d.peer.id)
                out.push(chatItem(d.peer, d.isPinned))
                if (out.length % PAGE === 0) {
                    if (mine !== epoch) break
                    dialogsSoFar = out.slice()
                    for (const fn of dialogListeners) fn(dialogsSoFar)
                }
            }
            if (mine === epoch) {
                dialogs = out
                dialogsAt = Date.now()
            }
            return out
        })().finally(() => (dialogsLoading = null))
        return await dialogsLoading
    } finally {
        dialogListeners.delete(onBatch)
    }
}

/**
 * The list's first page read again: Telegram's head in its own order, then the rest as kept, so
 * a chat moved up or newly joined comes first and nothing further down is asked for again. A
 * failure keeps the list as it was and waits another minute before trying.
 */
async function freshenHead(tg: TelegramClient): Promise<ChatItem[]> {
    const mine = epoch
    const kept = dialogs ?? []
    try {
        const head: ChatItem[] = []
        const seen = new Set<number>()
        for await (const d of tg.iterDialogs({ archived: 'exclude', pinned: 'include', limit: PAGE })) {
            if (seen.has(d.peer.id)) continue
            seen.add(d.peer.id)
            head.push(chatItem(d.peer, d.isPinned))
        }
        if (mine !== epoch) return kept
        // Pinned chats all come in the head, so one further down is pinned no longer.
        const rest = kept.filter((c) => !seen.has(c.id)).map((c) => (c.pinned ? { ...c, pinned: false } : c))
        dialogs = [...head, ...rest]
        dialogsAt = Date.now()
        return dialogs
    } catch {
        if (mine === epoch) dialogsAt = Date.now()
        return kept
    }
}

let archived: ChatItem[] | null = null
let archivedLoading: Promise<ChatItem[]> | null = null
const archivedListeners = new Set<(chats: ChatItem[]) => void>()

/**
 * Every chat in Telegram's archive, read whole (HX32) in pages of a hundred and kept for the
 * session. Only the Add chats picker and a Chats list narrowed to archived chats the viewer
 * added ask for it: a channel archived to keep Telegram tidy is still a channel of videos.
 */
export async function archivedList(tg: TelegramClient, onBatch: (chats: ChatItem[]) => void): Promise<ChatItem[]> {
    if (archived) return archived
    archivedListeners.add(onBatch)
    try {
        archivedLoading ??= (async () => {
            const mine = epoch
            const out: ChatItem[] = []
            const seen = new Set<number>()
            for await (const d of tg.iterDialogs({ archived: 'only', pinned: 'include' })) {
                if (seen.has(d.peer.id)) continue
                seen.add(d.peer.id)
                out.push({ ...chatItem(d.peer, false), archived: true })
                if (out.length % PAGE === 0) {
                    if (mine !== epoch) break
                    const soFar = out.slice()
                    for (const fn of archivedListeners) fn(soFar)
                }
            }
            if (mine === epoch) archived = out
            return out
        })().finally(() => (archivedLoading = null))
        return await archivedLoading
    } finally {
        archivedListeners.delete(onBatch)
    }
}

/** The chat list: whole once it has loaded (`done`), else as far as it has come. */
export function knownChats(): { chats: ChatItem[]; done: boolean } {
    return dialogs ? { chats: dialogs, done: true } : { chats: dialogsSoFar, done: false }
}

/** A search by topic name waits this long for a chat list that is already being read (every chat is searched). */
const TOPIC_LIST_WAIT_MS = 3_000
/** Added chats the list has not reached whose peer is looked up (from the stored peers, no call) per search. */
const TOPIC_PEER_CHECKS = 150
/** The head of the chat list (the chats used most recently), read for a search by topic name when the whole list is not there: kept two minutes. */
let topicHead: { at: number; chats: ChatItem[] } | null = null

/**
 * The groups with topics a search by topic name may ask (U14), and the chat list's order for ties.
 * Telling a group with topics from one without is a read of what the chat list already holds (the
 * forum flag of the dialog), never a call per chat. Added chats the list has not reached are looked
 * up among the stored peers, at most TOPIC_PEER_CHECKS. With every chat searched, the list is used
 * as far as it has come (waiting a few seconds for one already being read); when none is being
 * read, only its first page (the chats used most recently, one call) is read, so a search never
 * starts the read of 3,000 chats.
 */
export async function topicSources(tg: TelegramClient, added: readonly number[] | null, signal?: AbortSignal): Promise<{ forums: ForumChat[]; order: number[] }> {
    let head: ChatItem[] = []
    if (!added && !dialogs) {
        if (dialogsLoading) {
            await Promise.race([dialogsLoading.catch(() => []), new Promise((resolve) => setTimeout(resolve, TOPIC_LIST_WAIT_MS))])
        } else if (dialogsSoFar.length === 0) {
            if (!topicHead || Date.now() - topicHead.at > 120_000) {
                const mine = epoch
                const first: ChatItem[] = []
                try {
                    for await (const d of tg.iterDialogs({ archived: 'exclude', pinned: 'include', limit: PAGE })) {
                        // A new letter gave the search up: the read stops with the page it is on, and the part read is not kept.
                        if (signal?.aborted) break
                        first.push(chatItem(d.peer, d.isPinned))
                    }
                } catch {
                    // The chats read so far are used; none means no group is asked.
                }
                if (mine !== epoch || signal?.aborted) return { forums: [], order: [] }
                topicHead = { at: Date.now(), chats: first }
            }
            head = topicHead.chats
        }
    }
    const known: ChatItem[] = [...(dialogs ?? dialogsSoFar), ...head, ...(archived ?? [])]
    const byId = new Map<number, ChatItem>()
    for (const c of known) if (!byId.has(c.id)) byId.set(c.id, c)
    if (added) {
        const missing = added.filter((id) => !byId.has(id)).slice(0, TOPIC_PEER_CHECKS)
        for (let i = 0; i < missing.length; i += 6) {
            if (signal?.aborted) return { forums: [], order: [] }
            await Promise.all(
                missing.slice(i, i + 6).map(async (id) => {
                    try {
                        byId.set(id, chatItem(await tg.getPeer(id), false))
                    } catch {
                        // Not a peer this device knows (left, never seen): not asked.
                    }
                }),
            )
        }
    }
    const forums: ForumChat[] = []
    for (const c of byId.values()) if (c.forum && c.kind === 'group') forums.push({ id: c.id, title: c.title, archived: !!c.archived })
    return { forums, order: known.map((c) => c.id) }
}

/** Ids of the listed chats whose names answer `query`, best first, by the app's fuzzy search. */
export async function rankChats(query: string): Promise<number[]> {
    const r = await rules()
    const list = dialogs ?? []
    return r.rank(list.map((c) => c.title), query).map((i) => list[i].id)
}
