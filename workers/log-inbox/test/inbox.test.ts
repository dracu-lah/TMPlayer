// Run with `node --test workers/log-inbox/test` (Node 22.6 or newer, no install, no Cloudflare).
import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  ALPHABET,
  CODE,
  HEADER,
  KEEP_DAYS,
  MAX_BYTES,
  PREFIX,
  handle,
  isPlainText,
  newCode,
  sweep,
  type Bucket,
  type Deps,
  type Env,
} from '../src/inbox.ts'
import {
  MAX_NAME,
  MAX_TITLE,
  MAX_TITLE_BYTES,
  NAME_CODE,
  TITLES_PATH,
  TITLE_PREFIX,
  codeOf,
  hashOf,
  parsePair,
  scrub,
} from '../src/titles.ts'

class FakeBucket implements Bucket {
  objects = new Map<string, { bytes: Uint8Array; uploaded: Date }>()
  puts = 0
  async put(key: string, value: Uint8Array, options?: { onlyIf?: { etagDoesNotMatch?: string } }) {
    this.puts++
    if (options?.onlyIf?.etagDoesNotMatch === '*' && this.objects.has(key)) return null
    this.objects.set(key, { bytes: value, uploaded: new Date() })
    return { key }
  }
  async list(options?: { prefix?: string; cursor?: string; limit?: number }) {
    // Like R2, the cursor is a place in the key order, so deleting while paging loses nothing.
    const all = [...this.objects.entries()]
      .filter(([k]) => k.startsWith(options?.prefix ?? '') && k > (options?.cursor ?? ''))
      .sort(([a], [b]) => (a < b ? -1 : 1))
      .map(([key, v]) => ({ key, uploaded: v.uploaded }))
    const limit = options?.limit ?? 1000
    const objects = all.slice(0, limit)
    const truncated = all.length > limit
    return { objects, truncated, cursor: truncated ? objects[objects.length - 1].key : undefined }
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

let seed = 0
const deps: Deps = {
  random: (n) => Uint8Array.from({ length: n }, () => (seed++ * 37) % 256),
  now: () => 0,
}

const report = `${HEADER}\nApp version: 1.30.0\n`
const post = (body: BodyInit | null, headers: Record<string, string> = { 'Content-Type': 'text/plain; charset=utf-8' }, path = '/v1/report') =>
  new Request(`https://inbox.example${path}`, { method: 'POST', body, headers })

test('a report is stored under a short code and the code comes back', async () => {
  const e = env()
  const res = await handle(post(report), e, deps)
  assert.equal(res.status, 201)
  const body = (await res.json()) as { code: string; keepDays: number }
  assert.match(body.code, CODE)
  assert.equal(body.keepDays, KEEP_DAYS)
  assert.equal(res.headers.get('Cache-Control'), 'no-store')
  assert.equal(new TextDecoder().decode(e.bucket.objects.get(`${PREFIX}${body.code}.txt`)!.bytes), report)
})

test('codes only use letters and digits that are not mistaken for each other', () => {
  for (let i = 0; i < 500; i++) {
    const code = newCode((n) => crypto.getRandomValues(new Uint8Array(n)))
    assert.match(code, CODE)
  }
  for (const c of '01ILOilo') assert.ok(!ALPHABET.includes(c))
})

test('a taken code is never overwritten: the next one is drawn', async () => {
  const e = env()
  let calls = 0
  const same: Deps = { now: () => 0, random: () => Uint8Array.from({ length: 8 }, () => (calls++ < 8 ? 0 : 5)) }
  const a = (await (await handle(post(report), e, same)).json()) as { code: string }
  const b = (await (await handle(post(report + 'other'), e, same)).json()) as { code: string }
  assert.notEqual(a.code, b.code)
  assert.equal(e.bucket.objects.size, 2)
})

test('only POST to the report path', async () => {
  const e = env()
  assert.equal((await handle(new Request('https://inbox.example/v1/report'), e, deps)).status, 405)
  assert.equal((await handle(post(report, undefined, '/'), e, deps)).status, 404)
  assert.equal(e.bucket.objects.size, 0)
})

test('content type must be plain text', async () => {
  const e = env()
  for (const type of ['application/json', 'text/html', 'text/plain; charset=latin1', 'multipart/form-data']) {
    assert.equal((await handle(post(report, { 'Content-Type': type }), e, deps)).status, 415, type)
  }
  assert.equal((await handle(post(new TextEncoder().encode(report), {}), e, deps)).status, 415)
  assert.ok(isPlainText('Text/Plain; charset=UTF-8'))
  assert.ok(isPlainText('text/plain'))
})

test('over 2 MB is refused, by its header and by what really arrives', async () => {
  const e = env()
  const big = HEADER + 'x'.repeat(MAX_BYTES)
  const declared = await handle(post(big, { 'Content-Type': 'text/plain', 'Content-Length': String(big.length) }), e, deps)
  assert.equal(declared.status, 413)
  // A body that says nothing about its size (chunked) is counted as it is read.
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      const chunk = new TextEncoder().encode(HEADER + 'y'.repeat(1024 * 1024))
      controller.enqueue(chunk)
      controller.enqueue(chunk)
      controller.enqueue(chunk)
      controller.close()
    },
  })
  const chunked = await handle(
    new Request('https://inbox.example/v1/report', { method: 'POST', body: stream, headers: { 'Content-Type': 'text/plain' }, duplex: 'half' } as RequestInit),
    e,
    deps,
  )
  assert.equal(chunked.status, 413)
  assert.equal(e.bucket.objects.size, 0)
})

