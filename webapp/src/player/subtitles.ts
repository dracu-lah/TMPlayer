// A subtitle file from the device laid over the video playing now, as the apps take one (the
// phone's document picker, the desktop's drop): SubRip, SubStation Alpha, WebVTT and MicroDVD
// `.sub`, all turned into WebVTT text cues. Text cues are what the browser draws itself, and the
// only kind an iPhone shows in its own full screen. Styled ASS is read as plain text, as the app's
// Cast does; its styling waits for JASSUB (section 2 step C3).
import { parse as parseAss } from 'ass-compiler'

/** What the file chooser offers, as ExternalSubtitle.EXTENSIONS. */
export const SUBTITLE_EXTENSIONS = ['srt', 'ass', 'ssa', 'vtt', 'sub']
/**
 * The subtitle file in what was dropped on the player, the first by extension, or null. Case does
 * not matter, a folder or a video among the files is ignored, and a drop with no subtitle does nothing.
 */
export function subtitleFromDrop(files: ArrayLike<File> | null | undefined): File | null {
    for (const file of Array.from(files ?? [])) {
        const ext = file.name.slice(file.name.lastIndexOf('.') + 1).toLowerCase()
        if (file.name.includes('.') && SUBTITLE_EXTENSIONS.includes(ext)) return file
    }
    return null
}

/** A feature's subtitles are tens of kilobytes; anything far larger is not a subtitle file. */
const MAX_BYTES = 4 * 1024 * 1024
/** Frames a second for a MicroDVD file that does not say, the film rate. */
const DEFAULT_FPS = 23.976
const LANGUAGE_TAG = /^[A-Za-z]{2,3}([-_][A-Za-z0-9]{2,4})?$/

export class SubtitleError extends Error {
    readonly why: 'too-big' | 'unreadable' | 'vobsub' | 'empty'
    constructor(why: 'too-big' | 'unreadable' | 'vobsub' | 'empty') {
        super(why)
        this.why = why
    }
}

interface Cue {
    start: number
    end: number
    text: string
}

export interface LoadedSubtitle {
    /** A WebVTT blob for a `<track>`; revoke it when the player closes. */
    url: string
    label: string
    /** The language its name gives before the extension ("Show.S01E01.pt-BR.srt"), else empty. */
    language: string
}

const extensionOf = (name: string) => (name.includes('.') ? name.slice(name.lastIndexOf('.') + 1).toLowerCase() : '')

/** As ExternalSubtitle.languageOf: the part before the extension when it reads as a language tag. */
function languageOf(name: string): string {
    const parts = name.split('.')
    if (parts.length < 3) return ''
    const tag = parts[parts.length - 2]
    return LANGUAGE_TAG.test(tag) ? tag.replace('_', '-').toLowerCase() : ''
}

/** UTF-8 or UTF-16 by their marks, UTF-8 when it is valid, else the Western code page old files use. */
function decode(bytes: Uint8Array): string {
    if (bytes[0] === 0xff && bytes[1] === 0xfe) return new TextDecoder('utf-16le').decode(bytes)
    if (bytes[0] === 0xfe && bytes[1] === 0xff) return new TextDecoder('utf-16be').decode(bytes)
    try {
        return new TextDecoder('utf-8', { fatal: true }).decode(bytes).replace(/^﻿/, '')
    } catch {
        return new TextDecoder('windows-1252').decode(bytes)
    }
}

/** "00:01:02,500" or "01:02.5" in seconds; NaN when it is not a time. */
function seconds(stamp: string): number {
    const m = /^(?:(\d+):)?(\d{1,2}):(\d{1,2})(?:[.,](\d{1,3}))?$/.exec(stamp.trim())
    if (!m) return NaN
    return Number(m[1] ?? 0) * 3600 + Number(m[2]) * 60 + Number(m[3]) + (m[4] ? Number(m[4].padEnd(3, '0')) / 1000 : 0)
}

