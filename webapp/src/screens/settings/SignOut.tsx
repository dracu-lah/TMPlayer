import { Logout05Icon } from '@hugeicons/core-free-icons'
import { me, signOut } from '../../session'
import { many } from '../../accounts'
import { t } from '../../i18n'
import { askConfirm } from '../../ui/Confirm'
import { Icon } from '../../ui/Icon'
import { rowDomId } from '../SettingsFind'

/**
 * The question before a sign out, the one Settings and the TV's account menu (WP-02) both ask: it names
 * the account it signs out of (also with one profile), says what goes and what stays, and has Cancel
 * focused. With other profiles (HX88) it signs this one out only, and the question says the others stay.
 */
export function askSignOut() {
    const name = me()?.name?.trim() ?? ''
    return askConfirm({
        title: many() && name ? t('accounts.sign_out_title', { name }) : name ? t('settings.sign_out_title_named', { name }) : t('settings.sign_out_title'),
        message: many() && name ? t('accounts.sign_out_message', { name }) : t('settings.sign_out_message'),
        confirm: t('settings.sign_out_confirm'),
        busy: t('settings.signing_out'),
        run: signOut,
    })
}

/**
 * Sign out at the end of the list: a quiet outlined row with the danger colour in its words only, and a question first that names what goes with it.
 * With other profiles (HX88) it signs this one out only, and the question says the others stay.
 */
export function SignOut() {
    const ask = () => void askSignOut()
    return (
        <button class="danger-button set-signout" id={rowDomId('sign_out')} type="button" onClick={ask}>
            <Icon icon={Logout05Icon} size={20} />
            {t('settings.sign_out')}
        </button>
    )
}
