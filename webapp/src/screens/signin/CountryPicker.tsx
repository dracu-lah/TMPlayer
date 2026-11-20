import { createMemo, createSignal, For } from 'solid-js'
import { Cancel01Icon } from '@hugeicons/core-free-icons'
import { t } from '../../i18n'
import { Icon } from '../../ui/Icon'
import * as phoneRules from '../phone'
import type { Country } from '../../tg/types'

/** Telegram's countries, searched by name or code, as the app's CountryPicker. */
export function CountryPicker(props: { ref: (d: HTMLDialogElement) => void; countries: Country[]; onPick: (c: Country) => void }) {
    const [query, setQuery] = createSignal('')
    const shown = createMemo(() => props.countries.filter((c) => phoneRules.matches(c, query())))
    let dialog!: HTMLDialogElement
    return (
        <dialog
            class="countries"
            ref={(d) => {
                dialog = d
                props.ref(d)
            }}
            onClose={() => setQuery('')}
            aria-label={t('login.choose_country')}
        >
            <div class="countries-head">
                <input type="search" aria-label={t('login.country_search')} placeholder={t('login.country_search')} value={query()} onInput={(e) => setQuery(e.currentTarget.value)} autofocus />
                <button type="button" class="icon-button" aria-label={t('login.close_country_list')} onClick={() => dialog.close()}>
                    <Icon icon={Cancel01Icon} size={20} />
                </button>
            </div>
            <ul>
                <For each={shown()} fallback={<li class="muted countries-none">{t('login.country_no_match')}</li>}>
                    {(c) => (
                        <li>
                            <button type="button" onClick={() => props.onPick(c)}>
                                <span class="flag" aria-hidden="true">{phoneRules.flag(c.iso)}</span>
                                <span class="name">{c.name}</span>
                                <span class="muted">+{c.codes[0].code}</span>
                            </button>
                        </li>
                    )}
                </For>
            </ul>
        </dialog>
    )
}