function parseSrt(text: string): Cue[] {
    const cues: Cue[] = []
    for (const block of text.replace(/\r\n?/g, '\n').split(/\n{2,}/)) {
        const lines = block.split('\n')
        const at = lines.findIndex((l) => l.includes('-->'))
        if (at < 0) continue
        const [from, to] = lines[at].split('-->')
        const start = seconds(from)
        const end = seconds(to.trim().split(/\s+/)[0])
        const body = lines.slice(at + 1).join('\n').trim()
        if (Number.isFinite(start) && Number.isFinite(end) && body) cues.push({ start, end, text: body.replace(/<\/?font[^>]*>/gi, '') })
    }
    return cues
}

function parseAssText(text: string): Cue[] {
    return parseAss(text)
        .events.dialogue.map((e) => ({
            start: e.Start,
            end: e.End,
            text: e.Text.combined.replace(/\\[Nn]/g, '\n').replace(/\\h/g, ' ').trim(),
        }))
        .filter((c) => c.text)
        .sort((a, b) => a.start - b.start)
}

/** `{100}{250}Text|Second line`, frame numbers at the rate the file's first cue may give (`{1}{1}23.976`). */
function parseMicroDvd(text: string): Cue[] {
    const cues: Cue[] = []
    let fps = DEFAULT_FPS
    for (const line of text.split(/\r?\n/)) {
        const m = /^\{(\d+)\}\{(\d+)\}(.*)$/.exec(line.trim())
        if (!m) continue
        const [, a, b, body] = m
        if (cues.length === 0 && a === b && /^\d+(\.\d+)?$/.test(body.trim())) {
            fps = Number(body.trim()) || DEFAULT_FPS
            continue
        }
        const clean = body.replace(/\{[^}]*\}/g, '').split('|').join('\n').trim()
        if (clean) cues.push({ start: Number(a) / fps, end: Number(b) / fps, text: clean })
    }
    return cues
}

function stamp(s: number): string {
    const ms = Math.max(0, Math.round(s * 1000))
    const h = Math.floor(ms / 3_600_000)
    const m = Math.floor((ms % 3_600_000) / 60_000)
    const sec = Math.floor((ms % 60_000) / 1000)
    return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:${String(sec).padStart(2, '0')}.${String(ms % 1000).padStart(3, '0')}`
}

function writeVtt(cues: Cue[]): string {
    let out = 'WEBVTT\n\n'
    for (const c of cues) out += `${stamp(c.start)} --> ${stamp(Math.max(c.end, c.start + 0.1))}\n${c.text.replace(/-->/g, '->')}\n\n`
    return out
}

/** The file as a WebVTT track the player can show. */
export async function readSubtitle(file: File): Promise<LoadedSubtitle> {
    if (file.size > MAX_BYTES) throw new SubtitleError('too-big')
    const ext = extensionOf(file.name)
    const text = decode(new Uint8Array(await file.arrayBuffer()))
    let vtt: string
    if (ext === 'vtt' || text.startsWith('WEBVTT')) vtt = text.startsWith('WEBVTT') ? text : `WEBVTT\n\n${text}`
    else {
        let cues: Cue[]
        if (ext === 'ass' || ext === 'ssa' || /^\s*\[Script Info\]/i.test(text)) cues = parseAssText(text)
        else if (ext === 'sub') {
            // The other `.sub`, VobSub, is pictures with an `.idx` beside it.
            if (!/^\s*\{\d+\}\{\d+\}/.test(text)) throw new SubtitleError('vobsub')
            cues = parseMicroDvd(text)
        } else cues = parseSrt(text)
        if (!cues.length) throw new SubtitleError(text.trim() ? 'unreadable' : 'empty')
        vtt = writeVtt(cues)
    }
    const url = URL.createObjectURL(new Blob([vtt], { type: 'text/vtt' }))
    return { url, label: file.name.replace(/\.[^.]+$/, ''), language: languageOf(file.name) }
}
