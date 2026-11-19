// "Can't reach Telegram right now" (the apps' connection banner, U2): the browser is online and the
// worker's line to Telegram has stayed down for 20 s (net.ts, reach.ts). Dressed as the update
// notice, so the layout, the TV's size and its reach by the remote (a button at the foot, HX22) are
// the ones that notice already has. Retry opens the connections again and says so; the banner goes
// at once and comes back after another 20 s if the line is still down.
import { retryReach } from '../net'
import { showToast } from './Toast'
import { t } from '../i18n'

export function ReachBanner() {
    return (
        <div class="update-notice reach-banner" role="status" data-tv-reach="bottom">
            <p>
                <strong>{t('connect.banner')}</strong>
                <br />
                {t('connect.hint_not_answering')}
            </p>
            <button
                class="primary"
                type="button"
                onClick={() => {
                    retryReach()
                    showToast({ text: t('connect.retrying') })
                }}
            >
                {t('common.retry')}
            </button>
        </div>
    )
}
