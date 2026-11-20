import { Logout05Icon } from '@hugeicons/core-free-icons'
import { me, signOut } from '../../session'
import { many } from '../../accounts'
import { t } from '../../i18n'
import { askConfirm } from '../../ui/Confirm'
import { Icon } from '../../ui/Icon'
import { rowDomId } from '../SettingsFind'

/**
 * Sign out at the end of the list: a quiet outlined row with the danger colour in its words only, and a question first that names what goes with it.
 * With other profiles (HX88) it signs this one out only, and the question says the others stay.
 */
export function SignOut() {
    const ask = () => {
        const name = me()?.name ?? ''
        const one = !many() || !name
        void askConfirm({
            title: one ? t('settings.sign_out_title') : t('accounts.sign_out_title', { name }),
            message: one ? t('settings.sign_out_message') : t('accounts.sign_out_message', { name }),
            confirm: t('settings.sign_out_confirm'),
            busy: t('settings.signing_out'),
            run: signOut,
        })
    }
    return (
        <button class="danger-button set-signout" id={rowDomId('sign_out')} type="button" onClick={ask}>
            <Icon icon={Logout05Icon} size={20} />
            {t('settings.sign_out')}
        </button>
    )
}
