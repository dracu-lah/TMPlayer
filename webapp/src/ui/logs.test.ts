// `node --test src/ui/logs.test.ts` (pnpm test:ui): the log scrubber, the ring and the bundle (src/logs.ts), and that nothing a viewer plays reaches them.
import assert from 'node:assert/strict'
import { beforeEach, test } from 'node:test'
import {
    CRASH_KEY,
    MAX_BYTES,
    MAX_KNOWN,
    MAX_LINE,
    MAX_LINES,
    buildBundle,
    clearLogs,
    earlierCrash,
    hookConsole,
    knownPatterns,
    log,
    noteCrash,
    rememberNames,
    reportCode,
    reportUrl,
    ringLines,
    scrubLine,
    sizeText,
    uploadLogs,
} from '../logs.ts'

const facts = { version: 'web', platform: 'Web', device: 'Safari 17 on iPhone', language: 'en' }
const memory = new Map<string, string>()
// A page's storage, as node has none: noteCrash and clearLogs reach it through `localStorage`.
Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    value: {
        getItem: (k: string) => memory.get(k) ?? null,
        setItem: (k: string, v: string) => void memory.set(k, v),
        removeItem: (k: string) => void memory.delete(k),
    },
})

beforeEach(() => {
    clearLogs()
    memory.clear()
})

test('the scrubber takes out file names, paths, e-mail, phones, @usernames and long numbers, and keeps the words and short numbers', () => {
    const cases: [string, string][] = [
        ['opened Kerala.Crime.Files.S01E02.mkv at 1234', 'opened [file] at 1234'],
        ['read /storage/emulated/0/Movies/My Holiday Video (2019).mp4 failed', 'read /storage/emulated/0/Movies/[file] failed'],
        ['mail me@example.com now', 'mail [email] now'],
        ['call +91 98765 43210 today', 'call [phone] today'],
        ['by @some_user and @ab', 'by @[user] and @ab'],
        ['chat -1001234567890 message 9876543210', 'chat -[number] message [number]'],
        ['error 404 after 12 ms line 77', 'error 404 after 12 ms line 77'],
        ['Object@1a2b3c stays', 'Object@1a2b3c stays'],
        ['subs Show.S01E02.en.srt and notes.pdf', 'subs [file] and [file]'],
    ]
    for (const [line, want] of cases) assert.equal(scrubLine(line), want, line)
})

test('known names go word for word, longest first, whatever their case, and short ones stay', () => {
    const known = knownPatterns(['Blue Box', 'Blue Box Season Two', 'TV', 'a.b(c)', 'x'.repeat(10)])
    assert.equal(scrubLine('playing blue box season two now', known), 'playing [name] now')
    assert.equal(scrubLine('the TV is on', known), 'the TV is on')
    assert.equal(scrubLine('odd a.b(c) title', known), 'odd [name] title')
    assert.equal(scrubLine(`long ${'x'.repeat(10)}`, known), 'long [name]')
})

test('a line over the cap is cut before the patterns run, so a hostile line cannot stall the export', () => {
    const t0 = Date.now()
    const out = scrubLine('a'.repeat(300_000) + '.mkv ' + 'b'.repeat(100_000))
    assert.ok(Date.now() - t0 < 2000, 'bounded work')
    assert.ok(out.length <= MAX_LINE + 20)
    assert.ok(out.endsWith('[cut]'))
})

test('garbage in: wrong types, control characters, lone surrogates never throw', () => {
    for (const v of [undefined, null, 5, {}, [], Symbol.iterator.toString(), '\u0000\u0007‮', '\ud800 half', 'x y']) assert.doesNotThrow(() => scrubLine(v))
    assert.equal(scrubLine('a\u0000b\u0007c'), 'a b c')
    assert.deepEqual(knownPatterns([1, null, {}, 'ab', '   ', 'Good name']).length, 1)
    for (let i = 0; i < 200; i++) {
        const junk = Array.from({ length: 80 }, () => String.fromCharCode(Math.floor(Math.random() * 65536))).join('')
        assert.doesNotThrow(() => scrubLine(junk))
    }
})

