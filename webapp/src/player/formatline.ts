// The one plain line the error card says about a file that would not play (EG-F37):
// "HEVC Main 10 L5.0, 3840 x 2160, E-AC-3 5.1, Matroska". The same words as the apps' FormatLine
// (core/src/pureMain/.../player/FormatLine.kt), whose tests this file's tests repeat, so a file reads
// the same on every platform. Pure and total: everything here comes from a file's own header, so a
// missing, empty, absurdly long or unknown part is left out and nothing throws.

export interface FormatFacts {
    /** mediabunny's name for the picture codec (`avc`, `hevc`, `vp9`, `av1`, `vp8`), or its plain-words fallback for one it does not know. */
    videoCodec: string | null
    /** The picture's codec parameter string (`hvc1.2.4.L150.B0`), when the file gives one. */
    videoParams: string | null
    width: number
    height: number
    /** `pq` or `hlg` where the file says, anything else is left out. */
    transfer: string | null
    /** mediabunny's name for the sound codec (`aac`, `ac3`, `eac3`, `opus`...), or its fallback. */
    audioCodec: string | null
    audioParams: string | null
    channels: number
    /** The container as mediabunny names it (`MP4`, `Matroska`, `WebM`, `MPEG-TS`). */
    container: string | null
}

export const MAX_PART = 40
const MAX_SIDE = 20_000

/** Outside text made safe for one line: control characters and runs of space dropped, cut at `limit` with "...". Null when nothing is left. */
export function clean(text: string | null | undefined, limit = MAX_PART): string | null {
    if (typeof text !== 'string') return null
    const head = text.length > limit * 4 ? text.slice(0, limit * 4) : text
    // eslint-disable-next-line no-control-regex
    const flat = head.replace(/[\u0000-\u001f\u007f\s]+/g, ' ').trim()
    if (!flat) return null
    return flat.length > limit ? flat.slice(0, limit).trimEnd() + '...' : flat
}

const levelText = (level: number, per: number): string | null => (level > 0 && level <= 255 ? `L${Math.floor(level / per)}.${Math.floor(((level % per) * 10) / per)}` : null)

const join = (parts: (string | null)[]): string | null => parts.filter(Boolean).join(' ') || null

function hevcDetail(params: string): string | null {
    const fields = params.split(',')[0].split('.')
    if (fields.length < 2 || (fields[0] !== 'hvc1' && fields[0] !== 'hev1')) return null
    const profile = parseInt(fields[1].replace(/^[abc]/, ''), 10)
    const names: Record<number, string> = { 1: 'Main', 2: 'Main 10', 3: 'Main Still Picture', 4: 'Range Extensions', 5: 'High Throughput', 9: 'Screen Content' }
    const level = fields[3]?.startsWith('l') ? parseInt(fields[3].slice(1), 10) : NaN
    return join([names[profile] ?? null, Number.isFinite(level) ? levelText(level, 30) : null])
}

function avcDetail(params: string): string | null {
    const tag = params.split(',')[0].split('.')[1] ?? ''
    if (!/^[0-9a-f]{6}$/.test(tag)) return null
    const names: Record<number, string> = { 66: 'Baseline', 77: 'Main', 88: 'Extended', 100: 'High', 110: 'High 10', 122: 'High 4:2:2', 244: 'High 4:4:4', 44: 'CAVLC 4:4:4' }
    return join([names[parseInt(tag.slice(0, 2), 16)] ?? null, levelText(parseInt(tag.slice(4, 6), 16), 10)])
}

function av1Detail(params: string): string | null {
    const fields = params.split(',')[0].split('.')
    if (fields.length < 4 || fields[0] !== 'av01') return null
    const names: Record<string, string> = { '0': 'Main', '1': 'High', '2': 'Professional' }
    const index = /^\d{1,2}/.exec(fields[2])?.[0]
    const level = index !== undefined && +index <= 31 ? `L${2 + Math.floor(+index / 4)}.${+index % 4}` : null
    const bits = /^\d+$/.test(fields[3]) && +fields[3] >= 8 && +fields[3] <= 16 ? `${+fields[3]}-bit` : null
    return join([names[fields[1]] ?? null, level, bits])
}

function vp9Detail(params: string): string | null {
    const fields = params.split(',')[0].split('.')
    if (fields.length < 2 || fields[0] !== 'vp09') return null
    const profile = /^\d+$/.test(fields[1]) && +fields[1] <= 3 ? `Profile ${+fields[1]}` : null
    const level = /^\d+$/.test(fields[2] ?? '') && +fields[2] >= 10 && +fields[2] <= 62 ? levelText(+fields[2], 10) : null
    return join([profile, level])
}

