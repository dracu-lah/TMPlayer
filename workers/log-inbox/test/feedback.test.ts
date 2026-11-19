// "Report a problem" and "Request a feature" (HX64). Run with `npm test` (Node 22.6 or newer, no install).
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { KEEP_DAYS, handle, sweep, type Bucket, type Deps, type Env } from '../src/inbox.ts'
import {
  FEEDBACK_CODE,
  FEEDBACK_PATH,
  FEEDBACK_PREFIX,
  MAX_CONTACT,
  MAX_FEEDBACK_BYTES,
  MAX_MESSAGE,
  feedbackCode,
  parseFeedback,
} from '../src/feedback.ts'

class FakeBucket implements Bucket {
  objects = new Map<string, { bytes: Uint8Array; uploaded: Date }>()
  async put(key: string, value: Uint8Array, options?: { onlyIf?: { etagDoesNotMatch?: string } }) {
    if (options?.onlyIf?.etagDoesNotMatch === '*' && this.objects.has(key)) return null
    this.objects.set(key, { bytes: value, uploaded: new Date() })
    return { key }
  }
  async list(options?: { prefix?: string; cursor?: string; limit?: number }) {
    const objects = [...this.objects.entries()]
      .filter(([k]) => k.startsWith(options?.prefix ?? '') && k > (options?.cursor ?? ''))
      .sort(([a], [b]) => (a < b ? -1 : 1))
      .map(([key, v]) => ({ key, uploaded: v.uploaded }))
    return { objects, truncated: false }
  }
  async delete(keys: string[]) {
    for (const k of keys) this.objects.delete(k)
  }
}

function env(overrides: Partial<Env> = {}): Env & { bucket: FakeBucket } {
  const bucket = new FakeBucket()
  return {
    bucket,
    REPORTS: bucket,
    LIMITER: { limit: async () => ({ success: true }) },
    GLOBAL: { limit: async () => ({ success: true }) },
    ...overrides,
  }
}

const deps: Deps = { random: (n) => new Uint8Array(n), now: () => 0 }
const ID = '0123456789abcdef0123456789abcdef'

const body = (extra: Record<string, unknown> = {}) =>
  JSON.stringify({
    v: 1,
    id: ID,
    kind: 'problem',
    message: 'The video stops after ten seconds.',
    app: { version: '1.40.0', platform: 'Android TV or Google TV', device: 'Mi Box S, Android 9', language: 'en' },
    ...extra,
  })
const post = (text: BodyInit | null, headers: Record<string, string> = { 'Content-Type': 'text/plain; charset=utf-8' }) =>
  new Request(`https://inbox.example${FEEDBACK_PATH}`, { method: 'POST', body: text, headers })
const filed = (e: { bucket: FakeBucket }) => [...e.bucket.objects.values()].map((o) => new TextDecoder().decode(o.bytes)).join()

test('a report is filed under a code made from its id, and the code comes back', async () => {
  const e = env()
  const res = await handle(post(body()), e, deps)
  assert.equal(res.status, 201)
  const answer = (await res.json()) as { code: string; duplicate: boolean; keepDays: number }
  assert.match(answer.code, FEEDBACK_CODE)
  assert.equal(answer.code, await feedbackCode(ID))
  assert.equal(answer.duplicate, false)
  assert.equal(answer.keepDays, KEEP_DAYS)
  assert.ok(e.bucket.objects.has(`${FEEDBACK_PREFIX}${answer.code}-${ID}.txt`))
  const text = filed(e)
  assert.ok(text.startsWith(`TMPlayer feedback ${answer.code}\nKind: problem\n`))
  assert.ok(text.includes('Device: Mi Box S, Android 9'))
  assert.ok(text.includes('Logs: none'))
  assert.ok(text.includes('Reply to: none given'))
  assert.ok(text.includes('The video stops after ten seconds.'))
})

test('the same draft sent twice (a double press, a retry) is filed once', async () => {
  const e = env()
  await handle(post(body()), e, deps)
  const again = await handle(post(body({ message: 'changed on the way' })), e, deps)
  assert.equal(again.status, 200)
  assert.equal(((await again.json()) as { duplicate: boolean }).duplicate, true)
  assert.equal(e.bucket.objects.size, 1)
  assert.ok(!filed(e).includes('changed on the way'))
})

