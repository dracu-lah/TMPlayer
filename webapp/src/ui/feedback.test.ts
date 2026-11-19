// "Report a problem" and "Request a feature" on the web (WB11): the report, its caps, the draft kept
// on the device, one send per draft id, and the GitHub and e-mail ways round. The report is also
// checked against the log inbox's own parser (workers/log-inbox/src/feedback.ts), so what the page
// sends is what the Worker accepts. `node --test src/ui/feedback.test.ts`
import assert from 'node:assert/strict'
import { test } from 'node:test'
// The inbox's own parser, loaded by address so the web app's type check does not read the Worker's sources.
const { parseFeedback } = (await import(new URL('../../../workers/log-inbox/src/feedback.ts', import.meta.url).href)) as {
    parseFeedback: (text: string) => { ok: true; feedback: { id: string; kind: string; message: string; contact: string | null; logs: string | null; facts: { device: string } } } | { ok: false; error: string }
}
import {
    DRAFT_KEY,
    FLUSH_DELAY_MS,
    MAX_CONTACT,
    MAX_JSON,
    MAX_MAILTO,
    MAX_MESSAGE,
    MAX_URL,
    OFF_KEY,
    SAVE_DELAY_MS,
    aboutLine,
    blankDraft,
    browserLine,
    cleanLine,
    cleanMessage,
    cut,
    copyText,
    decodeDraft,
    edited,
    encodeDraft,
    featureUrl,
    feedbackUrl,
    flush,
    inboxUrl,
    issueUrl,
    localDraftStore,
    mailto,
    makeReport,
    memoryDraftStore,
    newId,
    outcome,
    parseReport,
    poster,
    problem,
    reviewLines,
    saver,
    stripLone,
    send,
    sending,
    setFormOpen,
    toJson,
    webFacts,
    worthRetrying,
    type Draft,
    type Facts,
    type Outcome,
    type Poster,
    type Timers,
} from './feedback.ts'

const FACTS: Facts = { version: 'web', platform: 'Web', device: 'Safari 17 on iPhone', language: 'en-GB' }
const ID = '0123456789abcdef0123456789abcdef'
const draft = (over: Partial<Draft> = {}): Draft => ({ kind: 'problem', message: 'The picture stops after a minute.', contact: '', id: ID, pending: null, ...over })

class FakeStorage implements Pick<Storage, 'getItem' | 'setItem' | 'removeItem'> {
    map = new Map<string, string>()
    getItem = (k: string) => this.map.get(k) ?? null
    setItem = (k: string, v: string) => void this.map.set(k, v)
    removeItem = (k: string) => void this.map.delete(k)
}

// ---- The report ----

test('a report is the one JSON object of the apps, in their order, with the contact only when given', () => {
    const r = makeReport(ID, 'problem', '  It stops.  ', '', FACTS)!
    assert.equal(toJson(r), `{"v":1,"id":"${ID}","kind":"problem","message":"It stops.","app":{"version":"web","platform":"Web","device":"Safari 17 on iPhone","language":"en-GB"}}`)
    const withContact = makeReport(ID, 'feature', 'A sleep timer', ' me@example.org ', FACTS)!
    assert.match(toJson(withContact), /"message":"A sleep timer","contact":"me@example\.org","app"/)
})

test('what the page builds is accepted by the inbox unchanged, at the caps and with odd text', () => {
    for (const message of ['It stops.', 'a\nb\n\n\n\n\nc', 'x'.repeat(MAX_MESSAGE), 'العربية \u{1F600} tab\tin', 'with \u202e control \u0007 marks']) {
        const r = makeReport(ID, 'problem', message, 'me@example.org', FACTS)!
        assert.ok(r, message)
        const parsed = parseFeedback(toJson(r))
        assert.ok(parsed.ok, message)
        if (parsed.ok) {
            assert.equal(parsed.feedback.message, r.message)
            assert.equal(parsed.feedback.contact, r.contact)
            assert.equal(parsed.feedback.id, ID)
            assert.equal(parsed.feedback.kind, 'problem')
            assert.equal(parsed.feedback.logs, null)
            assert.equal(parsed.feedback.facts.device, 'Safari 17 on iPhone')
        }
    }
})