test('the ring keeps the last 500 lines, each cut to the cap, and never throws on what it is given', () => {
    for (let i = 0; i < MAX_LINES + 50; i++) log('I', 'player', `line ${i}`)
    const lines = ringLines()
    assert.equal(lines.length, MAX_LINES)
    assert.match(lines[0], /line 50$/u)
    assert.match(lines.at(-1)!, /line 549$/u)
    log('E', 'x'.repeat(100), 'y'.repeat(10_000))
    assert.ok(ringLines().at(-1)!.length < MAX_LINE + 60)
    assert.doesNotThrow(() => log('W', 'p', { toString: () => { throw new Error('boom') } }))
    assert.doesNotThrow(() => log('W', 'p', undefined))
})

test('the console hook copies warnings and errors, still prints, and is installed once', () => {
    const printed: string[] = []
    const fake = { warn: (...a: unknown[]) => void printed.push(`w ${a.join(' ')}`), error: (...a: unknown[]) => void printed.push(`e ${a.join(' ')}`) }
    hookConsole(fake)
    hookConsole(fake)
    fake.warn('[player] slow start')
    fake.error('[player]', new Error('decode failed'), { a: 1 })
    assert.deepEqual(printed.length, 2, 'the console still prints, once each')
    const lines = ringLines()
    assert.equal(lines.length, 2)
    assert.match(lines[0], / W console: \[player\] slow start$/u)
    assert.match(lines[1], / E console: \[player\] Error: decode failed \[object Object\]$/u)
})

test('the bundle: a header, the parts in order, every line scrubbed, names taken out as written', () => {
    rememberNames(['Blue Box', 'Kerala Crimes'])
    log('I', 'player', 'opened Blue Box S01E02.mkv in Kerala Crimes from +91 9876543210')
    log('W', 'tg', 'FLOOD_WAIT 30 for chat 1001234567890')
    noteCrash(new TypeError('x is not a function in Blue Box'))
    const b = buildBundle(facts, { device: ['Engines: native yes, remux yes'], marks: ['Playback trace: 1 moment(s) the viewer marked as stuttering, oldest first.'] }, ['Extra Name'], new Date('2026-11-20T03:10:00Z'))
    assert.match(b.text, /^TMPlayer logs\nApp version: web\nPlatform: Web\nDevice: Safari 17 on iPhone\nApp language: en\nSaved: 2026-11-20 03:10\n/u)
    const at = (s: string) => b.text.indexOf(s)
    assert.ok(at('== Earlier run ==') < at('== Device ==') && at('== Device ==') < at('== Moments marked') && at('== Moments marked') < at('== Page log'))
    assert.doesNotMatch(b.text, /Blue Box|Kerala|9876543210|1001234567890|\.mkv|S01E02/u)
    assert.match(b.text, /opened \[name\] \[file\] in \[name\] from \[phone\]/u)
    assert.match(b.text, /FLOOD_WAIT 30 for chat \[number\]/u)
    assert.deepEqual(b.parts.map((p) => p.kind), ['crash', 'device', 'marks', 'log'])
    assert.equal(b.bytes, new TextEncoder().encode(b.text).length)
    assert.equal(b.droppedLines, 0)
})

test('an extra name given at export is taken out too, and nothing is kept of it', () => {
    log('I', 'player', 'now playing Hidden Title Here')
    assert.doesNotMatch(buildBundle(facts, {}, ['Hidden Title Here']).text, /Hidden Title/u)
    assert.match(buildBundle(facts, {}).text, /Hidden Title Here/u)
})

test('over the cap the oldest log lines go first and the file says how many', () => {
    for (let i = 0; i < MAX_LINES; i++) log('I', 'player', `entry ${i} ${'z'.repeat(400)}`)
    const small = buildBundle(facts, {}, [], new Date(), 60_000)
    assert.ok(small.bytes <= 60_000, String(small.bytes))
    assert.ok(small.droppedLines > 0)
    assert.match(small.text, new RegExp(`${small.droppedLines} older log line\\(s\\) were left out`, 'u'))
    assert.match(small.text, /entry 499 /u)
    assert.doesNotMatch(small.text, /entry 0 /u)
    assert.ok(buildBundle(facts).bytes <= MAX_BYTES)
})

