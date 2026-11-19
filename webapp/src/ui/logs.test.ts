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
    crashFacts,
    earlierCrash,
    hookConsole,
    knownPatterns,
    log,
    noteCrash,
    noteNames,
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
        ['player: opened Kerala.Crime.Files.S01E02.mkv at 1234', 'player: [file] at 1234'],
        ['read /storage/emulated/0/Movies/My Holiday Video (2019).mp4 failed', 'read [file] failed'],
        ['mail me@example.com now', 'mail [email] now'],
        ['call +91 98765 43210 today', 'call [phone] today'],
        ['by @some_user and @ab', 'by @[user] and @ab'],
        ['chat -1001234567890 message 9876543210', 'chat -[number] message [number]'],
        ['error 404 after 12 ms line 77', 'error 404 after 12 ms line 77'],
        ['Object@1a2b3c stays', 'Object@1a2b3c stays'],
        ['subs: Show.S01E02.en.srt and notes.pdf', 'subs: [file] [file]'],
        ['tool .mkvtoolnix stays', 'tool .mkvtoolnix stays'],
    ]
    for (const [line, want] of cases) assert.equal(scrubLine(line), want, line)
})

test('a file name with spaces is taken out whole, however it is written, and the words before the line\'s colon stay', () => {
    assert.equal(scrubLine('player: could not open My Holiday Video (2019) Part 2.mp4 for you'), 'player: [file] for you')
    assert.equal(scrubLine('x "Quiet Evening Show - 07 [1080p].mkv" y'), 'x "[file]" y')
    assert.equal(scrubLine('z "[Some Group] Show Name - 12 (720p) [AB12CD].mkv" w'), 'z "[file]" w')
    assert.doesNotMatch(scrubLine('open C:\\Users\\me\\Videos\\Some Show S02E05.avi now'), /Some Show|Users/)
    assert.equal(scrubLine('file=Name With Spaces.mkv&x=1'), 'file=[file]&x=1')
    // A very long name keeps only what is past the reach: the rest of it goes.
    assert.doesNotMatch(scrubLine(`${'word '.repeat(60)}end.mkv`.slice(-120)), /end\.mkv/)
})

test('URLs lose their query and fragment, and so do relative addresses with a query', () => {
    const cases: [string, RegExp, string][] = [
        ['GET https://api.example.org/v1/x?token=abcdef0123456789abcdef&api_key=0123456789abcdef0123456789abcdef done', /abcdef|api_key|token=/, 'GET https://api.example.org/v1/x[query] done'],
        ['open https://a.example/p#access_token=zzzzzzzz1 now', /access_token|zzzz/, 'open https://a.example/p[query] now'],
        ['fetch /stream/x?sig=Q2xhaW1lZA&exp=1 ok', /sig=|Q2xhaW1lZA/, 'fetch /stream/x?[query] ok'],
        ['bare ?a=b&c=d', /a=b/, 'bare ?[query]'],
    ]
    for (const [line, bad, want] of cases) {
        const got = scrubLine(line)
        assert.doesNotMatch(got, bad, line)
        assert.equal(got, want, line)
    }
})

test('any run of 16 or more token characters goes, short identifiers stay', () => {
    assert.equal(scrubLine('key 0123456789abcdef0123 x'), 'key [token] x')
    assert.equal(scrubLine('hash AbCdEfGhIjKlMnOp_-12 y'), 'hash [token] y')
    assert.equal(scrubLine('FLOOD_WAIT_30 and appendBuffer ok'), 'FLOOD_WAIT_30 and appendBuffer ok')
    assert.equal(scrubLine('A'.repeat(15)), 'A'.repeat(15))
    assert.equal(scrubLine('A'.repeat(16)), '[token]')
})