test('the body of a report at its caps stays under the inbox 16 KB', () => {
    const r = makeReport(ID, 'problem', '\u{1F600}'.repeat(MAX_MESSAGE / 2), 'c'.repeat(MAX_CONTACT), FACTS)!
    assert.ok(new TextEncoder().encode(toJson(r)).byteLength < MAX_JSON)
})

test('empty, too long and a too long contact are never sent, and nothing is cut', () => {
    assert.equal(problem('   \n ', ''), 'empty')
    assert.equal(problem('\u200b\u0007', ''), 'empty')
    assert.equal(problem('x'.repeat(MAX_MESSAGE + 1), ''), 'too_long')
    assert.equal(problem('x'.repeat(MAX_MESSAGE), ''), null)
    assert.equal(problem('hi', 'c'.repeat(MAX_CONTACT + 1)), 'contact_too_long')
    assert.equal(makeReport(ID, 'problem', 'x'.repeat(MAX_MESSAGE + 1), null, FACTS), null)
    assert.equal(makeReport('nope', 'problem', 'hi', null, FACTS), null)
    assert.equal(makeReport(ID.toUpperCase(), 'problem', 'hi', null, FACTS), null)
})

test('the message keeps its line breaks, two blank lines at most, no control characters', () => {
    assert.equal(cleanMessage('a\r\nb\rc\n\n\n\n\n\nd  \n'), 'a\nb\nc\n\n\nd')
    assert.equal(cleanMessage('x\u202ey\u0000z\u200b'), 'xyz')
})

test('a stored or received report is parsed with garbage in every field', () => {
    const good = toJson(makeReport(ID, 'problem', 'hi', null, FACTS)!)
    assert.ok(parseReport(good))
    for (const bad of [undefined, null, 5, {}, '', '[]', 'null', '{', '{"v":2}', `{"v":1,"id":"x","kind":"problem","message":"m"}`, `{"v":1,"id":"${ID}","kind":"idea","message":"m"}`, `{"v":1,"id":"${ID}","kind":"problem","message":5}`, 'x'.repeat(MAX_JSON + 1)]) {
        assert.equal(parseReport(bad), null, String(bad))
    }
    const odd = parseReport(`{"v":1,"id":"${ID}","kind":"feature","message":"m","contact":7,"app":[1]}`)!
    assert.equal(odd.contact, null)
    assert.equal(odd.facts.device, '')
})

test('the confirm step lists exactly what the report holds', () => {
    const r = makeReport(ID, 'feature', 'A sleep timer', '@me', FACTS)!
    const lines = reviewLines(r)
    assert.deepEqual(lines, { kind: 'feature', message: 'A sleep timer', contact: '@me', about: 'Web, Safari 17 on iPhone, TMPlayer web, en-GB' })
    assert.equal(reviewLines(makeReport(ID, 'problem', 'x', null, FACTS)!).contact, null)
    assert.equal(aboutLine({ version: '', platform: '', device: 'd', language: '' }), 'd')
})

test('a draft id is 32 hex digits, drawn each time', () => {
    const a = newId()
    assert.match(a, /^[0-9a-f]{32}$/)
    assert.notEqual(a, newId())
    assert.equal(newId(() => new Uint8Array(16).fill(255)), 'ff'.repeat(16))
})

// ---- What the browser says ----

test('the browser line names the family, the major version and the system, and nothing else', () => {
    assert.equal(browserLine('Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1'), 'Safari 17 on iPhone')
    assert.equal(browserLine('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 Edg/126.0.2592.87'), 'Edge 126 on Windows')
    assert.equal(browserLine('Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0'), 'Firefox 128 on Linux')
    assert.equal(browserLine('Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36'), 'Chrome 126 on Android 14')
    assert.equal(browserLine('Mozilla/5.0 (Web0S; Linux/SmartTV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/79.0.3945.79 Safari/537.36 WebAppManager'), 'Chrome 79 on webOS TV')
    assert.equal(browserLine('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 Version/17.5 Safari/605.1.15', true), 'Safari 17 on macOS, installed app')
    assert.equal(browserLine('curl/8'), 'a browser')
    assert.equal(browserLine(undefined as unknown as string), 'a browser')
    assert.ok(!browserLine('Mozilla/5.0 (Linux; Android 14; SM-S918B) Chrome/126.0.0.0 Mobile').includes('SM-S918B'))
})

