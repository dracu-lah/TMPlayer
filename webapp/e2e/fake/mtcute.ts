// A stand-in for @mtcute/web in the end to end tests (e2e/vite.e2e.ts swaps it in), loaded by the
// real Telegram worker so its paging, the app's rules and the player run unchanged: one signed in
// account, a few chats, and the small files in e2e/media served as Telegram's file chunks.
// A test picks a case with the `e2e` cookie, which /__e2e/flags hands back to the worker:
// `signedout`, `revoked` (Telegram says the session ended), `slow` (lists answer after 3 s),
// `password` (signed out, the QR code is taken at once and the account asks its two-step password),
// `huge` (a very large account for e2e/scale.spec.ts, described below the dialogs),
// `forums` (three forum chats with topics, for e2e/search-topics.spec.ts, described below the dialogs),
// `hinted` (Test Shows is named for a language: "Test Shows Malayalam", for the chat language hints of e2e/posters.spec.ts).
// Read live, so a test can change them while the page runs (the cookie is read again): `stall` (a
// file read answers nothing until the flag goes), `late` (it answers after 6 s), `flood:N` (file reads are told to wait N seconds, from the first one), `linkdown` (the line to
// Telegram reports `connecting`, and nothing is answered), `linkupdating` (the line reports `updating`, catching up, and answers as usual), `topicflood` (the first ask for Language Club's topics is told to wait 2 s),
// `countflood` (the Add chats picker's counts are told to wait 15 minutes after twenty), `countfail` (three channels of the huge account always refuse a count),`topicsrefuse` (every ask for topics is refused), `topicslow` (an ask for topics answers after 1.5 s).

// Read synchronously (a worker may): a top-level await would hold back the worker's message
// handler, and the page's first call would be lost.
import { cloudFor } from './cloud'

function get(url: string): string {
    const xhr = new XMLHttpRequest()
    xhr.open('GET', url, false)
    xhr.send()
    return xhr.responseText
}
const flags = new Set(get('/__e2e/flags').split(',').filter(Boolean))
/** Saved Messages for the sync file: one per test (`cloud:<key>`), kept by the test server. */
const cloud = cloudFor([...flags].find((f) => f.startsWith('cloud:'))?.slice('cloud:'.length) ?? 'default')
const sizes: Record<string, number> = JSON.parse(get('/__e2e/sizes'))

const delay = (ms: number) => new Promise((r) => setTimeout(r, ms))
const rpcError = (text: string, code = 400) => Object.assign(new Error(text), { text, code })
const THUMB = '/__e2e/media/thumb.jpg'

/** The accounts the fake knows (HX88's profiles): 1 is the one every test starts signed in as. */
const NAMES: Record<number, string> = { 1: 'Dana Test', 2: 'Robin Test', 3: 'Sam Test' }

/** 60 characters, with no space to wrap at once (the `longname` case). */
export const LONG_NAME = 'Maximilian-Alexander Featherstonehaugh-Montgomery Fitzgerald'

export class User {
    constructor(public raw: unknown) {}
    get id() {
        const id = (this.raw as { id?: unknown } | null)?.id
        return typeof id === 'number' ? id : 1
    }
    get displayName() {
        // `noname`: an account Telegram gives no name (the bar's picture falls back to the person glyph).
        if (flags.has('noname')) return ''
        // `longname`: a name no bar label fits (it ellipsizes in its own cell).
        if (flags.has('longname') && this.id === 1) return LONG_NAME
        return NAMES[this.id] ?? `User ${this.id}`
    }
    get username() {
        return this.id === 1 ? 'danatest' : `user${this.id}`
    }
    get photo() {
        return null
    }
}

/**
 * The session, in an IndexedDB database named after the storage the worker was given, as mtcute's
 * own is (`tmplayer`, `tmplayer-<folder>` for another profile), so the page's sign out and delete
 * act on it the same way. The browser's own storage with nothing kept is signed in as account 1
 * (unless `signedout`); another profile's starts signed out. `qr:N` (read live) signs the QR code
 * in as account N.
 */
