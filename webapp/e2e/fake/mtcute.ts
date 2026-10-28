// A stand-in for @mtcute/web in the end to end tests (e2e/vite.e2e.ts swaps it in), loaded by the
// real Telegram worker so its paging, the app's rules and the player run unchanged: one signed in
// account, a few chats, and the small files in e2e/media served as Telegram's file chunks.
// A test picks a case with the `e2e` cookie, which /__e2e/flags hands back to the worker:
// `signedout`, `revoked` (Telegram says the session ended), `slow` (lists answer after 3 s).

// Read synchronously (a worker may): a top-level await would hold back the worker's message
// handler, and the page's first call would be lost.
function get(url: string): string {
    const xhr = new XMLHttpRequest()
    xhr.open('GET', url, false)
    xhr.send()
    return xhr.responseText
}
const flags = new Set(get('/__e2e/flags').split(',').filter(Boolean))
const sizes: Record<string, number> = JSON.parse(get('/__e2e/sizes'))

const delay = (ms: number) => new Promise((r) => setTimeout(r, ms))
const rpcError = (text: string, code = 400) => Object.assign(new Error(text), { text, code })
const THUMB = '/__e2e/media/thumb.jpg'

export class User {
    constructor(public raw: unknown) {}
    get id() {
        return 1
    }
    get displayName() {
        return 'Dana Test'
    }
    get username() {
        return 'danatest'
    }
}
export class SentCode {}
export class WebCryptoProvider {
    constructor(_?: unknown) {}
}
export class BaseTelegramClient {
    constructor(_: unknown) {}
}
export class Photo {
    constructor(public raw: unknown) {}
    get thumbnails() {
        return [this.getThumbnail()]
    }
    getThumbnail() {
        return { uniqueFileId: 'sponsor-photo', url: THUMB }
    }
}
export function _messageMediaFromTl(_: unknown, m: unknown) {
    return m ? new Photo(m) : null
}

interface Peer {
    id: number
    displayName: string
    type: 'user' | 'chat'
    chatType: string
    username?: string | null
    about?: string
    members?: number
    isSelf: boolean
    isBot: boolean
    isForum: boolean
    photo: unknown
    raw: Record<string, unknown>
}

const peers = new Map<number, Peer>()
function peer(id: number, title: string, type: 'user' | 'chat', extra: Partial<Peer> = {}): Peer {
    const p: Peer = { id, displayName: title, type, chatType: 'supergroup', isSelf: false, isBot: false, isForum: false, photo: null, raw: {}, ...extra }
    peers.set(id, p)
    return p
}

interface Opts {
    /** The real file in e2e/media whose bytes it has; else a name only, and a size. */
    file?: string
    size?: number
    video?: boolean
    duration?: number
    ttl?: number
    withheld?: boolean
}

let n = 0
function msg(chat: Peer, name: string, o: Opts = {}) {
    const id = ++n
    const size = o.file ? sizes[o.file] : (o.size ?? 0)
    const thumb = { uniqueFileId: `thumb${id}`, url: THUMB }
    return {
        id,
        chat,
        date: new Date(Date.UTC(2026, 9, 1) + id * 3600_000),
        text: '',
        groupedId: null,
        media: {
            type: o.video ? 'video' : 'document',
            fileName: name,
            mimeType: name.endsWith('.pdf') ? 'application/pdf' : 'video/mp4',
            // `big`: every video says 1.5 GB, past the carousel's 400 MB floor (its bytes stay the real file's).
            fileSize: flags.has('big') && o.file ? 1_500_000_000 : size,
            uniqueFileId: `file${id}`,
            location: { _: 'fake', file: o.file ?? null },
            dcId: 2,
            duration: o.video ? (o.duration ?? 4) : 0,
            width: 320,
            height: 180,
            isRound: false,
            isAnimation: false,
            thumbnails: [thumb],
            getThumbnail: () => thumb,
        },
        raw: {
            _: 'message',
            noforwards: false,
            media: { _: 'messageMediaDocument', ttlSeconds: o.ttl, altDocuments: [] },
            restrictionReason: o.withheld ? [{ platform: 'all', reason: 'test', text: 'withheld' }] : undefined,
        },
        isVideo: !!o.video,
    }
}
type Msg = ReturnType<typeof msg>