test('the facts are web, with the language the browser asks for', () => {
    assert.deepEqual(webFacts('x', 'fr-CA'), { version: 'web', platform: 'Web', device: 'a browser', language: 'fr-CA' })
    assert.equal(webFacts('x', '').language, 'en')
    assert.equal(webFacts('x', 'a'.repeat(100)).language.length, 20)
})

// ---- GitHub and e-mail ----

test('the issue page carries the template, the version and the browser, and no names', () => {
    const url = new URL(issueUrl(FACTS))
    assert.equal(url.origin + url.pathname, 'https://github.com/dracu-lah/TMPlayer/issues/new')
    assert.equal(url.searchParams.get('template'), 'bug_report.yml')
    assert.equal(url.searchParams.get('version'), 'web')
    assert.equal(url.searchParams.get('device'), 'Safari 17 on iPhone')
    assert.equal(new URL(issueUrl({ ...FACTS, language: 'fr-CA' })).searchParams.get('device'), 'Safari 17 on iPhone, app language fr-CA')
    assert.equal(url.searchParams.get('platform'), null)
    assert.equal(url.searchParams.get('what'), null)
})

test('typed words go into the issue and the feature request, and a very long text is cut to keep the address under 8 KB', () => {
    assert.equal(new URL(issueUrl(FACTS, 'It stops')).searchParams.get('what'), 'It stops')
    assert.equal(new URL(featureUrl('A sleep timer')).searchParams.get('problem'), 'A sleep timer')
    assert.equal(new URL(featureUrl()).searchParams.get('template'), 'feature_request.yml')
    for (const text of ['x'.repeat(MAX_MESSAGE), 'é\n&=#?%'.repeat(800), '\u{1F600}'.repeat(2000)]) {
        const url = issueUrl(FACTS, text)
        assert.ok(url.length <= MAX_URL, `${url.length}`)
        assert.ok(url.length < 8 * 1024)
        const kept = new URL(url).searchParams.get('what')!
        assert.ok(text.startsWith(kept))
        assert.ok(!/[\ud800-\udbff]$/.test(kept), 'not through a surrogate pair')
        assert.ok(featureUrl(text).length <= MAX_URL)
    }
})

test('the e-mail has the same facts and the typed words, and stays short enough for a mail program', () => {
    const url = mailto(FACTS, 'problem', 'It stops')
    assert.ok(url.startsWith('mailto:hello@tmplayer.org?subject='))
    const body = decodeURIComponent(url.split('&body=')[1])
    assert.match(body, /Device: Safari 17 on iPhone/)
    assert.match(body, /What happened:\n\nIt stops$/)
    assert.match(decodeURIComponent(mailto(FACTS, 'feature').split('&body=')[1]), /What I would like TMPlayer to do:/)
    assert.ok(!url.includes('+'), 'spaces are %20, not +')
    assert.ok(mailto(FACTS, 'problem', 'y'.repeat(MAX_MESSAGE)).length <= MAX_MAILTO)
    assert.ok(mailto(FACTS, 'problem', '\u{1F600}'.repeat(2000)).length <= MAX_MAILTO)
})

test('Copy details holds the facts and nothing typed', () => {
    assert.equal(copyText(FACTS), 'TMPlayer web\nPlatform: Web\nApp version: web\nDevice: Safari 17 on iPhone\nApp language: en-GB')
})

// ---- The inbox address ----

test('the feedback address comes from the build inbox address, and only a secure one counts', () => {
    assert.equal(feedbackUrl('https://inbox.example/v1/report'), 'https://inbox.example/v1/feedback')
    assert.equal(feedbackUrl(' https://inbox.example/v1/report/?a=1#x '), 'https://inbox.example/v1/feedback')
    assert.equal(feedbackUrl('https://inbox.example'), 'https://inbox.example/v1/feedback')
    assert.equal(feedbackUrl('https://inbox.example/v1/feedback'), 'https://inbox.example/v1/feedback')
    for (const none of ['', '   ', 'http://inbox.example/v1/report', 'https://', 'https://a b/v1/report', 'ftp://x', null, undefined]) {
        assert.equal(feedbackUrl(none), null, String(none))
    }
})

