// node --test: libav.js frames made into I420 and sound buffers, MPEG audio layers, the decoder
// names of tracks mediabunny cannot name, and the software picture cap (libavframes.ts).
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { joinSound, mpegAudioLayer, softwarePixels, soundDecoderFor, toI420, type PixelFormats } from './libavframes.ts'

// FFmpeg 9.0's AVPixelFormat numbers, as libav.js exports them.
const PF: PixelFormats = {
    AV_PIX_FMT_YUV420P: 0,
    AV_PIX_FMT_RGB24: 2,
    AV_PIX_FMT_BGR24: 3,
    AV_PIX_FMT_YUV422P: 4,
    AV_PIX_FMT_YUV444P: 5,
    AV_PIX_FMT_YUV410P: 6,
    AV_PIX_FMT_YUV411P: 7,
    AV_PIX_FMT_GRAY8: 8,
    AV_PIX_FMT_YUVJ420P: 12,
    AV_PIX_FMT_YUVJ422P: 13,
    AV_PIX_FMT_YUVJ444P: 14,
    AV_PIX_FMT_ARGB: 25,
    AV_PIX_FMT_RGBA: 26,
    AV_PIX_FMT_ABGR: 27,
    AV_PIX_FMT_BGRA: 28,
}

/** A planar picture with each plane filled with one value. */
function planar(width: number, height: number, cw: number, ch: number, y: number, u: number, v: number) {
    const data = new Uint8Array(width * height + 2 * cw * ch)
    data.fill(y, 0, width * height)
    data.fill(u, width * height, width * height + cw * ch)
    data.fill(v, width * height + cw * ch)
    return data
}

test('I420 passes through, odd sizes are cropped to even', () => {
    const f = { format: PF.AV_PIX_FMT_YUV420P, width: 5, height: 3, data: planar(5, 3, 3, 2, 50, 60, 70) }
    const out = toI420(f, PF)
    assert.ok(out)
    assert.equal(out.width, 4)
    assert.equal(out.height, 2)
    assert.equal(out.data.length, 4 * 2 + 2 * 2 * 1)
    assert.deepEqual([...out.data], [50, 50, 50, 50, 50, 50, 50, 50, 60, 60, 70, 70])
})

test('4:2:2, 4:4:4, 4:1:1 and 4:1:0 chroma come out at 4:2:0', () => {
    for (const [format, xs, ys] of [
        [PF.AV_PIX_FMT_YUVJ422P, 2, 1],
        [PF.AV_PIX_FMT_YUV444P, 1, 1],
        [PF.AV_PIX_FMT_YUV411P, 4, 1],
        [PF.AV_PIX_FMT_YUV410P, 4, 4],
    ]) {
        const w = 16
        const h = 8
        const out = toI420({ format, width: w, height: h, data: planar(w, h, Math.ceil(w / xs), Math.ceil(h / ys), 100, 30, 200) }, PF)
        assert.ok(out, `format ${format}`)
        assert.equal(out.data.length, w * h + 2 * (w / 2) * (h / 2))
        assert.equal(out.data[w * h], 30)
        assert.equal(out.data[out.data.length - 1], 200)
    }
})

test('libav.js plane layout with padded strides is followed', () => {
    // 4 x 2 picture, luma stride 8, chroma stride 4.
    const data = new Uint8Array(8 * 2 + 4 * 1 * 2).fill(9)
    for (let y = 0; y < 2; y++) for (let x = 0; x < 4; x++) data[y * 8 + x] = 1
    data[16] = 2
    data[17] = 2
    data[20] = 3
    data[21] = 3
    const out = toI420({ format: PF.AV_PIX_FMT_YUV420P, width: 4, height: 2, data, layout: [{ offset: 0, stride: 8 }, { offset: 16, stride: 4 }, { offset: 20, stride: 4 }] }, PF)
    assert.ok(out)
    assert.deepEqual([...out.data], [1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 3, 3])
})

test('RGB becomes studio range YUV: white, black and red', () => {
    const px = (rgb: number[]) => new Uint8Array([...rgb, ...rgb, ...rgb, ...rgb])
    const white = toI420({ format: PF.AV_PIX_FMT_RGB24, width: 2, height: 2, data: px([255, 255, 255]) }, PF)
    assert.ok(white)
    assert.ok(Math.abs(white.data[0] - 235) <= 1 && Math.abs(white.data[4] - 128) <= 1)
    const black = toI420({ format: PF.AV_PIX_FMT_BGRA, width: 2, height: 2, data: px([0, 0, 0, 255]) }, PF)
    assert.ok(black)
    assert.ok(Math.abs(black.data[0] - 16) <= 1)
    const red = toI420({ format: PF.AV_PIX_FMT_RGBA, width: 2, height: 2, data: px([255, 0, 0, 255]) }, PF)
    assert.ok(red)
    assert.ok(red.data[5] > 200, 'V is high for red')
    const redBgr = toI420({ format: PF.AV_PIX_FMT_BGR24, width: 2, height: 2, data: px([0, 0, 255]) }, PF)
    assert.deepEqual(redBgr?.data, red.data)
})

