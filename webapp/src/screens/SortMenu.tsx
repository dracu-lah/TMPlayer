import { onGlobal } from '../ui/globalInput'
import { createEffect, createSignal, For, onCleanup, Show } from 'solid-js'
import { PlayIcon, ShuffleIcon, Sorting01Icon, Tick02Icon } from '@hugeicons/core-free-icons'
import { t } from '../i18n'
import { settings, setSetting } from '../store'
import { form } from '../form'
import { arrowKeys } from '../ui/arrows'
import { Icon } from '../ui/Icon'
import { ADDED_WORDS, FILTERS, FILTER_WORDS, languageName, LENGTH_WORDS, PICTURE_WORDS, type ChatFilter, type Facets } from '../ui/videoFilter'
import type { Sort } from '../tg/types'
import './sortMenu.css'

const SORTS: { value: Sort; key: Parameters<typeof t>[0] }[] = [
    { value: 'Newest', key: 'grid.sort_newest' },
    { value: 'Oldest', key: 'grid.sort_oldest' },
    { value: 'Name', key: 'grid.sort_name' },
    { value: 'Year', key: 'grid.sort_year' },
    { value: 'Size', key: 'grid.sort_size' },
    { value: 'Longest', key: 'grid.sort_longest' },
    { value: 'LastPlayed', key: 'grid.sort_last_played' },
    { value: 'Unwatched', key: 'grid.sort_unwatched' },
]

/** One chip: a pill that is one of a group's choices (a radio) or an on and off switch (a checkbox). */
function Chip(props: { label: string; on: boolean; onPress: () => void; check?: boolean }) {
    return (
        <button type="button" class="sort-chip" classList={{ on: props.on }} role={props.check ? 'menuitemcheckbox' : 'menuitemradio'} aria-checked={props.on} onClick={props.onPress}>
            {props.label}
        </button>
    )
}

/**
 * Sort videos by, and the chat's filters (U6): the app's eight orders in one list, the chosen one
 * ticked, then the Show group, Length, Picture, With, Language and Added as chips that combine, and
 * "Hide small clips". Each pick applies at once and the menu stays open so the grid is seen to
 * change behind it. The order is kept for every chat, the filters for each chat.
 */