test('a feature request, a reply contact and a logs code are filed when given', async () => {
  const e = env()
  const res = await handle(post(body({ kind: 'feature', contact: '  me@example.org\n', logs: 'TMP-7K3Q' })), e, deps)
  assert.equal(res.status, 201)
  const text = filed(e)
  assert.ok(text.includes('Kind: feature request'))
  assert.ok(text.includes('Reply to: me@example.org\n'))
  assert.ok(text.includes('Logs: TMP-7K3Q'))
})

test('a logs code that is not one of ours is dropped, not filed', async () => {
  const e = env()
  await handle(post(body({ logs: 'https://evil.example/x' })), e, deps)
  assert.ok(filed(e).includes('Logs: none'))
  assert.ok(!filed(e).includes('evil'))
})

test('the message keeps its lines; control characters go and one line fields stay one line', () => {
  const parsed = parseFeedback(body({ message: 'line one\r\nline two\u0000\u202e\n\n\n\n\n\nend  ', contact: 'a\nb\u0007c' }))
  assert.ok(parsed.ok)
  if (!parsed.ok) return
  assert.equal(parsed.feedback.message, 'line one\nline two\n\n\nend')
  assert.equal(parsed.feedback.contact, 'a bc')
})

test('a forged header line in a fact cannot add a line to the file', async () => {
  const e = env()
  await handle(post(body({ app: { version: '1.0\nReply to: forged', device: 'x\r\nKind: feature' } })), e, deps)
  const lines = filed(e).split('\n')
  assert.equal(lines.filter((l) => l.startsWith('Reply to:')).length, 1)
  assert.equal(lines.filter((l) => l.startsWith('Kind:')).length, 1)
})

test('what is not a report is refused: not JSON, wrong types, a bad id or kind, a blank message, a log report', async () => {
  const e = env()
  for (const text of [
    'hello',
    '[]',
    'null',
    '"text"',
    JSON.stringify({}),
    body({ v: 2 }),
    body({ id: 'short' }),
    body({ id: ID.toUpperCase() }),
    body({ id: 42 }),
    body({ kind: 'praise' }),
    body({ kind: ['problem'] }),
    body({ message: 42 }),
    body({ message: '   \n\t ' }),
    body({ message: null }),
    'TMPlayer logs\nApp version: 1',
  ]) {
    assert.equal((await handle(post(text), e, deps)).status, 400, text)
  }
  assert.equal((await handle(post(''), e, deps)).status, 400)
  assert.equal((await handle(post(new Uint8Array([0xff, 0xfe, 0x00, 0x80])), e, deps)).status, 400)
  assert.equal(e.bucket.objects.size, 0)
})

test('a message over the cap is refused, never cut; exactly the cap is filed', async () => {
  const e = env()
  const long = await handle(post(body({ message: 'x'.repeat(MAX_MESSAGE + 1) })), e, deps)
  assert.equal(long.status, 400)
  assert.equal(((await long.json()) as { error: string }).error, 'message_too_long')
  assert.equal((await handle(post(body({ message: 'y'.repeat(MAX_MESSAGE) })), e, deps)).status, 201)
})

test('a message at its cap in a script of three byte letters still fits the body cap', () => {
  assert.ok(new TextEncoder().encode(body({ message: '夜'.repeat(MAX_MESSAGE), contact: '夜'.repeat(MAX_CONTACT) })).length < MAX_FEEDBACK_BYTES)
})

test('over 16 KB is refused, by its header and by what really arrives', async () => {
  const e = env()
  const big = body({ message: 'z'.repeat(MAX_FEEDBACK_BYTES) })
  const declared = await handle(post(big, { 'Content-Type': 'text/plain', 'Content-Length': String(big.length) }), e, deps)
  assert.equal(declared.status, 413)
  assert.equal(((await declared.json()) as { maxBytes: number }).maxBytes, MAX_FEEDBACK_BYTES)
  const chunked = await handle(
    new Request(`https://inbox.example${FEEDBACK_PATH}`, {
      method: 'POST',
      body: new ReadableStream<Uint8Array>({
        start(c) {
          c.enqueue(new TextEncoder().encode(big))
          c.close()
        },
      }),
      headers: { 'Content-Type': 'text/plain' },
      duplex: 'half',
    } as RequestInit),
    e,
    deps,
  )
  assert.equal(chunked.status, 413)
  assert.equal(e.bucket.objects.size, 0)
})

