// Chapter marks, read from the file's own bytes the way core's MkvChapters and Mp4Chapters read
// them for the apps (mediabunny does not expose chapters). A Matroska file's Chapters element, and
// an MP4's Nero `chpl` box; both are in the head of the file, or at its tail where TgFile's warm-up
// has already fetched. Nothing is asked of the middle of a file: that would move the window the
// player is streaming from. QuickTime chapter tracks (a text track whose samples are the titles)
// are not read here; the apps do (session 13b).
import type { TgFile } from '../stream/reader'

export interface Chapter {
    /** Seconds from the start. */
    start: number
    title: string | null
}

/** Chapters.RESTART_AFTER_MS and LANDING_SLACK_MS, in seconds. */
const RESTART_AFTER = 3
const SLACK = 1

/** Chapters.at: the index of the chapter the position is in, -1 before the first. */
export function chapterAt(chapters: Chapter[], position: number): number {
    for (let i = chapters.length - 1; i >= 0; i--) if (chapters[i].start <= position + SLACK) return i
    return -1
}

/** Chapters.next: the chapter after the position, or null at the last. */
export function chapterNext(chapters: Chapter[], position: number): number | null {
    const i = chapters.findIndex((c) => c.start > position + SLACK)
    return i >= 0 ? i : null
}

/** Chapters.previous: the start of this chapter once a few seconds in, else the one before. */
export function chapterPrevious(chapters: Chapter[], position: number): number | null {
    const at = chapterAt(chapters, position)
    if (at < 0) return null
    return position - chapters[at].start > RESTART_AFTER ? at : Math.max(0, at - 1)
}

/** Chapters.marks: the starts worth drawing on the bar (not 0, not past the end). */
export function chapterMarks(chapters: Chapter[], duration: number): number[] {
    return [...new Set(chapters.map((c) => c.start).filter((s) => s > 0 && (duration <= 0 || s < duration)))]
}

/** A name a person wrote, not the muxer's "Chapter 3". */
export const isGeneric = (name: string) => /^chapter\s*\d+$/i.test(name.trim())

const finish = (list: Chapter[]): Chapter[] => {
    const seen = new Set<number>()
    return list
        .sort((a, b) => a.start - b.start)
        .filter((c) => (seen.has(c.start) ? false : (seen.add(c.start), true)))
}

// ---- the file ------------------------------------------------------------------------------

/** The chapters of a file; empty for one with none, one this does not read, or a part not at hand. */
export async function readChapters(file: TgFile, language?: string): Promise<Chapter[]> {
    // Only the head and the tail are cheap: the middle is a network seek.
    const near = file.chunk * 4
    const cheap = (offset: number, length: number) => offset + length <= near || offset >= file.size - file.chunk * 2
    const read = async (offset: number, length: number): Promise<Uint8Array | null> => {
        if (offset < 0 || offset >= file.size || !cheap(offset, length)) return null
        try {
            return await file.read(offset, offset + length)
        } catch {
            return null
        }
    }
    try {
        const head = await read(0, Math.min(file.size, MKV_HEAD))
        if (!head || head.length < 12) return []
        if (head[0] === 0x1a && head[1] === 0x45 && head[2] === 0xdf && head[3] === 0xa3) return await matroska(head, read, language)
        return await mp4(head, read)
    } catch {
        return []
    }
}

type Reader = (offset: number, length: number) => Promise<Uint8Array | null>

// ---- Matroska ------------------------------------------------------------------------------

const MKV_HEAD = 256 * 1024
const MAX_ELEMENT = 1024 * 1024
const EBML = 0x1a45dfa3
const SEGMENT = 0x18538067
const SEEK_HEAD = 0x114d9b74
const SEEK = 0x4dbb
const SEEK_ID = 0x53ab
const SEEK_POSITION = 0x53ac
const CLUSTER = 0x1f43b675
const CHAPTERS = 0x1043a770
const EDITION = 0x45b9
const EDITION_HIDDEN = 0x45bd
const EDITION_DEFAULT = 0x45db
const ATOM = 0xb6
const TIME_START = 0x91
const FLAG_HIDDEN = 0x98
const FLAG_ENABLED = 0x4598
const SEGMENT_UID = 0x6e67
const DISPLAY = 0x80
const CHAP_STRING = 0x85
const CHAP_LANGUAGE = 0x437c
const CHAP_LANGUAGE_BCP47 = 0x437d

interface El {
    id: number
    data: number
    /** -1 for an unknown size. */
    size: number
    end: number
}

/** The element header at `pos`, or null when it is cut short or malformed. */
function element(b: Uint8Array, pos: number, limit = b.length): El | null {
    const id = vint(b, pos, limit, true, 4)
    if (!id) return null
    const size = vint(b, pos + id.length, limit, false, 8)
    if (!size) return null
    const data = pos + id.length + size.length
    return { id: id.value, data, size: size.value, end: size.value < 0 ? -1 : data + size.value }
}

