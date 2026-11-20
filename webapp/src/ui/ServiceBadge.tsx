import { For, Show } from 'solid-js'
import './serviceBadge.css'
import { nameArt } from './colour'
import { initialOf } from './initial'
import { TMDB_SQUARE } from './serviceLogos'
import { serviceOf, type ServiceId } from './services'

// Where the web player names an outside service (HX93): its logo in a rounded tile of 24 to 32 px,
// then the name. The twin of the apps' ServiceBadge (ui/.../components/ServiceBadge.kt): the bundled
// official mark on a white chip that reads on a dark theme, a light one and a TV, or the service's
// initial in its name colour where it offers no mark TMPlayer may use. The tile is decoration
// (aria-hidden): the name beside it is read once, and the tile is never a tab stop or a button.

let ids = 0

/** The side of the tile a screen picks from. */
export const BADGE_SIZE = { line: 24, credit: 28, row: 32 } as const

export function ServiceBadge(props: { service: ServiceId; size?: number; name?: boolean; class?: string }) {
    const service = () => serviceOf(props.service)
    const size = () => Math.min(32, Math.max(24, props.size ?? BADGE_SIZE.credit))
    const gradient = `svc-grad-${++ids}`
    const tile = () => (
        <span
            class="svc-tile"
            classList={{ 'svc-logo': service().logo !== null, 'svc-initial': service().logo === null }}
            data-service={service().id}
            aria-hidden="true"
            style={{
                width: `${size()}px`,
                height: `${size()}px`,
                'border-radius': `${Math.round(size() / 4)}px`,
                'font-size': `${Math.round(size() / 2)}px`,
                ...(service().logo === null ? { background: nameArt(service().label) } : {}),
            }}
        >
            <Show when={service().logo === 'tmdb-square'} fallback={initialOf(service().label)}>
                <svg viewBox={TMDB_SQUARE.viewBox}>
                    <defs>
                        <linearGradient id={gradient} gradientUnits="userSpaceOnUse" x1="0" y1="40.76" x2={TMDB_SQUARE.width} y2="40.76">
                            <For each={TMDB_SQUARE.stops}>{(stop) => <stop offset={stop[0]} stop-color={stop[1]} />}</For>
                        </linearGradient>
                    </defs>
                    <path fill={`url(#${gradient})`} d={TMDB_SQUARE.path} />
                </svg>
            </Show>
        </span>
    )
    return (
        <Show when={props.name} fallback={tile()}>
            <span class={`svc-line ${props.class ?? ''}`}>
                {tile()}
                <span class="svc-name">{service().label}</span>
            </span>
        </Show>
    )
}

/** Several services named in one sentence, each with its name, wrapping at a narrow width or large text. */
export function ServiceBadgeRow(props: { services: readonly ServiceId[]; size?: number }) {
    return (
        <span class="svc-row">
            <For each={props.services}>{(id) => <ServiceBadge service={id} size={props.size ?? BADGE_SIZE.line} name />}</For>
        </span>
    )
}
