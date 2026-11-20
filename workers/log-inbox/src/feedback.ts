/* "Report a problem" and "Request a feature" (plan HX64): what the viewer typed, sent from the app
   after they saw exactly what goes, filed privately here for the developer to read.

   One JSON object, the app's FeedbackReport:
     {"v":1,"id":"<32 hex>","kind":"problem"|"feature","message":"...","contact":"...",
      "logs":"TMP-7K3Q","app":{"version":"...","platform":"...","device":"...","language":"..."}}
   Only id, kind and message are needed. "contact" is there only when the viewer typed one, "logs"
   only when they ticked "Add my logs" (the code of a report they sent to /v1/report first).

   Nothing here trusts the text: every field has its type and its cap checked, control characters
   go, one line fields are one line, and an unknown field is dropped. The message is kept as the
   viewer wrote it (it is theirs, and they saw it); a message over the cap is refused, never cut,
   so nothing they wrote is silently lost. No address, user agent or header is kept.

   The app draws the id once per draft and sends the same one on every try (a double press, a
   retry after a lost connection, the app killed part way), so one draft is filed once: the key
   holds the code made from the id, and a second put of the same key is a duplicate. */

import type { Bucket } from './inbox.ts'

export const FEEDBACK_PATH = '/v1/feedback'
export const FEEDBACK_PREFIX = 'feedback/'

/** The whole body: a message at its cap in any script, and the rest, fits well under this. */
export const MAX_FEEDBACK_BYTES = 16 * 1024

/** Matches FeedbackReport.MAX_MESSAGE in core. */
export const MAX_MESSAGE = 4000
export const MAX_CONTACT = 120
export const MAX_FACT = 160

export const KINDS = ['problem', 'feature'] as const
export type Kind = (typeof KINDS)[number]

const ALPHABET = '23456789ABCDEFGHJKMNPQRSTUVWXYZ'
export const FEEDBACK_CODE = /^FB-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}$/
const ID = /^[0-9a-f]{32}$/
const LOG_CODE = /^TMP-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}$/

// Control characters, but not the line break and the tab a message may hold.
const CONTROL = /[\u0000-\u0008\u000b-\u001f\u007f-\u009f\u200b-\u200f\u2028-\u202e\u2066-\u2069\ufeff]/gu
const SPACES = /\s+/gu

export interface Facts {
  version: string
  platform: string
  device: string
  language: string
}

export interface Feedback {
  id: string
  kind: Kind
  message: string
  contact: string | null
  logs: string | null
  facts: Facts
}

/** Cut to [max] UTF-16 units, not through a surrogate pair, and trimmed. */
function capped(text: string, max: number): string {
  const trimmed = text.trim()
  if (trimmed.length <= max) return trimmed
  let end = max
  const last = trimmed.charCodeAt(end - 1)
  if (last >= 0xd800 && last <= 0xdbff) end--
  return trimmed.slice(0, end).trim()
}

/** One line, no control characters, at most [max]; anything but a string is empty. */
export function oneLine(value: unknown, max: number): string {
  if (typeof value !== 'string') return ''
  return capped(value.slice(0, max * 4).replace(CONTROL, '').replace(SPACES, ' '), max)
}

/** The message as written, line breaks kept (at most two blank lines in a row), without control characters. */
export function cleanMessage(text: string): string {
  return text
    .replace(/\r\n?/gu, '\n')
    .replace(CONTROL, '')
    .replace(/[ \t]+$/gmu, '')
    .replace(/\n{4,}/gu, '\n\n\n')
    .trim()
}

export type Parsed = { ok: true; feedback: Feedback } | { ok: false; error: string }

/** The feedback [text] holds, cleaned, or why it is not one. */
export function parseFeedback(text: string): Parsed {
  let json: unknown
  try {
    json = JSON.parse(text)
  } catch {
    return { ok: false, error: 'not_feedback' }
  }
  if (typeof json !== 'object' || json === null || Array.isArray(json)) return { ok: false, error: 'not_feedback' }
  const o = json as Record<string, unknown>
  if (o.v !== 1) return { ok: false, error: 'not_feedback' }
  if (typeof o.id !== 'string' || !ID.test(o.id)) return { ok: false, error: 'not_feedback' }
  if (typeof o.kind !== 'string' || !(KINDS as readonly string[]).includes(o.kind)) return { ok: false, error: 'not_feedback' }
  if (typeof o.message !== 'string') return { ok: false, error: 'not_feedback' }
  const message = cleanMessage(o.message)
  if (message === '') return { ok: false, error: 'empty_message' }
  if (message.length > MAX_MESSAGE) return { ok: false, error: 'message_too_long' }
  const contact = oneLine(o.contact, MAX_CONTACT) || null
  const logs = typeof o.logs === 'string' && LOG_CODE.test(o.logs) ? o.logs : null
  const app = typeof o.app === 'object' && o.app !== null && !Array.isArray(o.app) ? (o.app as Record<string, unknown>) : {}
  const facts: Facts = {
    version: oneLine(app.version, 40),
    platform: oneLine(app.platform, 60),
    device: oneLine(app.device, MAX_FACT),
    language: oneLine(app.language, 20),
  }
  return { ok: true, feedback: { id: o.id, kind: o.kind as Kind, message, contact, logs, facts } }
}

