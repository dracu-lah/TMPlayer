import { For, Show, type JSX } from 'solid-js'
import { ArrowRight01Icon } from '@hugeicons/core-free-icons'
import { t } from '../i18n'
import { rowDomId } from '../screens/SettingsFind'
import { Icon, type IconData } from './Icon'
import { TmdbMark } from './TmdbMark'
import { BADGE_SIZE, ServiceBadge, ServiceBadgeRow } from './ServiceBadge'
import type { ServiceId } from './services'

// The parts Settings is built from: its rows, the switch, the choices and the TV's panels.
// `row` is a row's id in ui/settingsMap, which the search over Settings jumps to (rowDomId).

/** The arrow at the end of a row that opens something. */
export function Chevron() {
    return <Icon icon={ArrowRight01Icon} size={20} class="set-chevron" />
}

export function RowBody(props: { icon: IconData; service?: ServiceId; title: string; subtitle?: string; note?: string }) {
    return (
        <>
            <span class="set-icon">
                <Show when={props.service} fallback={<Icon icon={props.icon} size={22} />}>
                    <ServiceBadge service={props.service!} size={BADGE_SIZE.row} />
                </Show>
            </span>
            <span class="set-text">
                <span class="set-title">{props.title}</span>
                <Show when={props.subtitle}>
                    <span class="set-sub">{props.subtitle}</span>
                </Show>
                <Show when={props.note}>
                    <span class="set-sub">{props.note}</span>
                </Show>
            </span>
        </>
    )
}

/** A row that does something when pressed, with the arrow at its end when it opens something. */
export function ActionRow(props: {
    row?: string
    icon: IconData
    title: string
    subtitle?: string
    note?: string
    busy?: boolean
    chevron?: boolean
    onClick: (button: HTMLButtonElement) => void
}) {
    return (
        <button type="button" class="set-row" id={props.row && rowDomId(props.row)} aria-busy={props.busy} onClick={(e) => props.onClick(e.currentTarget)}>
            <RowBody icon={props.icon} title={props.title} subtitle={props.subtitle} note={props.note} />
            <Show when={props.chevron}>
                <Chevron />
            </Show>
        </button>
    )
}

/** A row that is a link, with the arrow at its end; by default it opens in a new tab. */
export function LinkRow(props: { row?: string; href: string; icon: IconData; title: string; subtitle?: string; sameTab?: boolean }) {
    return (
        <a class="set-row" id={props.row && rowDomId(props.row)} href={props.href} target={props.sameTab ? undefined : '_blank'} rel={props.sameTab ? undefined : 'noopener'}>
            <RowBody icon={props.icon} title={props.title} subtitle={props.subtitle} />
            <Chevron />
        </a>
    )
}

/** An on or off row: the title, what it does, and the app's switch at the end. */
export function ToggleRow(props: { id?: string; icon: IconData; service?: ServiceId; title: string; subtitle: string; on: boolean; onChange: (on: boolean) => void }) {
    return (
        <button type="button" role="switch" class="set-row" id={props.id && rowDomId(props.id)} aria-checked={props.on} onClick={() => props.onChange(!props.on)}>
            <RowBody icon={props.icon} service={props.service} title={props.title} subtitle={props.subtitle} />
            <span class="switch" aria-hidden="true" />
        </button>
    )
}

export function Choices<T extends string | number>(props: { label: string; name: string; options: { value: T; label: string }[]; value: T; onPick: (v: T) => void }) {
    return (
        <div class="segmented" role="radiogroup" aria-labelledby={props.label} style={{ 'grid-template-columns': `repeat(${props.options.length}, 1fr)` }}>
            <For each={props.options}>
                {(o) => (
                    <label classList={{ on: props.value === o.value }}>
                        <input type="radio" name={props.name} aria-label={o.label} checked={props.value === o.value} onChange={() => props.onPick(o.value)} />
                        {o.label}
                    </label>
                )}
            </For>
        </div>
    )
}

/** On and Off as choices, labelled by the heading `${id}-label`. */
export function OnOffChoices(props: { id: string; on: boolean; onPick: (on: boolean) => void }) {
    return (
        <Choices
            label={`${props.id}-label`}
            name={props.id}
            options={[
                { value: 'on', label: t('common.on') },
                { value: 'off', label: t('common.off') },
            ]}
            value={props.on ? 'on' : 'off'}
            onPick={(v) => props.onPick(v === 'on')}
        />
    )
}

/** The TV's block of Settings: a heading (when it has one) and its content; `anchor` is the DOM id the search jumps to. */
export function TvPanel(props: { title: string; id: string; anchor?: string; children: JSX.Element }) {
    return (
        <div class="panel" id={props.anchor}>
            <Show when={props.title}>
                <h2 class="panel-title" id={props.id}>{props.title}</h2>
            </Show>
            {props.children}
        </div>
    )
}

/** One of Settings' switches on a TV: its words and an On or Off choice made with OK, and what else belongs under it. */
export function TvSwitch(props: { id: string; row: string; title: string; on: boolean; onText: string; offText: string; onPick: (on: boolean) => void; children?: JSX.Element }) {
    return (
        <TvPanel title={props.title} id={`${props.id}-label`} anchor={rowDomId(props.row)}>
            <p class="muted small">{props.on ? props.onText : props.offText}</p>
            <OnOffChoices id={props.id} on={props.on} onPick={props.onPick} />
            {props.children}
        </TvPanel>
    )
}

/** The TMDB mark and the notice that goes with it. */
export function TmdbCredit() {
    return (
        <>
            <ServiceBadgeRow services={['tmdb', 'tvmaze', 'anilist']} />
            <a class="tmdb-credit" href="https://www.themoviedb.org/" target="_blank" rel="noopener" aria-label="TMDB">
                <TmdbMark height={14} />
            </a>
            <p class="muted small">{t('metadata.about_notice')}</p>
        </>
    )
}