function vint(b: Uint8Array, pos: number, limit: number, keepMarker: boolean, maxLength: number): { value: number; length: number } | null {
    if (pos >= limit) return null
    const first = b[pos]
    if (first === 0) return null
    const length = Math.clz32(first) - 23
    if (length > maxLength || pos + length > limit) return null
    // Sizes past 2^53 do not occur in a Chapters element; doubles hold the rest exactly.
    let value = keepMarker ? first : first & (0xff >> length)
    let allOnes = value === 0xff >> length
    for (let i = 1; i < length; i++) {
        const x = b[pos + i]
        if (x !== 0xff) allOnes = false
        value = value * 256 + x
    }
    return { value: !keepMarker && allOnes ? -1 : value, length }
}

function children(b: Uint8Array, from: number, to: number): El[] {
    const out: El[] = []
    let pos = from
    while (pos < to) {
        const e = element(b, pos, to)
        if (!e || e.size < 0 || e.end > to) break
        out.push(e)
        pos = e.end
    }
    return out
}

function uint(b: Uint8Array, e: El): number | null {
    if (e.size === 0) return 0
    if (e.size < 1 || e.size > 8) return null
    let v = 0
    for (let i = 0; i < e.size; i++) v = v * 256 + b[e.data + i]
    return v
}

const text = (b: Uint8Array, e: El) => new TextDecoder().decode(b.subarray(e.data, e.end)).replace(/\0+$/, '')

async function matroska(head: Uint8Array, read: Reader, language?: string): Promise<Chapter[]> {
    const ebml = element(head, 0)
    if (!ebml || ebml.id !== EBML || ebml.end < 0 || ebml.end >= head.length) return []
    const segment = element(head, ebml.end)
    if (!segment || segment.id !== SEGMENT) return []
    let pointed: number | null = null
    let pos = segment.data
    while (pos < head.length) {
        const at = pos
        const child = element(head, pos)
        if (!child) break
        if (child.id === CHAPTERS) {
            if (child.size < 0 || child.end > head.length) return fromElement(await read(at, MAX_ELEMENT), language)
            return parseChapters(head, child.data, child.end, language)
        }
        if (child.id === SEEK_HEAD && child.size >= 0 && child.end <= head.length) {
            const found = seekTo(head, child.data, child.end, CHAPTERS)
            if (found !== null) pointed = segment.data + found
        }
        if (child.id === CLUSTER || child.size < 0) break
        pos = child.end
    }
    if (pointed === null) return []
    if (pointed < head.length) return fromElement(head.subarray(pointed), language)
    const start = await read(pointed, 16)
    const first = start && element(start, 0)
    if (!first || first.size < 0 || first.end > MAX_ELEMENT) return []
    return fromElement(await read(pointed, first.end), language)
}

function fromElement(bytes: Uint8Array | null, language?: string): Chapter[] {
    if (!bytes) return []
    const e = element(bytes, 0)
    if (!e || e.id !== CHAPTERS || e.size < 0 || e.end > bytes.length) return []
    return parseChapters(bytes, e.data, e.end, language)
}

function seekTo(b: Uint8Array, from: number, to: number, target: number): number | null {
    for (const seek of children(b, from, to)) {
        if (seek.id !== SEEK) continue
        let id: number | null = null
        let position: number | null = null
        for (const field of children(b, seek.data, seek.end)) {
            if (field.id === SEEK_ID) id = uint(b, field)
            else if (field.id === SEEK_POSITION) position = uint(b, field)
        }
        if (id === target && position !== null) return position
    }
    return null
}

function parseChapters(b: Uint8Array, from: number, to: number, language?: string): Chapter[] {
    const editions = children(b, from, to)
        .filter((e) => e.id === EDITION)
        .map((edition) => {
            let isDefault = false
            let hidden = false
            const chapters: Chapter[] = []
            for (const child of children(b, edition.data, edition.end)) {
                if (child.id === EDITION_DEFAULT) isDefault = uint(b, child) === 1
                else if (child.id === EDITION_HIDDEN) hidden = uint(b, child) === 1
                else if (child.id === ATOM) {
                    const c = atom(b, child, language)
                    if (c) chapters.push(c)
                }
            }
            return { isDefault, hidden, chapters }
        })
    const chosen = editions.find((e) => e.isDefault && !e.hidden) ?? editions.find((e) => !e.hidden) ?? editions[0]
    return chosen ? finish(chosen.chapters) : []
}