test('the form can be turned off on one browser, which sends Report a problem to GitHub', () => {
    const s = new FakeStorage()
    assert.equal(inboxUrl('https://i.example/v1/report', s), 'https://i.example/v1/feedback')
    s.setItem(OFF_KEY, '1')
    assert.equal(inboxUrl('https://i.example/v1/report', s), null)
    assert.equal(inboxUrl('', null), null)
    assert.equal(inboxUrl('https://i.example/v1/report', { getItem: () => { throw new Error('refused') } }), 'https://i.example/v1/feedback')
})

test('the inbox answer is read: a code, a duplicate, a slow down, a failure, garbage', () => {
    assert.deepEqual(outcome(201, '{"code":"FB-7K3Q-M4XP","duplicate":false,"keepDays":30}'), { kind: 'sent', code: 'FB-7K3Q-M4XP', duplicate: false })
    assert.deepEqual(outcome(200, '{"code":"FB-7K3Q-M4XP","duplicate":true}'), { kind: 'sent', code: 'FB-7K3Q-M4XP', duplicate: true })
    assert.deepEqual(outcome(201, '{"code":"FB-0000-0000"}'), { kind: 'failed', status: 201 })
    assert.deepEqual(outcome(201, '<html>captive portal</html>'), { kind: 'failed', status: 201 })
    assert.deepEqual(outcome(201, null), { kind: 'failed', status: 201 })
    assert.deepEqual(outcome(429, '{}', '30'), { kind: 'slow', seconds: 30 })
    assert.deepEqual(outcome(429, '{}', 'soon'), { kind: 'slow', seconds: 60 })
    assert.deepEqual(outcome(429, '{}', '99999'), { kind: 'slow', seconds: 3600 })
    assert.deepEqual(outcome(400, '{"error":"empty_message"}'), { kind: 'failed', status: 400 })
    assert.deepEqual(outcome(Number.NaN, ''), { kind: 'failed', status: 0 })
    const retry = (o: Outcome) => worthRetrying(o)
    assert.ok(retry({ kind: 'slow', seconds: 1 }) && retry({ kind: 'offline' }) && retry({ kind: 'failed', status: 503 }) && retry({ kind: 'failed', status: 0 }))
    assert.ok(!retry({ kind: 'failed', status: 400 }) && !retry({ kind: 'failed', status: 413 }) && !retry({ kind: 'sent', code: 'x', duplicate: false }))
})

test('the poster sends plain text with no cookies and no referrer, and a lost connection is offline, never a throw', async () => {
    let seen: { url: string; init: RequestInit } | null = null
    const ok = poster('https://i.example/v1/feedback', (async (url: string, init: RequestInit) => {
        seen = { url, init }
        return new Response('{"code":"FB-7K3Q-M4XP","duplicate":false}', { status: 201 })
    }) as unknown as typeof fetch, 1000, () => true)
    assert.deepEqual(await ok('{"a":1}'), { kind: 'sent', code: 'FB-7K3Q-M4XP', duplicate: false })
    const got = seen as unknown as { url: string; init: RequestInit }
    assert.equal(got.url, 'https://i.example/v1/feedback')
    assert.equal(got.init.method, 'POST')
    assert.deepEqual(got.init.headers, { 'Content-Type': 'text/plain; charset=utf-8' })
    assert.equal(got.init.credentials, 'omit')
    assert.equal(got.init.referrerPolicy, 'no-referrer')
    assert.equal(got.init.redirect, 'error')

    const down = poster('https://i.example/v1/feedback', (async () => { throw new TypeError('Failed to fetch') }) as unknown as typeof fetch, 1000, () => true)
    assert.deepEqual(await down('{}'), { kind: 'offline' })
    let called = false
    const away = poster('https://i.example/v1/feedback', (async () => { called = true; return new Response('', { status: 201 }) }) as unknown as typeof fetch, 1000, () => false)
    assert.deepEqual(await away('{}'), { kind: 'offline' })
    assert.equal(called, false, 'offline sends nothing')
    const slow = poster('https://i.example/v1/feedback', ((_u: string, init: RequestInit) => new Promise((_r, reject) => init.signal!.addEventListener('abort', () => reject(new DOMException('x', 'AbortError'))))) as unknown as typeof fetch, 20, () => true)
    assert.deepEqual(await slow('{}'), { kind: 'offline' })
    const limited = poster('https://i.example/v1/feedback', (async () => new Response('{}', { status: 429, headers: { 'Retry-After': '45' } })) as unknown as typeof fetch, 1000, () => true)
    assert.deepEqual(await limited('{}'), { kind: 'slow', seconds: 45 })
})

