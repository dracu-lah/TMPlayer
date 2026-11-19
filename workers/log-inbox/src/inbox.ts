/* The rules of the log inbox, apart from Cloudflare's runtime so `node --test` can run them.

   The app's "Send to developer" button (reliability plan L5) POSTs one text report here, after the
   viewer has seen what it holds and said yes. The report is stored privately for 30 days under a
   short code (TMP-7K3Q) that the viewer says in the group or an issue.

   What this never does: log or store an address, a user agent or any header; accept more than
   2 MB (2 KB for a title report, see titles.ts, 16 KB for feedback, see feedback.ts); accept anything but plain text that starts with the app's own header; keep a report past
   30 days; list or serve a report to anyone (there is no read route; the developer reads them in
   the Cloudflare dashboard or with wrangler). */

import { FEEDBACK_PATH, FEEDBACK_PREFIX, MAX_FEEDBACK_BYTES, storeFeedback } from './feedback.ts'
import { MAX_TITLE_BYTES, TITLES_PATH, TITLE_PREFIX, storePair } from './titles.ts'

export interface StoredObject {
  key: string
  uploaded: Date
}

/** The part of an R2 bucket this worker uses. */
export interface Bucket {
  put(
    key: string,
    value: Uint8Array,
    options?: { onlyIf?: { etagDoesNotMatch?: string }; httpMetadata?: { contentType?: string } },
  ): Promise<unknown | null>
  list(options?: {
    prefix?: string
    cursor?: string
    limit?: number
  }): Promise<{ objects: StoredObject[]; truncated: boolean; cursor?: string }>
  delete(keys: string[]): Promise<void>
}

/** Cloudflare's rate limit binding. */
export interface Limiter {
  limit(options: { key: string }): Promise<{ success: boolean }>
}

export interface Env {
  REPORTS: Bucket
  /** Per address, hashed, a handful a minute. */
  LIMITER: Limiter
  /** Everyone together, so a crowd of addresses cannot fill the bucket. */
  GLOBAL: Limiter
}

export const MAX_BYTES = 2 * 1024 * 1024
export const KEEP_DAYS = 30
export const PREFIX = 'reports/'

/** No 0, 1, I, L or O: a code read aloud or typed from a TV screen is not mistaken. */
export const ALPHABET = '23456789ABCDEFGHJKMNPQRSTUVWXYZ'
export const CODE = /^TMP-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}$/

/** What the app writes first in every report; anything else is not ours. */
export const HEADER = 'TMPlayer logs'

const DAY_MS = 86_400_000

export interface Deps {
  /** Random bytes; replaced in tests. */
  random: (n: number) => Uint8Array
  now: () => number
}

export const realDeps: Deps = {
  random: (n) => crypto.getRandomValues(new Uint8Array(n)),
  now: () => Date.now(),
}

/** A code such as TMP-7K3Q, drawn without modulo bias. */
export function newCode(random: (n: number) => Uint8Array): string {
  let out = ''
  const limit = 256 - (256 % ALPHABET.length)
  while (out.length < 4) {
    for (const b of random(8)) {
      if (b < limit && out.length < 4) out += ALPHABET[b % ALPHABET.length]
    }
  }
  return `TMP-${out}`
}

function json(status: number, body: Record<string, unknown>, extra: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store', ...extra },
  })
}

/** `text/plain`, with or without a UTF-8 charset; nothing else. */
export function isPlainText(contentType: string | null): boolean {
  if (!contentType) return false
  const [type, ...params] = contentType.split(';').map((p) => p.trim().toLowerCase())
  if (type !== 'text/plain') return false
  return params.every((p) => p === '' || p === 'charset=utf-8' || p === 'charset="utf-8"')
}

/** The body, or null when it is over [max] bytes. Counts what really arrives, not what the header claims. */
export async function readLimited(body: ReadableStream<Uint8Array> | null, max: number): Promise<Uint8Array | null> {
  if (!body) return new Uint8Array(0)
  const reader = body.getReader()
  const chunks: Uint8Array[] = []
  let total = 0
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    total += value.byteLength
    if (total > max) {
      await reader.cancel().catch(() => {})
      return null
    }
    chunks.push(value)
  }
  const all = new Uint8Array(total)
  let at = 0
  for (const c of chunks) {
    all.set(c, at)
    at += c.byteLength
  }
  return all
}

