// Worker only: Home's rows, built by the app's own rules (WebRules' homeChats and home, over
// HomeRows) from what is on this device and what Telegram has answered so far: Continue watching
// read again off its messages, the newest videos across every chat, and each of Home's chats'
// newest. Rows still waiting are sent as placeholders, and Home is sent again as answers land,
// a moment after they stop arriving, as the app builds it (ROWS_SETTLE_MS).
import type { Message, TelegramClient } from '@mtcute/web'
import { rules, type Video, type WebResume } from '../rules'
import { across, chatItem, chatList, knownChats, newestOf, tilesOf } from './library'
import { chatProtects, itemOf, rawOf } from './media'
import type { ChatItem, HomePage, HomeRow, HomeView, Tile, VideoItem } from './types'

/** Chats read at once: enough to fill a screen quickly, few enough to stay polite to Telegram (HomeViewModel.PARALLEL). */
const PARALLEL = 3
const SETTLE_MS = 300
/** Videos part way read again: Continue shows ten once copies fold. */
const RESUMES_READ = 20
/**
 * Home picks its own chats once this much of the chat list has come (the app reads 300 at
 * first), or the list is whole: an account with thousands of chats waited for every page of its
 * list, through Telegram's flood waits, before Home settled (HX8).
 */
const LIST_ENOUGH = 300

/** Telegram's answer for a chat this account can no longer read: its videos are gone for it. */
const chatGone = (e: unknown) => /^(CHANNEL_PRIVATE|CHAT_FORBIDDEN|USER_BANNED_IN_CHANNEL|PEER_ID_INVALID|CHANNEL_INVALID|CHAT_ID_INVALID)/.test(String((e as { text?: string })?.text ?? ''))