let storageName = 'tmplayer'
type FakeSession = { id: number } | { out: true } | { corrupt: true }
function sessionDb(): Promise<IDBDatabase> {
    return new Promise((resolve, reject) => {
        const open = indexedDB.open(storageName, 1)
        open.onupgradeneeded = () => open.result.createObjectStore('fake')
        open.onsuccess = () => resolve(open.result)
        open.onerror = () => reject(open.error)
    })
}
async function sessionOp(write?: FakeSession): Promise<FakeSession | null> {
    const db = await sessionDb()
    try {
        return await new Promise((resolve, reject) => {
            const tx = db.transaction('fake', write ? 'readwrite' : 'readonly')
            const req = write ? tx.objectStore('fake').put(write, 'self') : tx.objectStore('fake').get('self')
            tx.oncomplete = () => resolve(write ?? ((req.result as FakeSession | undefined) ?? null))
            tx.onerror = () => reject(tx.error)
        })
    } finally {
        db.close()
    }
}
async function signedInAs(): Promise<number | null> {
    const s = await sessionOp().catch(() => null)
    // A session a test broke on purpose: reading it fails, as a corrupt mtcute store does.
    if (s && 'corrupt' in s) throw new Error('the stored session cannot be read')
    if (s && 'out' in s) return null
    if (s) return s.id
    return storageName === 'tmplayer' && !flags.has('signedout') ? 1 : null
}
export class SentCode {}
export class WebCryptoProvider {
    constructor(_?: unknown) {}
}
/** The flags as they are now: the cookie is read again, so a test turns a case on and off while the page runs. */
async function live(): Promise<Set<string>> {
    const text = await (await fetch('/__e2e/flags', { cache: 'no-store' })).text()
    return new Set(text.split(',').filter(Boolean))
}
/** When the fake flood wait ends (ms, real time): it starts at the first read after `flood:N` is set. */
let floodEnds = 0

export class BaseTelegramClient {
    private listeners: ((state: string) => void)[] = []
    private state = ''
    /** mtcute's emitter: the worker adds a listener and gets each change of the line. */
    onConnectionState = { add: (fn: (state: string) => void) => void this.listeners.push(fn) }
    /** Retries since the line was told to come back, readable as `fakeCalls`. */
    mt = { network: { notifyNetworkChanged: (up: boolean) => void calls.push(`notifyNetworkChanged ${up}`) } }
    constructor(options: unknown) {
        const storage = (options as { storage?: unknown } | null)?.storage
        if (typeof storage === 'string') storageName = storage
        // The line follows the `linkdown` flag, polled twice a second like a socket that notices late.
        const follow = async () => {
            const flags = await live().catch(() => new Set<string>())
            const next = flags.has('linkdown') ? 'connecting' : flags.has('linkupdating') ? 'updating' : 'connected'
            if (next !== this.state) {
                this.state = next
                for (const fn of this.listeners) fn(next)
            }
        }
        setInterval(follow, 500)
        void follow()
    }
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
    /** What Telegram says the file is (a document's mime type), else it is told from the name. */
    mime?: string
    /** The forum topic it was posted in (0 or none: no topic). */
    topic?: number
}