test('exactly the cap is accepted', async () => {
  const e = env()
  const exact = HEADER + 'z'.repeat(MAX_BYTES - HEADER.length)
  assert.equal((await handle(post(exact), e, deps)).status, 201)
})

test('empty, not UTF-8 and not ours are refused', async () => {
  const e = env()
  assert.equal((await handle(post(''), e, deps)).status, 400)
  assert.equal((await handle(post(new Uint8Array([0xff, 0xfe, 0x00, 0x80])), e, deps)).status, 400)
  assert.equal((await handle(post('hello there'), e, deps)).status, 400)
  assert.equal(e.bucket.objects.size, 0)
})

test('a limit hit, per address or for everyone, is a 429 and stores nothing', async () => {
  const mine = env({ LIMITER: { limit: async () => ({ success: false }) } })
  const res = await handle(post(report), mine, deps)
  assert.equal(res.status, 429)
  assert.equal(res.headers.get('Retry-After'), '60')
  const all = env({ GLOBAL: { limit: async () => ({ success: false }) } })
  assert.equal((await handle(post(report), all, deps)).status, 429)
  assert.equal(mine.bucket.objects.size + all.bucket.objects.size, 0)
})

test('an address its own limiter refused does not spend the shared budget', async () => {
  let spent = 0
  const e = env({
    LIMITER: { limit: async () => ({ success: false }) },
    GLOBAL: { limit: async () => (spent++, { success: true }) },
  })
  for (let i = 0; i < 5; i++) assert.equal((await handle(post(report), e, deps)).status, 429)
  assert.equal(spent, 0)
})

test('the limiter gets a hash, never the address', async () => {
  const keys: string[] = []
  const e = env({ LIMITER: { limit: async ({ key }) => (keys.push(key), { success: true }) } })
  await handle(post(report, { 'Content-Type': 'text/plain', 'CF-Connecting-IP': '203.0.113.9' }), e, deps)
  assert.equal(keys.length, 1)
  assert.match(keys[0], /^[0-9a-f]{24}$/)
  assert.ok(!keys[0].includes('203'))
})

test('the stored report and the answer carry no address', async () => {
  const e = env()
  const res = await handle(post(report, { 'Content-Type': 'text/plain', 'CF-Connecting-IP': '203.0.113.9', 'User-Agent': 'TMPlayer/1.30' }), e, deps)
  const text = JSON.stringify(await res.json()) + [...e.bucket.objects.values()].map((o) => new TextDecoder().decode(o.bytes)).join()
  assert.ok(!text.includes('203.0.113.9'))
  assert.ok(!text.includes('TMPlayer/1.30'))
})

test('storage that fails is a 503, not a crash', async () => {
  const e = env()
  e.bucket.put = async () => {
    throw new Error('R2 is down')
  }
  assert.equal((await handle(post(report), e, deps)).status, 503)
})

test('the sweep removes reports older than 30 days and keeps the rest, across pages', async () => {
  const bucket = new FakeBucket()
  const now = Date.UTC(2026, 11, 15)
  for (let i = 0; i < 2500; i++) {
    const age = i % 2 === 0 ? 31 : 29
    bucket.objects.set(`${PREFIX}TMP-${i}.txt`, { bytes: new Uint8Array(1), uploaded: new Date(now - age * 86_400_000) })
  }
  bucket.objects.set('other/keep.txt', { bytes: new Uint8Array(1), uploaded: new Date(0) })
  const removed = await sweep(bucket, now)
  assert.equal(removed, 1250)
  assert.equal(bucket.objects.size, 1250 + 1)
  assert.ok(bucket.objects.has('other/keep.txt'))
})

