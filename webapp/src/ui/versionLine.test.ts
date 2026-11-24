// The web's version line (WP-104): the support text, the facts and the state words' source. The
// text takes three facts and nothing else, cuts each, and keeps account data out.
// `node --test src/ui/versionLine.test.ts`
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { BUILD_MAX, DEVICE_MAX, VERSION_MAX, compactVersion, copyText, dragCloses, shown, versionState, webVersionFacts } from './versionLine.ts'

const SAFARI = 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1'

test('the web facts are "web", the build with its day, and the browser as a report names it', () => {
    const f = webVersionFacts('a1b2c3d', '2026-11-24', SAFARI, false)
    assert.deepEqual(f, { version: 'web', build: 'a1b2c3d, 2026-11-24', device: 'Safari 17 on iPhone' })
    assert.equal(webVersionFacts('dev', '2026-11-24', SAFARI, true).device, 'Safari 17 on iPhone, installed app')
})

test('the copy text is the app, the version, the build and the device, on one line', () => {
    assert.equal(copyText(webVersionFacts('a1b2c3d', '2026-11-24', SAFARI, false)), 'TMPlayer web (a1b2c3d, 2026-11-24) Safari 17 on iPhone')
})

test('each part is cut and no line break survives', () => {
    const text = copyText({ version: 'v'.repeat(1000), build: 'b\n'.repeat(100), device: 'd\r\n'.repeat(500) })
    assert.ok(!/[\r\n]/u.test(text))
    const m = /^TMPlayer (.*) \((.*)\) (.*)$/u.exec(text)
    assert.ok(m)
    assert.ok(m[1].length <= VERSION_MAX)
    assert.ok(m[2].length <= BUILD_MAX)
    assert.ok(m[3].length <= DEVICE_MAX)
    const s = shown({ version: 'x'.repeat(999), build: 'y', device: 'z' })
    assert.equal(s.version.length, VERSION_MAX)
})

test('a fake account never reaches the text', () => {
    // The facts are the build's and the browser's; a name or username only appears if a caller hands one in.
    const text = copyText(webVersionFacts('a1b2c3d', '2026-11-24', SAFARI, false))
    for (const secret of ['Dana Test', 'dana', '+15551234567']) assert.ok(!text.includes(secret))
})

test('wrong types are made safe, not thrown on', () => {
    const text = copyText({ version: undefined as unknown as string, build: 7 as unknown as string, device: null as unknown as string })
    assert.equal(text, 'TMPlayer  ()')
})

test('the state follows the service worker: a waiting version wins, then a look in progress, then a finished one, else nothing', () => {
    assert.equal(versionState({ updateReady: true, checking: true, lastChecked: 5 }), 'available')
    assert.equal(versionState({ updateReady: false, checking: true, lastChecked: 5 }), 'checking')
    assert.equal(versionState({ updateReady: false, checking: false, lastChecked: 5 }), 'upToDate')
    // Never checked, no service worker (the TV build): the version alone, never a false "Up to date".
    assert.equal(versionState({ updateReady: false, checking: false, lastChecked: null }), 'none')
    assert.equal(versionState({ updateReady: false, checking: false, lastChecked: 0 }), 'none')
})

test('the compact version is "v" and the digits, a build suffix dropped, and nothing made up', () => {
    assert.equal(compactVersion('1.28.7'), 'v1.28.7')
    assert.equal(compactVersion('v1.28.7-beta.2'), 'v1.28.7')
    assert.equal(compactVersion(' v1.29 '), 'v1.29')
    for (const bad of ['', 'web', 'dev', 'main', 'v1', '1.2.3.4', 'x1.2.3', null, undefined, 7, {}]) assert.equal(compactVersion(bad), '', String(bad))
})

test('a drag down on the sheet grip closes it when far or quick, never up or on garbage', () => {
    assert.equal(dragCloses(120, 900), true)
    assert.equal(dragCloses(40, 40), true)
    assert.equal(dragCloses(40, 900), false)
    assert.equal(dragCloses(-200, 10), false)
    assert.equal(dragCloses(0, 0), false)
    assert.equal(dragCloses(Number.NaN, 10), false)
    assert.equal(dragCloses(100, Number.POSITIVE_INFINITY), false)
})
