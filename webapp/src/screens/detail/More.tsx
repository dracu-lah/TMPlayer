// Other videos of the chat, as cards.
import { For } from 'solid-js'
import { t } from '../../i18n'
import '../series.css'
import { Card, tileCard, useProgress } from '../VideoList'
import '../versions.css'
import type { WatchInfo } from '../../tg/types'


/** Other videos of the chat, newest first, as cards. */
export function More(props: { info: WatchInfo }) {
    const progress = useProgress()
    return (
        <section class="more-from">
            <h2 class="detail-section">{t('web.more_from_chat', { chat: props.info.chatTitle })}</h2>
            <div class="cards more-cards">
                <For each={props.info.more}>{(tile) => <Card card={tileCard(tile)} progress={progress(tile.item)} replace />}</For>
            </div>
        </section>
    )
}
