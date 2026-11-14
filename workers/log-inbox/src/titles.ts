/* The title reports ("Wrong title?", reliability plan N1): one file name and the title the viewer
   chose for it, sent only after the viewer saw exactly this pair and ticked the box.

   The rules of core/.../TitleReport.kt, in the same order, so what arrives is clean whatever sent
   it: the text is scrubbed again (phone numbers, e-mail addresses, t.me links, @usernames), capped,
   and the hash and the short code are worked out here, never taken from the sender. A pair the
   inbox already holds is not stored twice. No address, user agent or header is kept. */

import type { Bucket } from './inbox.ts'

export const TITLES_PATH = '/v1/titles'
export const TITLE_PREFIX = 'titles/'

/** A pair is two short lines; anything bigger is not one of ours. Matches TitleReport.MAX_JSON. */
export const MAX_TITLE_BYTES = 2048

export const MAX_NAME = 255
export const MAX_TITLE = 200
export const MAX_CHAT = 100

/** Same alphabet as the log codes: no 0, 1, I, L or O. */
const ALPHABET = '23456789ABCDEFGHJKMNPQRSTUVWXYZ'
export const NAME_CODE = /^NAME-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}$/

const SPACES = /\s+/gu
// A run that starts and ends on a digit, with the separators phone numbers are written with.
const PHONE_CANDIDATE = /(?<![\p{L}\p{N}])\+?\(?\p{Nd}[\p{Nd}\s().\-]{6,}\p{Nd}(?![\p{L}\p{N}])/gu
const DATE = /(?:19|20)\d{2}[ .\-]\d{2}[ .\-]\d{2}|\d{2}[ .\-]\d{2}[ .\-](?:19|20)\d{2}/gu
const EMAIL = /[\p{L}\p{N}._%+\-]+@[\p{L}\p{N}.\-]+\.\p{L}{2,}/gu
const TELEGRAM_LINK = /(?:https?:\/\/)?(?:t\.me|telegram\.me|telegram\.dog)\/\S*/giu
const USERNAME = /@[\p{L}\p{N}_]{2,}/gu

function digitsIn(text: string): number {
  return [...text].filter((c) => /^\p{Nd}$/u.test(c)).length
}

/** [text] without what identifies a person. Twice is the same as once. */
export function scrub(text: string): string {
  if (text === '') return text
  let out = text.replace(EMAIL, ' ').replace(TELEGRAM_LINK, ' ')
  out = out.replace(PHONE_CANDIDATE, (found) => {
    const need = found.trimStart().startsWith('+') ? 8 : 10
    return digitsIn(found.replace(DATE, '')) >= need ? ' ' : found
  })
  out = out.replace(USERNAME, '@user')
  return out.replace(SPACES, ' ').trim()
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

export interface Pair {
  name: string
  title: string
  chat: string | null
}

/** The pair a text holds, scrubbed and capped, or null when it is not one or nothing is left of it. */
export function parsePair(text: string): Pair | null {
  let json: unknown
  try {
    json = JSON.parse(text)
  } catch {
    return null
  }
  if (typeof json !== 'object' || json === null || Array.isArray(json)) return null
  const o = json as Record<string, unknown>
  if (typeof o.name !== 'string' || typeof o.title !== 'string') return null
  const name = capped(scrub(o.name), MAX_NAME)
  const title = capped(scrub(o.title), MAX_TITLE)
  if (name === '' || title === '') return null
  const chat = typeof o.chat === 'string' ? capped(scrub(o.chat), MAX_CHAT) || null : null
  return { name, title, chat }
}

function normalised(text: string): string {
  return text.trim().toLowerCase().replace(SPACES, ' ')
}

/** FNV-1a, 64 bits, over the UTF-8 bytes of the normalised pair: 16 hex digits, as TitleReport.hash. */
export function hashOf(name: string, title: string): string {
  const key = normalised(name) + '\u0000' + normalised(title)
  let hash = 0xcbf29ce484222325n
  for (const byte of new TextEncoder().encode(key)) {
    hash = BigInt.asUintN(64, (hash ^ BigInt(byte)) * 0x100000001b3n)
  }
  return hash.toString(16).padStart(16, '0')
}

/** NAME-7K3Q: the hash read as a number in base 31, last four digits. For the viewer to say, not to find a file. */
export function codeOf(hash: string): string {
  let value = BigInt('0x' + hash)
  let out = ''
  for (let i = 0; i < 4; i++) {
    out = ALPHABET[Number(value % 31n)] + out
    value /= 31n
  }
  return `NAME-${out}`
}

export type Stored =
  | { status: 201 | 200; code: string; hash: string }
  | { status: 400 | 503; error: string }

/**
 * Stores the pair in [text] as `titles/<hash>.txt`, one line `name TAB title` (the format
 * scripts/names/ingest.mjs reads) and, when the viewer ticked it, a `# chat:` line after it.
 * 201 stored, 200 held already, 400 not a pair.
 */
export async function storePair(bucket: Bucket, text: string): Promise<Stored> {
  const pair = parsePair(text)
  if (pair === null) return { status: 400, error: 'not_a_title_report' }
  const hash = hashOf(pair.name, pair.title)
  const body = `${pair.name}\t${pair.title}\n` + (pair.chat === null ? '' : `# chat: ${pair.chat}\n`)
  try {
    const stored = await bucket.put(`${TITLE_PREFIX}${hash}.txt`, new TextEncoder().encode(body), {
      onlyIf: { etagDoesNotMatch: '*' },
      httpMetadata: { contentType: 'text/plain; charset=utf-8' },
    })
    return { status: stored ? 201 : 200, code: codeOf(hash), hash }
  } catch {
    return { status: 503, error: 'unavailable' }
  }
}