test('a phone number without a plus goes too, written with spaces, dashes or brackets; six digits and dates stay readable as what they are', () => {
    const cases: [string, string][] = [
        ['call 98765 43210 now', 'call [phone] now'],
        ['call (080) 2345-6789 now', 'call [phone] now'],
        ['call 98765-43210', 'call [phone]'],
        ['call 9 8 7 6 5 4 3', 'call [phone]'],
        ['size 1 234 now', 'size 1 234 now'],
        ['code 123456 end', 'code [number] end'],
        ['+91 98765 43210', '[phone]'],
    ]
    for (const [line, want] of cases) assert.equal(scrubLine(line), want, line)
})

test('e-mail addresses in any alphabet go', () => {
    for (const mail of ['me@example.com', 'jose.munoz+tag@ejemplo.es', 'пользователь@пример.рф', '用户@例子.广告', 'a_b%c@sub.domain.example.org', 'ñandú@correo.cl']) {
        assert.equal(scrubLine(`write ${mail} now`), 'write [email] now', mail)
    }
    for (const notMail of ['a@b', '@home alone', 'at@', 'x @ y']) assert.equal(scrubLine(notMail), notMail, notMail)
})

test('a line with a letter whose lower case is longer (a dotted capital I) still loses a known name written in any case', () => {
    const known = knownPatterns(['Blue Box'])
    // U+0130 lower-cases to two units, which used to switch the whole line to a case-sensitive match.
    assert.equal(scrubLine('\u0130stanbul: playing BLUE BOX now', known), '\u0130stanbul: playing [name] now')
    assert.equal(scrubLine('BLUE BOX then \u0130 then blue box', known), '[name] then \u0130 then [name]')
    // The original characters a name touched go whole, and nothing around them moves.
    assert.equal(scrubLine('x \u0130Blue Boxy', known), 'x \u0130[name]y')
})

test('known names go word for word in every form a log line writes them: case, accents either way, URL encoded, dotted, underscored', () => {
    const known = knownPatterns(['Blue Box', 'Blue Box Season Two', 'Café Société', 'Ünïcode Show'])
    const lines: [string, string][] = [
        ['playing blue box season two now', 'playing [name] now'],
        ['playing BLUE BOX now', 'playing [name] now'],
        ['q=Blue%20Box&x', 'q=[name]&x'],
        ['q=blue+box here', 'q=[name] here'],
        ['dotted Blue.Box.S01 end', 'dotted [name].S01 end'],
        ['under Blue_Box_S01 end', 'under [name]_S01 end'],
        ['chat Caf\u00e9 Soci\u00e9t\u00e9 (NFC)', 'chat [name] (NFC)'],
        ['chat Cafe\u0301 Socie\u0301te\u0301 (NFD)', 'chat [name] (NFD)'],
        ['/c/Caf%C3%A9%20Soci%C3%A9t%C3%A9/x', '/c/[name]/x'],
        ['/c/caf%c3%a9%20soci%c3%a9t%c3%a9/x', '/c/[name]/x'],
        ['\u00dcn\u00efcode Show and \u00dcni\u0308code Show', '[name] and [name]'.replace('[name] and [name]', '[name] and \u00dcni\u0308code Show')],
    ]
    for (const [line, want] of lines) {
        const got = scrubLine(line, known)
        if (line.includes('\u00dcni\u0308code')) assert.doesNotMatch(got, /\u00dcn\u00efcode Show/)
        else assert.equal(got, want, line)
    }
    const nfdKnown = knownPatterns(['Cafe\u0301 Bar'])
    assert.equal(scrubLine('at Caf\u00e9 Bar open', nfdKnown), 'at [name] open', 'a name typed one way, logged the other')
})

test('names of two and three characters go as whole words only, case ignored', () => {
    const known = knownPatterns(['Up', 'TV', 'Zoe', 'a'])
    assert.equal(scrubLine('watching up now, Update and group stay', known), 'watching [name] now, Update and group stay')
    assert.equal(scrubLine('the TV is on, tv. TVs and ATV stay', known), 'the [name] is on, [name]. TVs and ATV stay')
    assert.equal(scrubLine('Zoe, zoe-9 and Zoey', known), '[name], [name]-9 and Zoey')
    assert.equal(scrubLine('a b', known), 'a b', 'one character is not a name')
})

