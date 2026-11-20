import { Logout05Icon } from '@hugeicons/core-free-icons'
import { signOut } from '../../session'
import { t } from '../../i18n'
import { askConfirm } from '../../ui/Confirm'
import { Icon } from '../../ui/Icon'
import { rowDomId } from '../SettingsFind'

/** Sign out at the end of the list: a quiet outlined row with the danger colour in its words only, and a question first that names what goes with it. */
export function SignOut() {
    const ask = () =>
        void askConfirm({
            title: t('settings.sign_out_title'),
            message: t('settings.sign_out_message'),
            confirm: t('settings.sign_out_confirm'),
            busy: t('settings.signing_out'),
            run: signOut,
        })
    return (
        <button class="danger-button set-signout" id={rowDomId('sign_out')} type="button" onClick={ask}>
            <Icon icon={Logout05Icon} size={20} />
            {t('settings.sign_out')}
        </button>
    )
}
