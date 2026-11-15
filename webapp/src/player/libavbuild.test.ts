// node --test: TMPlayer's libav.js builds in public/libav/ open and decode what they are built for.
// Each clip is made by the host's ffmpeg at test time (skipped, and said, when there is none or
// when that ffmpeg lacks the encoder): the engine build demuxes it and decodes every stream, and a
// picture comes out as I420 (libavframes.ts) unless the test says the engine refuses its layout.
// The sound build decodes the five codecs mediabunny names, through the class the player uses
// (libavsound.ts), and the `.mvp.wasm` lowered for Chromium 68 decodes as the full one does.
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { after, test } from 'node:test'
import { fileURLToPath } from 'node:url'
import { ALL_FORMATS, AudioSampleSink, BufferSource, Input } from 'mediabunny'
import type * as LibAVTypes from '../../libav/libav.types'
import { toI420 } from './libavframes.ts'

type LibAV = LibAVTypes.LibAV

const BASE = fileURLToPath(new URL('../../public/libav/', import.meta.url)).replace(/\/$/, '')
const VERSION = '6.10.9.0'
const hasFfmpeg = spawnSync('ffmpeg', ['-version']).status === 0
const encoders = hasFfmpeg ? spawnSync('ffmpeg', ['-hide_banner', '-encoders'], { encoding: 'utf8' }).stdout : ''
const dir = mkdtempSync(join(tmpdir(), 'tmplayer-libav-'))
after(() => rmSync(dir, { recursive: true, force: true }))

/** The clip made by the host's ffmpeg, or null when it lacks one of `need`. */
function clip(name: string, need: string[], args: string[], size = '160x128'): string | null {
    for (const e of need) if (!new RegExp(`^ \\S+ ${e} `, 'm').test(encoders)) return null
    const out = join(dir, name)
    const r = spawnSync('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-y', '-f', 'lavfi', '-i', `testsrc=size=${size}:rate=25`, '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000', '-t', '1', '-strict', '-2', ...args, out])
    if (r.status !== 0) {
        // Said, and the test skips: the host ffmpeg's encoder refused these settings.
        console.warn(`ffmpeg could not make ${name}: ${r.stderr}`)
        return null
    }
    return out
}

async function libav(variant: string, mvp = false): Promise<LibAV> {
    const m = await import(`${BASE}/libav-${VERSION}-tmplayer.mjs`)
    const LibAV = m.default ?? m
    const l: LibAV = await LibAV.LibAV({ noworker: true, base: BASE, variant, ...(mvp ? { wasmurl: `${BASE}/libav-${VERSION}-${variant}.mvp.wasm` } : {}) })
    // The damaged clips make FFmpeg complain; the assertions say what matters.
    await l.av_log_set_level(l.AV_LOG_QUIET)
    return l
}

let engine: Promise<LibAV> | null = null
const L = () => (engine ??= libav('tmplayer'))

/** Opens `file`, decodes every stream to its end; per stream: codec name, frames, and the first picture's I420 or null. */
async function decodeAll(L: LibAV, file: string) {
    const name = `in-${Math.random().toString(36).slice(2)}`
    await L.writeFile(name, new Uint8Array(readFileSync(file)))
    const [fmt, streams] = await L.ff_init_demuxer_file(name)
    const pkt = await L.av_packet_alloc()
    const decoders = new Map<number, { c: number; pkt: number; frame: number }>()
    const result = new Map<number, { codec: string; type: number; frames: number; i420: boolean | null }>()
    for (const s of streams) {
        const codec = await L.avcodec_get_name(s.codec_id)
        result.set(s.index, { codec, type: s.codec_type, frames: 0, i420: null })
        if (!(await L.avcodec_find_decoder(s.codec_id))) continue
        const [, c, p, f] = await L.ff_init_decoder(s.codec_id, { codecpar: s.codecpar, time_base: [s.time_base_num, s.time_base_den] })
        decoders.set(s.index, { c, pkt: p, frame: f })
    }
    for (;;) {
        const [res, packets] = await L.ff_read_frame_multi(fmt, pkt, { limit: 1024 * 1024 })
        const end = res === L.AVERROR_EOF
        for (const [index, d] of decoders) {
            const list = packets[index] ?? []
            if (!list.length && !end) continue
            const frames = await L.ff_decode_multi(d.c, d.pkt, d.frame, list, { fin: end, ignoreErrors: true, copyoutFrame: 'video_packed' })
            const r = result.get(index)!
            if (r.type === L.AVMEDIA_TYPE_VIDEO && frames.length && r.i420 === null) {
                const f = frames[0]
                const out = toI420({ format: f.format, width: f.width ?? 0, height: f.height ?? 0, data: f.data, layout: f.layout }, L)
                r.i420 = !!out && out.data.length === out.width * out.height * 1.5
            }
            r.frames += frames.length
        }
        if (end || (res < 0 && res !== -L.EAGAIN)) break
    }
    for (const d of decoders.values()) await L.ff_free_decoder(d.c, d.pkt, d.frame)
    await L.av_packet_free_js(pkt)
    await L.avformat_close_input_js(fmt)
    await L.unlink(name)
    return [...result.values()]
}