test('longer names go whole before a shorter one inside them, and a name that is a regex means nothing', () => {
    const known = knownPatterns(['Blue Box', 'Blue Box Season Two', 'a.b(c)', '.*+?^${}()|[]\\'])
    assert.equal(scrubLine('playing blue box season two now', known), 'playing [name] now')
    assert.equal(scrubLine('odd a.b(c) title', known), 'odd [name] title')
    assert.equal(scrubLine('axb stays and a.b(c) goes', known), 'axb stays and [name] goes')
    assert.equal(scrubLine('x .*+?^${}()|[]\\ y', known), 'x [name] y')
})

test('a line over the cap is cut before the patterns run', () => {
    const out = scrubLine('a'.repeat(300_000) + '.mkv ' + 'b'.repeat(100_000))
    assert.ok(out.length <= MAX_LINE + 20)
    assert.ok(out.endsWith('[cut]'))
})

test('every scan is linear: a full ring of 2,000 character lines scrubs in a time budget, whatever the lines are made of', () => {
    const shapes = [
        'a'.repeat(MAX_LINE),
        'a.'.repeat(MAX_LINE / 2),
        '1 '.repeat(MAX_LINE / 2),
        '@'.repeat(MAX_LINE),
        'x@'.repeat(MAX_LINE / 2),
        '?a='.repeat(MAX_LINE / 3),
        'http://'.repeat(MAX_LINE / 7),
        '.mkv'.repeat(MAX_LINE / 4),
        'word '.repeat(MAX_LINE / 5),
        'é'.repeat(MAX_LINE),
        '/'.repeat(MAX_LINE),
        'Name Of Show '.repeat(MAX_LINE / 13),
    ]
    rememberNames(Array.from({ length: MAX_KNOWN }, (_, i) => `Some Known Name ${i}`))
    const t0 = Date.now()
    for (let i = 0; i < MAX_LINES; i++) log('I', 'p', shapes[i % shapes.length])
    const bundle = buildBundle(facts)
    const ms = Date.now() - t0
    assert.ok(bundle.parts.some((p) => p.kind === 'log'))
    assert.ok(ms < 1500, `500 lines of 2,000 characters took ${ms} ms`)
    // Opening it again for the same ring scans nothing.
    const t1 = Date.now()
    for (let i = 0; i < 20; i++) buildBundle(facts)
    assert.ok(Date.now() - t1 < ms + 200, 'the same ring is scrubbed once')
})

