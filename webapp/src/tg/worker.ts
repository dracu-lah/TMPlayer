// The Telegram connection: MTProto, crypto, the session and every file location live here, off the
// main thread. The page talks to it through `api` below by message (./rpc.ts), and never loads
// mtcute itself.
import { BaseTelegramClient, SentCode as MtSentCode, TelegramClient, User, type Message, type tl } from '@mtcute/web'
import { rules, type Rules, type Video } from '../rules'
import { holdTouches } from './touchCache'
import { cryptoProvider } from './crypto'
import { findChatSubtitles, forgetChatSubtitles } from './chatsubs'
import { homePage, rowShown } from './home'
import { chatPage, forgetAll, joinedFor, listTiles, searchAll, setCopyContext, videoCounts, watchInfo } from './library'
import { archivedList, chatList, rankChats, topicSources } from './dialogs'
import { recentPage } from './recent'
import { messageLink, telegramLook, type LinkAnswer } from './links'
import { findTopics, keepWhole, keptTopics, telegramTopics, topicWords } from './topics'
import { clickSponsored, reportSponsored, viewSponsored } from './sponsored'
import { deleteEverything, deleteMessages, readFiles, writeFile, type FoundFile } from './syncfile'
import { chatProtects, files, itemOf, rawOf, thumbnails, toItem } from './media'
import { storageOfWorker } from '../ui/accountBook'
import type { ChatInfo, ChatItem, ChatPage, ChatSubtitle, ChatsEvent, ChatView, Country, Found, HomePage, HomeView, ListView, Me, RecentPage, QrEvent, RpcError, SentCode, SponsoredReport, Tile, TopicsFound, VideoItem, WatchInfo } from './types'

// `VITE_TG_TEST=1 pnpm dev` signs in on Telegram's test servers (numbers 99966XYYYY, the code
// is X five times), with its own storage, to try the sign in steps without a real account.
const testMode = import.meta.env.DEV && import.meta.env.VITE_TG_TEST === '1'

const base = new BaseTelegramClient({
    apiId: __TG_API_ID__,
    apiHash: __TG_API_HASH__,
    crypto: cryptoProvider(),
    // The profile's own session (HX88), from the name the page gave this worker: the adopted profile's is `tmplayer` as always.
    storage: storageOfWorker(self.name, testMode),
    testMode,
    initConnectionOptions: {
        deviceModel: 'TMPlayer Web',
        appVersion: '2.0',
        systemVersion: navigator.userAgent.includes('iPhone') ? 'iOS' : 'Web',
    },
    network: {
        // Three download connections per DC, as Telegram's own web clients use for a free
        // account; more only invites flood waits.
        connectionCount: (kind, _dc, isPremium) => {
            if (kind === 'download') return isPremium ? 6 : 3
            if (kind === 'downloadSmall') return 2
            if (kind === 'upload') return 2
            return 0
        },
        inactivityTimeout: 300_000,
    },
})
const tg = new TelegramClient({ client: base })
// The line to Telegram as mtcute reports it, for the page's "Can't reach Telegram" banner (net.ts).
base.onConnectionState.add((link) => postMessage({ link }))

/** How long a sign out waits for Telegram before forgetting the account on this device anyway. */
const LOG_OUT_WAIT_MS = 5_000
/** A 1 MiB read that has not answered by now is asked again (see chunk): slower than 35 KB/s plays nothing anyway. */
const CHUNK_TIMEOUT_MS = 30_000
/** A profile's picture is kept in the page's storage: a small one only. */
const PHOTO_MAX = 60_000

// The app's rules start loading beside the connection, so the first list does not wait for them.
const rulesStarted = performance.now()
rules().then(
    (r) => {
        if (import.meta.env.DEV) {
            const ms = Math.round(performance.now() - rulesStarted)
            console.info(`[rules] ready in ${ms} ms`, JSON.stringify(r.parse('Harbour.Notes.S01E02.1080p.mkv', null)))
        }
    },
    (e) => console.warn('[rules] not loaded', e),
)

/** Language catalogs handed to the rules, so each is fetched once. */
const spoken = new Set<string>()

/**
 * Runs `run` with the rules speaking `tag`: the page's own catalog for that language (the hashed
 * file `url` the page has already fetched, so it comes from the cache) laid over English, key by
 * key. A tag or address that is not the page's own, or a catalog that does not come, leaves
 * English. Nothing awaits between switching and switching back, so no other rule call ever runs
 * in the other language.
 */