test('garbage frames give null, never a throw', () => {
    assert.equal(toI420({ format: PF.AV_PIX_FMT_YUV420P, width: 64, height: 64, data: new Uint8Array(10) }, PF), null)
    assert.equal(toI420({ format: 999, width: 4, height: 4, data: new Uint8Array(100) }, PF), null)
    assert.equal(toI420({ format: PF.AV_PIX_FMT_YUV420P, width: 0, height: 4, data: new Uint8Array(100) }, PF), null)
    assert.equal(toI420({ format: PF.AV_PIX_FMT_YUV420P, width: 1, height: 1, data: new Uint8Array(100) }, PF), null)
    assert.equal(toI420({ format: PF.AV_PIX_FMT_RGB24, width: 4, height: 4, data: new Uint8Array(3) }, PF), null)
    assert.equal(toI420({ format: PF.AV_PIX_FMT_YUV420P, width: 4, height: 4, data: new Uint8Array(100), layout: [{ offset: 90, stride: 4 }, { offset: 0, stride: 2 }, { offset: 0, stride: 2 }] }, PF), null)
    assert.equal(toI420({ format: PF.AV_PIX_FMT_YUV420P, width: 4, height: 4, data: 'x' as unknown as Uint8Array }, PF), null)
    for (let i = 0; i < 200; i++) {
        const w = Math.floor(Math.random() * 40)
        const h = Math.floor(Math.random() * 40)
        const layout = Math.random() < 0.5 ? undefined : [0, 1, 2].map(() => ({ offset: Math.floor(Math.random() * 3000) - 100, stride: Math.floor(Math.random() * 60) }))
        const format = Object.values(PF)[i % 15]
        const out = toI420({ format, width: w, height: h, data: new Uint8Array(Math.floor(Math.random() * 3000)), layout }, PF)
        if (out) assert.equal(out.data.length, out.width * out.height * 1.5)
    }
})

test('sound planes are joined, doubles become floats, garbage gives null', () => {
    const joined = joinSound([new Float32Array([1, 2]), new Float32Array([3, 4])])
    assert.deepEqual([...(joined as Float32Array)], [1, 2, 3, 4])
    assert.ok(joinSound([new Float64Array([0.5])]) instanceof Float32Array)
    assert.ok(joinSound(new Float64Array([0.5])) instanceof Float32Array)
    assert.ok(joinSound(new Int16Array([1])) instanceof Int16Array)
    assert.equal(joinSound([]), null)
    assert.equal(joinSound([new Float32Array(2), new Float32Array(3)]), null)
    assert.equal(joinSound([null]), null)
    assert.equal(joinSound('x'), null)
    assert.equal(joinSound(undefined), null)
})

test('MPEG audio layer from the frame header', () => {
    assert.equal(mpegAudioLayer(new Uint8Array([0xff, 0xfb, 0x90, 0x00])), 3)
    assert.equal(mpegAudioLayer(new Uint8Array([0xff, 0xfd, 0x90, 0x00])), 2)
    assert.equal(mpegAudioLayer(new Uint8Array([0x00, 0x12, 0xff, 0xff, 0x90])), 1)
    assert.equal(mpegAudioLayer(new Uint8Array([0xff, 0xf9])), null)
    assert.equal(mpegAudioLayer(new Uint8Array(0)), null)
    assert.equal(mpegAudioLayer(new Uint8Array(10_000)), null)
})

test('tracks mediabunny cannot name get an FFmpeg decoder from their codec id', () => {
    assert.equal(soundDecoderFor('A_TRUEHD'), 'truehd')
    assert.equal(soundDecoderFor('A_MPEG/L2'), 'mp2')
    assert.equal(soundDecoderFor('A_REAL/COOK'), 'cook')
    assert.equal(soundDecoderFor('alac'), 'alac')
    assert.equal(soundDecoderFor('samr'), 'amrnb')
    assert.equal(soundDecoderFor('A_SOMETHING'), null)
    assert.equal(soundDecoderFor(7), null)
    assert.equal(soundDecoderFor(null), null)
})

test('the software picture cap follows the device', () => {
    assert.equal(softwarePixels(4, 8, false), 854 * 480)
    assert.equal(softwarePixels(8, 2, false), 854 * 480)
    assert.equal(softwarePixels(8, undefined, true), 1280 * 720)
    assert.equal(softwarePixels(12, 16, false), 1920 * 1088)
    // A DVD's 720 x 576 fits on all but the weakest; 1080p only on a desktop.
    assert.ok(720 * 576 <= softwarePixels(8, 4, true))
    assert.ok(1920 * 1080 > softwarePixels(8, 4, true))
})
