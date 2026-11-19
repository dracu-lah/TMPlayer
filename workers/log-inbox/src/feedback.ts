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
export const FEEDBACK_CODE = /^FB-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}$/
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

/** FB-7K3Q from the draft's id: the same draft has the same code on every try. */
export function feedbackCode(id: string): string {
  let value = BigInt('0x' + id.slice(0, 16))
  let out = ''
  for (let i = 0; i < 4; i++) {
    out = ALPHABET[Number(value % 31n)] + out
    value /= 31n
  }
  return `FB-${out}`
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
  const code = feedbackCode(f.id)
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