async function inLanguage<T>(tag: string, url: string | null, run: (r: Rules) => T): Promise<T> {
    const r = await rules()
    let use = 'en'
    if (typeof tag === 'string' && /^[a-z]{2,3}(-[A-Za-z]{2,4})?$/.test(tag) && tag !== 'en' && typeof url === 'string' && sameOrigin(url)) {
        try {
            if (!spoken.has(tag)) {
                const answer = await fetch(url)
                if (answer.ok) {
                    r.provideCatalog(tag, await answer.text())
                    spoken.add(tag)
                }
            }
            if (spoken.has(tag)) use = tag
        } catch (e) {
            console.warn('[rules] catalog', tag, e)
        }
    }
    r.useLanguage(use)
    try {
        return run(r)
    } finally {
        r.useLanguage('en')
    }
}

/** An address on this site: what the page hands over is never fetched from elsewhere. */
function sameOrigin(url: string): boolean {
    try {
        return new URL(url, self.location.href).origin === self.location.origin
    } catch {
        return false
    }
}

/** What a call gets besides its arguments: its abort signal, and a way to talk to the page while it runs. */
export interface Ctx {
    signal: AbortSignal
    /** Sends the page an event for this call. */
    emit(data: unknown): void
    /** Sends the page an event and waits for its answer. */
    ask<T>(data: unknown): Promise<T>
}

function isUnauthorized(e: unknown): boolean {
    const text = String((e as { text?: string })?.text ?? e)
    return (e as { code?: number })?.code === 401 || /AUTH_KEY_UNREGISTERED|SESSION_REVOKED|USER_DEACTIVATED|AUTH_KEY_INVALID/.test(text)
}

const me = (u: User): Me => ({ id: u.id, name: u.displayName, username: u.username ?? null })

/** An error the page can tell apart by its text, as Telegram's own are. */
const rpcLike = (text: string) => Object.assign(new Error(text), { text })

/** Digits only: the raw auth calls take the number without "+" or spaces. */
const digitsOf = (phone: string) => phone.replace(/\D/g, '')

function sentCode(raw: tl.auth.TypeSentCode): SentCode | 'success' {
    if (raw._ === 'auth.sentCodeSuccess') return 'success'
    // Telegram wants a paid sign in here; a QR code from the app on the phone still works.
    if (raw._ === 'auth.sentCodePaymentRequired') throw rpcLike('PAYMENT_REQUIRED')
    const code = new MtSentCode(raw)
    const type = raw.type
    return {
        hash: code.phoneCodeHash,
        type: code.type,
        next: code.nextType,
        timeout: code.timeout,
        length: code.length,
        email: type._ === 'auth.sentCodeTypeEmailCode' ? type.emailPattern : null,
        beginning: code.beginning ?? null,
    }
}

/** A sent code, or the user when Telegram signed in without one. */
async function sentOrMe(raw: tl.auth.TypeSentCode): Promise<{ sent: SentCode } | { me: Me }> {
    const sent = sentCode(raw)
    if (sent !== 'success') return { sent }
    return { me: me(new User(await tg.notifyLoggedIn((raw as tl.auth.RawSentCodeSuccess).authorization))) }
}

const loginSetup = (phone: string, hash: string): tl.RawEmailVerifyPurposeLoginSetup => ({
    _: 'emailVerifyPurposeLoginSetup',
    phoneNumber: digitsOf(phone),
    phoneCodeHash: hash,
})

let countryList: Country[] | null = null

const warmedDcs = new Set<number>()

/** Bytes cut out of a larger buffer, as their own buffer so they can be handed over without a copy. */
const own = (b: Uint8Array): ArrayBuffer => (b.byteOffset === 0 && b.byteLength === b.buffer.byteLength ? b.buffer : b.slice().buffer) as ArrayBuffer

