// A subtitle file posted in the video's chat (EG-F48) as a File for the player, so it takes the same
// way in as a picked one (subtitles.ts: the format, the encoding, VobSub refused). A zip of subtitles
// gives the entry for this episode: a stored entry as it is, a compressed one through the browser's
// own inflate where it has one. Pure of the page and the worker, so a Node test runs it.
import { zipEntries, type ZipEntry, type ZipSource } from '../stream/zip.ts'
import { episodeOf } from './msone.ts'

/** A subtitle is tens of kilobytes; a zip of a season a few megabytes (ChatSubtitles.MAX_BYTES, MAX_ZIP_BYTES). */
export const MAX_BYTES = 4 * 1024 * 1024
export const MAX_ZIP_BYTES = 8 * 1024 * 1024
/** Most entries and bytes of a zip that are looked at (ChatSubtitleText). */
const ZIP_ENTRIES = 600
const ZIP_BYTES = 40 * 1024 * 1024

export type ChatSubtitleFailure = 'gone' | 'too-big' | 'compressed' | 'unreadable' | 'offline'

export class ChatSubtitleError extends Error {
    readonly why: ChatSubtitleFailure
    constructor(why: ChatSubtitleFailure) {
        super(why)
        this.why = why
    }
}

/** What the chooser takes (subtitles.ts SUBTITLE_EXTENSIONS, ExternalSubtitle.EXTENSIONS), kept here so a Node test needs no `ass-compiler`. */
const SUBTITLE_EXTENSIONS = ['srt', 'ass', 'ssa', 'vtt', 'sub']

const extensionOf = (name: string) => (name.includes('.') ? name.slice(name.lastIndexOf('.') + 1).toLowerCase() : '')
const isSubtitle = (name: string) => SUBTITLE_EXTENSIONS.includes(extensionOf(name))

/** Whether bytes start as a ZIP. */
const isZip = (b: Uint8Array) => b.length > 4 && b[0] === 0x50 && b[1] === 0x4b

function bytesSource(bytes: Uint8Array): ZipSource {
    return {
        size: bytes.length,
        read: async (offset, length) => (offset < 0 || offset > bytes.length ? null : bytes.subarray(offset, Math.min(bytes.length, offset + length))),
    }
}

/** The entry's bytes: as they are when stored, inflated when the browser can, else "compressed". */
async function entryBytes(zip: Uint8Array, entry: ZipEntry): Promise<Uint8Array> {
    const view = new DataView(zip.buffer, zip.byteOffset, zip.byteLength)
    const at = entry.headerOffset
    if (at < 0 || at + 30 > zip.length || view.getUint32(at, true) !== 0x04034b50) throw new ChatSubtitleError('unreadable')
    const start = at + 30 + view.getUint16(at + 26, true) + view.getUint16(at + 28, true)
    if (start + entry.compressedSize > zip.length) throw new ChatSubtitleError('unreadable')
    const data = zip.subarray(start, start + entry.compressedSize)
    if (entry.method === 0) return data
    if (entry.method !== 8 || typeof DecompressionStream === 'undefined') throw new ChatSubtitleError('compressed')
    try {
        const stream = new Blob([data as BlobPart]).stream().pipeThrough(new DecompressionStream('deflate-raw'))
        const out = new Uint8Array(await new Response(stream).arrayBuffer())
        if (out.length > MAX_BYTES) throw new ChatSubtitleError('too-big')
        return out
    } catch (e) {
        throw e instanceof ChatSubtitleError ? e : new ChatSubtitleError('unreadable')
    }
}

/** The subtitle inside a zip for [episode]: its numbered entry, the only one, or the first when none is numbered. */
async function fromZip(bytes: Uint8Array, episode: { season: number | null; episode: number } | null): Promise<File> {
    const all = await zipEntries(bytesSource(bytes))
    if (!all || all.length > ZIP_ENTRIES) throw new ChatSubtitleError('unreadable')
    const inside = all.filter((e) => !e.name.endsWith('/') && isSubtitle(e.name.split('/').pop() ?? ''))
    if (!inside.length || inside.some((e) => e.encrypted) || inside.reduce((n, e) => n + e.uncompressedSize, 0) > ZIP_BYTES) throw new ChatSubtitleError('unreadable')
    const numbered = inside.map((e) => ({ e, ep: episodeOf(e.name.split('/').pop() ?? '') }))
    let pick = inside[0]
    if (episode) {
        const same = numbered.find(({ ep }) => ep?.episode === episode.episode && (ep.season === null || episode.season === null || ep.season === episode.season))
        if (same) pick = same.e
        // A season pack that numbers its episodes and has none of this one has nothing to offer.
        else if (inside.length > 1 && numbered.some(({ ep }) => ep)) throw new ChatSubtitleError('unreadable')
    }
    if (pick.uncompressedSize > MAX_BYTES) throw new ChatSubtitleError('too-big')
    const name = pick.name.split('/').pop() ?? pick.name
    return new File([(await entryBytes(bytes, pick)) as BlobPart], name)
}

/**
 * The listed file's [bytes] as a File for the player, or an error saying why not: too big,
 * compressed beyond what this browser can open, not a subtitle. [video] is the playing video's
 * season and episode, which pick the entry of a zip.
 */
export async function chatSubtitleFile(name: string, archive: boolean, bytes: Uint8Array, video: { season: number | null; episode: number } | null): Promise<File> {
    if (!bytes.length) throw new ChatSubtitleError('unreadable')
    if (bytes.length > (archive ? MAX_ZIP_BYTES : MAX_BYTES)) throw new ChatSubtitleError('too-big')
    if (isZip(bytes)) return fromZip(bytes, video)
    if (archive || !isSubtitle(name)) throw new ChatSubtitleError('unreadable')
    return new File([bytes as BlobPart], name)
}