/** Clips the engine plays: [file, encoders it needs, ffmpeg arguments, picture size, the codecs it must find]. */
const CLIPS: [string, string[], string[], string | undefined, string[]][] = [
    ['wmv2-wmav2.wmv', ['wmv2', 'wmav2'], ['-c:v', 'wmv2', '-c:a', 'wmav2'], undefined, ['wmv2', 'wmav2']],
    ['wmv1-wmav1.asf', ['wmv1', 'wmav1'], ['-c:v', 'wmv1', '-c:a', 'wmav1'], undefined, ['wmv1', 'wmav1']],
    ['flv-nellymoser.flv', ['flv', 'nellymoser'], ['-c:v', 'flv', '-c:a', 'nellymoser', '-ar', '22050', '-ac', '1'], undefined, ['flv1', 'nellymoser']],
    ['flv-adpcm.flv', ['flv', 'adpcm_swf'], ['-c:v', 'flv', '-c:a', 'adpcm_swf', '-ar', '22050'], undefined, ['flv1', 'adpcm_swf']],
    ['h264-aac.flv', ['libx264', 'aac'], ['-c:v', 'libx264', '-c:a', 'aac'], undefined, ['h264', 'aac']],
    ['rv10-ra144.rm', ['rv10', 'real_144'], ['-c:v', 'rv10', '-c:a', 'real_144', '-ar', '8000', '-ac', '1'], undefined, ['rv10', 'ra_144']],
    ['rv20-ac3.rm', ['rv20', 'ac3'], ['-c:v', 'rv20', '-c:a', 'ac3'], undefined, ['rv20', 'ac3']],
    ['mpeg2-mp2.vob', ['mpeg2video', 'mp2'], ['-c:v', 'mpeg2video', '-c:a', 'mp2', '-f', 'vob'], '720x576', ['mpeg2video', 'mp2']],
    ['mpeg1-mp2.mpg', ['mpeg1video', 'mp2'], ['-c:v', 'mpeg1video', '-c:a', 'mp2', '-f', 'mpeg'], undefined, ['mpeg1video', 'mp2']],
    ['mpeg2-mp2.ts', ['mpeg2video', 'mp2'], ['-c:v', 'mpeg2video', '-c:a', 'mp2'], undefined, ['mpeg2video', 'mp2']],
    ['h263-amr.3gp', ['h263', 'libopencore_amrnb'], ['-c:v', 'h263', '-c:a', 'libopencore_amrnb', '-ar', '8000', '-ac', '1'], '176x144', ['h263', 'amr_nb']],
    ['mpeg4-mp3.mp4', ['mpeg4', 'libmp3lame'], ['-c:v', 'mpeg4', '-c:a', 'libmp3lame'], undefined, ['mpeg4', 'mp3']],
    ['mpeg4-truehd.mkv', ['mpeg4', 'truehd'], ['-c:v', 'mpeg4', '-c:a', 'truehd'], undefined, ['mpeg4', 'truehd']],
    ['h264-mlp.mkv', ['libx264', 'mlp'], ['-c:v', 'libx264', '-c:a', 'mlp'], undefined, ['h264', 'mlp']],
    ['mpeg4-alac.mkv', ['mpeg4', 'alac'], ['-c:v', 'mpeg4', '-c:a', 'alac'], undefined, ['mpeg4', 'alac']],
    ['mpeg4-wavpack.mkv', ['mpeg4', 'wavpack'], ['-c:v', 'mpeg4', '-c:a', 'wavpack'], undefined, ['mpeg4', 'wavpack']],
    ['mpeg4-tta.mkv', ['mpeg4', 'tta'], ['-c:v', 'mpeg4', '-c:a', 'tta'], undefined, ['mpeg4', 'tta']],
    ['mpeg4-mp2.mkv', ['mpeg4', 'mp2'], ['-c:v', 'mpeg4', '-c:a', 'mp2'], undefined, ['mpeg4', 'mp2']],
    ['mjpeg-pcm.avi', ['mjpeg', 'pcm_u8'], ['-c:v', 'mjpeg', '-pix_fmt', 'yuvj422p', '-c:a', 'pcm_u8'], undefined, ['mjpeg', 'pcm_u8']],
    ['msmpeg4v2-adpcm.avi', ['msmpeg4v2', 'adpcm_ms'], ['-c:v', 'msmpeg4v2', '-c:a', 'adpcm_ms'], undefined, ['msmpeg4v2', 'adpcm_ms']],
    ['dv-pcm.avi', ['dvvideo', 'pcm_s16le'], ['-c:v', 'dvvideo', '-pix_fmt', 'yuv411p', '-r', '30000/1001', '-c:a', 'pcm_s16le'], '720x480', ['dvvideo', 'pcm_s16le']],
    ['h264-dts.avi', ['libx264', 'dca'], ['-c:v', 'libx264', '-c:a', 'dca', '-ac', '2'], undefined, ['h264', 'dts']],
    ['h263p-gsm.avi', ['h263p', 'libgsm_ms'], ['-c:v', 'h263p', '-c:a', 'libgsm_ms', '-ar', '8000', '-ac', '1'], '176x144', ['h263', 'gsm_ms']],
    ['cinepak-alaw.avi', ['cinepak', 'pcm_alaw'], ['-c:v', 'cinepak', '-c:a', 'pcm_alaw'], undefined, ['cinepak', 'pcm_alaw']],
    ['theora-vorbis.ogv', ['libtheora', 'libvorbis'], ['-c:v', 'libtheora', '-c:a', 'libvorbis'], undefined, ['theora', 'vorbis']],
    ['theora-speex.ogv', ['libtheora', 'libspeex'], ['-c:v', 'libtheora', '-c:a', 'libspeex', '-ar', '16000', '-ac', '1'], undefined, ['theora', 'speex']],
    ['svq1-ima.mov', ['svq1', 'adpcm_ima_qt'], ['-c:v', 'svq1', '-c:a', 'adpcm_ima_qt'], undefined, ['svq1', 'adpcm_ima_qt']],
    ['mpeg4-flac.mkv', ['mpeg4', 'flac'], ['-c:v', 'mpeg4', '-c:a', 'flac'], undefined, ['mpeg4', 'flac']],
    ['mpeg4-opus.mkv', ['mpeg4', 'libopus'], ['-c:v', 'mpeg4', '-c:a', 'libopus'], undefined, ['mpeg4', 'opus']],
    ['mpeg4-eac3.mkv', ['mpeg4', 'eac3'], ['-c:v', 'mpeg4', '-c:a', 'eac3'], undefined, ['mpeg4', 'eac3']],
]