test('the caps on one line fields hold, never through a surrogate pair', () => {
  const parsed = parseFeedback(body({ contact: '😀'.repeat(400), app: { device: 'd'.repeat(10_000) } }))
  assert.ok(parsed.ok)
  if (!parsed.ok) return
  assert.ok((parsed.feedback.contact ?? '').length <= MAX_CONTACT)
  assert.ok(!/[\ud800-\udbff]$/u.test(parsed.feedback.contact ?? ''))
  assert.ok(parsed.feedback.facts.device.length <= 160)
})

test('wrong types in the facts and unknown fields are dropped, the rest is filed', async () => {
  const e = env()
  const res = await handle(post(body({ app: { version: 7, platform: null, device: ['x'] }, extra: 'secret', contact: 99 })), e, deps)
  assert.equal(res.status, 201)
  const text = filed(e)
  assert.ok(text.includes('App version: unknown'))
  assert.ok(text.includes('Reply to: none given'))
  assert.ok(!text.includes('secret'))
})

test('feedback needs plain text, POST and the same limits as a log report', async () => {
  const e = env()
  assert.equal((await handle(post(body(), { 'Content-Type': 'application/json' }), e, deps)).status, 415)
  assert.equal((await handle(new Request(`https://inbox.example${FEEDBACK_PATH}`), e, deps)).status, 405)
  const limited = env({ LIMITER: { limit: async () => ({ success: false }) } })
  const res = await handle(post(body()), limited, deps)
  assert.equal(res.status, 429)
  assert.equal(res.headers.get('Retry-After'), '60')
  const all = env({ GLOBAL: { limit: async () => ({ success: false }) } })
  assert.equal((await handle(post(body()), all, deps)).status, 429)
  assert.equal(e.bucket.objects.size + limited.bucket.objects.size + all.bucket.objects.size, 0)
})

test('feedback keeps no address, no user agent and no header', async () => {
  const e = env()
  const res = await handle(post(body(), { 'Content-Type': 'text/plain', 'CF-Connecting-IP': '203.0.113.9', 'User-Agent': 'TMPlayer/1.40' }), e, deps)
  const text = JSON.stringify(await res.json()) + filed(e)
  assert.ok(!text.includes('203.0.113.9'))
  assert.ok(!text.includes('TMPlayer/1.40'))
})

test('storage that fails is a 503 for feedback too', async () => {
  const e = env()
  e.bucket.put = async () => {
    throw new Error('R2 is down')
  }
  assert.equal((await handle(post(body()), e, deps)).status, 503)
})

test('random garbage never throws and never files', async () => {
  const e = env()
  let seed = 7
  const next = () => (seed = (seed * 1103515245 + 12345) & 0x7fffffff)
  for (let i = 0; i < 300; i++) {
    const n = next() % 400
    const text = Array.from({ length: n }, () => String.fromCharCode(next() % 0x3000)).join('')
    const res = await handle(post(text), e, deps)
    assert.ok([400, 413].includes(res.status), String(res.status))
  }
  // Cut short at every length: never a crash, never half a report filed.
  const whole = body()
  for (let i = 0; i < whole.length; i++) assert.equal((await handle(post(whole.slice(0, i)), e, deps)).status, 400)
  assert.equal(e.bucket.objects.size, 0)
})

test('the sweep clears feedback older than 30 days with the rest', async () => {
  const bucket = new FakeBucket()
  const now = Date.UTC(2026, 11, 15)
  bucket.objects.set(`${FEEDBACK_PREFIX}FB-2222-old.txt`, { bytes: new Uint8Array(1), uploaded: new Date(now - 31 * 86_400_000) })
  bucket.objects.set(`${FEEDBACK_PREFIX}FB-3333-new.txt`, { bytes: new Uint8Array(1), uploaded: new Date(now - 2 * 86_400_000) })
  assert.equal(await sweep(bucket, now), 1)
  assert.ok(bucket.objects.has(`${FEEDBACK_PREFIX}FB-3333-new.txt`))
})

