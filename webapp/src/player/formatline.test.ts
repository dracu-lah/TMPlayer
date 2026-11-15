// node --test: the line the error card says about the failed format (formatline.ts), the same
// words as the apps' FormatLine (core), checked on real codec strings and on garbage.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { audioName, channelLayout, clean, copyDetails, formatLine, videoName, type FormatFacts } from './formatline.ts'

const facts = (over: Partial<FormatFacts> = {}): FormatFacts => ({
    videoCodec: null,
    videoParams: null,
    width: 0,
    height: 0,
    transfer: null,
    audioCodec: null,
    audioParams: null,
    channels: 0,
    container: null,
    ...over,
})

test('the example from the plan', () => {
    assert.equal(
        formatLine(facts({ videoCodec: 'hevc', videoParams: 'hvc1.2.4.L150.B0', width: 3840, height: 2160, audioCodec: 'eac3', channels: 6 })),
        'HEVC Main 10 L5.0, 3840 x 2160, E-AC-3 5.1',
    )
})

test('a full line with HDR and the container', () => {
    assert.equal(
        formatLine(facts({ videoCodec: 'hevc', videoParams: 'hev1.2.4.L153.90', width: 3840, height: 2160, transfer: 'pq', audioCodec: 'truehd', channels: 8, container: 'Matroska' })),
        'HEVC Main 10 L5.1, 3840 x 2160, HDR10, TrueHD 7.1, Matroska',
    )
})

test('real codec strings', () => {
    assert.equal(videoName('avc', 'avc1.640028'), 'H.264 High L4.0')
    assert.equal(videoName('avc', 'avc1.4D401F'), 'H.264 Main L3.1')
    assert.equal(videoName('avc', 'avc1.6E0033'), 'H.264 High 10 L5.1')
    assert.equal(videoName('hevc', 'hvc1.1.6.L120.90'), 'HEVC Main L4.0')
    assert.equal(videoName('av1', 'av01.0.08M.10'), 'AV1 Main L4.0 10-bit')
    assert.equal(videoName('vp9', 'vp09.02.10.10'), 'VP9 Profile 2 L1.0')
    assert.equal(videoName(null, 'hvc1.2.4.L120.B0'), 'HEVC Main 10 L4.0')
    assert.equal(videoName('MPEG-4 Part 2 (DivX, Xvid)', null), 'MPEG-4 Part 2 (DivX, Xvid)')
    assert.equal(audioName('aac', 'mp4a.40.2'), 'AAC LC')
    assert.equal(audioName('aac', 'mp4a.40.5'), 'HE-AAC')
    assert.equal(audioName('ac3', null), 'AC-3')
    assert.equal(audioName('opus', null), 'Opus')
    assert.equal(audioName(null, 'mp4a.40.2'), 'AAC')
})

test('no video track, an audio only file and no format at all', () => {
    assert.equal(formatLine(facts({ audioCodec: 'flac', channels: 2, container: 'Ogg' })), 'FLAC stereo, Ogg')
    // A size without a picture is dropped, not printed on its own.
    assert.equal(formatLine(facts({ audioCodec: 'aac', audioParams: 'mp4a.40.2', channels: 2, width: 1920, height: 1080 })), 'AAC LC stereo')
    assert.equal(formatLine(facts()), '')
    assert.equal(formatLine(null), '')
    assert.equal(formatLine(undefined), '')
})

test('absurd numbers and long strings are left out or cut', () => {
    assert.equal(formatLine(facts({ videoCodec: 'avc', width: -1, height: 99_999_999, channels: -4 })), 'H.264')
    assert.equal(formatLine(facts({ videoCodec: 'avc', width: NaN, height: Infinity })), 'H.264')
    assert.equal(channelLayout(40), null)
    assert.equal(channelLayout(12), '12 ch')
    const long = 'hvc1.' + '9'.repeat(2048)
    assert.ok((videoName('hevc', long) ?? '').length < 80)
    const wild = formatLine(facts({ videoCodec: 'q'.repeat(2048), audioCodec: 'z'.repeat(2048), container: 'c'.repeat(2048) }))
    assert.ok(wild.length < 3 * (40 + 10), String(wild.length))
    assert.ok(!wild.includes('\n'))
})

test('garbage never throws', () => {
    const texts: (string | null)[] = [null, '', ' ', '\u0000', '\n\t', 'a'.repeat(2048), '😀'.repeat(900), 'hvc1.', 'hvc1..', 'hvc1.x.y.Lz', 'avc1.', 'avc1.zzzzzz', 'av01', 'av01.9.99Z.99', 'vp09', 'vp09.99.99', 'mp4a.40.', 'mp4a.40.999999999999', 'hvc1.2.4.L2147483647.B0', 'hvc1.2.4.L-5.B0', 'avc1.640000', 'avc1.64FFFF', '__proto__', 'constructor', 'toString']
    for (const a of texts) {
        for (const b of texts) {
            const line = formatLine(facts({ videoCodec: a, videoParams: b, audioCodec: b, audioParams: a, container: a, width: 100, height: 100, channels: 6 }))
            assert.ok(!line.includes('\n'))
            assert.ok(line.length < 1000)
            copyDetails(line, [a ?? '', b ?? ''], 3, a ?? '')
        }
    }
    assert.equal(clean(null), null)
    assert.equal(clean('   \n'), null)
})

test('Copy details carries the line, the support and the datacenter and no name', () => {
    const text = copyDetails('HEVC Main 10 L5.0, 3840 x 2160', ['video/mp4; codecs="hvc1.2.4.L150.B0" no'], 4, 'Mozilla/5.0 (X11; Linux x86_64)')
    assert.equal(text, 'TMPlayer web\nFormat: HEVC Main 10 L5.0, 3840 x 2160\nDecoder support video/mp4; codecs="hvc1.2.4.L150.B0" no\nDatacenter: DC 4\nBrowser: Mozilla/5.0 (X11; Linux x86_64)')
    assert.equal(copyDetails('', [], undefined, ''), 'TMPlayer web')
    assert.ok(!copyDetails('x', [], 9, '').includes('Datacenter'))
})