// ---- The draft kept on the device ----

test('a draft round trips, and a stored one with wrong fields falls back, never throws', () => {
    const d = draft({ kind: 'feature', contact: '@me' })
    assert.deepEqual(decodeDraft(encodeDraft(d)), d)
    for (const bad of [undefined, null, '', '  ', '{', '[]', 'null', 5, 'x'.repeat(70 * 1024)]) assert.equal(decodeDraft(bad), null, String(bad))
    const odd = decodeDraft('{"kind":"idea","message":5,"contact":[],"id":"nope","pending":"{}"}')!
    assert.equal(odd.kind, 'problem')
    assert.equal(odd.message, '')
    assert.equal(odd.contact, '')
    assert.match(odd.id, /^[0-9a-f]{32}$/)
    assert.equal(odd.pending, null)
    const huge = decodeDraft(JSON.stringify({ message: 'x'.repeat(30_000), id: ID }))!
    assert.equal(huge.message.length, MAX_MESSAGE * 2)
})

test('a pending report is kept only when it is for the same draft id', () => {
    const pending = toJson(makeReport(ID, 'problem', 'hi', null, FACTS)!)
    assert.equal(decodeDraft(JSON.stringify({ message: 'hi', id: ID, pending }))!.pending, pending)
    const other = toJson(makeReport('f'.repeat(32), 'problem', 'hi', null, FACTS)!)
    assert.equal(decodeDraft(JSON.stringify({ message: 'hi', id: ID, pending: other }))!.pending, null)
})

test('the stored draft holds what was typed and the id, never logs, an account or the facts', () => {
    const stored = encodeDraft(draft({ contact: '@me' }))
    assert.deepEqual(Object.keys(JSON.parse(stored)).sort(), ['contact', 'id', 'kind', 'message'])
})

test('the local store reads and writes the one key, clears on a blank draft, and a refused store loses nothing else', () => {
    const storage = new FakeStorage()
    const store = localDraftStore(storage as unknown as Storage)
    store.write(draft())
    assert.ok(storage.map.has(DRAFT_KEY))
    assert.deepEqual(store.read(), draft())
    store.write(draft({ message: '  ', contact: '' }))
    assert.equal(storage.map.has(DRAFT_KEY), false, 'a blank draft is nothing worth keeping')
    store.write(draft())
    store.write(null)
    assert.equal(store.read(), null)
    const refused = localDraftStore({ getItem: () => { throw new Error('no') }, setItem: () => { throw new Error('full') }, removeItem: () => { throw new Error('no') } } as unknown as Storage)
    refused.write(draft())
    assert.equal(refused.read(), null)
    assert.equal(localDraftStore(null).read(), null)
})

class FakeTimers implements Timers {
    now = 0
    jobs = new Map<number, { at: number; fn: () => void }>()
    next = 1
    set(fn: () => void, ms: number) {
        const id = this.next++
        this.jobs.set(id, { at: this.now + ms, fn })
        return id
    }
    clear(handle: unknown) {
        this.jobs.delete(handle as number)
    }
    advance(ms: number) {
        this.now += ms
        for (const [id, job] of [...this.jobs]) {
            if (job.at <= this.now) {
                this.jobs.delete(id)
                job.fn()
            }
        }
    }
}