test('codes are eight letters from the whole id, worked out here, and differ for ids that share a head', async () => {
  assert.match(await feedbackCode('00000000000000000000000000000000'), FEEDBACK_CODE)
  assert.match(await feedbackCode('ffffffffffffffffffffffffffffffff'), FEEDBACK_CODE)
  // Same first 16 hex digits, different tail: the old head-only code would have matched.
  assert.notEqual(await feedbackCode('0123456789abcdef0000000000000000'), await feedbackCode('0123456789abcdef1111111111111111'))
  const seen = new Set<string>()
  for (let i = 0; i < 2000; i++) seen.add(await feedbackCode(i.toString(16).padStart(32, '0')))
  assert.equal(seen.size, 2000)
})

test('the web app can read the answer of a feedback post, and no other page can', async () => {
  const worker = (await import('../src/index.ts')).default
  const e = env()
  const web = 'https://web.tmplayer.org'
  const fromWeb = await worker.fetch(new Request(`https://inbox.example${FEEDBACK_PATH}`, { method: 'POST', body: body(), headers: { 'Content-Type': 'text/plain; charset=utf-8', Origin: web } }), e)
  assert.equal(fromWeb.status, 201)
  assert.equal(fromWeb.headers.get('Access-Control-Allow-Origin'), web)
  assert.equal(fromWeb.headers.get('Access-Control-Expose-Headers'), 'Retry-After')
  assert.equal(fromWeb.headers.get('Vary'), 'Origin')
  assert.match(((await fromWeb.json()) as { code: string }).code, FEEDBACK_CODE)
  // An answer that is an error is readable too: the page says why.
  const refused = await worker.fetch(new Request(`https://inbox.example${FEEDBACK_PATH}`, { method: 'POST', body: '{', headers: { 'Content-Type': 'text/plain', Origin: web } }), e)
  assert.equal(refused.status, 400)
  assert.equal(refused.headers.get('Access-Control-Allow-Origin'), web)
  const elsewhere = await worker.fetch(new Request(`https://inbox.example${FEEDBACK_PATH}`, { method: 'POST', body: body({ id: 'f'.repeat(32) }), headers: { 'Content-Type': 'text/plain; charset=utf-8', Origin: 'https://evil.example' } }), e)
  assert.equal(elsewhere.headers.get('Access-Control-Allow-Origin'), null)
  // Vary: Origin on every feedback answer, so a cache never hands one origin's answer to another.
  assert.equal(elsewhere.headers.get('Vary'), 'Origin')
  assert.equal(refused.headers.get('Vary'), 'Origin')
  const app = await worker.fetch(post(body({ id: 'e'.repeat(32) })), e)
  assert.equal(app.headers.get('Access-Control-Allow-Origin'), null, 'the apps send no Origin and need nothing')
  assert.equal(app.headers.get('Vary'), 'Origin')
  // The 404 and 405 answers of the path carry it too.
  const wrong = await worker.fetch(new Request(`https://inbox.example${FEEDBACK_PATH}`, { method: 'GET' }), e)
  assert.equal(wrong.status, 405)
  assert.equal(wrong.headers.get('Vary'), 'Origin')
  // Log reports and title reports are not the web app's.
  const logs = await worker.fetch(new Request('https://inbox.example/v1/report', { method: 'POST', body: 'x', headers: { 'Content-Type': 'text/plain', Origin: web } }), e)
  assert.equal(logs.headers.get('Access-Control-Allow-Origin'), null)
})

test('a preflight from the web app is answered, from another page refused', async () => {
  const worker = (await import('../src/index.ts')).default
  const ask = (origin: string) => worker.fetch(new Request(`https://inbox.example${FEEDBACK_PATH}`, { method: 'OPTIONS', headers: { Origin: origin, 'Access-Control-Request-Method': 'POST' } }), env())
  const ok = await ask('https://web.tmplayer.org')
  assert.equal(ok.status, 204)
  assert.equal(ok.headers.get('Access-Control-Allow-Origin'), 'https://web.tmplayer.org')
  assert.equal(ok.headers.get('Access-Control-Allow-Methods'), 'POST')
  assert.equal(ok.headers.get('Vary'), 'Origin')
  const refused = await ask('https://evil.example')
  assert.equal(refused.status, 403)
  assert.equal(refused.headers.get('Vary'), 'Origin')
  assert.equal(refused.headers.get('Access-Control-Allow-Origin'), null)
})
