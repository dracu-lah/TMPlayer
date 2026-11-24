// Worker only: Home's rows, built by the app's own rules (WebRules' homeChats and home, over
// HomeRows) from what is on this device and what Telegram has answered so far: Continue watching
// read again off its messages, the newest videos across every chat, and each of Home's chats'
// newest. Rows still waiting are sent as placeholders, and Home is sent again as answers land,
// a moment after they stop arriving, as the app builds it (ROWS_SETTLE_MS).
import type { Message, TelegramClient } from '@mtcute/web'
import { rules, type Video, type WebResume } from '../rules'
import { addCapped } from '../ui/cappedSet'
import { across, lookup, newestOf, tilesOf } from './library'
import { chatItem, chatList, knownChats } from './dialogs'
import { rememberHomeChats } from './recent'
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
/** Added chats Recently added waits on, read at once (HomeRows.CHOSEN_WAIT); the rest count through the search across chats. */
const CHOSEN_WAIT = 8
/**
 * Chat rows read at once, from the top, without waiting to be seen (HomeViewModel.EAGER_CHATS): what fills
 * the first screens. The others are read once their row has stayed on screen (HX54, `rowShown`).
 */
const EAGER_ROWS = 8
/** Rows kept as on screen at once: a screen shows a handful, so more is garbage; past it the oldest is let go. */
const SHOWN_MAX = 120
/** Every row on: Home as it was before Settings > Home. */
const ALL_ON: HomeView['layout'] = { carousel: true, continueWatching: true, recentlyAdded: true, starredRows: true, order: '' }

/** A chat row's read that has not answered in this long counts as failed (LS-26): its row leaves and the page's foot says so, with Try again. */
const ROW_READ_MS = 45_000

/** `work`, or a rejection after `ms`; the work itself goes on into the chat's cache, so Try again finds it read. */
function readWithin<T>(work: Promise<T>, ms: number): Promise<T> {
    return new Promise<T>((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error('row read timed out')), ms)
        work.then(resolve, reject).finally(() => clearTimeout(timer))
    })
}

/** Telegram's answer for a chat this account can no longer read: its videos are gone for it. */
const chatGone = (e: unknown) => /^(CHANNEL_PRIVATE|CHAT_FORBIDDEN|USER_BANNED_IN_CHANNEL|PEER_ID_INVALID|CHANNEL_INVALID|CHAT_ID_INVALID)/.test(String((e as { text?: string })?.text ?? ''))

/** The chats whose rows are on screen now (HX54), kept here so a Home asked again starts from them. */
const shown = new Set<number>()
/** What the running Home does when a row comes or goes: read it, or drop its read that has not started. */
let running: { wake(): void; unask(chatId: number): void } | null = null

/**
 * The page says a chat's row has stayed on screen (`on`) or has left it. A row that arrives is read
 * by the Home that is running; one that leaves before its read began is never read. Anything but a
 * whole number and a boolean is ignored (the page is not trusted with more than that).
 */
export function rowShown(chatId: unknown, on: unknown) {
    if (typeof chatId !== 'number' || !Number.isSafeInteger(chatId) || typeof on !== 'boolean') return
    if (on) {
        // Past the cap the oldest goes: the newest rows are the ones on screen, and none is refused.
        addCapped(shown, chatId, SHOWN_MAX)
        running?.wake()
    } else {
        shown.delete(chatId)
        running?.unask(chatId)
    }
}