test('typing is kept 1.5 s after the last change: one wait, replaced by each change, nothing left running', () => {
    const store = memoryDraftStore()
    const timers = new FakeTimers()
    const keep = saver(store, SAVE_DELAY_MS, timers)
    assert.equal(SAVE_DELAY_MS, 1500)
    keep.change(draft({ message: 'a' }))
    timers.advance(1000)
    keep.change(draft({ message: 'ab' }))
    timers.advance(1000)
    assert.equal((store as { draft: Draft | null }).draft, null, 'the wait started again at the second change')
    timers.advance(500)
    assert.equal((store as { draft: Draft | null }).draft?.message, 'ab')
    assert.equal(keep.waiting, false)
    assert.equal(timers.jobs.size, 0)
    keep.change(draft({ message: 'abc' }))
    keep.now()
    assert.equal((store as { draft: Draft | null }).draft?.message, 'abc', 'closing writes at once')
    assert.equal(timers.jobs.size, 0)
    keep.change(draft({ message: 'zzz' }))
    keep.cancel()
    timers.advance(5000)
    assert.equal((store as { draft: Draft | null }).draft?.message, 'abc', 'a cancelled wait writes nothing')
})

// ---- Sending ----

const never = (): Poster => async () => { throw new Error('must not send') }
const sent = (code = 'FB-7K3Q-M4XP'): Poster => async () => ({ kind: 'sent', code, duplicate: false })

test('a send keeps the exact report before it goes, and clears the draft once filed', async () => {
    const store = memoryDraftStore()
    let kept: string | null = null
    const post: Poster = async (json) => {
        kept = store.draft?.pending ?? null
        assert.equal(JSON.parse(json).id, ID)
        return { kind: 'sent', code: 'FB-7K3Q-M4XP', duplicate: false }
    }
    const step = await send(draft(), FACTS, post, store)
    assert.deepEqual(step, { step: 'sent', code: 'FB-7K3Q-M4XP', duplicate: false })
    assert.ok(kept, 'pending was written before the post')
    assert.equal(store.draft, null)
    assert.equal(sending(ID), false)
})

test('a double press sends once: the second call gets the first answer', async () => {
    const store = memoryDraftStore()
    let calls = 0
    let release!: (o: Outcome) => void
    const post: Poster = () => {
        calls++
        return new Promise<Outcome>((resolve) => (release = resolve))
    }
    const first = send(draft(), FACTS, post, store)
    const second = send(draft(), FACTS, post, store)
    assert.equal(sending(ID), true)
    release({ kind: 'sent', code: 'FB-7K3Q-M4XP', duplicate: false })
    assert.deepEqual(await first, await second)
    assert.equal(calls, 1)
    assert.equal(sending(ID), false)
})

test('no connection or an inbox failing keeps it queued with the same id, and Try again sends that id again', async () => {
    for (const out of [{ kind: 'offline' }, { kind: 'failed', status: 503 }, { kind: 'failed', status: 0 }, { kind: 'slow', seconds: 60 }] as Outcome[]) {
        const store = memoryDraftStore()
        const step = await send(draft(), FACTS, async () => out, store)
        assert.equal(step.step, 'queued', JSON.stringify(out))
        if (step.step === 'queued') {
            assert.equal(step.wait, out.kind === 'slow' ? 60 : 0)
            assert.equal(step.draft.id, ID)
            assert.equal(parseReport(step.draft.pending)?.id, ID)
            assert.equal(store.draft?.pending, step.draft.pending, 'kept on the device')
            const bodies: string[] = []
            const again = await send(step.draft, FACTS, async (j) => (bodies.push(j), { kind: 'sent', code: 'FB-7K3Q-M4XP', duplicate: true }), store)
            assert.equal(again.step, 'sent')
            assert.equal(JSON.parse(bodies[0]).id, ID)
        }
    }
})

test('a refusal keeps the words for editing and drops the pending report', async () => {
    const store = memoryDraftStore()
    const step = await send(draft(), FACTS, async () => ({ kind: 'failed', status: 400 }), store)
    assert.equal(step.step, 'refused')
    if (step.step === 'refused') {
        assert.equal(step.status, 400)
        assert.equal(step.draft.pending, null)
        assert.equal(step.draft.message, draft().message)
    }
    assert.equal(store.draft?.pending, null)
    assert.equal(store.draft?.message, draft().message)
})