const me = peer(1, 'Dana Test', 'user', { isSelf: true })
const clips = peer(-1001, 'Test Clips', 'chat', { chatType: 'channel', username: 'testclips', about: 'Short clips for trying the player: every container and codec the web player knows.', members: 12400 })
const shows = peer(-1002, 'Test Shows', 'chat')
const locked = peer(-1003, 'Locked Room', 'chat', { chatType: 'channel', raw: { noforwards: true } })
const quiet = peer(-1004, 'Quiet Room', 'chat')
const copies = peer(-1005, 'Test Copies', 'chat', { chatType: 'channel' })

const messages = new Map<number, Msg[]>([
    [
        clips.id,
        [
            msg(clips, 'Native Clip.mp4', { file: 'h264-aac.mp4', video: true }),
            msg(clips, 'Tail Index.mp4', { file: 'tail-index.mp4' }),
            msg(clips, 'Matroska Clip.mkv', { file: 'h264-aac.mkv' }),
            msg(clips, 'Transport Clip.ts', { file: 'h264-aac.ts' }),
            msg(clips, 'Surround Clip.mkv', { file: 'h264-ac3.mkv' }),
            msg(clips, 'Webm Clip.webm', { file: 'vp9-opus.webm' }),
            msg(clips, 'Old Clip.avi', { file: 'mpeg4.avi' }),
            msg(clips, 'Xvid Clip.avi', { file: 'xvid-mp3.avi' }),
            msg(clips, 'Divx Clip.avi', { file: 'divx3.avi' }),
            msg(clips, 'Avc Avi Clip.avi', { file: 'h264-mp3.avi' }),
            msg(clips, 'Windows Clip.wmv', { file: 'wmv2.wmv' }),
            msg(clips, 'Broken Clip.avi', { file: 'broken.avi' }),
            msg(clips, 'Odd Picture Clip.avi', { file: 'wmv2.avi' }),
            msg(clips, 'Odd Sound Clip.avi', { file: 'mpeg4-wma.avi' }),
            msg(clips, 'Cut Clip.avi', { file: 'cut.avi' }),
            msg(clips, 'Burning Clip.mp4', { file: 'h264-aac.mp4', ttl: 30 }),
            msg(clips, 'Withheld Clip.mp4', { file: 'h264-aac.mp4', withheld: true }),
            msg(clips, 'Notes.pdf', { size: 20_000 }),
        ],
    ],
    [shows.id, [1, 2, 3].map((e) => msg(shows, `Harbour.Notes.S01E0${e}.mkv`, { file: 'h264-aac.mkv' }))],
    [locked.id, [msg(locked, 'Locked Clip.mp4', { file: 'h264-aac.mp4' })]],
    [quiet.id, [msg(quiet, 'readme.pdf', { size: 2000 })]],
    [me.id, [msg(me, 'Saved Clip.mp4', { file: 'h264-aac.mp4' })]],
    // One film posted three times, as copies of it: the page lists them for choosing.
    [copies.id, ['2160p.HDR.HEVC', '1080p.BluRay.x264', '720p.WEB-DL.x264'].map((q) => msg(copies, `Big.Buck.Bunny.2008.${q}.mkv`, { file: 'h264-aac.mkv' }))],
])

// A chat of its own at the very end (opened by its address, /chat/-1006), made last so no other id moves: 20 s,
// chaptered, two episodes of one show, for the chapter, skip and autoplay tests.
const lighthouse = peer(-1006, 'Lighthouse Show', 'chat')
messages.set(
    lighthouse.id,
    ['Lighthouse.S01E01.mkv', 'Lighthouse.S01E02.mkv'].map((name) => msg(lighthouse, name, { file: 'chapters.mkv', video: true, duration: 20 })),
)

const dialogs: Peer[] = [me, clips, shows, locked, quiet, copies]
for (let i = 0; i < 1500; i++) dialogs.push(peer(-5000 - i, `Group ${i}`, 'chat'))
dialogs.push(lighthouse)

/** The raw calls made, readable in the worker as `fakeCalls`. */
const calls: string[] = []