export async function homePage(tg: TelegramClient, view: HomeView, signal: AbortSignal, emit: (page: HomePage) => void): Promise<HomePage> {
    const r = await rules()
    const within = (v: Video) => r.sizeMatches(v.size, view.minBytes, view.maxBytes)
    const items = new Map<string, VideoItem>()
    let resumes: WebResume[] | null = null
    // Continue's cards are named as a chat's tiles are ("Long Road (2019)", not its file name).
    const resumeTitles = new Map<string, string>()
    const gone: HomePage['gone'] = []
    let recent: Video[] | null = null
    const loaded = new Map<number, { chatId: number; videos: Video[]; complete: boolean }>()
    // Starred chats and Saved Messages the chat list has not reached yet.
    const early = new Map<number, ChatItem>()
    let selfId: number | null = null
    let chatsDone = false
    // Enough of the list to pick Home's chats from: the whole list, or its first LIST_ENOUGH.
    const listEnough = () => chatsDone || knownChats().chats.length >= LIST_ENOUGH
    let enough: () => void = () => {}
    const enoughCame = new Promise<void>((resolve) => (enough = resolve))

    // Home's chats, read a few at a time.
    const asked = new Set<number>()
    const queue: number[] = []
    const loads: Promise<void>[] = []
    let active = 0
    const pump = () => {
        while (active < PARALLEL && queue.length && !signal.aborted) {
            const id = queue.shift()!
            active++
            loads.push(
                newestOf(tg, id)
                    .then(
                        (got) => {
                            for (const [k, v] of got.items) items.set(k, v)
                            loaded.set(id, { chatId: id, videos: got.videos.filter(within), complete: got.complete })
                        },
                        // A chat that cannot be read has nothing for Home: its row goes rather than waiting for ever.
                        () => void loaded.set(id, { chatId: id, videos: [], complete: true }),
                    )
                    .finally(() => {
                        active--
                        pump()
                        schedule()
                    }),
            )
        }
    }

    const chatsNow = () => {
        const listed = knownChats().chats
        const ids = new Set(listed.map((c) => c.id))
        return [...listed, ...[...early.values()].filter((c) => !ids.has(c.id))].map((c) => ({ id: c.id, title: c.title, saved: c.self, pinned: c.pinned }))
    }

    const build = (): HomePage => {
        const chats = chatsNow()
        const byId = new Map(chats.map((c) => [c.id, c]))
        // The chats Home picks for itself from the search across chats are only known once the whole list is.
        const picked = r.homeChats(chats, view.starred, listEnough() ? recent : null)
        for (const id of picked) {
            if (asked.has(id)) continue
            asked.add(id)
            queue.push(id)
        }
        pump()
        const built = r.home(resumes ?? [], picked.map((id) => byId.get(id)!), view.starred, [...loaded.values()], recent, !listEnough())
        const rows: HomeRow[] = built.map((row) => ({
            key: row.key,
            kind: row.kind as HomeRow['kind'],
            chatId: row.chatId ?? null,
            title: row.title ?? null,
            loaded: row.loaded,
            starredOnly: row.starredOnly,
            hasMore: row.hasMore,
            tiles:
                row.kind === 'continue'
                    ? row.resumes.flatMap((id): Tile[] => {
                          const found = items.get(id)
                          const item = found && { ...found, title: resumeTitles.get(id) ?? found.title }
                          return item ? [{ key: item.key, kind: 'file', title: item.title, item, seasons: 0, episodes: 0, copies: 0, parts: 0, pieces: 0, extra: null }] : []
                      })
                    : tilesOf(row, items, view),
        }))
        const settled = resumes !== null && recent !== null && listEnough() && picked.every((id) => loaded.has(id))
        return { rows, gone, selfId, settled }
    }

    let timer: ReturnType<typeof setTimeout> | undefined
    // Nothing is sent before Continue watching is read, so it never lands above rows already drawn.
    let finished = false
    const schedule = () => {
        // The list's later pages still arrive once Home is built: they change nothing here.
        if (resumes === null || signal.aborted || finished) return
        clearTimeout(timer)
        timer = setTimeout(() => !signal.aborted && emit(build()), SETTLE_MS)
    }

    const readResumes = async () => {
        const marks = view.resumes.slice(0, RESUMES_READ)
        const byChat = new Map<number, typeof marks>()
        for (const m of marks) byChat.set(m.chatId, [...(byChat.get(m.chatId) ?? []), m])
        const found = new Map<string, WebResume>()
        await Promise.all(
            [...byChat].map(async ([chatId, list]) => {
                let msgs: (Message | null)[]
                try {
                    msgs = (await tg.getMessages(chatId, list.map((m) => m.messageId))) as (Message | null)[]
                } catch (e) {
                    if (chatGone(e)) gone.push(...list.map(({ chatId, messageId }) => ({ chatId, messageId })))
                    return
                }
                list.forEach((mark, i) => {
                    const msg = msgs[i]
                    const raw = msg && rawOf(msg, chatProtects(msg))
                    if (!msg || !raw) return void gone.push({ chatId: mark.chatId, messageId: mark.messageId })
                    // Withheld or self-destructing now: not played, but its place is kept.
                    const video = r.screen([raw]).videos[0]
                    const item = video && itemOf(msg, video.title, r.posterAsk(video))
                    if (!video || !item) return
                    items.set(video.id, item)
                    resumeTitles.set(video.id, r.tileTitle(video))
                    found.set(video.id, { video, chatTitle: msg.chat.displayName, positionMs: mark.position * 1000, durationMs: mark.length * 1000, updatedAt: mark.time })
                })
            }),
        )
        resumes = marks.flatMap((m) => found.get(`${m.chatId}:${m.messageId}`) ?? [])
    }

    const readEarly = async () => {
        selfId = (await tg.storage.self.fetch())?.userId ?? null
        const ids = [...new Set([...view.starred, ...(selfId ? [selfId] : [])])]
        await Promise.all(
            ids.map(async (id) => {
                const peer = await tg.getPeer(id).catch(() => null)
                if (peer) early.set(id, chatItem(peer, false))
            }),
        )
    }

    const readRecent = async () => {
        try {
            const got = await across(tg, '')
            for (const [k, v] of got.items) items.set(k, v)
            recent = got.videos.filter(within)
        } catch {
            recent = []
        }
    }

    const readChats = async () => {
        await chatList(tg, false, () => {
            if (listEnough()) enough()
            schedule()
        }).catch(() => {})
        chatsDone = true
        enough()
    }

    try {
        const first = Promise.all([readResumes(), readEarly()]).then(schedule)
        // The rest of the list goes on loading behind Home, for the Chats page; Home waits for
        // the first LIST_ENOUGH of it only.
        void readChats()
        await Promise.all([first, readRecent().then(schedule), enoughCame.then(schedule)])
        // Home's chats come out of each build; wait for them until a build asks for none new.
        for (;;) {
            if (signal.aborted) throw new DOMException('aborted', 'AbortError')
            const before = asked.size
            build()
            await Promise.all(loads)
            if (asked.size === before && !queue.length) break
        }
        return build()
    } finally {
        finished = true
        clearTimeout(timer)
    }
}