test('a report that cannot go is never posted', async () => {
    const store = memoryDraftStore()
    assert.deepEqual(await send(draft({ message: '  ' }), FACTS, never(), store), { step: 'editing', problem: 'empty' })
    assert.deepEqual(await send(draft({ message: 'x'.repeat(MAX_MESSAGE + 1) }), FACTS, never(), store), { step: 'editing', problem: 'too_long' })
    assert.deepEqual(await send(draft({ contact: 'c'.repeat(MAX_CONTACT + 1) }), FACTS, never(), store), { step: 'editing', problem: 'contact_too_long' })
    assert.equal(store.draft, null)
})

test('changing a draft that was tried draws a new id, so the changed words are never taken for the old ones', () => {
    const tried = draft({ pending: toJson(makeReport(ID, 'problem', 'hi', null, FACTS)!) })
    const same = edited(tried, { ...tried })
    assert.equal(same.id, ID)
    assert.ok(same.pending)
    const changed = edited(tried, { ...tried, message: 'different' })
    assert.notEqual(changed.id, ID)
    assert.equal(changed.pending, null)
    const fresh = draft()
    assert.equal(edited(fresh, { ...fresh, message: 'more' }).id, ID, 'an untried draft keeps its id')
})

test('a draft that was typed after a queued try is not cleared by the background retry', async () => {
    const store = memoryDraftStore()
    const queued = await send(draft(), FACTS, async () => ({ kind: 'offline' }), store)
    assert.equal(queued.step, 'queued')
    let posted: string | null = null
    assert.equal(await flush(async (j) => ((posted = j), { kind: 'sent', code: 'FB-7K3Q-M4XP', duplicate: false }), store), true)
    assert.equal(JSON.parse(posted!).id, ID)
    assert.equal(store.draft, null)
})

test('the background retry does nothing without a waiting report, while the form is open, or while a send is going', async () => {
    const store = memoryDraftStore(draft())
    assert.equal(await flush(never(), store), false, 'nothing pending')
    const queued = memoryDraftStore(draft({ pending: toJson(makeReport(ID, 'problem', 'hi', null, FACTS)!) }))
    setFormOpen(true)
    assert.equal(await flush(never(), queued), false)
    setFormOpen(false)
    assert.equal(await flush(async () => ({ kind: 'offline' }), queued), false)
    assert.ok(queued.draft?.pending, 'a failed retry keeps it')
    assert.equal(await flush(sent(), queued), true)
    assert.equal(queued.draft, null)
    assert.equal(FLUSH_DELAY_MS, 60_000)
})

// ---- Half of an emoji ----

const HIGH = String.fromCharCode(0xd83d)
const LOW = String.fromCharCode(0xde00)
const GRIN = '\u{1F600}'

test('a lone surrogate (a cut emoji, a paste) is dropped from the message and the lines, whole pairs stay', () => {
    assert.equal(stripLone(`a${HIGH}b`), 'ab')
    assert.equal(stripLone(`a${LOW}b`), 'ab')
    assert.equal(stripLone(`${HIGH}${HIGH}${LOW}${LOW}`), `${HIGH}${LOW}`)
    assert.equal(stripLone(GRIN), GRIN)
    assert.equal(cleanMessage(`hi ${HIGH} there ${LOW}`), 'hi  there')
    assert.equal(cleanLine(`me${LOW}@x`), 'me@x')
    for (const text of [`x${HIGH}`, `${LOW}x`]) {
        assert.doesNotThrow(() => encodeURIComponent(cleanMessage(text)))
        assert.equal(JSON.parse(JSON.stringify(cleanMessage(text))), cleanMessage(text))
    }
})

test('cut never ends half way through a pair', () => {
    assert.equal(cut(`ab${GRIN}`, 3), 'ab')
    assert.equal(cut(`ab${GRIN}`, 4), `ab${GRIN}`)
    assert.equal(cut('abc', 10), 'abc')
    assert.equal(cut(GRIN, 1), '')
})

