// node --test: the HEVC names the player asks a Media Source about (hevcnames.ts).
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { av1Names, hevcAccepted, hevcNames, pictureNames, tvDecodesHevc, withHevc } from './hevcnames.ts'

test('LG and Samsung TVs are trusted with HEVC, other browsers are asked', () => {
    // The webOS 6 agent (Chromium 79) of a 2021 LG QNED TV, and Tizen 5.5.
    assert.ok(tvDecodesHevc('Mozilla/5.0 (Web0S; Linux/SmartTV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/79.0.3945.79 Safari/537.36 WebAppManager'))
    assert.ok(tvDecodesHevc('Mozilla/5.0 (SMART-TV; LINUX; Tizen 5.5) AppleWebKit/537.36 (KHTML, like Gecko) 69.0.3497.106/5.5 TV Safari/537.36'))
    assert.ok(!tvDecodesHevc('Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36'))
    assert.ok(!tvDecodesHevc('Mozilla/5.0 (Linux; Android 12; BRAVIA 4K) AppleWebKit/537.36 Chrome/120.0 Safari/537.36'))
})

test('a browser that takes the file own name gets it, as hvc1', () => {
    assert.equal(
        hevcAccepted('hev1.2.4.L153.B0', () => true),
        'hvc1.2.4.L153.B0',
    )
})

test('a TV that knows only hev1 gets hev1', () => {
    assert.equal(
        hevcAccepted('hvc1.1.6.L150.90', (m) => m.includes('hev1.')),
        'hev1.1.6.L150.90',
    )
})

test('a TV that turns the file own name down gets a stand-in of the same profile', () => {
    // A 4K Main 10 file at level 5.1 with constraint bytes the TV does not read, on a TV taking only L150.90.
    const tv = (m: string) => /codecs="hvc1\.2\.4\.L150\.90"/.test(m)
    assert.equal(hevcAccepted('hvc1.2.4.L153.B0.0.0.0.0.0', tv), 'hvc1.2.4.L150.90')
    // The name bili-webos plays 4K with on LG TVs comes first among the stand-ins.
    assert.equal(hevcAccepted('hev1.1.6.H153', (m) => m.includes('L153.90')), 'hvc1.1.6.L153.90')
    // Never a Main 8 bit name for a Main 10 file.
    assert.equal(
        hevcAccepted('hvc1.2.4.L153.B0', (m) => m.includes('hvc1.1.')),
        null,
    )
})

test('a browser with no HEVC at all, and one that throws, say no', () => {
    assert.equal(hevcAccepted('hvc1.1.6.L93.B0', () => false), null)
    assert.equal(
        hevcAccepted('hvc1.1.6.L93.B0', () => {
            throw new Error('bad string')
        }),
        null,
    )
})

test('names: none for other codecs, no stand-ins for range extensions, no repeats', () => {
    assert.deepEqual(hevcNames('avc1.640028'), [])
    assert.deepEqual(hevcNames(''), [])
    assert.deepEqual(hevcNames('hvc1.4.10.L120.90'), ['hvc1.4.10.L120.90', 'hev1.4.10.L120.90'])
    const main = hevcNames('hvc1.1.6.L150.90')
    assert.equal(new Set(main).size, main.length)
    assert.equal(main[0], 'hvc1.1.6.L150.90')
    // A profile space letter before the profile still finds its stand-ins.
    assert.ok(hevcNames('hvc1.A2.4.L153.B0').includes('hvc1.2.4.L150.90'))
})

test('withHevc swaps the picture codec and keeps the sound', () => {
    assert.equal(withHevc('video/mp4; codecs="hvc1.2.4.L153.B0, mp4a.40.2"', 'hev1.2.4.L150.90'), 'video/mp4; codecs="hev1.2.4.L150.90, mp4a.40.2"')
    assert.equal(withHevc('video/mp4; codecs="hvc1.1.6.L93.B0"', 'hvc1.1.6.L93.B0'), 'video/mp4; codecs="hvc1.1.6.L93.B0"')
    assert.equal(withHevc('video/mp4; codecs="avc1.640028"', 'hvc1.1.6.L93.B0'), 'video/mp4; codecs="avc1.640028"')
})

test('AV1 names: own, short form, then 5.1, 5.0 and 4.1 of the same profile and depth', () => {
    assert.deepEqual(av1Names('av01.0.12M.10.0.112.09.16.09.0'), ['av01.0.12M.10.0.112.09.16.09.0', 'av01.0.12M.10', 'av01.0.13M.10', 'av01.0.09M.10'])
    assert.deepEqual(av1Names('hvc1.1.6.L93.B0'), [])
    assert.equal(pictureNames('av01.0.08M.08')[0], 'av01.0.08M.08')
    assert.equal(pictureNames('hvc1.1.6.L93.B0')[0], 'hvc1.1.6.L93.B0')
})

test('a TV that takes only the short AV1 form gets it, and the sound is kept', () => {
    assert.equal(hevcAccepted('av01.0.12M.10.0.112.09.16.09.0', (m) => /codecs="av01\.0\.12M\.10"$/.test(m)), 'av01.0.12M.10')
    assert.equal(withHevc('video/mp4; codecs="av01.0.12M.10.0.112.09.16.09.0, ec-3"', 'av01.0.12M.10'), 'video/mp4; codecs="av01.0.12M.10, ec-3"')
})
