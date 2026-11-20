// `node --test src/ui/logsAddon.test.ts` (pnpm test:ui): the subtitles addon's install address is a secret, so
// no log line, console line or exported bundle may carry it, its token, or any address of an addon's shapes.
import assert from 'node:assert/strict'
import { beforeEach, test } from 'node:test'
import { buildBundle, clearLogs, hideAddon, hookConsole, log, scrubLine } from '../logs.ts'

const TOKEN = 'Zq83mNpLw0vXk2RtYb7HcUe5'
const BASE = `https://addon.example/${TOKEN}`

beforeEach(() => {
    clearLogs()
    hideAddon(null)
})

test('addresses of an addon become [addon] with nothing of them left, even with no base set', () => {
    for (const line of [
        'fetch https://host.example/abc/subtitles/movie/tt1234567.json failed',
        'GET /subtitles/series/tt1234567:1:2.json',
        'translate https://host.example/x/translate/abc123.srt answered 500',
        'manifest https://host.example/x/manifest.json bad',
        'link stremio://host.example/secret/manifest.json pasted',
        'STREMIO://HOST.EXAMPLE/secret/manifest.json',
        'http://127.0.0.1:7000/t/subtitles/movie/tt1234567.json',
    ]) {
        const out = scrubLine(line)
        assert.match(out, /\[addon\]/u, line)
        assert.ok(!/stremio:|subtitles\/|translate\/|manifest\.json|host\.example|127\.0\.0\.1/iu.test(out), `${line} -> ${out}`)
    }
    // The words around it stay.
    assert.equal(scrubLine('before https://host.example/abc/manifest.json after'), 'before [addon] after')
})

test('with the base set, the base, its token and its forms go from any line', () => {
    hideAddon(BASE)
    for (const line of [
        `base is ${BASE}`,
        `token ${TOKEN} here`,
        `path /${TOKEN}/ only`,
        `encoded ${encodeURIComponent(BASE)}`,
        `${BASE}/subtitles/movie/tt1234567.json`,
        `Error: request to ${BASE}/translate/x.srt failed`,
        `lower ${BASE.toLowerCase().replace('addon.example', 'addon.example')} cased token ${TOKEN}`,
    ]) {
        const out = scrubLine(line)
        assert.ok(!out.includes(TOKEN), `${line} -> ${out}`)
        assert.ok(!out.includes(encodeURIComponent(BASE)), out)
        assert.match(out, /\[addon\]/u)
    }
    assert.equal(scrubLine(`x ${TOKEN} y`), 'x [addon] y')
    // Removed again: only the pattern step is left (a 24 character token is still a token).
    hideAddon(null)
    assert.ok(!scrubLine(`x ${TOKEN} y`).includes(TOKEN))
    assert.ok(!scrubLine(`x ${TOKEN} y`).includes('[addon]'))
})

test('a base with an encoded path is taken out in both spellings', () => {
    const encoded = 'https://addon.example/%7B%22key%22%3A%22abcdef123456%22%7D'
    hideAddon(encoded)
    const out = scrubLine(`got ${encoded}/x and {"key":"abcdef123456"}`)
    assert.ok(!out.includes('abcdef123456'), out)
    assert.ok(!out.includes('%7B'), out)
})

test('the ring, the console and the exported bundle never hold it', () => {
    hideAddon(BASE)
    log('E', 'subtitles', `could not read ${BASE}/subtitles/movie/tt1234567.json`)
    log('W', 'subtitles', `stremio://addon.example/${TOKEN}/manifest.json`)
    const printed: string[] = []
    const fake = { warn: (...a: unknown[]) => void printed.push(a.join(' ')), error: (...a: unknown[]) => void printed.push(a.join(' ')) }
    hookConsole(fake)
    fake.error('[submaker]', new Error(`failed ${BASE}/translate/abc.srt`))
    fake.warn('[submaker]', TOKEN)
    const bundle = buildBundle({ version: '1.0.0', platform: 'web', device: 'test', language: 'en' })
    assert.ok(!bundle.text.includes(TOKEN), bundle.text)
    assert.ok(!bundle.text.includes('addon.example'), bundle.text)
    assert.ok(!/subtitles\/movie|translate\/abc|stremio:/iu.test(bundle.text), bundle.text)
    assert.match(bundle.text, /\[addon\]/u)
})

test('ordinary lines are left as they were', () => {
    hideAddon(BASE)
    assert.equal(scrubLine('[player] slow start after 1200 ms'), '[player] slow start after 1200 ms')
    assert.equal(scrubLine('Translating, 40 lines so far'), 'Translating, 40 lines so far')
})

test('the singular /subtitle/ and the toolbox path are addon addresses too', () => {
    for (const line of [
        'fetch https://host.example/abc/subtitle/movie/tt1234567.json failed',
        'GET /subtitle/series/tt1234567:1:2.json',
        'page https://host.example/abc/sub-toolbox/xyz',
    ]) {
        const out = scrubLine(line)
        assert.match(out, /\[addon\]/u, line)
        assert.ok(!/subtitle\/|sub-toolbox|host\.example/iu.test(out), `${line} -> ${out}`)
    }
})

test('the address is taken out before a long line is cut, so a secret across the cut does not survive half', () => {
    hideAddon(BASE)
    // The base sits across the 2000 character cut: cut first, only its head would be left in the line.
    const line = `${'x '.repeat(985)}${BASE} tail`
    assert.ok(line.indexOf(TOKEN) < 2000 && line.indexOf(TOKEN) + TOKEN.length > 2000)
    const out = scrubLine(line)
    assert.ok(!out.includes(TOKEN.slice(0, 8)), out)
    assert.ok(!out.includes('addon.example'), out)
})