/** Copied, not decoded, by the engine (patents): its build only parses them. */
const COPIED = new Set(['h264'])
/** Decoded, but in a layout the engine does not convert (RGB555): it says the video needs the app. */
const NOT_CONVERTED = new Set(['msvideo1'])

for (const [file, need, args, size, codecs] of CLIPS) {
    const path = hasFfmpeg ? clip(file, need, args, size) : null
    test(`engine build: ${file}`, { skip: !path && `no host ffmpeg encoder for ${need.join(', ')}` }, async () => {
        const streams = await decodeAll(await L(), path!)
        for (const codec of codecs) {
            const s = streams.find((x) => x.codec === codec)
            assert.ok(s, `${file}: no ${codec} stream (found ${streams.map((x) => x.codec).join(', ')})`)
            if (COPIED.has(codec)) continue
            assert.ok(s.frames > 0, `${file}: ${codec} decoded nothing`)
            if (s.type === (await L()).AVMEDIA_TYPE_VIDEO) assert.equal(s.i420, !NOT_CONVERTED.has(codec), `${file}: ${codec} picture to I420`)
        }
    })
}

test('engine build: a damaged file fails to open or decodes what it can, without a crash', { skip: !hasFfmpeg && 'no host ffmpeg' }, async () => {
    const path = clip('damaged-src.wmv', ['wmv2', 'wmav2'], ['-c:v', 'wmv2', '-c:a', 'wmav2'])
    if (!path) return
    const good = readFileSync(path)
    const l = await L()
    for (let seed = 1; seed <= 6; seed++) {
        const bad = new Uint8Array(good)
        let x = seed * 2654435761
        const rnd = () => ((x = (x * 1103515245 + 12345) >>> 0) / 2 ** 32)
        if (seed % 3 === 0) bad.fill(0, Math.floor(bad.length * rnd()), Math.floor(bad.length * rnd()))
        else for (let i = 0; i < 400; i++) bad[Math.floor(rnd() * bad.length)] ^= 0xff
        const cut = seed % 2 ? bad : bad.subarray(0, Math.floor(bad.length * (0.2 + rnd() * 0.6)))
        const at = join(dir, `damaged-${seed}.wmv`)
        await import('node:fs').then((fs) => fs.writeFileSync(at, cut))
        await decodeAll(l, at).catch(() => null)
    }
    // Still answers after the damaged files.
    const again = await decodeAll(l, path)
    assert.ok(again.every((s) => s.frames > 0))
})

