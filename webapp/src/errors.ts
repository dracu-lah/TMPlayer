// Errors in the app's words (core's `errors.*`): Telegram's own error names and a lost connection,
// for sign in and every list alike.
import { t, waitPhrase } from './i18n'

const KNOWN: [RegExp, Parameters<typeof t>[0]][] = [
    [/PHONE_NUMBER_INVALID|PHONE_NUMBER_FLOOD/, 'errors.phone_check'],
    [/PHONE_NUMBER_BANNED/, 'errors.phone_banned'],
    [/PHONE_NUMBER_UNOCCUPIED/, 'errors.no_account'],
    [/PHONE_CODE_INVALID|PHONE_CODE_EMPTY|EMAIL_CODE_INVALID|CODE_INVALID/, 'errors.code_check'],
    [/PHONE_CODE_EXPIRED|EMAIL_VERIFY_EXPIRED|AUTH_RESTART/, 'errors.code_expired'],
    [/PASSWORD_HASH_INVALID/, 'errors.wrong_password'],
    [/EMAIL_INVALID|EMAIL_NOT_ALLOWED/, 'errors.email_invalid'],
    [/SEND_CODE_UNAVAILABLE/, 'errors.code_unavailable'],
    [/PAYMENT_REQUIRED|Payment is required/i, 'errors.premium_qr'],
    [/CHANNEL_PRIVATE|CHAT_FORBIDDEN|USER_BANNED_IN_CHANNEL/, 'errors.channel_private'],
    [/PEER_ID_INVALID|CHANNEL_INVALID|CHAT_ID_INVALID|USERNAME_NOT_OCCUPIED/, 'errors.chat_gone'],
    [/AUTH_KEY_UNREGISTERED|SESSION_REVOKED|USER_DEACTIVATED/, 'errors.signed_out'],
    [/timeout|timed out|connection|network|websocket|fetch|offline/i, 'errors.offline'],
]

/** What went wrong in the app's words (Failures.kt), and for a flood wait how many seconds to hold back; null for an abort, which says nothing. */
export function failure(e: unknown): { text: string; wait?: number } | null {
    const err = e as { name?: string; text?: string; message?: string; seconds?: number }
    if (err?.name === 'AbortError') return null
    const raw = err?.text ?? err?.message ?? String(e)
    const seconds = err?.seconds ?? Number(/FLOOD_WAIT_(\d+)/.exec(raw)?.[1] ?? NaN)
    if (raw.includes('FLOOD') && seconds > 0) return { text: t('errors.flood_wait', { wait: waitPhrase(seconds) }), wait: seconds }
    // Offline, the browser knows before any call says so.
    if (!navigator.onLine) return { text: t('errors.offline') }
    const hit = KNOWN.find(([pattern]) => pattern.test(raw))
    if (!hit) console.warn('[error] unmapped', raw)
    return { text: t(hit ? hit[1] : 'errors.no_answer') }
}