// ---- title reports (N1) ----------------------------------------------------------------------

const pair = (name: string, title: string, extra: Record<string, unknown> = {}) =>
  JSON.stringify({ v: 1, name, title, hash: 'ignored', ...extra })
const postTitle = (body: BodyInit | null, headers: Record<string, string> = { 'Content-Type': 'text/plain; charset=utf-8' }) =>
  post(body, headers, TITLES_PATH)
const stored = (e: { bucket: FakeBucket }, hash: string) =>
  new TextDecoder().decode(e.bucket.objects.get(`${TITLE_PREFIX}${hash}.txt`)!.bytes)
const everything = (e: { bucket: FakeBucket }) => [...e.bucket.objects.values()].map((o) => new TextDecoder().decode(o.bytes)).join()

test('a title report is stored once under its hash, as a line the ingest script reads', async () => {
  const e = env()
  const res = await handle(postTitle(pair('Night.Train.2024.mkv', 'Night Train')), e, deps)
  assert.equal(res.status, 201)
  const body = (await res.json()) as { code: string; duplicate: boolean; keepDays: number }
  assert.match(body.code, NAME_CODE)
  assert.equal(body.duplicate, false)
  assert.equal(body.keepDays, KEEP_DAYS)
  assert.equal(stored(e, '3742ef92863ebf19'), 'Night.Train.2024.mkv\tNight Train\n')
  assert.equal(body.code, 'NAME-2PGE')
})

test('the same pair again, in another case or spacing, is held already and stored once', async () => {
  const e = env()
  await handle(postTitle(pair('Night.Train.2024.mkv', 'Night Train')), e, deps)
  const again = await handle(postTitle(pair(' NIGHT.TRAIN.2024.MKV ', 'night   train')), e, deps)
  assert.equal(again.status, 200)
  assert.equal(((await again.json()) as { duplicate: boolean }).duplicate, true)
  assert.equal(e.bucket.objects.size, 1)
  // The first copy stays as it was.
  assert.equal(stored(e, '3742ef92863ebf19'), 'Night.Train.2024.mkv\tNight Train\n')
})

test('the hash and the code agree with the app (TitleReport.hash)', () => {
  assert.equal(hashOf('Night.Train.2024.mkv', 'Night Train'), '3742ef92863ebf19')
  assert.equal(hashOf('Ñandú Ünï 夜行列車 😀.mkv', 'Night Train'), '99d103d629d04c7e')
  assert.equal(hashOf(' NIGHT.TRAIN.2024.MKV ', 'night   train'), '3742ef92863ebf19')
  assert.notEqual(hashOf('ab', 'c'), hashOf('a', 'bc'))
  assert.match(codeOf('0000000000000000'), NAME_CODE)
  assert.match(codeOf('ffffffffffffffff'), NAME_CODE)
})

test('a phone number, an address and a username are taken out again here', async () => {
  const e = env()
  const res = await handle(
    postTitle(pair('Night Train +91 98765 43210 @someone mail me a@b.example t.me/joinchat/xyz.mkv', 'Night Train @CC_Night')),
    e,
    deps,
  )
  assert.equal(res.status, 201)
  const text = everything(e)
  assert.ok(!text.includes('98765'))
  assert.ok(!text.includes('someone'))
  assert.ok(!text.includes('a@b'))
  assert.ok(!text.includes('t.me'))
  assert.ok(text.includes('@user'))
  assert.equal(scrub(scrub('x +91 98765 43210 @abc')), scrub('x +91 98765 43210 @abc'))
})

test('a year, a resolution and an air date are not phone numbers', () => {
  assert.equal(scrub('Night.Train.2024.1080p.mkv'), 'Night.Train.2024.1080p.mkv')
  assert.equal(scrub('News 2024-10-14 1080p'), 'News 2024-10-14 1080p')
})

test('the chat name is stored only when the sender included it, on a comment line', async () => {
  const e = env()
  await handle(postTitle(pair('A.mkv', 'A title', { chat: 'My Videos' })), e, deps)
  assert.equal(everything(e), 'A.mkv\tA title\n# chat: My Videos\n')
  const without = env()
  await handle(postTitle(pair('A.mkv', 'A title')), without, deps)
  assert.ok(!everything(without).includes('chat'))
})

