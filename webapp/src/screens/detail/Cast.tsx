// The cast row.
import { createSignal, For, onMount, Show } from 'solid-js'
import { t } from '../../i18n'
import '../series.css'
import { initialOf } from '../../ui/initial'
import { loadFile } from '../posters'
import '../versions.css'


/** The cast: a face (or its first letter), the name and who they play, in a row that scrolls sideways. */
export function Cast(props: { people: { name: string; role: string; photo: string | null }[] }) {
    return (
        <section class="detail-cast">
            <h2 class="detail-section">{t('metadata.cast')}</h2>
            <ul class="cast-row">
                <For each={props.people}>{(p) => <Person person={p} />}</For>
            </ul>
        </section>
    )
}

function Person(props: { person: { name: string; role: string; photo: string | null } }) {
    const [src, setSrc] = createSignal<string | null>(null)
    onMount(() => {
        if (props.person.photo) loadFile(props.person.photo, 'w185').then(setSrc, () => {})
    })
    return (
        <li class="cast-person">
            <span class="cast-face" aria-hidden="true">
                <Show when={src()} fallback={<span class="initial">{initialOf(props.person.name)}</span>}>
                    <img src={src()!} alt="" decoding="async" />
                </Show>
            </span>
            <span class="cast-name">{props.person.name}</span>
            <Show when={props.person.role}>
                <span class="cast-role">{props.person.role}</span>
            </Show>
        </li>
    )
}