test('garbage in: wrong types, control characters, lone surrogates never throw', () => {
    for (const v of [undefined, null, 5, {}, [], Symbol.iterator.toString(), '\u0000\u0007\u202e', '\ud800 half', 'x\u2028y', '%', '%E0%A4%A', '\udc00']) assert.doesNotThrow(() => scrubLine(v))
    assert.equal(scrubLine('a\u0000b\u0007c'), 'a b c')
    const k = knownPatterns([1, null, {}, 'ab', '   ', 'Good name', 'x', '\ud800 lone'])
    assert.ok(k.long.size > 0)
    for (let i = 0; i < 300; i++) {
        const junk = Array.from({ length: 80 }, () => String.fromCharCode(Math.floor(Math.random() * 65536))).join('')
        assert.doesNotThrow(() => scrubLine(junk, k))
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

test('the console hook copies warnings and errors, cut short, still prints, and is installed once', () => {
    const printed: string[] = []
    const fake = { warn: (...a: unknown[]) => void printed.push(`w ${a.join(' ')}`), error: (...a: unknown[]) => void printed.push(`e ${a.join(' ')}`) }
    hookConsole(fake)
    hookConsole(fake)
    fake.warn('[player] slow start')
    fake.error('[player]', new Error('decode failed'), { a: 1 })
    fake.error('z'.repeat(5000))
    assert.deepEqual(printed.length, 3, 'the console still prints, once each')
    const lines = ringLines()
    assert.equal(lines.length, 3)
    assert.match(lines[0], / W console: \[player\] slow start$/u)
    assert.match(lines[1], / E console: \[player\] Error: decode failed \[object Object\]$/u)
    assert.ok(lines[2].length < 340)
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
    assert.match(b.text, /player: \[file\] in \[name\] from \[phone\]/u)
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
    for (let i = 0; i < MAX_LINES; i++) log('I', 'player', `entry ${i} ${'z '.repeat(200)}`)
    const small = buildBundle(facts, {}, [], new Date(), 60_000)
    assert.ok(small.bytes <= 60_000, String(small.bytes))
    assert.ok(small.droppedLines > 0)
    assert.match(small.text, new RegExp(`${small.droppedLines} older log line\\(s\\) were left out`, 'u'))
    assert.match(small.text, /entry 499 /u)
    assert.doesNotMatch(small.text, /entry 0 /u)
    assert.ok(buildBundle(facts).bytes <= MAX_BYTES)
})

test('names are bounded, the newest are kept, and a large account\'s chats all fit', () => {
    rememberNames(Array.from({ length: MAX_KNOWN + 50 }, (_, i) => `Name number ${i}`))
    log('I', 'p', 'Name number 0 and Name number 49 and Name number 50 and Name number 4049')
    assert.match(buildBundle(facts).text, /Name number 0 and Name number 49 and \[name\] and \[name\]/u)
    clearLogs()
    rememberNames(Array.from({ length: 3000 }, (_, i) => `Chat room ${i}`))
    log('I', 'p', 'in Chat room 0 and Chat room 2999')
    assert.match(buildBundle(facts).text, /in \[name\] and \[name\]/u)
})

test('names are remembered from every answer the page gets, through the title-like keys only, and a huge or odd one costs little', () => {
    noteNames({ chats: [{ id: 1, title: 'Evening Club', last: { caption: 'Some Caption Text', fileName: 'x.mkv' } }], me: { firstName: 'Priya', username: 'priya_k99' }, count: 3, other: 'Not A Name' })
    log('I', 'p', 'chat Evening Club said Some Caption Text to Priya, not Not A Name, user priya_k99')
    const text = buildBundle(facts).text
    assert.doesNotMatch(text, /Evening Club|Some Caption|Priya|priya_k99/u)
    assert.match(text, /Not A Name/u)
    const t0 = Date.now()
    noteNames(Array.from({ length: 100_000 }, (_, i) => ({ title: `T${i} title` })))
    const loop: Record<string, unknown> = { title: 'Loop Title' }
    loop.self = loop
    noteNames(loop)
    // A chunk of a file is half a megabyte of bytes: it is not walked.
    const bytes = new Uint8Array(4_000_000)
    const t2 = Date.now()
    noteNames(bytes)
    noteNames({ chunk: bytes, blob: new Blob(['x']), map: new Map([['title', 'Map Title']]) })
    assert.ok(Date.now() - t2 < 200, 'bytes are skipped')
    log('I', 'p', 'Map Title stays')
    assert.match(buildBundle(facts).text, /Map Title stays/u)
    for (const odd of [null, undefined, 5, 'x', [], [[[[[[[[[[1]]]]]]]]]], { title: 5 }, { get title(): string { throw new Error('no') } }]) assert.doesNotThrow(() => noteNames(odd))
    assert.ok(Date.now() - t0 < 1500)
})

test('the crash note holds the error class and code only, never its message, and is scrubbed with the names known at save and at read', () => {
    rememberNames(['Blue Box'])
    noteCrash(Object.assign(new TypeError('x is not a function in Blue Box for me@example.org'), { code: 420 }))
    const stored = memory.get(CRASH_KEY)!
    assert.doesNotMatch(stored, /Blue Box|example|not a function/u)
    assert.deepEqual(JSON.parse(stored), { at: JSON.parse(stored).at, kind: 'TypeError', code: '420' })
    assert.match(earlierCrash()[0], /^The page stopped on \d{4}-\d\d-\d\d \d\d:\d\d: TypeError, code 420$/u)
    assert.doesNotMatch(ringLines().join('\n'), /Blue Box|example/u, 'the ring has the class too, not the message')
    // A class or code the page does not know is only Error, and a code that is not a code is not kept.
    assert.deepEqual(crashFacts(Object.assign(new Error('x'), { name: 'Blue Box Failed', code: 'not a code' })), { kind: 'Error', code: '' })
    assert.deepEqual(crashFacts({ name: 'DOMException', code: 'FLOOD_WAIT' }), { kind: 'DOMException', code: 'FLOOD_WAIT' })
    assert.deepEqual(crashFacts({ name: 'AbortError', code: Infinity }), { kind: 'AbortError', code: '' })
    // Read time: a tampered or old value is scrubbed again with the names known now, and nothing in it is trusted.
    const store = (v: unknown) => ({ getItem: () => v as string })
    for (const bad of [null, undefined, 5, '', '{', '[]', 'null', '"x"', '{"kind":5}', '{"text":"Blue Box"}', 'x'.repeat(100_000), JSON.stringify({ kind: 'x'.repeat(5000) })]) {
        const got = earlierCrash(store(bad))
        assert.ok(got.length === 0 || /: Error$/u.test(got[0]), String(bad).slice(0, 20))
    }
    assert.doesNotMatch(earlierCrash(store(JSON.stringify({ at: 'x', kind: 'TypeError', code: 'Blue Box' })))[0] ?? '', /Blue Box/u)
    assert.match(earlierCrash(store(JSON.stringify({ at: 'not a date', kind: 'RangeError', code: '' })))[0], /an earlier time: RangeError$/u)
    assert.deepEqual(earlierCrash(null), [])
    assert.equal(earlierCrash({ getItem: () => { throw new Error('denied') } }).length, 0)
    for (const odd of [undefined, null, { message: { not: 'text' } }, 5, 'str', { name: 7 }]) assert.doesNotThrow(() => noteCrash(odd))
})

test('clearLogs forgets the ring, the names and the crash note, and the names stop being taken out of nothing', () => {
    rememberNames(['Blue Box'])
    log('I', 'p', 'Blue Box')
    noteCrash(new Error('x'))
    assert.ok(memory.has(CRASH_KEY))
    clearLogs()
    assert.equal(ringLines().length, 0)
    assert.equal(memory.has(CRASH_KEY), false)
    log('I', 'p', 'Blue Box again')
    assert.match(buildBundle(facts).text, /Blue Box again/u, 'the forgotten name is not remembered')
})

test('size text', () => {
    assert.deepEqual([0, 1, 1024, 1025, 500_000, 1_500_000, NaN].map(sizeText), ['1 KB', '1 KB', '1 KB', '2 KB', '489 KB', '1.4 MB', '1 KB'])
})

test('the upload hook reads the inbox answer and nothing else, and sends only a bundle', async () => {
    assert.equal(reportCode(201, '{"code":"TMP-7K3Q","keepDays":30}'), 'TMP-7K3Q')
    for (const [s, b] of [[200, '{"code":"TMP-7K3Q"}'], [201, '{"code":"TMP-0I1L"}'], [201, 'nope'], [201, null], [500, '{"code":"TMP-7K3Q"}']] as const) assert.equal(reportCode(s, b), null)
    assert.equal(reportUrl('https://logs.example.org/v1/feedback'), 'https://logs.example.org/v1/report')
    assert.equal(reportUrl('https://logs.example.org/v1/report'), 'https://logs.example.org/v1/report')
    assert.equal(reportUrl('https://logs.example.org'), 'https://logs.example.org/v1/report')
    for (const bad of [null, '', 'http://x.org/v1/feedback', 'not a url', 'https://', 'https://x .org']) assert.equal(reportUrl(bad), null)
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