export class TelegramClient {
    storage = { self: { fetch: async () => (flags.has('signedout') ? null : { userId: 1 }) } }
    constructor(_: unknown) {}
    async prepare() {}
    async getMe() {
        if (flags.has('revoked')) throw rpcError('AUTH_KEY_UNREGISTERED', 401)
        return new User({})
    }
    async getPasswordHint() {
        return null
    }
    async logOut() {}
    async resolvePeer(id: number) {
        return { _: 'inputPeerChannel', channelId: id, accessHash: 0 }
    }
    async getFullChat(id: number) {
        const p = peers.get(id)
        if (!p) throw rpcError('PEER_ID_INVALID')
        if (flags.has('infofail')) throw rpcError('CHANNEL_PRIVATE')
        return { bio: p.about ?? '', membersCount: p.members ?? 0 }
    }
    async getFullUser(id: number) {
        if (!peers.has(id)) throw rpcError('PEER_ID_INVALID')
        return { bio: '' }
    }
    async getPeer(id: number) {
        const p = peers.get(id)
        if (!p) throw rpcError('PEER_ID_INVALID')
        return p
    }
    async call(q: { _: string; location?: { file: string | null }; offset?: number; limit?: number; option?: Uint8Array }) {
        calls.push(q._)
        if (q._ === 'upload.getFile') {
            const file = q.location?.file
            if (!file) throw rpcError('FILE_REFERENCE_INVALID')
            const end = Math.min(q.offset! + q.limit!, sizes[file]) - 1
            const res = await fetch(`/__e2e/media/${file}`, { headers: { Range: `bytes=${q.offset}-${end}` } })
            return { _: 'upload.file', bytes: new Uint8Array(await res.arrayBuffer()) }
        }
        if (q._ === 'help.getNearestDc') return { country: 'GB' }
        if (q._ === 'help.getCountriesList') return { _: 'help.countriesList', countries: [] }
        if (q._ === 'messages.getSponsoredMessages')
            return {
                _: 'messages.sponsoredMessages',
                postsBetween: 3,
                messages: [{ _: 'sponsoredMessage', randomId: new Uint8Array([1, 2, 3]), url: 'https://example.org/', title: 'Lantern Maps', message: 'Hand drawn maps.', buttonText: 'Open', canReport: false }],
            }
        if (q._ === 'messages.viewSponsoredMessage' || q._ === 'messages.clickSponsoredMessage') return true
        throw rpcError('METHOD_NOT_FAKED')
    }
    async searchMessages(p: { chatId: number; filter: { _: string }; offset?: number; limit: number }) {
        await delay(flags.has('slow') ? 3000 : 50)
        if (!peers.has(p.chatId)) throw rpcError('PEER_ID_INVALID')
        const wantVideo = p.filter._ === 'inputMessagesFilterVideo'
        const all = (messages.get(p.chatId) ?? []).filter((m) => m.isVideo === wantVideo).sort((a, b) => b.id - a.id)
        const from = p.offset ?? 0
        const page = Object.assign(all.slice(from, from + p.limit), { total: all.length, next: from + p.limit < all.length ? from + p.limit : undefined })
        return page
    }
    async searchGlobal(p: { filter: { _: string }; query?: string; limit: number }) {
        await delay(flags.has('slow') ? 3000 : 50)
        const wantVideo = p.filter._ === 'inputMessagesFilterVideo'
        const q = p.query?.toLowerCase() ?? ''
        return [...messages.values()]
            .flat()
            .filter((m) => m.isVideo === wantVideo && m.media.fileName.toLowerCase().includes(q))
            .slice(0, p.limit)
    }
    async getMessages(chatId: number, ids: number | number[]) {
        if (!peers.has(chatId)) throw rpcError('PEER_ID_INVALID')
        return [ids].flat().map((id) => (messages.get(chatId) ?? []).find((m) => m.id === id) ?? null)
    }
    async *iterDialogs(_: unknown) {
        for (let i = 0; i < dialogs.length; i++) {
            if (i % 100 === 0) await delay(20)
            yield { peer: dialogs[i], isPinned: i < 2 }
        }
    }
    async downloadAsBuffer(t: { url: string }) {
        return new Uint8Array(await (await fetch(t.url)).arrayBuffer())
    }
}
;(globalThis as { fakeCalls?: string[] }).fakeCalls = calls