let n = 0
function msg(chat: Peer, name: string, o: Opts = {}) {
    n++
    return Object.assign(build(n, chat, name, o, new Date(Date.UTC(2026, 9, 1) + n * 3600_000), `thumb${n}`, `file${n}`), { topic: o.topic ?? 0 })
}
/** One message as the app reads it; `thumbId` and `fileId` are Telegram's unique ids, one per message. */
function build(id: number, chat: Peer, name: string, o: Opts, date: Date, thumbId: string, fileId: string) {
    const size = o.file ? sizes[o.file] : (o.size ?? 0)
    const thumb = { uniqueFileId: thumbId, url: THUMB }
    return {
        id,
        chat,
        date,
        text: '',
        groupedId: null,
        media: {
            type: o.video ? 'video' : 'document',
            fileName: name,
            mimeType: o.mime ?? (name.endsWith('.pdf') ? 'application/pdf' : 'video/mp4'),
            // `big`: every video says 1.5 GB, past the carousel's 400 MB floor (its bytes stay the real file's).
            // `mid`: every video says 100 MB, over Home's 50 MB floor and under the carousel's 400 MB.
            fileSize: flags.has('big') && o.file ? 1_500_000_000 : flags.has('mid') && o.file ? 100_000_000 : size,
            uniqueFileId: fileId,
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
const shows = peer(-1002, flags.has('hinted') ? 'Test Shows Malayalam' : 'Test Shows', 'chat')
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
            msg(clips, 'Odd Picture Clip.avi', { file: 'msvideo1.avi' }),
            msg(clips, 'Odd Sound Clip.avi', { file: 'mpeg4-g723.avi' }),
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

// `manyrecent`: four hundred more videos in Test Clips, for Recently added's See all to page through.
if (flags.has('manyrecent')) {
    const letters = (n: number) => [0, 1, 2].map((k) => String.fromCharCode(97 + (Math.floor(n / 26 ** k) % 26))).join('')
    for (let i = 0; i < 400; i++) messages.get(clips.id)!.push(msg(clips, `Reel ${letters(i)}.mp4`, { file: 'h264-aac.mp4' }))
}

// A chat of its own at the very end (opened by its address, /chat/-1006), made last so no other id moves: 20 s,
// chaptered, two episodes of one show, for the chapter, skip and autoplay tests.
const lighthouse = peer(-1006, 'Lighthouse Show', 'chat')
messages.set(
    lighthouse.id,
    ['Lighthouse.S01E01.mkv', 'Lighthouse.S01E02.mkv'].map((name) => msg(lighthouse, name, { file: 'chapters.mkv', video: true, duration: 20 })),
)

// `zipped`: a chat of its own (/chat/-1007, in no list, and in no search unless asked for) of videos in pieces: a stored
// ZIP cut into four, a deflated one, a ZIP with a piece missing, a video cut into three raw pieces, and a phone backup.
const zipped = peer(-1007, 'Zipped Room', 'chat')
if (flags.has('zipped')) {
    const zip = 'application/zip'
    messages.set(zipped.id, [
        ...[1, 2, 3, 4].map((i) => msg(zipped, `Zipped.Clip.2026.1080p.WEB-DL.zip.00${i}`, { file: `zipped.zip.00${i}`, mime: zip })),
        msg(zipped, 'Deflated.Clip.2026.1080p.WEB-DL.zip.001', { file: 'deflated.zip.001', mime: zip }),
        // Pieces 1, 2 and 4 of a ZIP: the directory is in the last, and the third is not in the chat.
        ...[1, 2, 4].map((i) => msg(zipped, `Gap.Clip.2026.1080p.WEB-DL.zip.00${i}`, { file: `zipped.zip.00${i}`, mime: zip })),
        ...[1, 2, 3].map((i) => msg(zipped, `Raw.Clip.2026.1080p.mkv.00${i}`, { file: `raw.mkv.00${i}`, mime: 'application/octet-stream' })),
        msg(zipped, 'IvyWalletBackup_20260930-1830.zip', { size: 165_710, mime: zip }),
    ])
}

// `oldformats`: a chat of its own (/chat/-1008, in no list) of the formats libav.js plays beyond AVI (player/libav.ts).
const oldFormats = peer(-1008, 'Old Formats', 'chat')
if (flags.has('oldformats')) {
    messages.set(oldFormats.id, [
        msg(oldFormats, 'Flash Clip.flv', { file: 'flv-mp3.flv' }),
        msg(oldFormats, 'Real Clip.rm', { file: 'rv20-ac3.rm' }),
        msg(oldFormats, 'Dvd Clip.vob', { file: 'mpeg2-mp2.vob' }),
        msg(oldFormats, 'Broadcast Clip.ts', { file: 'mpeg2-mp2.ts' }),
        msg(oldFormats, 'Part Two Clip.mkv', { file: 'mpeg4-aac.mkv' }),
        msg(oldFormats, 'Lossless Sound Clip.mkv', { file: 'h264-truehd.mkv' }),
        msg(oldFormats, 'Theora Clip.ogv', { file: 'theora-vorbis.ogv' }),
    ])
}

// `crosscopies`: a fourth copy of the film in Test Copies, posted in Quiet Room, so a search across chats folds copies
// from two chats into one card (HX38: the page lists the same group the card counts). Made last so no other id moves.
if (flags.has('crosscopies')) messages.get(quiet.id)!.push(msg(quiet, 'Big.Buck.Bunny.2008.480p.DVDRip.x264.mkv', { file: 'h264-aac.mkv' }))

const dialogs: Peer[] = [me, clips, shows, locked, quiet, copies]
if (!flags.has('huge')) for (let i = 0; i < 1500; i++) dialogs.push(peer(-5000 - i, `Group ${i}`, 'chat'))
dialogs.push(lighthouse)

// `links` (WB16, Telegram links): chats with ids as real Telegram ones are (-100 and a number), which the fake's small ids are not.
// Link Room is public (@linkroom) with a plain video and one in a forum topic (9); Back Room is a private channel with no
// username; Sealed Room restricts saving. `linksgone`: Telegram answers that Link Room's messages no longer exist. `linksprot`: its messages now say they restrict saving.
// Made last, so no other id moves.
const linkRoom = peer(-1001234567890, 'Link Room', 'chat', { chatType: 'channel', username: 'linkroom' })
const backRoom = peer(-1009876543210, 'Back Room', 'chat', { chatType: 'channel' })
const sealedRoom = peer(-1005555555555, 'Sealed Room', 'chat', { chatType: 'channel', raw: { noforwards: true } })
if (flags.has('links')) {
    const inTopic = msg(linkRoom, 'Topic.Clip.mp4', { file: 'h264-aac.mp4', video: true })
    Object.assign(inTopic.raw, { replyTo: { _: 'messageReplyHeader', forumTopic: true, replyToTopId: 9, replyToMsgId: 9 } })
    messages.set(linkRoom.id, [msg(linkRoom, 'Plain.Clip.mp4', { file: 'h264-aac.mp4', video: true }), inTopic])
    messages.set(backRoom.id, [msg(backRoom, 'Back.Clip.mp4', { file: 'h264-aac.mp4', video: true })])
    messages.set(sealedRoom.id, [msg(sealedRoom, 'Sealed.Clip.mp4', { file: 'h264-aac.mp4', video: true })])
    dialogs.splice(1, 0, linkRoom, backRoom, sealedRoom)
}

// `forums`: three groups with topics turned on, among the first chats of the list (/chat/-1010 to -1012), for the search by topic name.
// Course Hub has a topic of two videos ("☕ Café Lessons"), one with none ("Lessons archive", closed), one of files only,
// a pinned General and one with no title; Language Club has two small topics; Outside Forum is for the added-chats test.
interface FakeTopic {
    id: number
    title: string
    pinned?: boolean
    closed?: boolean
}
const topicsOf = new Map<number, FakeTopic[]>()
if (flags.has('forums')) {
    const hub = peer(-1010, 'Course Hub', 'chat', { isForum: true })
    const club = peer(-1011, 'Language Club', 'chat', { isForum: true })
    const outside = peer(-1012, 'Outside Forum', 'chat', { isForum: true })
    messages.set(hub.id, [
        msg(hub, 'Cafe.Lesson.One.mp4', { file: 'h264-aac.mp4', video: true, topic: 2 }),
        msg(hub, 'Cafe.Lesson.Two.mp4', { file: 'h264-aac.mp4', video: true, topic: 2 }),
        msg(hub, 'General.Chat.Clip.mp4', { file: 'h264-aac.mp4', video: true, topic: 1 }),
        msg(hub, 'readme.pdf', { size: 2000, topic: 4 }),
    ])
    messages.set(club.id, [msg(club, 'Week.One.mp4', { file: 'h264-aac.mp4', video: true, topic: 1 }), msg(club, 'Week.Two.mp4', { file: 'h264-aac.mp4', video: true, topic: 2 })])
    messages.set(outside.id, [msg(outside, 'Outside.Clip.mp4', { file: 'h264-aac.mp4', video: true, topic: 1 })])
    topicsOf.set(hub.id, [
        { id: 1, title: 'General', pinned: true },
        { id: 2, title: '☕ Café Lessons' },
        { id: 3, title: 'Lessons archive', closed: true },
        { id: 4, title: 'Lessons notes (PDF)' },
        { id: 5, title: '' },
    ])
    topicsOf.set(club.id, [
        { id: 1, title: 'Lessons week 1' },
        { id: 2, title: 'Lessons week 2' },
    ])
    topicsOf.set(outside.id, [{ id: 1, title: 'Lessons outside' }])
    // Among the first chats (the head of the list is what a search by topic name reads when the whole list is not there).
    dialogs.splice(6, 0, hub, club, outside)
}
/** Telegram's own matching of a topic's name: lower case, no accents, the words inside it. */
const fold = (text: string) => text.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase()
/** The picker's counts asked so far (the `countflood` case refuses after twenty). */
let countsAsked = 0
/** Group ids whose topics were asked once already (the `topicflood` case answers a flood wait the first time only). */
const floodedOnce = new Set<number>()
// Telegram's archive: two chats of its own, listed only when the archive is asked for.
const archive: Peer[] = [peer(-7001, 'Archived Reels', 'chat', { chatType: 'channel' }), peer(-7002, 'Old Group', 'chat')]

// `huge`: the account of e2e/scale.spec.ts (AGENTS.md, "Scale"), built only on that flag so the other tests pay nothing.
// 3,000 chats in the list (`Scale Chat 0000`, no real titles), 300 more in the archive and 100,000 videos in all:
// 100 in each of the first 800 chats, and 20,000 in Deep Room (/chat/-1009), the one long history. Every video says
// 120 MB (450 MB for each fifth), so Home's and the carousel's size floors let them in; none has bytes, so none plays.
// A video's message id is its place in its chat (1 is the oldest), its date follows the place, so the newest across
// chats is the highest place. The messages are made when a page asks for them, never all held at once. The web app
// reads no Telegram folders, so there are none here.
const HUGE = flags.has('huge')
const HUGE_CHATS = 3000
const HUGE_ARCHIVED = 300
const HUGE_SHOWS = 800
const HUGE_EACH = 100
const HUGE_DEEP = 20_000
const hugeId = (slot: number) => -2_000_000 - slot
const deepRoom: Peer = HUGE
    ? peer(-1009, 'Deep Room', 'chat', { chatType: 'channel', about: 'One long history for the scale test.', members: 90000 })
    : ({ id: -1009 } as Peer)
/** The videos of a chat of the huge account; 0 for every other chat. */
const hugeCount = (id: number) => (!HUGE ? 0 : id === deepRoom.id ? HUGE_DEEP : id <= hugeId(0) && id > hugeId(HUGE_SHOWS) ? HUGE_EACH : 0)
const hugeSlot = (id: number) => (id === deepRoom.id ? HUGE_CHATS : hugeId(0) - id)
const pad = (v: number, w: number) => String(v).padStart(w, '0')
/** The name of the video at place `k` (0 is the oldest) of the chat of slot `slot`. */
function hugeName(slot: number, k: number): string {
    if (slot === HUGE_CHATS) return `Deep Reel ${pad(k + 1, 5)}.mp4`
    return `Scale Show ${pad(slot, 4)} S0${1 + (Math.floor(k / 20) % 5)}E${pad((k % 20) + 1, 2)} 1080p.mkv`
}
/** One number that orders the account's videos by date: place first, chat second. */
const hugeKey = (slot: number, k: number) => k * 4000 + slot
const hugeMsg = (chat: Peer, k: number) => {
    const slot = hugeSlot(chat.id)
    // Seconds from 2020 by key: the newest of the account is far older than the small chats' (2026), which sort first.
    return build(k + 1, chat, hugeName(slot, k), { video: true, size: k % 5 === 0 ? 450_000_000 : 120_000_000, duration: 5400 }, new Date(Date.UTC(2020, 0, 1) + hugeKey(slot, k) * 1000), `h${slot}_${k}`, `f${slot}_${k}`)
}
if (HUGE) {
    for (let i = 0; i < HUGE_CHATS; i++) dialogs.push(peer(hugeId(i), `Scale Chat ${pad(i, 4)}`, 'chat', { chatType: i % 3 === 0 ? 'channel' : 'supergroup' }))
    for (let i = 0; i < HUGE_ARCHIVED; i++) archive.push(peer(hugeId(HUGE_CHATS + 1 + i), `Scale Archive ${pad(i, 3)}`, 'chat', { chatType: i % 2 ? 'channel' : 'supergroup' }))
    // Deep Room is the third chat of the list, after the two pinned ones.
    dialogs.splice(2, 0, deepRoom)
}
/** The keys (hugeKey) of every video of the account, newest first. */
let hugeOrder: Int32Array | null = null
function hugeKeys(): Int32Array {
    if (hugeOrder) return hugeOrder
    const keys = new Int32Array(HUGE_SHOWS * HUGE_EACH + HUGE_DEEP)
    let at = 0
    for (let s = 0; s < HUGE_SHOWS; s++) for (let k = 0; k < HUGE_EACH; k++) keys[at++] = hugeKey(s, k)
    for (let k = 0; k < HUGE_DEEP; k++) keys[at++] = hugeKey(HUGE_CHATS, k)
    return (hugeOrder = keys.sort().reverse())
}
const hugeFound = new Map<string, number[]>()
/** The keys of the videos of the account whose name has `query` (lower case), newest first. */
function hugeAcross(query: string): ArrayLike<number> {
    if (!query) return hugeKeys()
    let found = hugeFound.get(query)
    if (!found) {
        found = Array.from(hugeKeys()).filter((key) => hugeName(key % 4000, Math.floor(key / 4000)).toLowerCase().includes(query))
        hugeFound.set(query, found)
    }
    return found
}
const hugePeerOfSlot = (slot: number) => peers.get(slot === HUGE_CHATS ? deepRoom.id : hugeId(slot))!
/** The places (0 is the oldest) of the videos of one chat whose name has `query`, newest first. */
function hugeIn(chat: Peer, query: string): number[] {
    const slot = hugeSlot(chat.id)
    const out: number[] = []
    for (let k = hugeCount(chat.id) - 1; k >= 0; k--) if (!query || hugeName(slot, k).toLowerCase().includes(query)) out.push(k)
    return out
}

let globalFailsLeft = 2

/** The thumbnails fetched (their ids), readable in the worker as `fakeThumbs`. */
const thumbFetches: string[] = []
/** The raw calls made, readable in the worker as `fakeCalls`. */
const calls: string[] = []

export class TelegramClient {
    storage = { self: { fetch: async () => ((id) => (id === null ? null : { userId: id }))(await signedInAs()) } }
    constructor(_: unknown) {}
    async prepare() {}
    async getMe() {
        if (flags.has('revoked')) throw rpcError('AUTH_KEY_UNREGISTERED', 401)
        const id = await signedInAs()
        if (id === null) throw rpcError('AUTH_KEY_UNREGISTERED', 401)
        return new User({ id })
    }
    async getPasswordHint() {
        return flags.has('password') ? 'harbour' : null
    }
    /** The QR sign in: a code that never gets scanned, or with `password`, one scanned at once that asks the password. */
    async signInQr(p: { abortSignal?: AbortSignal; onUrlUpdated: (url: string, expires: Date) => void; password: () => Promise<string> }) {
        p.onUrlUpdated('tg://login?token=e2e', new Date(Date.now() + 30_000))
        if (flags.has('password')) {
            await delay(50)
            await p.password()
            await sessionOp({ id: 1 })
            return new User({})
        }
        // Scanned once a test sets `qr:N`: signed in as account N.
        for (;;) {
            if (p.abortSignal?.aborted) throw new Error('aborted')
            const scanned = [...(await live().catch(() => new Set<string>()))].find((f) => /^qr:\d$/.test(f))
            if (scanned) {
                const id = Number(scanned.slice(3))
                await sessionOp({ id })
                return new User({ id })
            }
            await delay(200)
        }
    }
    async logOut() {
        await sessionOp({ out: true }).catch(() => {})
    }
    async resolvePeer(id: number) {
        return { _: 'inputPeerChannel', channelId: id, accessHash: 0 }
    }
    async getFullChat(id: number) {
        calls.push(`getFullChat ${id}`)
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
            const now = await live()
            // A read that Telegram never answers while the flag is on (up to 90 s), then served as usual.
            for (let waited = 0; (now.has('stall') || now.has('linkdown')) && waited < 90_000; waited += 200) {
                await delay(200)
                const again = await live()
                now.clear()
                again.forEach((f) => now.add(f))
            }
            if (now.has('late')) await delay(6000)
            const flood = [...now].find((f) => f.startsWith('flood:'))
            if (flood) {
                if (!floodEnds) floodEnds = Date.now() + Number(flood.slice(6)) * 1000
                const left = Math.ceil((floodEnds - Date.now()) / 1000)
                if (left > 0) throw Object.assign(rpcError(`FLOOD_WAIT_${left}`, 420), { seconds: left })
            } else floodEnds = 0
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
    async getForumTopics(chatId: number, p?: { query?: string; limit?: number }) {
        const now = await live()
        calls.push(`getForumTopics ${chatId} ${p?.query ?? ''} ${p?.limit ?? 0}`)
        await delay(now.has('topicslow') ? 1500 : 30)
        if (now.has('topicsrefuse')) throw rpcError('CHANNEL_PRIVATE')
        if (now.has('topicflood') && chatId === -1011 && !floodedOnce.has(chatId)) {
            floodedOnce.add(chatId)
            throw Object.assign(rpcError('FLOOD_WAIT_2', 420), { seconds: 2 })
        }
        const q = fold(p?.query ?? '')
        const found = (topicsOf.get(chatId) ?? []).filter((t) => fold(t.title).includes(q)).slice(0, p?.limit ?? 100)
        return found.map((t) => ({ id: t.id, title: t.title, titleMissing: !t.title, isPinned: !!t.pinned, isClosed: !!t.closed }))
    }
    async getForumTopicsById(chatId: number, ids: number[]) {
        calls.push(`getForumTopicsById ${chatId} ${ids.join(',')}`)
        return (topicsOf.get(chatId) ?? []).filter((t) => ids.includes(t.id)).map((t) => ({ id: t.id, title: t.title, titleMissing: !t.title, isPinned: !!t.pinned, isClosed: !!t.closed }))
    }
    async searchMessages(p: { chatId: number | 'self'; query?: string; filter?: { _: string }; offset?: number; limit: number; threadId?: number }) {
        calls.push(`searchMessages ${p.chatId} ${p.filter?._ ?? ''} ${p.offset ?? 0}`)
        if (p.threadId) calls.push(`thread ${p.chatId} ${p.threadId}`)
        await delay(flags.has('slow') ? 3000 : 50)
        // `countflood`: after twenty single-message counts (the Add chats picker's) Telegram asks it to wait a quarter of an hour.
        if (flags.has('countflood') && p.limit === 1 && p.chatId !== 'self' && ++countsAsked > 20) throw Object.assign(rpcError('FLOOD_WAIT_900', 420), { seconds: 900 })
        // `countfail`: three channels of the huge account (slots 66, 69, 72) always refuse a count with a plain error (a private channel).
        if (flags.has('countfail') && p.limit === 1 && [66, 69, 72].some((slot) => p.chatId === hugeId(slot))) throw rpcError('CHANNEL_PRIVATE')
        // Saved Messages: the sync file, found by its caption.
        if (p.chatId === 'self') return (await cloud.all(me)).filter((m) => m.text.includes(p.query ?? '')).slice(0, p.limit)
        if (!p.filter) throw rpcError('FILTER_MISSING')
        if (!peers.has(p.chatId)) throw rpcError('PEER_ID_INVALID')
        const wantVideo = p.filter._ === 'inputMessagesFilterVideo'
        if (hugeCount(p.chatId) > 0) {
            // The account of the scale test: a page of the chat's places, newest first, made now.
            const chat = peers.get(p.chatId)!
            const places = wantVideo ? hugeIn(chat, p.query?.toLowerCase() ?? '') : []
            const from = p.offset ?? 0
            const next = from + p.limit < places.length ? from + p.limit : undefined
            return Object.assign(places.slice(from, from + p.limit).map((k) => hugeMsg(chat, k)), { total: places.length, next })
        }
        const all = (messages.get(p.chatId) ?? []).filter((m) => m.isVideo === wantVideo && (!p.threadId || m.topic === p.threadId)).sort((a, b) => b.id - a.id)
        const from = p.offset ?? 0
        const page = Object.assign(all.slice(from, from + p.limit), { total: all.length, next: from + p.limit < all.length ? from + p.limit : undefined })
        return page
    }
    /** Newest first, in pages: `offset.rate` is where the next page starts (Telegram's is its own cursor). */
    async searchGlobal(p: { filter: { _: string }; query?: string; limit: number; offset?: { rate: number } }) {
        await delay(flags.has('slow') ? 3000 : 50)
        calls.push(`searchGlobal ${p.filter._} ${p.offset?.rate ?? 0}`)
        // `recentfails`: the first ask across chats (both its feeds) fails, the next ones answer.
        if (flags.has('recentfails') && globalFailsLeft-- > 0) throw rpcError('INTERNAL_SERVER_ERROR', 500)
        const wantVideo = p.filter._ === 'inputMessagesFilterVideo'
        const q = p.query?.toLowerCase() ?? ''
        const all = [...messages.values()]
            .flat()
            .filter((m) => m.isVideo === wantVideo && m.media.fileName.toLowerCase().includes(q))
            .sort((a, b) => b.date.getTime() - a.date.getTime())
        // The scale test's videos follow the small chats', all older: a page is cut across both.
        const far = HUGE && wantVideo ? hugeAcross(q) : []
        const total = all.length + far.length
        const from = p.offset?.rate ?? 0
        const next = from + p.limit < total ? { rate: from + p.limit, id: from + p.limit, peer: { _: 'inputPeerEmpty' } } : undefined
        const page: unknown[] = all.slice(from, from + p.limit)
        for (let i = Math.max(from - all.length, 0); page.length < p.limit && i < far.length; i++) {
            const key = far[i]
            page.push(hugeMsg(hugePeerOfSlot(key % 4000), Math.floor(key / 4000)))
        }
        return Object.assign(page, { next })
    }
    async getHistory(_: 'self', p?: { limit?: number }) {
        return (await cloud.all(me)).slice(0, p?.limit ?? 100)
    }
    async editMessage(p: { chatId: 'self'; message: number; text?: string; media: { file: Uint8Array; fileName: string } }) {
        calls.push(`editMessage ${p.message}`)
        return cloud.edit(me, p.message, { file: p.media.file, fileName: p.media.fileName, caption: p.text ?? '' })
    }
    async sendMedia(_: 'self', media: { file: Uint8Array; fileName: string; caption?: string }) {
        calls.push('sendMedia')
        return cloud.put(me, { file: media.file, fileName: media.fileName, caption: media.caption ?? '' })
    }
    async pinMessage(_: { chatId: 'self'; message: number }) {
        calls.push('pinMessage')
        return null
    }
    async deleteMessagesById(_: 'self', ids: number[]) {
        calls.push(`deleteMessages ${ids.join(',')}`)
        await cloud.remove(ids)
    }
    async getMessages(chatId: number | 'self', ids: number | number[]) {
        calls.push(`getMessages ${chatId}`)
        if (chatId === 'self') {
            const all = await cloud.all(me)
            return [ids].flat().map((id) => all.find((m) => m.id === id) ?? null)
        }
        if (!peers.has(chatId)) throw rpcError('PEER_ID_INVALID')
        if (flags.has('linksgone') && chatId === linkRoom.id) return [ids].flat().map(() => null)
        // `linksprot`: the chat became protected after the lists were read: Telegram's message now says so, the stored cards do not.
        if (flags.has('linksprot') && chatId === linkRoom.id) return [ids].flat().map((id) => { const m = (messages.get(chatId) ?? []).find((x) => x.id === id); return m ? { ...m, raw: { ...m.raw, noforwards: true } } : null })
        if (hugeCount(chatId) > 0) return [ids].flat().map((id) => (id >= 1 && id <= hugeCount(chatId) ? hugeMsg(peers.get(chatId)!, id - 1) : null))
        return [ids].flat().map((id) => (messages.get(chatId) ?? []).find((m) => m.id === id) ?? null)
    }
    async *iterDialogs(p?: { archived?: string; limit?: number }) {
        const list = p?.archived === 'only' ? archive : dialogs
        const limit = Math.min(list.length, p?.limit ?? Infinity)
        calls.push(`iterDialogs ${p?.archived ?? 'include'} ${limit}`)
        for (let i = 0; i < limit; i++) {
            if (i % 100 === 0) await delay(20)
            yield { peer: list[i], isPinned: p?.archived !== 'only' && i < 2 }
        }
    }
    async downloadAsBuffer(t: { url: string; bytes?: Uint8Array; uniqueFileId?: string }) {
        // A thumbnail fetch (e2e/home-defer.spec.ts counts them); `slowthumbs` answers after 150 ms, so a queue forms.
        thumbFetches.push(t.uniqueFileId ?? t.url)
        if (flags.has('slowthumbs')) await delay(150)
        if (t.bytes) return t.bytes
        return new Uint8Array(await (await fetch(t.url)).arrayBuffer())
    }
}
;(globalThis as { fakeCalls?: string[] }).fakeCalls = calls
;(globalThis as { fakeThumbs?: string[] }).fakeThumbs = thumbFetches