async function addressKey(request: Request): Promise<string> {
  // Hashed before it reaches the limiter, which never needs the address itself. Not logged, not stored.
  const raw = request.headers.get('CF-Connecting-IP') ?? 'none'
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(raw))
  return [...new Uint8Array(digest)].slice(0, 12).map((b) => b.toString(16).padStart(2, '0')).join('')
}

export async function handle(request: Request, env: Env, deps: Deps = realDeps): Promise<Response> {
  const url = new URL(request.url)
  const titles = url.pathname === TITLES_PATH
  const feedback = url.pathname === FEEDBACK_PATH
  if (url.pathname !== '/v1/report' && !titles && !feedback) return json(404, { error: 'not_found' })
  if (request.method !== 'POST') return json(405, { error: 'method_not_allowed' }, { Allow: 'POST' })
  if (!isPlainText(request.headers.get('Content-Type'))) return json(415, { error: 'plain_text_only' })

  // A title report is a pair of short lines and feedback a typed message, so their caps are small; a log report may be big.
  const max = titles ? MAX_TITLE_BYTES : feedback ? MAX_FEEDBACK_BYTES : MAX_BYTES
  const declared = Number(request.headers.get('Content-Length'))
  if (Number.isFinite(declared) && declared > max) return json(413, { error: 'too_large', maxBytes: max })

  const mine = await env.LIMITER.limit({ key: await addressKey(request) })
  // The shared budget is spent only by a caller its own address let through, so one noisy address
  // cannot use up the minute for everyone else.
  if (!mine.success) return json(429, { error: 'slow_down' }, { 'Retry-After': '60' })
  const all = await env.GLOBAL.limit({ key: 'all' })
  if (!all.success) return json(429, { error: 'slow_down' }, { 'Retry-After': '60' })

  const bytes = await readLimited(request.body, max)
  if (bytes === null) return json(413, { error: 'too_large', maxBytes: max })
  if (bytes.byteLength === 0) return json(400, { error: 'empty' })

  let text: string
  try {
    text = new TextDecoder('utf-8', { fatal: true }).decode(bytes)
  } catch {
    return json(400, { error: 'not_utf8' })
  }

  if (titles) {
    const stored = await storePair(env.REPORTS, text)
    if (stored.status === 400 || stored.status === 503) return json(stored.status, { error: stored.error })
    return json(stored.status, { code: stored.code, duplicate: stored.status === 200, keepDays: KEEP_DAYS })
  }
  if (feedback) {
    const stored = await storeFeedback(env.REPORTS, text)
    if (stored.status === 400 || stored.status === 503) return json(stored.status, { error: stored.error })
    return json(stored.status, { code: stored.code, duplicate: stored.status === 200, keepDays: KEEP_DAYS })
  }
  if (!text.startsWith(HEADER)) return json(400, { error: 'not_a_report' })

  // A taken code is a precondition failure, so two reports can never share one even at the same moment.
  for (let attempt = 0; attempt < 8; attempt++) {
    const code = newCode(deps.random)
    try {
      const stored = await env.REPORTS.put(PREFIX + code + '.txt', bytes, {
        onlyIf: { etagDoesNotMatch: '*' },
        httpMetadata: { contentType: 'text/plain; charset=utf-8' },
      })
      if (stored) return json(201, { code, keepDays: KEEP_DAYS })
    } catch {
      return json(503, { error: 'unavailable' })
    }
  }
  return json(503, { error: 'unavailable' })
}

/** Deletes reports, title reports and feedback older than [KEEP_DAYS]; run daily, and the bucket's own lifecycle rules back it up. Returns how many went. */
export async function sweep(bucket: Bucket, nowMs: number, pages = 20): Promise<number> {
  const cutoff = nowMs - KEEP_DAYS * DAY_MS
  let removed = 0
  for (const prefix of [PREFIX, TITLE_PREFIX, FEEDBACK_PREFIX]) {
    let cursor: string | undefined
    for (let i = 0; i < pages; i++) {
      const page = await bucket.list({ prefix, cursor, limit: 1000 })
      const old = page.objects.filter((o) => o.uploaded.getTime() < cutoff).map((o) => o.key)
      if (old.length > 0) {
        await bucket.delete(old)
        removed += old.length
      }
      if (!page.truncated) break
      cursor = page.cursor
    }
  }
  return removed
}