test('the addresses never throw on lone surrogates or a cut emoji pair, at any length', () => {
    const texts = [HIGH, LOW, `a${HIGH}${HIGH}b${LOW}`, `${'x'.repeat(MAX_URL)}${HIGH}`, GRIN.repeat(3000).slice(0, 5001), `${'é'.repeat(5000)}${LOW}`]
    for (const text of texts) {
        assert.doesNotThrow(() => issueUrl(FACTS, text))
        assert.doesNotThrow(() => featureUrl(text))
        assert.doesNotThrow(() => mailto(FACTS, 'problem', text))
        assert.ok(issueUrl(FACTS, text).length <= MAX_URL)
        assert.ok(mailto(FACTS, 'feature', text).length <= MAX_MAILTO)
        assert.doesNotThrow(() => decodeURIComponent(issueUrl(FACTS, text).split('&what=')[1] ?? ''))
    }
})

test('a report with half an emoji is built from the clean text and the inbox accepts it', () => {
    const r = makeReport(ID, 'problem', `It stops ${HIGH}`, `me${LOW}`, FACTS)!
    assert.equal(r.message, 'It stops')
    assert.equal(r.contact, 'me')
    assert.ok(parseFeedback(toJson(r)).ok)
    assert.equal(makeReport(ID, 'problem', HIGH, null, FACTS), null, 'only half an emoji is nothing to send')
})

test('a stored draft with lone surrogates or a cut pair opens clean and never breaks a reopen', () => {
    const stored = JSON.stringify({ kind: 'problem', message: `${'x'.repeat(MAX_MESSAGE * 2 - 1)}${GRIN}`, contact: `a${HIGH}`, id: ID })
    const d = decodeDraft(stored)!
    assert.equal(d.message.length, MAX_MESSAGE * 2 - 1)
    assert.equal(d.contact, 'a')
    assert.doesNotThrow(() => issueUrl(FACTS, d.message))
    const raw = decodeDraft(JSON.stringify({ message: `x${HIGH}y`, contact: LOW, id: ID }))!
    assert.equal(raw.message, 'xy')
    assert.equal(raw.contact, '')
    assert.deepEqual(decodeDraft(encodeDraft(raw)), raw)
})

test('a blank draft takes the kind it is opened for and starts with a fresh id', () => {
    const a = blankDraft('feature')
    assert.equal(a.kind, 'feature')
    assert.notEqual(a.id, blankDraft('feature').id)
    assert.equal(a.message, '')
})

test('the logs code (WB18 hook): a TMP code from the inbox rides in the report, anything else is dropped, and the Worker reads it back', () => {
    const r = makeReport(ID, 'problem', 'It stops.', null, FACTS, 'TMP-7K3Q')!
    assert.equal(r.logs, 'TMP-7K3Q')
    assert.match(toJson(r), /"message":"It stops\.","logs":"TMP-7K3Q","app"/)
    const back = parseReport(toJson(r))
    assert.equal(back?.logs, 'TMP-7K3Q')
    const worker = parseFeedback(toJson(r))
    assert.ok(worker.ok && worker.feedback.logs === 'TMP-7K3Q')
    for (const bad of ['TMP-0I1L', 'tmp-7k3q', 'TMP-7K3Q ', 'FB-7K3Q-M4XP', '', 'TMP-7K3QX', '<script>']) {
        assert.equal(makeReport(ID, 'problem', 'x', null, FACTS, bad)!.logs, null, bad)
    }
    assert.equal(makeReport(ID, 'problem', 'x', null, FACTS)!.logs, null)
    assert.doesNotMatch(toJson(makeReport(ID, 'problem', 'x', null, FACTS)!), /logs/)
    assert.equal(parseReport(toJson(r).replace('"logs":"TMP-7K3Q"', '"logs":5'))?.logs, null)
})

test('Copy details carries the scrubbed logs when the page has any, and only for a problem (attach false leaves them off)', async () => {
    const { clearLogs, log, rememberNames } = await import('../logs.ts')
    clearLogs()
    const bare = copyText(FACTS)
    assert.doesNotMatch(bare, /== /, 'an empty ring adds nothing')
    rememberNames(['Blue Box'])
    log('W', 'player', 'could not play Blue Box S01E02.mkv for me@example.org')
    const text = copyText(FACTS)
    assert.ok(text.startsWith(bare))
    assert.match(text, /== Page log, kept in this tab only ==\n.* W player: \[file\] for \[email\]/u)
    assert.doesNotMatch(text, /Blue Box|\.mkv|example\.org/u)
    assert.equal(copyText(FACTS, false), bare)
    clearLogs()
})