export function SortMenu(props: {
    cell?: boolean
    /** The "Hide small clips" switch: on or off now, its words, and what turning it does; undefined until the limits are known. */
    hideSmall: boolean
    clipLabel?: string
    onHideSmall?: (on: boolean) => void
    /** A floor chosen before the switch, as a sentence, with a Reset. */
    customRange?: string
    onReset: () => void
    /** The chat's Show filter, the others as the rules read them, and the languages the loaded names state. */
    filter: ChatFilter
    facets: Facets
    languages: readonly string[]
    /** Filters in force: the button says so. */
    filtersOn: number
    onFilter: (f: ChatFilter) => void
    onFacet: (field: 'length' | 'picture' | 'added' | 'hdr' | 'subtitles' | 'language', value: string | null) => void
    onClear: () => void
    /** Play all (WP-32): the first listed video, then the chat's order; left out where there is nothing to play. */
    onPlayAll?: () => void
    /** Play random over what is loaded; left out where there is nothing to play. */
    onRandom?: () => void
}) {
    const [open, setOpen] = createSignal(false)
    let box!: HTMLDivElement
    let trigger!: HTMLButtonElement
    // Escape or a pick that leaves puts the keyboard back on the button that opened the menu, not on a line that is gone.
    const shut = () => (setOpen(false), trigger.focus({ preventScroll: true }))
    const close = (e: Event) => {
        if (e instanceof KeyboardEvent) {
            if (e.key === 'Escape') shut()
        } else if (!box.contains(e.target as Node)) setOpen(false)
    }
    createEffect(() => {
        if (!open()) return
        // The keyboard starts on the first line (a ring shows only where it came by keys).
        queueMicrotask(() => box.querySelector<HTMLElement>('[role^=menuitem]')?.focus({ preventScroll: true }))
        onCleanup(onGlobal(document, 'pointerdown', close))
        onCleanup(onGlobal(document, 'keydown', close))
    })
    const label = () => (props.filtersOn ? t('grid.filter_on') : t('grid.sort_label'))
    const glyph = () => (
        <>
            <Icon icon={Sorting01Icon} size={22} />
            <Show when={props.filtersOn}>
                <span class="sort-dot" aria-hidden="true" />
            </Show>
        </>
    )
    return (
        <div class="menu-box" ref={box}>
            <button
                ref={trigger}
                class={props.cell ? 'nav-cell' : 'round-button'}
                classList={{ 'sort-on': props.filtersOn > 0 }}
                type="button"
                aria-haspopup="menu"
                aria-expanded={open()}
                aria-label={label()}
                title={label()}
                onClick={() => setOpen(!open())}
            >
                <Show when={props.cell} fallback={glyph()}>
                    <span class="nav-glyph">{glyph()}</span>
                    <span class="nav-label">{t('grid.sort')}</span>
                </Show>
            </button>
            <Show when={open()}>
                <div
                    class="pop-menu sort-menu"
                    role="menu"
                    aria-label={t('grid.sort_label')}
                    // The menu scrolls on its own; whatever takes the focus (the remote's walk too) is brought into view inside it.
                    onFocusIn={(e) => (e.target as HTMLElement).scrollIntoView?.({ block: 'nearest', inline: 'nearest' })}
                    onKeyDown={(e) => {
                        if (e.key === 'Tab') shut()
                        else if (form() !== 'tv') {
                            // Up and Down walk every line; Left and Right walk the chips along their row (the same list, one step).
                            arrowKeys(e, e.currentTarget, '[role^=menuitem]', { vertical: true })
                            if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') arrowKeys(e, e.currentTarget, '[role^=menuitem]')
                        }
                    }}
                >
                    <Show when={props.onPlayAll}>
                        <button
                            type="button"
                            role="menuitem"
                            onClick={() => {
                                props.onPlayAll!()
                                shut()
                            }}
                        >
                            <span>{t('browse.play_all')}</span>
                            <Icon icon={PlayIcon} size={20} />
                        </button>
                    </Show>
                    <Show when={props.onRandom}>
                        <button
                            type="button"
                            role="menuitem"
                            onClick={() => {
                                props.onRandom!()
                                shut()
                            }}
                        >
                            <span>{t('grid.play_random')}</span>
                            <Icon icon={ShuffleIcon} size={20} />
                        </button>
                    </Show>
                    <p class="pop-title">{t('grid.sort_label')}</p>
                    <For each={SORTS}>
                        {(s) => (
                            <button
                                type="button"
                                role="menuitemradio"
                                aria-checked={settings().sort === s.value}
                                classList={{ on: settings().sort === s.value }}
                                onClick={() => {
                                    setSetting('sort', s.value)
                                    shut()
                                }}
                            >
                                <span>{t(s.key)}</span>
                                <Show when={settings().sort === s.value}>
                                    <Icon icon={Tick02Icon} size={20} />
                                </Show>
                            </button>
                        )}
                    </For>
                    <p class="pop-title">{t('grid.filter_label')}</p>
                    <div class="sort-chips" role="group" aria-label={t('grid.filter_label')}>
                        <For each={FILTERS}>{(f) => <Chip label={t(FILTER_WORDS[f])} on={props.filter === f} onPress={() => props.onFilter(f)} />}</For>
                    </div>
                    <p class="pop-title">{t('grid.filter_length_label')}</p>
                    <div class="sort-chips" role="group" aria-label={t('grid.filter_length_label')}>
                        <For each={Object.entries(LENGTH_WORDS)}>
                            {([code, key]) => <Chip label={t(key)} on={props.facets.length === code} onPress={() => props.onFacet('length', code)} />}
                        </For>
                    </div>
                    <p class="pop-title">{t('grid.filter_picture_label')}</p>
                    <div class="sort-chips" role="group" aria-label={t('grid.filter_picture_label')}>
                        <For each={Object.entries(PICTURE_WORDS)}>
                            {([code, key]) => <Chip label={t(key)} on={props.facets.picture === code} onPress={() => props.onFacet('picture', code)} />}
                        </For>
                    </div>
                    <p class="pop-title">{t('grid.filter_with_label')}</p>
                    <div class="sort-chips" role="group" aria-label={t('grid.filter_with_label')}>
                        <Chip check label={t('grid.filter_hdr')} on={props.facets.hdr} onPress={() => props.onFacet('hdr', props.facets.hdr ? null : '1')} />
                        <Chip check label={t('grid.filter_subtitles')} on={props.facets.subtitles} onPress={() => props.onFacet('subtitles', props.facets.subtitles ? null : '1')} />
                    </div>
                    {/* Only the languages the loaded names state; the picked one stays in the list however the pages move. */}
                    <Show when={props.languages.length}>
                        <p class="pop-title">{t('grid.filter_language_label')}</p>
                        <div class="sort-chips" role="group" aria-label={t('grid.filter_language_label')}>
                            <Chip label={t('grid.filter_any')} on={props.facets.language === null} onPress={() => props.onFacet('language', null)} />
                            <For each={props.languages}>
                                {(code) => <Chip label={languageName(code) || code} on={props.facets.language === code} onPress={() => props.onFacet('language', code)} />}
                            </For>
                        </div>
                    </Show>
                    <p class="pop-title">{t('grid.filter_added_label')}</p>
                    <div class="sort-chips" role="group" aria-label={t('grid.filter_added_label')}>
                        <For each={Object.entries(ADDED_WORDS)}>
                            {([code, key]) => <Chip label={t(key)} on={props.facets.added === code} onPress={() => props.onFacet('added', code)} />}
                        </For>
                    </div>
                    <Show when={props.filtersOn > 0}>
                        <button type="button" role="menuitem" class="sort-clear" onClick={() => (props.onClear(), shut())}>
                            <span>{t('grid.filter_clear')}</span>
                        </button>
                    </Show>
                    <Show when={props.onHideSmall}>
                        <p class="pop-title">{t('grid.filter_size_label')}</p>
                        {/* Stays open when pressed, so the grid is seen to change behind it. */}
                        <button
                            type="button"
                            role="menuitemcheckbox"
                            aria-checked={props.hideSmall}
                            classList={{ on: props.hideSmall }}
                            onClick={() => props.onHideSmall!(!props.hideSmall)}
                        >
                            <span>{t('grid.hide_small_clips', { size: props.clipLabel ?? '' })}</span>
                            <Show when={props.hideSmall}>
                                <Icon icon={Tick02Icon} size={20} />
                            </Show>
                        </button>
                        <Show when={props.customRange}>
                            <button type="button" role="menuitem" class="on" onClick={() => { props.onReset(); shut() }}>
                                <span>{t('grid.size_limit_on', { range: props.customRange! })}</span>
                            </button>
                        </Show>
                    </Show>
                </div>
            </Show>
        </div>
    )
}