test('names are bounded: the newest 200 win', () => {
    rememberNames(Array.from({ length: MAX_KNOWN + 50 }, (_, i) => `Name number ${i}`))
    log('I', 'p', 'Name number 0 and Name number 249')
    const text = buildBundle(facts).text
    assert.match(text, /Name number 0 and \[name\]/u)
})

test('the earlier run\'s crash is scrubbed, short, and read back with nothing trusted', () => {
    noteCrash(new Error(`broke on ${'Q'.repeat(2000)}.mkv for me@example.com`))
    const lines = earlierCrash()
    assert.equal(lines.length, 1)
    assert.ok(lines[0].length < 800)
    assert.doesNotMatch(lines[0], /example\.com|\.mkv/u)
    const store = (v: unknown) => ({ getItem: () => v as string })
    for (const bad of [null, undefined, 5, '', '{', '[]', 'null', '"x"', '{"text":5}', '{"text":""}', JSON.stringify({ text: 'x'.repeat(5000) }), 'x'.repeat(100_000)]) {
        assert.deepEqual(earlierCrash(store(bad)), [], String(bad).slice(0, 20))
    }
    assert.match(earlierCrash(store(JSON.stringify({ at: 'not a date', text: 'boom' })))[0], /an earlier time: boom$/u)
    assert.deepEqual(earlierCrash(null), [])
    assert.equal(earlierCrash({ getItem: () => { throw new Error('denied') } }).length, 0)
    noteCrash(undefined)
    noteCrash(null)
    noteCrash({ message: { not: 'text' } })
    clearLogs()
    assert.equal(memory.has(CRASH_KEY), false)
})

test('size text', () => {
    assert.deepEqual([0, 1, 1024, 1025, 500_000, 1_500_000, NaN].map(sizeText), ['1 KB', '1 KB', '1 KB', '2 KB', '489 KB', '1.4 MB', '1 KB'])
})

test('the upload hook reads the inbox answer and nothing else, and sends only a bundle', async () => {
    assert.equal(reportCode(201, '{"code":"TMP-7K3Q","keepDays":30}'), 'TMP-7K3Q')
    for (const [s, b] of [[200, '{"code":"TMP-7K3Q"}'], [201, '{"code":"TMP-0I1L"}'], [201, 'nope'], [201, null], [500, '{"code":"TMP-7K3Q"}']] as const) assert.equal(reportCode(s, b), null)
    assert.equal(reportUrl('https://logs.example.org/v1/feedback'), 'https://logs.example.org/v1/report')
    for (const bad of [null, '', 'http://x.org/v1/feedback', 'https://x.org/other', 'not a url']) assert.equal(reportUrl(bad), null)
    let sent: { url: string; init: RequestInit } | null = null
    const ok = (async (url: string, init: RequestInit) => {
        sent = { url, init }
        return new Response('{"code":"TMP-7K3Q"}', { status: 201 })
    }) as unknown as typeof fetch
    assert.equal(await uploadLogs('TMPlayer logs\nx', 'https://x.org/v1/report', ok), 'TMP-7K3Q')
    assert.equal((sent as { init: RequestInit } | null)?.init.credentials, 'omit')
    sent = null
    assert.equal(await uploadLogs('anything else', 'https://x.org/v1/report', ok), null)
    assert.equal(sent, null, 'not a bundle: nothing leaves')
    assert.equal(await uploadLogs('TMPlayer logs\n' + 'x'.repeat(2_100_000), 'https://x.org/v1/report', ok), null)
    assert.equal(await uploadLogs('TMPlayer logs\n', 'https://x.org/v1/report', (async () => { throw new TypeError('offline') }) as unknown as typeof fetch), null)
})