/**
 * FB-7K3Q-M4XP from the draft's id: worked out here, from a SHA-256 of the whole id, so the sender
 * cannot pick it, and eight letters of 31 (about 850 billion), so two drafts never share one in
 * practice. The same draft has the same code on every try.
 */
export async function feedbackCode(id: string): Promise<string> {
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(id)))
  let value = 0n
  for (const byte of digest.slice(0, 8)) value = (value << 8n) | BigInt(byte)
  let out = ''
  for (let i = 0; i < 8; i++) {
    out = ALPHABET[Number(value % 31n)] + out
    value /= 31n
  }
  return `FB-${out.slice(0, 4)}-${out.slice(4)}`
}

/** The file the developer reads: the facts first, then the message as the viewer wrote it. */
export function render(f: Feedback, code: string): string {
  const lines = [
    `TMPlayer feedback ${code}`,
    `Kind: ${f.kind === 'problem' ? 'problem' : 'feature request'}`,
    `App version: ${f.facts.version || 'unknown'}`,
    `Platform: ${f.facts.platform || 'unknown'}`,
    `Device: ${f.facts.device || 'unknown'}`,
    `App language: ${f.facts.language || 'unknown'}`,
    `Logs: ${f.logs ?? 'none'}`,
    `Reply to: ${f.contact ?? 'none given'}`,
    '',
    f.message,
    '',
  ]
  return lines.join('\n')
}

export type FeedbackStored =
  | { status: 201 | 200; code: string }
  | { status: 400 | 503; error: string }

/** Files [text] as `feedback/FB-XXXX-<id>.txt`. 201 filed, 200 the same draft was filed already, 400 not one. */
export async function storeFeedback(bucket: Bucket, text: string): Promise<FeedbackStored> {
  const parsed = parseFeedback(text)
  if (!parsed.ok) return { status: 400, error: parsed.error }
  const f = parsed.feedback
  const code = await feedbackCode(f.id)
  try {
    const stored = await bucket.put(`${FEEDBACK_PREFIX}${code}-${f.id}.txt`, new TextEncoder().encode(render(f, code)), {
      onlyIf: { etagDoesNotMatch: '*' },
      httpMetadata: { contentType: 'text/plain; charset=utf-8' },
    })
    return { status: stored ? 201 : 200, code }
  } catch {
    return { status: 503, error: 'unavailable' }
  }
}

/** The web app's origin: the one page besides the apps that writes to /v1/feedback (WB11). */
export const WEB_ORIGINS: readonly string[] = ['https://web.tmplayer.org']

/**
 * What a browser needs to read the answer of a feedback post from the web app: the origin's own
 * address echoed back (never `*`), and the one header the app reads. A request from any other
 * origin gets no allow header, so a page elsewhere cannot read the answer. `Vary: Origin` is on every
 * answer, so a cache never hands one origin's answer to another. The apps send no Origin.
 */
export function corsHeaders(origin: string | null): Record<string, string> {
  if (origin === null || !WEB_ORIGINS.includes(origin)) return { Vary: 'Origin' }
  return {
    'Access-Control-Allow-Origin': origin,
    'Access-Control-Expose-Headers': 'Retry-After',
    Vary: 'Origin',
  }
}

/** The answer to a browser's preflight for /v1/feedback (the post is plain text, so a browser rarely asks). */
export function preflight(origin: string | null): Response {
  const headers = corsHeaders(origin)
  if (!('Access-Control-Allow-Origin' in headers)) return new Response(null, { status: 403, headers })
  return new Response(null, {
    status: 204,
    headers: { ...headers, 'Access-Control-Allow-Methods': 'POST', 'Access-Control-Allow-Headers': 'Content-Type', 'Access-Control-Max-Age': '600' },
  })
}

/** [response] with the CORS headers of [request]'s origin added (Vary on every one). */
export function withCors(request: Request, response: Response): Response {
  const headers = new Headers(response.headers)
  for (const [k, v] of Object.entries(corsHeaders(request.headers.get('Origin')))) {
    if (k === 'Vary') headers.append(k, v)
    else headers.set(k, v)
  }
  return new Response(response.body, { status: response.status, headers })
}