test('a title report over 2 KB is refused, by its header and by what arrives', async () => {
  const e = env()
  const big = pair('A.mkv', 'x'.repeat(MAX_TITLE_BYTES))
  const declared = await handle(postTitle(big, { 'Content-Type': 'text/plain', 'Content-Length': String(big.length) }), e, deps)
  assert.equal(declared.status, 413)
  assert.equal(((await declared.json()) as { maxBytes: number }).maxBytes, MAX_TITLE_BYTES)
  const chunked = await handle(
    new Request(`https://inbox.example${TITLES_PATH}`, {
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

test('what is not a pair is refused: not JSON, wrong types, a blank name, a list, a log report', async () => {
  const e = env()
  for (const body of [
    'hello',
    '[]',
    '"text"',
    JSON.stringify({ name: 1, title: 'x' }),
    JSON.stringify({ name: 'a.mkv' }),
    JSON.stringify({ name: '   ', title: 'x' }),
    JSON.stringify({ name: '+91 98765 43210', title: 'x' }),
    JSON.stringify({ name: 'a.mkv', title: 'a@b.example' }),
    report,
  ]) {
    assert.equal((await handle(postTitle(body), e, deps)).status, 400, body)
  }
  assert.equal((await handle(postTitle(''), e, deps)).status, 400)
  assert.equal((await handle(postTitle(new Uint8Array([0xff, 0xfe, 0x00, 0x80])), e, deps)).status, 400)
  assert.equal(e.bucket.objects.size, 0)
})

test('a title report needs plain text, POST and the same limits as a log report', async () => {
  const e = env()
  assert.equal((await handle(postTitle(pair('A.mkv', 'A'), { 'Content-Type': 'application/json' }), e, deps)).status, 415)
  assert.equal((await handle(new Request(`https://inbox.example${TITLES_PATH}`), e, deps)).status, 405)
  const limited = env({ LIMITER: { limit: async () => ({ success: false }) } })
  const res = await handle(postTitle(pair('A.mkv', 'A')), limited, deps)
  assert.equal(res.status, 429)
  assert.equal(res.headers.get('Retry-After'), '60')
  const all = env({ GLOBAL: { limit: async () => ({ success: false }) } })
  assert.equal((await handle(postTitle(pair('A.mkv', 'A')), all, deps)).status, 429)
  assert.equal(e.bucket.objects.size + limited.bucket.objects.size + all.bucket.objects.size, 0)
})

test('a name and a title are capped, never through a surrogate pair', () => {
  const long = parsePair(pair('😀'.repeat(300), 'T'.repeat(500)))!
  assert.ok(long.name.length <= MAX_NAME)
  assert.ok(long.title.length <= MAX_TITLE)
  assert.ok(!/[\ud800-\udbff]$/u.test(long.name))
})

test('a title report keeps no address, no user agent and no header', async () => {
  const e = env()
  const res = await handle(
    postTitle(pair('A.mkv', 'A'), { 'Content-Type': 'text/plain', 'CF-Connecting-IP': '203.0.113.9', 'User-Agent': 'TMPlayer/1.30' }),
    e,
    deps,
  )
  const text = JSON.stringify(await res.json()) + everything(e)
  assert.ok(!text.includes('203.0.113.9'))
  assert.ok(!text.includes('TMPlayer/1.30'))
})

test('storage that fails is a 503 for a title report too', async () => {
  const e = env()
  e.bucket.put = async () => {
    throw new Error('R2 is down')
  }
  assert.equal((await handle(postTitle(pair('A.mkv', 'A')), e, deps)).status, 503)
})

test('the sweep clears old title reports with the old logs', async () => {
  const bucket = new FakeBucket()
  const now = Date.UTC(2026, 11, 15)
  for (let i = 0; i < 10; i++) {
    bucket.objects.set(`${TITLE_PREFIX}${i}.txt`, { bytes: new Uint8Array(1), uploaded: new Date(now - (i % 2 === 0 ? 31 : 3) * 86_400_000) })
  }
  bucket.objects.set(`${PREFIX}TMP-2222.txt`, { bytes: new Uint8Array(1), uploaded: new Date(now - 40 * 86_400_000) })
  assert.equal(await sweep(bucket, now), 6)
  assert.equal(bucket.objects.size, 5)
})
