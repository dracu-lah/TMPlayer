// Downloads on the web (R2): a place on the bar, as it is in the apps, whose page is one card. A
// browser cannot keep a video on the device for later (no folder, no background transfer), so the
// honest page says so and points at the app, which can. Nothing here asks Telegram anything.
import { Download04Icon } from '@hugeicons/core-free-icons'
import { t } from '../i18n'
import { Icon } from '../ui/Icon'

/** Where the apps are, as the player's "needs the app" card links them. */
const APP_URL = 'https://tmplayer.org/#download'

export function Downloads() {
    return (
        <>
            <header class="library-head">
                <h1 class="page-title">{t('nav.downloads')}</h1>
            </header>
            <div class="empty-state" data-downloads-card>
                <span class="empty-disc">
                    <Icon icon={Download04Icon} size={28} />
                </span>
                <h2 class="downloads-card-title">{t('web.downloads_title')}</h2>
                <p>{t('web.downloads_body')}</p>
                <a class="button primary" href={APP_URL} target="_blank" rel="noopener">
                    {t('web.app_get')}
                </a>
            </div>
        </>
    )
}