export async function homePage(tg: TelegramClient, view: HomeView, signal: AbortSignal, emit: (page: HomePage) => void): Promise<HomePage> {
    const r = await rules()
    // Home keeps its own floor so trailers and samples do not fill Recently added (SizeFilter.HOME_MIN).
    const homeMin = r.sizeHomeMin(view.minBytes)
    const within = (v: Video) => r.sizeMatches(v.size, homeMin, view.maxBytes)
    // The chats the viewer added (HX27), or null for every chat: narrows the rows Home builds
    // (starred chat rows, Recently added) and the search across chats it reads for the carousel.
    const gate = view.added ? new Set(view.added) : null
    // A starred chat keeps its row whether or not it was added (HX86): a star is the viewer's own act.
    const stars = new Set(view.starred)
    const allowed = (id: number) => !gate || gate.has(id) || stars.has(id)
    // Settings > Home (HomeLayout): a row that is off is not built and nothing is read for it.
    const layout = view.layout ?? ALL_ON
    const needsRecent = layout.carousel || layout.recentlyAdded
    // The chats Recently added reads one by one: the added ones (HomeLayout.chosenChats), none before the picker is answered.
    const chosenWanted = !needsRecent ? [] : (view.added ?? [])
    // Looked up by id once the list is in, for those it does not carry (an archived chat).
    let chosenLooked = chosenWanted.length === 0
    const items = new Map<string, VideoItem>()
    let resumes: WebResume[] | null = null
    // Continue's cards are named as a chat's tiles are ("Long Road (2019)", not its file name).
    const resumeTitles = new Map<string, string>()
    const gone: HomePage['gone'] = []
    let recent: Video[] | null = null
    const loaded = new Map<number, { chatId: number; videos: Video[]; complete: boolean }>()
    // Chats whose read failed (not a chat this account lost): Home says how many could not be read.
    const failed = new Set<number>()
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
    // Some chat rows are waiting to be seen: the call then stays open for them (see the end).
    let deferred = false
    const queue: number[] = []
    const loads: Promise<void>[] = []
    let active = 0
    const pump = () => {
        while (active < PARALLEL && queue.length && !signal.aborted) {
            const id = queue.shift()!
            active++
            loads.push(
                readWithin(newestOf(tg, id), ROW_READ_MS)
                    .then(
                        (got) => {
                            for (const [k, v] of got.items) items.set(k, v)
                            loaded.set(id, { chatId: id, videos: got.videos.filter(within), complete: got.complete })
                        },
                        // A chat that cannot be read has nothing for Home: its row goes rather than waiting for ever.
                        (e) => {
                            if (!chatGone(e)) failed.add(id)
                            loaded.set(id, { chatId: id, videos: [], complete: true })
                        },
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
        return [...listed, ...[...early.values()].filter((c) => !ids.has(c.id))].filter((c) => allowed(c.id)).map((c) => ({ id: c.id, title: c.title, saved: c.self, pinned: c.pinned }))
    }

    const build = (): HomePage => {
        const chats = chatsNow()
        const byId = new Map(chats.map((c) => [c.id, c]))
        // The chats Home picks for itself from the search across chats are only known once the whole list is.
        const plan = r.homePlan(chats, view.starred, listEnough() ? recent : null, layout, view.added ?? null, listEnough() && chosenLooked)
        const picked = plan.picked
        // Chosen chats past the first few are not read one by one: the search across chats counts them.
        const chosenLate = new Set((plan.recentFrom ?? []).slice(CHOSEN_WAIT))
        // A chat row below the first screens waits until it has stayed on screen (HX54): a hard scroll over
        // forty rows is not forty reads. What Recently added or the carousel needs is read at once.
        const needed = new Set(plan.recentFrom ?? [])
        const wanted = picked.filter((id) => !chosenLate.has(id))
        const reads = wanted.filter((id, i) => i < EAGER_ROWS || needed.has(id) || shown.has(id) || asked.has(id))
        deferred = reads.length < wanted.length
        for (const id of reads) {
            if (asked.has(id)) continue
            asked.add(id)
            queue.push(id)
        }
        pump()
        // An id the rules picked that the chats here do not carry is left out, never passed on as undefined.
        const pickedChats = picked.flatMap((id) => byId.get(id) ?? [])
        const built = r.home(resumes ?? [], pickedChats, view.starred, [...loaded.values()], recent, plan.autoPick && !listEnough(), layout, plan.recentFrom ?? null, plan.noneChosen)
        // The copies a row's cards choose among (HX38), looked up only when a card has more than one.
        const videoOf = lookup((function* () {
            for (const l of loaded.values()) yield* l.videos
            if (recent) yield* recent
        })())
        const rows: HomeRow[] = built.map((row) => ({
            key: row.key,
            kind: row.kind as HomeRow['kind'],
            chatId: row.chatId ?? null,
            title: row.title ?? null,
            loaded: row.loaded,
            starredOnly: row.starredOnly,
            hasMore: row.hasMore,
            asRow: row.asRow,
            noneChosen: row.noneChosen,
            tiles:
                row.kind === 'continue'
                    ? row.resumes.flatMap((id): Tile[] => {
                          const found = items.get(id)
                          const item = found && { ...found, title: resumeTitles.get(id) ?? found.title }
                          return item ? [{ key: item.key, kind: 'file', title: item.title, item, seasons: 0, episodes: 0, copies: 0, versions: 1, parts: 0, pieces: 0, extra: null }] : []
                      })
                    : tilesOf(row, items, view, { r, videoOf }),
        }))
        // Recently added's See all reads on through the same chats, and says "in starred chats" as the row does.
        if (listEnough() && recent !== null) rememberHomeChats(plan.recentFrom ?? pickedChats.map((c) => c.id), rows.find((row) => row.kind === 'recent')?.starredOnly ?? false)
        const settled = resumes !== null && recent !== null && listEnough() && chosenLooked && reads.every((id) => loaded.has(id))
        return { rows, gone, selfId, settled, failed: failed.size }
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
        // Continue watching off: nothing part way is read for Home.
        if (!layout.continueWatching) return void (resumes = [])
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
                    const item = video && itemOf(msg, video.title, r.posterAsk(video), r.continueTitle(video), undefined, r.tileTag(video))
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
        // Neither Recently added nor the carousel is on: no search across chats.
        if (!needsRecent) return void (recent = [])
        try {
            const got = await across(tg, '', gate)
            for (const [k, v] of got.items) items.set(k, v)
            recent = got.videos.filter(within)
        } catch {
            recent = []
        }
    }

    // Chosen (or added) chats the list does not carry, an archived one for instance, by id, at most
    // HomeLayout.CHOSEN_MAX, once the list is in: without this such a chat was never read for Home.
    const readChosen = async () => {
        if (chosenLooked) return
        await enoughCame
        const listed = new Set(knownChats().chats.map((c) => c.id))
        const missing = chosenWanted.filter((id) => !listed.has(id) && !early.has(id)).slice(0, r.homeChosenMax())
        for (let i = 0; i < missing.length && !signal.aborted; i += PARALLEL) {
            await Promise.all(
                missing.slice(i, i + PARALLEL).map(async (id) => {
                    const peer = await tg.getPeer(id).catch(() => null)
                    if (peer) early.set(id, chatItem(peer, false))
                }),
            )
        }
        chosenLooked = true
    }

    const readChats = async () => {
        await chatList(tg, false, () => {
            if (listEnough()) enough()
            schedule()
        }).catch(() => {})
        chatsDone = true
        enough()
    }

    const mine = {
        // A row came on screen: build again, which asks for it.
        wake: () => {
            if (signal.aborted || finished) return
            build()
            schedule()
        },
        // A row left before its read began: it is not read.
        unask: (id: number) => {
            const at = queue.indexOf(id)
            if (at < 0) return
            queue.splice(at, 1)
            asked.delete(id)
        },
    }
    running = mine
    try {
        const first = Promise.all([readResumes(), readEarly()]).then(schedule)
        // The rest of the list goes on loading behind Home, for the Chats page; Home waits for
        // the first LIST_ENOUGH of it only.
        void readChats()
        await Promise.all([first, readRecent().then(schedule), enoughCame.then(schedule), readChosen().then(schedule)])
        // Home's chats come out of each build; wait for them until a build asks for none new.
        for (;;) {
            if (signal.aborted) throw new DOMException('aborted', 'AbortError')
            const before = asked.size
            build()
            await Promise.all(loads)
            if (asked.size === before && !queue.length) break
        }
        const last = build()
        if (!deferred) return last
        // Rows below the first screens are read when they are seen: this Home stays open for them, sending each
        // page as it fills, until the page asks again or leaves (the call is aborted then).
        emit(last)
        return await new Promise<HomePage>((_, reject) => signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')), { once: true }))
    } finally {
        finished = true
        clearTimeout(timer)
        if (running === mine) running = null
    }
}