export const api = {
    /**
     * Who this browser's saved session belongs to, or null when it has none, read from storage
     * without asking Telegram, so the page paints at once. `whoami` then checks with Telegram.
     */
    async me(_: Ctx): Promise<Me | null> {
        await tg.prepare()
        const self = await tg.storage.self.fetch()
        return self ? { id: self.userId, name: null } : null
    },

    /** The signed in user as Telegram has it now; a 401 (ended on another device) signs the page out. */
    /**
     * The signed in account's small Telegram photo, for its profile's round picture on the picker
     * (HX88); null when it has none, or one too big to keep beside the profile.
     */
    async myPhoto(_: Ctx): Promise<ArrayBuffer | null> {
        const small = (await tg.getMe()).photo?.small
        if (!small) return null
        const bytes = await tg.downloadAsBuffer(small)
        return bytes.byteLength > 0 && bytes.byteLength <= PHOTO_MAX ? own(bytes) : null
    },

    async whoami(_: Ctx): Promise<Me | null> {
        try {
            return me(await tg.getMe())
        } catch (e) {
            if (isUnauthorized(e)) return null
            throw e
        }
    },

    async signInQr(ctx: Ctx): Promise<Me> {
        const emit = (e: QrEvent) => ctx.emit(e)
        return me(
            await tg.signInQr({
                abortSignal: ctx.signal,
                onUrlUpdated: (url, expires) => emit({ kind: 'url', url, expires: expires.getTime() }),
                onQrScanned: () => emit({ kind: 'scanned' }),
                password: async () => ctx.ask<string>({ kind: 'password', hint: await tg.getPasswordHint().catch(() => null) } satisfies QrEvent),
                invalidPasswordCallback: () => emit({ kind: 'badPassword' }),
            }),
        )
    },

    /** Telegram's country list for the number, kept for the session. */
    async countries(_: Ctx): Promise<Country[]> {
        if (!countryList) {
            const res = await tg.call({ _: 'help.getCountriesList', langCode: 'en', hash: 0 })
            countryList =
                res._ === 'help.countriesList'
                    ? res.countries
                          .filter((c) => !c.hidden && c.countryCodes.length)
                          .map((c) => ({
                              iso: c.iso2,
                              name: c.name ?? c.defaultName,
                              codes: c.countryCodes.map((k) => ({ code: k.countryCode, prefixes: k.prefixes ?? [], patterns: k.patterns ?? [] })),
                          }))
                          .sort((a, b) => a.name.localeCompare(b.name, 'en'))
                    : []
        }
        return countryList
    },

    /** The country of the address the connection comes from: Telegram's guess, as the apps start on. */
    async guessCountry(_: Ctx): Promise<string | null> {
        return (await tg.call({ _: 'help.getNearestDc' })).country || null
    },

    async sendCode(_: Ctx, phone: string): Promise<{ sent: SentCode } | { me: Me }> {
        const { id, hash } = await tg.getApiCredentials()
        return sentOrMe(
            await tg.call({ _: 'auth.sendCode', phoneNumber: digitsOf(phone), apiId: id, apiHash: hash, settings: { _: 'codeSettings' } }),
        )
    },

    /** The code again, the way `next` said (a text after the app, a call after a text). */
    async resendCode(_: Ctx, phone: string, hash: string): Promise<{ sent: SentCode } | { me: Me }> {
        return sentOrMe(await tg.call({ _: 'auth.resendCode', phoneNumber: digitsOf(phone), phoneCodeHash: hash }))
    },

    /** Lets Telegram drop a code nobody will type (a changed number, Start over). */
    async cancelCode(_: Ctx, phone: string, hash: string): Promise<void> {
        await tg.call({ _: 'auth.cancelCode', phoneNumber: digitsOf(phone), phoneCodeHash: hash }).catch(() => {})
    },

    /** The code from the app, a text or a call, or one sent by email when `email` is set. */
    async signIn(_: Ctx, phone: string, hash: string, code: string, email: boolean): Promise<Me> {
        const res = await tg.call({
            _: 'auth.signIn',
            phoneNumber: digitsOf(phone),
            phoneCodeHash: hash,
            ...(email ? { emailVerification: { _: 'emailVerificationCode', code } } : { phoneCode: code }),
        } as tl.auth.RawSignInRequest)
        // A number with no account: TMPlayer does not create one.
        if (res._ === 'auth.authorizationSignUpRequired') throw rpcLike('PHONE_NUMBER_UNOCCUPIED')
        return me(new User(await tg.notifyLoggedIn(res)))
    },

    /** An account Telegram signs in by email only, with no address yet: a code to the one given. */
    async sendEmailCode(_: Ctx, phone: string, hash: string, email: string): Promise<{ email: string; length: number }> {
        const res = await tg.call({ _: 'account.sendVerifyEmailCode', purpose: loginSetup(phone, hash), email })
        return { email: res.emailPattern, length: res.length }
    },

    /** That address's code; Telegram then sends the sign in code itself, or signs in. */
    async verifyEmail(_: Ctx, phone: string, hash: string, code: string): Promise<{ sent: SentCode } | { me: Me }> {
        const res = await tg.call({
            _: 'account.verifyEmail',
            purpose: loginSetup(phone, hash),
            verification: { _: 'emailVerificationCode', code },
        })
        if (res._ !== 'account.emailVerifiedLogin') throw rpcLike('AUTH_RESTART')
        return sentOrMe(res.sentCode)
    },

    async passwordHint(_: Ctx): Promise<string | null> {
        return tg.getPasswordHint().catch(() => null)
    },

    async checkPassword(_: Ctx, password: string): Promise<Me> {
        return me(await tg.checkPassword(password))
    },

    async logOut(_: Ctx): Promise<void> {
        // A session Telegram ended answers auth.logOut with a 401, or over a line that will not
        // come up never answers (HX23): either way this device forgets the account, rather than
        // keeping a session that opens signed in and is refused on every call.
        const told = await Promise.race([
            tg.logOut().then(() => true, () => false),
            new Promise<boolean>((r) => setTimeout(() => r(false), LOG_OUT_WAIT_MS)),
        ])
        if (!told) await tg.notifyLoggedOut().catch(() => {})
        files.clear()
        thumbnails.clear()
        forgetAll()
        warmedDcs.clear()
    },

    /** Every chat outside the archive, pinned first; pages of it arrive as `ChatsEvent`s while it loads. */
    async chats(ctx: Ctx, refresh: boolean): Promise<ChatItem[]> {
        return chatList(tg, refresh, (chats) => ctx.emit({ chats } satisfies ChatsEvent))
    },

    /** Every chat in Telegram's archive (HX32), for the Add chats picker; pages arrive as `ChatsEvent`s. */
    async archivedChats(ctx: Ctx): Promise<ChatItem[]> {
        return archivedList(tg, (chats) => ctx.emit({ chats } satisfies ChatsEvent))
    },

    /** Ids of the chats whose names answer `query`, best first. */
    async rankChats(_: Ctx, query: string): Promise<number[]> {
        return rankChats(query)
    },

    /** The Add chats picker's rough video count for each listed chat, three at a time (see library.ts videoCounts). */
    async videoCounts(ctx: Ctx, chatIds: number[]): Promise<Record<number, number>> {
        return videoCounts(tg, chatIds, ctx.signal)
    },

    /** A chat's grid as the app arranges it (see library.ts for the modes). */
    async chat(ctx: Ctx, chatId: number, view: ChatView, mode: 'open' | 'more' | 'refresh' | 'view'): Promise<ChatPage> {
        // The page left this chat or topic (LS-28): the call ends at once and is not waited for. The chat's own read goes on into its
        // kept state, one at a time per chat, so coming back finds it read; nothing else waits on it.
        ctx.signal.throwIfAborted()
        return Promise.race([
            chatPage(tg, chatId, view, mode),
            new Promise<never>((_, reject) => ctx.signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')), { once: true })),
        ])
    },

    /** Videos across every chat within the size limits, grouped as a chat's are, newest first. */
    async search(_: Ctx, query: string, view: ListView, everyChat = false): Promise<Found> {
        return searchAll(tg, query, view, everyChat)
    },

    /**
     * Topics whose names answer `query` ("Chat > Topic", U14), among the forum chats of `added` (null: every chat),
     * inside the apps' budget (topics.ts). Groups answer one by one as `TopicsFound` events; aborting the call
     * (a new letter, leaving the page) stops the asking. Answers are kept two minutes.
     */
    async topics(ctx: Ctx, query: string, added: number[] | null): Promise<TopicsFound> {
        const words = topicWords(query)
        if (!words) return { hits: [], asked: 0, refused: 0, partial: false, searching: false }
        const chats = Array.isArray(added) ? added.filter((id) => Number.isSafeInteger(id)).slice(0, 20_000) : null
        const key = `${chats ? [...chats].sort((a, b) => a - b).join() : '*'}\n${words.toLowerCase()}`
        const kept = keptTopics(key)
        if (kept) return kept
        const [r, sources] = await Promise.all([rules(), topicSources(tg, chats, ctx.signal)])
        ctx.signal.throwIfAborted()
        const found = await findTopics({ rules: r, ask: telegramTopics(tg), ...sources, added: chats, includeArchived: true, query: words, signal: ctx.signal, onPartial: (f) => ctx.emit(f) })
        // A search in which a group was skipped (a flood wait past thirty seconds, a refusal) is not kept: asking again asks again.
        keepWhole(key, found)
        return found
    },

    /** Recently added's See all, read on a page at a time (see library.ts recentPage for the modes). */
    async recent(ctx: Ctx, view: ListView, mode: 'open' | 'more' | 'view'): Promise<RecentPage> {
        return recentPage(tg, view, mode, ctx.signal)
    },

    /** Home's rows as the app builds them; Home so far arrives as `HomePage` events while the chats answer. */
    async home(ctx: Ctx, view: HomeView): Promise<HomePage> {
        return homePage(tg, view, ctx.signal, (page) => ctx.emit(page))
    },

    /** A Home row stayed on screen or left it (HX54): the open Home reads it, or drops its read that has not started. */
    async homeRow(_: Ctx, chatId: number, on: boolean): Promise<void> {
        rowShown(chatId, on)
    },

    /** A sponsored message was on screen: Telegram counts it once. */
    async sponsoredViewed(_: Ctx, id: string): Promise<void> {
        await viewSponsored(tg, id)
    },

    /** A press on a sponsored message, told to Telegram before its link opens. */
    async sponsoredClicked(_: Ctx, id: string, media: boolean): Promise<void> {
        await clickSponsored(tg, id, media)
    },

    /** Reports a sponsored message; `option` empty first, then the reason picked from Telegram's list. */
    async sponsoredReport(_: Ctx, id: string, option: number[]): Promise<SponsoredReport> {
        return reportSponsored(tg, id, option)
    },

    /** The size switch's numbers and words: "Hide small clips" hides under clipMin, Home leaves out under homeMin. */
    async sizeLimits(_: Ctx): Promise<{ clipMin: number; homeMin: number; ceiling: number; clipLabel: string; homeLabel: string }> {
        const r = await rules()
        const l = r.sizeLimits()
        return { clipMin: l.clipMin, homeMin: l.homeMin, ceiling: l.ceiling, clipLabel: r.sizeLabel(l.clipMin), homeLabel: r.sizeLabel(l.homeMin) }
    },

    /** "You'll see videos from 50 MB upwards." */
    async sizeRange(_: Ctx, minBytes: number, maxBytes: number): Promise<string> {
        return (await rules()).sizeRange(minBytes, maxBytes)
    },

    /** The Last played order's times (ids and milliseconds), held under `version`; a chat view or the watch page names the version instead of posting them. */
    async setTouches(_: Ctx, version: number, ids: string[], times: number[]): Promise<boolean> {
        return holdTouches(version, ids, times)
    },

    /** A chat's stored filter line as the Sort menu's chips read it (U6): each part the rules cannot read is that filter off. */
    async facets(_: Ctx, line: string): Promise<{ length: string; picture: string; hdr: boolean; subtitles: boolean; language: string | null; added: string; count: number }> {
        const f = (await rules()).facetsRead(line.slice(0, 200))
        return { length: f.length, picture: f.picture, hdr: f.hdr, subtitles: f.subtitles, language: f.language ?? null, added: f.added, count: f.count }
    },

    /** `line` with one filter changed, as the line the page stores ("clear" drops them all). */
    async facetsWith(_: Ctx, line: string, field: string, value: string | null): Promise<string> {
        return (await rules()).facetsWith(line.slice(0, 200), field, value)
    },

    /**
     * Which of the loaded videos "Play random" plays (U16, the apps' rule): an unwatched one while
     * any is left, never `last` again while another is there. `ids` are the videos on screen, so a
     * chat of 20,000 costs nothing extra; null for none.
     */
    async randomPick(_: Ctx, ids: string[], watched: string[], last: string | null): Promise<string | null> {
        // The rule reads a video's id and nothing else of it.
        const videos = ids.slice(0, 50_000).map((id) => ({ id }) as Video)
        return (await rules()).playRandom(videos, watched.slice(0, 200_000), last) ?? null
    },

    /**
     * The ids of the rows the apps' Settings search finds for `query` among `only` (the rows this
     * page draws), best first, in the language `tag` so a viewer searches in the words the page
     * shows (U20, WB20).
     */
    async settingsSearch(_: Ctx, query: string, form: string, only: string[], tag: string, url: string | null): Promise<string[]> {
        const ids = only.slice(0, 200).filter((id) => typeof id === 'string' && id.length < 64)
        return inLanguage(tag, url, (r) => [...r.settingsSearch(String(query).slice(0, 200), form === 'phone' ? 'phone' : 'desktop', ids)])
    },

    /** What the apps' "What's new" sheet says, in the language `tag`. */
    async whatsNew(_: Ctx, tag: string, url: string | null): Promise<{ version: string; changelog: string; highlights: string[]; sections: { title: string; lines: string[] }[] }> {
        return inLanguage(tag, url, (r) => {
            const n = r.whatsNew()
            return {
                version: n.version,
                changelog: n.changelog,
                highlights: [...n.highlights],
                sections: [...n.sections].map((x) => ({ title: x.title, lines: [...x.lines] })),
            }
        })
    },

    /** Settings' rewind choices in milliseconds, 0 for off, and the default. */
    async rewindChoices(_: Ctx): Promise<{ choices: number[]; fallback: number }> {
        const r = await rules()
        return { choices: r.rewindChoices(), fallback: r.rewindSetting(null) }
    },

    /**
     * A chat's info page (the apps' ChatInfoSheet): its large picture, kind, member count, public
     * username, description and t.me link. The name, kind and picture come from the peer the list
     * already has, so a failed second call still leaves the page its top.
     */
    async chatInfo(_: Ctx, chatId: number): Promise<ChatInfo> {
        const peer = await tg.getPeer(chatId)
        const self = peer.type === 'user' && peer.isSelf
        const big = peer.photo?.big ?? peer.photo?.small ?? null
        if (big && !self) thumbnails.set(big.uniqueFileId, big)
        const kind: ChatInfo['kind'] = self ? 'saved' : peer.type === 'user' ? (peer.isBot ? 'bot' : 'user') : peer.chatType === 'channel' ? 'channel' : 'group'
        const info: ChatInfo = {
            title: peer.displayName,
            kind,
            photo: big && !self ? big.uniqueFileId : null,
            members: null,
            username: peer.username ?? null,
            about: null,
            link: null,
            failed: false,
        }
        if (self) return info
        try {
            if (peer.type === 'user') {
                info.about = (await tg.getFullUser(chatId)).bio || null
            } else {
                const full = await tg.getFullChat(chatId)
                info.about = full.bio || null
                info.members = full.membersCount || null
            }
        } catch {
            info.failed = true
        }
        if (info.username) info.link = `https://t.me/${info.username}`
        return info
    },

    /** The link to a video's message (WB16, the apps' Copy link): public or private form, its forum topic between; or why there is none. */
    async messageLink(_: Ctx, chatId: number, messageId: number): Promise<LinkAnswer> {
        return messageLink(telegramLook(tg), await rules(), chatId, messageId)
    },

    /** A named list's cards (HX43), grouped as a chat's grid, from the videos the page keeps. */
    async listTiles(_: Ctx, lines: { item: VideoItem; show?: string }[]): Promise<Tile[]> {
        return listTiles(lines)
    },

    /**
     * Resume across devices (src/sync): every sync file in the viewer's own Saved Messages, read.
     * Asked for only once the viewer turned the sync on. `known` is the message the last sync used.
     */
    async syncFiles(_: Ctx, known: number): Promise<FoundFile[]> {
        return readFiles(tg, known)
    },

    /** The sync file written: `target` edited in place, else sent once and pinned. `gone`: the message was deleted meanwhile. */
    async syncWrite(_: Ctx, target: number | null, bytes: Uint8Array, caption: string): Promise<{ id: number } | { gone: true }> {
        return writeFile(tg, target, bytes, caption)
    },

    /** Other copies of the sync file, once merged in. */
    async syncDelete(_: Ctx, ids: number[]): Promise<void> {
        await deleteMessages(tg, ids)
    },

    /** "Turn off and delete the sync file": the file and an older TMPlayer's message gone from Saved Messages. */
    async syncDeleteAll(_: Ctx, known: number): Promise<boolean> {
        return deleteEverything(tg, known)
    },

    /** What the page says of the viewer's copies (HX38): the device, what the browser cannot play natively, "Not the same video", the copies chosen. */
    async copyContext(_: Ctx, context: unknown): Promise<void> {
        await setCopyContext(context)
    },
    /** The quality line (QualityHabit) with one more play at `height` noted: what the page keeps as the quality usually played. */
    async habitNoted(_: Ctx, habit: string | null, height: number | null): Promise<string> {
        const h = typeof height === 'number' && Number.isSafeInteger(height) && height > 0 && height <= 8_640 ? height : null
        return (await rules()).habitNoted(typeof habit === 'string' ? habit.slice(0, 400) : null, h)
    },
    /** One message's video, for a watch page opened by its address. Null when it is gone or not a video. */
    async video(_: Ctx, chatId: number, messageId: number): Promise<VideoItem | null> {
        const [msg] = await tg.getMessages(chatId, messageId)
        if (!msg) return null
        // Through the rules as a list's videos are, so its card name and poster question match theirs.
        const r = await rules()
        const raw = rawOf(msg as Message, chatProtects(msg as Message))
        const [v] = raw ? r.screen([raw]).videos : []
        return (v && itemOf(msg as Message, v.title, r.posterAsk(v), r.continueTitle(v), undefined, r.tileTag(v))) || toItem(msg as Message)
    },

    /** The pieces of the video a message is one piece of (a cut video, a split ZIP), for a player opened on that message alone. Null when it is not a set. */
    async joined(_: Ctx, chatId: number, messageId: number): Promise<VideoItem | null> {
        return joinedFor(tg, chatId, messageId)
    },

    /** The watch page's parts round the player: episodes and the ones either side, or more from the chat. Null when the message is gone. */
    async watch(_: Ctx, chatId: number, messageId: number, view: ChatView): Promise<WatchInfo | null> {
        return watchInfo(tg, chatId, messageId, view)
    },

    /** Where a video saved at `savedMs` opens, and what to keep when it is left at `positionMs` (the app's ResumeRewind). */
    /** The subtitle files posted next to a video, best first (the apps' ChatSubtitles), from one search of its chat. */
    async chatSubtitles(_: Ctx, chatId: number, messageId: number, fileName: string, caption: string, preferred: string[]): Promise<ChatSubtitle[]> {
        return findChatSubtitles(tg, chatId, messageId, fileName, caption, preferred)
    },

    /**
     * The keys a video's remembered online subtitle is filed under, most exact first (HX25, core's
     * SubtitlePickKeys): this post, then the video itself (a film's title and year, a show's episode),
     * so a re-post or another copy finds the pick. The page keeps the picks; the rule is core's.
     */
    async subtitlePickKeys(_: Ctx, chatId: number, messageId: number, title: string, fileName: string): Promise<string[]> {
        const r = await rules()
        return r.subtitlePickKeys({
            id: `${chatId}:${messageId}`,
            chatId,
            messageId,
            title: String(title).slice(0, 500),
            fileName: String(fileName).slice(0, 500),
            caption: '',
            mimeType: 'video/mp4',
            size: 0,
            duration: 0,
            width: 0,
            height: 0,
            date: 0,
            topicId: 0,
            albumId: null,
            albumPhoto: false,
            canBeSaved: true,
            round: false,
        })
    },

    /** A listed file that is no longer there: the next lookup of this video asks again. */
    async chatSubtitlesForget(_: Ctx, chatId: number, messageId: number): Promise<void> {
        forgetChatSubtitles(chatId, messageId)
    },

    async resumeStart(_: Ctx, savedMs: number, rewindMs: number | null): Promise<number> {
        const r = await rules()
        return r.resumeStart(savedMs, r.rewindSetting(rewindMs))
    },

    async resumeKept(_: Ctx, savedMs: number | null, positionMs: number, rewindMs: number | null): Promise<number> {
        const r = await rules()
        return r.resumeKept(savedMs, positionMs, r.rewindSetting(rewindMs))
    },

    /** Retry on the banner: the connections to Telegram are opened again, as when the browser says it is back online. */
    async reconnect(_: Ctx): Promise<void> {
        base.mt.network.notifyNetworkChanged(true)
    },

    /** Whether this worker knows where a file is (a page reloaded on a list item's state may outlive it). */
    async known(_: Ctx, key: string): Promise<boolean> {
        return files.has(key)
    },

    async thumb(_: Ctx, key: string): Promise<ArrayBuffer> {
        const t = thumbnails.get(key)
        if (!t) throw new Error('gone')
        return own(await tg.downloadAsBuffer(t))
    },

    /** Bytes [offset, offset + limit) of a file, `limit` being a power of two that does not cross 1 MiB. */
    async chunk(ctx: Ctx, key: string, offset: number, limit: number): Promise<ArrayBuffer> {
        let f = files.get(key)
        if (!f) throw new Error('gone')
        let refreshed = false
        for (;;) {
            try {
                const res = await tg.call(
                    { _: 'upload.getFile', location: f.location, offset, limit },
                    // A timeout, because mtcute's own is Infinity: a connection to a data centre that went quiet
                    // (idle for a while, a link that dropped without saying so) answers nothing and nothing
                    // fails, so the read hung for good ('This video hasn't started', Reload the same) until
                    // the page was closed. Timed out, the reader's own retries ask again over a fresh send.
                    // floodSleepThreshold 0: mtcute never sleeps through a flood wait of any length here. The error
                    // reaches the reader, which waits it out while the other reads go on, and the page says how long.
                    { dcId: f.dcId, kind: 'download', abortSignal: ctx.signal, floodSleepThreshold: 0, timeout: CHUNK_TIMEOUT_MS },
                )
                if (res._ !== 'upload.file') throw new Error('Telegram sent a CDN redirect, which browsers cannot follow.')
                const want = Math.min(limit, f.size - offset)
                return own(res.bytes.length > want ? res.bytes.subarray(0, want) : res.bytes)
            } catch (e) {
                // mtcute names it FILE_MIGRATE_%d and keeps the number apart.
                const err = e as { text?: string; newDc?: number }
                if (err.text?.startsWith('FILE_REFERENCE_') && !refreshed) {
                    // Telegram's file references run out after a while: the message read again carries a new one.
                    refreshed = true
                    const [msg] = await tg.getMessages(f.chatId, f.messageId)
                    if (!msg || !itemOf(msg as Message, '', null)) throw new Error('gone')
                    f = files.get(key)!
                    continue
                }
                if (err.text !== 'FILE_MIGRATE_%d' || !err.newDc) throw e
                f.dcId = err.newDc
            }
        }
    },

    /**
     * Opens the connection to each data centre holding the listed files (one 4 KB read), so the
     * first Play does not pay for the handshake. Once per data centre per session.
     */
    async warmDcs(_: Ctx, keys: string[]): Promise<void> {
        for (const key of keys) {
            const f = files.get(key)
            if (!f || warmedDcs.has(f.dcId)) continue
            warmedDcs.add(f.dcId)
            const dc = f.dcId
            tg.call({ _: 'upload.getFile', location: f.location, offset: 0, limit: 4096 }, { dcId: dc, kind: 'download' }).catch(() => warmedDcs.delete(dc))
        }
    },
}

export type Api = typeof api

// Messages from the page: a call, an abort of one, or the answer to a call's question.
type In =
    | { id: number; method: keyof Api; args: unknown[] }
    | { abort: number }
    | { reply: number; ask: number; value: unknown }

const running = new Map<number, { abort: AbortController; asks: Map<number, (v: unknown) => void> }>()

function errorOf(e: unknown): RpcError {
    const err = e as { message?: string; text?: string; code?: number; seconds?: number }
    return { message: err?.message ?? String(e), text: err?.text, code: err?.code, seconds: err?.seconds }
}

self.onmessage = async (ev: MessageEvent<In>) => {
    const msg = ev.data
    if ('abort' in msg) return running.get(msg.abort)?.abort.abort()
    if ('reply' in msg) {
        const call = running.get(msg.reply)
        call?.asks.get(msg.ask)?.(msg.value)
        call?.asks.delete(msg.ask)
        return
    }
    const { id, method, args } = msg
    const abort = new AbortController()
    const asks = new Map<number, (v: unknown) => void>()
    running.set(id, { abort, asks })
    let askId = 0
    const ctx: Ctx = {
        signal: abort.signal,
        emit: (data) => postMessage({ id, event: data }),
        // A question still open when the call is aborted (the page left the QR code waiting for a
        // password) fails, so the call ends instead of waiting for ever.
        ask: <T>(data: unknown) =>
            new Promise<T>((resolve, reject) => {
                const n = ++askId
                asks.set(n, resolve as (v: unknown) => void)
                abort.signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')), { once: true })
                postMessage({ id, event: data, ask: n })
            }),
    }
    try {
        const fn = api[method] as (ctx: Ctx, ...a: unknown[]) => Promise<unknown>
        if (!fn) throw new Error(`No such call: ${method}`)
        const value = await fn(ctx, ...args)
        postMessage({ id, value }, value instanceof ArrayBuffer ? [value] : [])
    } catch (e) {
        postMessage({ id, error: errorOf(e) })
    } finally {
        running.delete(id)
    }
}
