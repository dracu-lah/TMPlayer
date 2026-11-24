import { Show } from 'solid-js'
import { iosVersion } from '../install'
import { newInstallSteps } from './inAppBrowser'
import { t } from '../i18n'

/**
 * How to add TMPlayer to the iPhone's Home Screen, in the words of this iOS (WP-58): iOS 26 moved Share
 * under the More button and asks whether to Open as Web App; older iPhones have the Share button in the
 * bar. One sentence for the new one, the two steps the older ones had.
 */
export function HomeScreenSteps() {
    return (
        <Show
            when={newInstallSteps(iosVersion)}
            fallback={
                <ol class="install-steps">
                    <li>{t('web.install_ios_step_share')}</li>
                    <li>{t('web.install_ios_step_add')}</li>
                </ol>
            }
        >
            <p class="install-steps-new">{t('install.ios26_steps')}</p>
        </Show>
    )
}