const VIDEO_NAMES: Record<string, string> = { hevc: 'HEVC', avc: 'H.264', av1: 'AV1', vp9: 'VP9', vp8: 'VP8' }

/** "HEVC Main 10 L5.0", "H.264 High L4.0", or null when nothing is known. */
export function videoName(codec: string | null, params: string | null): string | null {
    const p = clean(params, 80)?.toLowerCase() ?? null
    const c = clean(codec, 80)
    const key = c?.toLowerCase() ?? null
    const family = (key && VIDEO_NAMES[key] ? key : null) ?? (p ? ({ hvc1: 'hevc', hev1: 'hevc', avc1: 'avc', avc3: 'avc', av01: 'av1', vp09: 'vp9', vp08: 'vp8' } as Record<string, string>)[p.split('.')[0]] : null) ?? null
    if (!family) return clean(c)
    const detail = p ? (family === 'hevc' ? hevcDetail(p) : family === 'avc' ? avcDetail(p) : family === 'av1' ? av1Detail(p) : family === 'vp9' ? vp9Detail(p) : null) : null
    return detail ? `${VIDEO_NAMES[family]} ${detail}` : VIDEO_NAMES[family]
}

const AUDIO_NAMES: Record<string, string> = {
    aac: 'AAC',
    ac3: 'AC-3',
    eac3: 'E-AC-3',
    opus: 'Opus',
    vorbis: 'Vorbis',
    flac: 'FLAC',
    mp3: 'MP3',
    dts: 'DTS',
    truehd: 'TrueHD',
    alac: 'ALAC',
}

/** "E-AC-3", "AAC LC", or null when nothing is known. */
export function audioName(codec: string | null, params: string | null): string | null {
    const c = clean(codec, 80)
    const key = c?.toLowerCase() ?? null
    if (key === 'aac') {
        const object = /^mp4a\.40\.(\d+)/.exec(clean(params, 80)?.toLowerCase() ?? '')?.[1]
        return ({ '2': 'AAC LC', '5': 'HE-AAC', '29': 'HE-AAC v2', '23': 'AAC LD', '39': 'AAC ELD', '42': 'xHE-AAC' } as Record<string, string>)[object ?? ''] ?? 'AAC'
    }
    if (key && AUDIO_NAMES[key]) return AUDIO_NAMES[key]
    return clean(c) ?? (params ? (/^mp4a/i.test(params) ? 'AAC' : null) : null)
}

/** Channels as people say them: 6 is 5.1, 8 is 7.1; 0 or an absurd count is unknown. */
export function channelLayout(channels: number): string | null {
    const named: Record<number, string> = { 1: 'mono', 2: 'stereo', 3: '3.0', 4: '4.0', 5: '5.0', 6: '5.1', 7: '6.1', 8: '7.1' }
    if (named[channels]) return named[channels]
    return channels >= 9 && channels <= 32 ? `${channels} ch` : null
}

/** The line, empty when nothing is known of the file. */
export function formatLine(f: FormatFacts | null | undefined): string {
    if (!f) return ''
    const parts: string[] = []
    const video = videoName(f.videoCodec, f.videoParams)
    if (video) {
        parts.push(video)
        if (Number.isFinite(f.width) && Number.isFinite(f.height) && f.width >= 1 && f.width <= MAX_SIDE && f.height >= 1 && f.height <= MAX_SIDE) parts.push(`${f.width} x ${f.height}`)
        if (f.transfer === 'pq') parts.push('HDR10')
        else if (f.transfer === 'hlg') parts.push('HLG')
    }
    const audio = audioName(f.audioCodec, f.audioParams)
    if (audio) {
        const layout = channelLayout(f.channels)
        parts.push(layout ? `${audio} ${layout}` : audio)
    }
    const container = clean(f.container)
    if (container) parts.push(container)
    return parts.join(', ')
}

/**
 * What the card's Copy details puts on the clipboard: the line, what this browser says about the
 * codecs (`support`: "video/mp4; codecs=... yes"), the datacenter when the file has one, and the
 * browser. No title, chat or file name.
 */
export function copyDetails(line: string, support: string[], dc: number | undefined, browser: string): string {
    const rows = ['TMPlayer web']
    const shown = clean(line, MAX_PART * 8)
    if (shown) rows.push(`Format: ${shown}`)
    for (const s of support.slice(0, 8)) {
        const row = clean(s, 120)
        if (row) rows.push(`Decoder support ${row}`)
    }
    if (dc && dc >= 1 && dc <= 5) rows.push(`Datacenter: DC ${dc}`)
    const agent = clean(browser, 200)
    if (agent) rows.push(`Browser: ${agent}`)
    return rows.join('\n')
}