function atom(b: Uint8Array, a: El, language?: string): Chapter | null {
    let startNs: number | null = null
    let hidden = false
    let enabled = true
    let linked = false
    const names: [string | null, string][] = []
    for (const child of children(b, a.data, a.end)) {
        if (child.id === TIME_START) startNs = uint(b, child)
        else if (child.id === FLAG_HIDDEN) hidden = uint(b, child) === 1
        else if (child.id === FLAG_ENABLED) enabled = uint(b, child) !== 0
        else if (child.id === SEGMENT_UID) linked = true
        else if (child.id === DISPLAY) {
            let name: string | null = null
            let lang: string | null = null
            for (const f of children(b, child.data, child.end)) {
                if (f.id === CHAP_STRING) name = text(b, f)
                else if ((f.id === CHAP_LANGUAGE || f.id === CHAP_LANGUAGE_BCP47) && lang === null) lang = text(b, f)
            }
            if (name?.trim()) names.push([lang, name.trim()])
        }
    }
    if (startNs === null || hidden || !enabled || linked) return null
    const wanted = language?.split('-')[0].toLowerCase()
    const title = (wanted && names.find(([lang]) => lang && lang.split('-')[0].toLowerCase().startsWith(wanted.slice(0, 2)))?.[1]) || names[0]?.[1] || null
    return { start: startNs / 1e9, title }
}

// ---- MP4 -----------------------------------------------------------------------------------

async function mp4(head: Uint8Array, read: Reader): Promise<Chapter[]> {
    if (String.fromCharCode(...head.subarray(4, 8)) !== 'ftyp' && String.fromCharCode(...head.subarray(4, 8)) !== 'moov' && String.fromCharCode(...head.subarray(4, 8)) !== 'wide') return []
    // The top level boxes up to moov.
    let offset = 0
    for (let n = 0; n < 32; n++) {
        const h = await read(offset, 16)
        if (!h || h.length < 8) return []
        const view = new DataView(h.buffer, h.byteOffset, h.length)
        let size = view.getUint32(0)
        const type = String.fromCharCode(...h.subarray(4, 8))
        let header = 8
        if (size === 1 && h.length >= 16) {
            size = Number(view.getBigUint64(8))
            header = 16
        }
        if (size < header) return []
        if (type === 'moov') {
            if (size > 4 * MAX_ELEMENT) return []
            const moov = await read(offset, size)
            return moov && moov.length === size ? chpl(moov, header, size) : []
        }
        offset += size
    }
    return []
}

/** Nero's chapter list: moov, udta, chpl. */
function chpl(b: Uint8Array, from: number, to: number): Chapter[] {
    const view = new DataView(b.buffer, b.byteOffset, b.length)
    const boxes = (start: number, end: number) => {
        const out: { type: string; data: number; end: number }[] = []
        let pos = start
        while (pos + 8 <= end) {
            const size = view.getUint32(pos)
            if (size < 8 || pos + size > end) break
            out.push({ type: String.fromCharCode(...b.subarray(pos + 4, pos + 8)), data: pos + 8, end: pos + size })
            pos += size
        }
        return out
    }
    for (const udta of boxes(from, to).filter((x) => x.type === 'udta')) {
        const box = boxes(udta.data, udta.end).find((x) => x.type === 'chpl')
        if (!box) continue
        let p = box.data
        const version = b[p]
        p += 4 + (version >= 1 ? 4 : 0)
        const count = b[p++]
        const out: Chapter[] = []
        for (let i = 0; i < count && p + 9 <= box.end; i++) {
            const start = Number(view.getBigUint64(p)) / 1e7
            const length = b[p + 8]
            p += 9
            if (p + length > box.end) break
            const title = new TextDecoder().decode(b.subarray(p, p + length)).trim()
            p += length
            out.push({ start, title: title || null })
        }
        if (out.length) return finish(out)
    }
    return []
}

// ---- skip offers ---------------------------------------------------------------------------

export type SkipKind = 'recap' | 'intro' | 'opening' | 'credits' | 'ending' | 'preview'

export interface SkipSegment {
    kind: SkipKind
    start: number
    end: number
}

const SKIP_NAMES: [RegExp, SkipKind][] = [
    [/\b(recap|previously)\b/i, 'recap'],
    [/\bopening\b|^op\d*$/i, 'opening'],
    [/\bintro(duction)?\b/i, 'intro'],
    [/\bending\b|^ed\d*$/i, 'ending'],
    [/\bcredits?\b/i, 'credits'],
    [/\b(preview|next time|coming up)\b/i, 'preview'],
]

/**
 * The stretches a file's own chapters name as a recap, intro, opening, credits, ending or preview
 * (the apps also time these from online lists, which a page cannot ask: a chapter titled that way
 * is what is left). One under five seconds or over two fifths of the video is not an intro.
 */
export function skipSegments(chapters: Chapter[], duration: number): SkipSegment[] {
    const out: SkipSegment[] = []
    chapters.forEach((c, i) => {
        const name = c.title?.trim()
        const kind = name ? SKIP_NAMES.find(([re]) => re.test(name))?.[1] : undefined
        if (!kind) return
        const end = chapters[i + 1]?.start ?? duration
        if (!(end - c.start >= 5) || (duration > 0 && end - c.start > duration * 0.4)) return
        out.push({ kind, start: c.start, end })
    })
    return out
}