test('the lowered .mvp.wasm builds decode as the full ones do', { skip: !hasFfmpeg && 'no host ffmpeg' }, async () => {
    const path = clip('mvp.vob', ['mpeg2video', 'mp2'], ['-c:v', 'mpeg2video', '-c:a', 'mp2', '-f', 'vob'])
    if (!path) return
    const full = await decodeAll(await L(), path)
    const mvp = await libav('tmplayer', true)
    assert.deepEqual(await decodeAll(mvp, path), full)
    mvp.terminate()
})

/** Sound clips for the sound build, through mediabunny as the player reads them. */
const SOUNDS: [string, string[], string[], 'aac' | 'mp3' | 'opus' | 'vorbis' | 'flac'][] = [
    ['vorbis.mkv', ['libvorbis'], ['-c:a', 'libvorbis'], 'vorbis'],
    ['opus.mkv', ['libopus'], ['-c:a', 'libopus'], 'opus'],
    ['flac.mkv', ['flac'], ['-c:a', 'flac'], 'flac'],
    ['aac.mp4', ['aac'], ['-c:a', 'aac'], 'aac'],
    ['mp3.mp4', ['libmp3lame'], ['-c:a', 'libmp3lame'], 'mp3'],
    // MPEG-TS Layer II, which mediabunny names mp3: FFmpeg's MP3 decoder takes every layer.
    ['mp2.ts', ['mp2'], ['-c:a', 'mp2'], 'mp3'],
]

for (const [file, need, args, codec] of SOUNDS) {
    const path = hasFfmpeg ? clip(file, ['mpeg4', ...need], ['-c:v', 'mpeg4', ...args]) : null
    test(`sound build through mediabunny: ${file}`, { skip: !path && `no host ffmpeg encoder for ${need.join(', ')}` }, async () => {
        const { setLibavSoundStarter, useLibavSound } = await import('./libavsound.ts')
        setLibavSoundStarter(() => libav('tmplayer-sound'))
        assert.ok(useLibavSound(codec))
        const input = new Input({ formats: ALL_FORMATS, source: new BufferSource(readFileSync(path!)) })
        const track = await input.getPrimaryAudioTrack()
        assert.ok(track)
        assert.equal(track.codec, codec)
        assert.ok(await track.canDecode(), 'mediabunny takes the FFmpeg decoder')
        let samples = 0
        let seconds = 0
        let last = -1
        for await (const s of new AudioSampleSink(track).samples()) {
            samples++
            seconds += s.duration
            assert.ok(s.timestamp >= last - 0.001, 'times go forward')
            last = s.timestamp
            s.close()
        }
        assert.ok(samples > 0, 'decoded sound')
        assert.ok(seconds > 0.8 && seconds < 1.3, `about a second of sound, got ${seconds}`)
        input.dispose()
    })
}
