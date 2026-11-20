import { t } from '../../i18n'
import type { SentCode } from '../../tg/types'

/** Codes typed as a row of digits; a word or a phrase sent by text is typed as it is. */
export const typedAsDigits = (type: string) => type !== 'sms_word' && type !== 'sms_phrase'

/** Where the code went, in words; `number` is the phone number as shown. */
export function codeBlurb(s: SentCode, number: string): string {
    switch (s.type) {
        case 'app': return t('login.delivery_app')
        case 'sms': return t('login.delivery_sms', { number })
        case 'call': return t('login.delivery_call', { number })
        case 'missed_call': return t('login.delivery_missed_call')
        case 'flash_call': return t('login.delivery_flash_call', { number })
        case 'fragment': return t('login.delivery_fragment')
        case 'sms_word': return t('login.delivery_word')
        case 'sms_phrase': return t('login.delivery_phrase')
        case 'firebase': return t('login.delivery_firebase', { number })
        case 'email':
        case 'email_setup': return t('login.email_code_body', { email: s.email ?? '' })
        default: return t('login.delivery_unknown', { number })
    }
}

/** Named after what pressing it will do, which is not always "the same again". */
export function resendLabel(s: SentCode): string {
    if (s.type === 'email' || s.type === 'email_setup') return t('login.email_resend')
    switch (s.next) {
        case 'sms': case 'sms_word': case 'sms_phrase': return t('login.resend_sms')
        case 'call': case 'missed_call': case 'flash_call': return t('login.resend_call')
        case 'fragment': return t('login.resend_fragment')
        default: return t('login.resend_again')
    }
}
