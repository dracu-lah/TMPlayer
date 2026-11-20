// The app's navigation, as the phone and the desktop draw it: one rounded pill floating at the foot
// of the page, holding Home, Chats, Search, Downloads and the viewer's picture, which opens the "You" menu
// (the viewer's own lists and Settings). A pointer gets a short name under each icon; a phone held
// sideways, where height is short, gets the compact bar of round icon buttons (CSS). One highlight
// slides to the chosen place.
import { onGlobal } from './globalInput'
import { createResource, createSignal, For, onCleanup, onMount, Show } from 'solid-js'
import { A, useLocation, useNavigate } from '@solidjs/router'
import { Clock01Icon, Download04Icon, History, Home03Icon, Message01Icon, Search01Icon, Settings01Icon, StarIcon } from '@hugeicons/core-free-icons'
import { Icon, type IconData } from './Icon'
import { initialOf } from './initial'
import { nameArt } from './colour'
import { me } from '../session'
import { t } from '../i18n'
import { form } from '../form'
import { isMac } from './shellKeys'
import { resumes, starredIds, watchedList } from '../store'

interface Place {
    href: string
    label: () => string
    icon: IconData
    on: (path: string) => boolean
    /** The letter or digit of its desktop shortcut (ui/shellKeys.ts), said in the title. */
    key: string
}

const PLACES: Place[] = [
    { href: '/', label: () => t('browse.tab_home'), icon: Home03Icon, on: (p) => p === '/' || p === '/recent', key: '1' },
    { href: '/chats', label: () => t('nav.chats'), icon: Message01Icon, on: (p) => p === '/chats' || p.startsWith('/chat/'), key: '2' },
    { href: '/search', label: () => t('common.search'), icon: Search01Icon, on: (p) => p === '/search', key: 'K' },
    // Downloads is a place on every platform (R2); the browser cannot keep videos, so its page is the app's card.
    { href: '/downloads', label: () => t('nav.downloads'), icon: Download04Icon, on: (p) => p === '/downloads', key: 'J' },
]

/** The card's width (POPOVER_WIDTH). */
const WIDTH = 232

/** The pages the "You" menu leads to: the picture is lit on them. */
const YOURS = /^\/(settings|favourites|history)(\/|$)/

/** "Search (Ctrl+K)" where a keyboard is the way in (a desktop), the bare name on a phone. */
const shortcutTitle = (label: string, key: string) => (form() === 'desktop' ? `${label} (${isMac() ? '⌘' : 'Ctrl+'}${key})` : label)

export function Nav() {
    const location = useLocation()
    const chosen = () => PLACES.findIndex((p) => p.on(location.pathname))
    const inProfile = () => YOURS.test(location.pathname)
    const initial = () => initialOf(me()?.name)
    const [open, setOpen] = createSignal(false)
    let you!: HTMLButtonElement
    const navigate = useNavigate()
    // The desktop's Settings key, as the "You" menu says it.
    const onKey = (e: KeyboardEvent) => {
        if ((e.ctrlKey || e.metaKey) && e.key === ',' && !e.altKey) {
            e.preventDefault()
            setOpen(false)
            navigate('/settings')
        }
    }
    onMount(() => onCleanup(onGlobal(document, 'keydown', onKey)))
    return (
        <nav class="nav" aria-label="TMPlayer">
            <div class="nav-cells" style={{ '--i': Math.max(0, chosen()) }}>
                <span class="nav-highlight" classList={{ shown: chosen() >= 0 }} aria-hidden="true" />
                <For each={PLACES}>
                    {(p, i) => (
                        <A class="nav-cell" href={p.href} aria-current={chosen() === i() ? 'page' : undefined} title={shortcutTitle(p.label(), p.key)}>
                            <span class="nav-glyph">
                                <Icon icon={p.icon} size={22} selected={chosen() === i()} />
                            </span>
                            <span class="nav-label">{p.label()}</span>
                        </A>
                    )}
                </For>
                <button
                    ref={you}
                    type="button"
                    class="nav-cell"
                    data-you
                    aria-haspopup="menu"
                    aria-expanded={open()}
                    aria-current={inProfile() ? 'page' : undefined}
                    title={t('nav.you')}
                    onClick={() => setOpen(!open())}
                >
                    <span class="nav-glyph">
                        <span
                            class="nav-picture"
                            classList={{ ringed: inProfile() || open() }}
                            style={initial() ? { background: nameArt(me()?.name), color: '#fff' } : undefined}
                        >
                            <Show when={initial()} fallback={<span class="nav-picture-blank" />}>
                                {initial()}
                            </Show>
                        </span>
                    </span>
                    <span class="nav-label">{t('nav.you')}</span>
                </button>
                <Show when={open()}>
                    <YouMenu anchor={you} onClose={() => setOpen(false)} />
                </Show>
            </div>
        </nav>
    )
}

/**
 * The viewer's own lists, behind their picture (ProfilePopover): a small card that grows out of
 * the bar, the counts of what is being watched, watched and starred, then Favourites, History,
 * Watch later and Settings. A click outside, Escape or picking a line closes it; Up and Down walk
 * the lines, and focus starts on the first.
 */
function YouMenu(props: { anchor: HTMLElement; onClose: () => void }) {
    const navigate = useNavigate()
    let card!: HTMLDivElement
    const [counts] = createResource(async () => {
        const [left, done] = await Promise.all([resumes(), watchedList()])
        return { watching: left.length, watched: done.length }
    })
    const go = (href: string) => {
        props.onClose()
        navigate(href)
    }
    const onKey = (e: KeyboardEvent) => {
        if (e.key === 'Escape') {
            props.onClose()
            props.anchor.focus()
            e.preventDefault()
            return
        }
        // Tab leaves a menu: it closes and the keyboard goes on from where the menu was.
        if (e.key === 'Tab') return (props.anchor.focus(), props.onClose())
        if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp' && e.key !== 'Home' && e.key !== 'End') return
        const items = [...card.querySelectorAll<HTMLElement>('button')]
        const at = items.indexOf(document.activeElement as HTMLElement)
        const to = e.key === 'Home' ? 0 : e.key === 'End' ? items.length - 1 : (at + (e.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length
        items[to]?.focus()
        e.preventDefault()
    }
    const outside = (e: PointerEvent) => {
        if (!card.contains(e.target as Node) && !props.anchor.contains(e.target as Node)) props.onClose()
    }
    // Over the bar, centred on the picture as far as the window allows (the phone keeps 16 px from its edge).
    const [place, setPlace] = createSignal<{ left: string; bottom: string }>()
    const measure = () => {
        const a = props.anchor.getBoundingClientRect()
        const bar = props.anchor.parentElement!.getBoundingClientRect()
        const left = Math.max(8, Math.min(innerWidth - WIDTH - 16, a.left + a.width / 2 - WIDTH / 2))
        setPlace({ left: `${left}px`, bottom: `${innerHeight - bar.top + 8}px` })
    }
    onMount(() => {
        measure()
        addEventListener('resize', measure)
        onCleanup(onGlobal(document, 'pointerdown', outside, true))
        card.querySelector<HTMLElement>('.you-row')?.focus({ preventScroll: true })
    })
    onCleanup(() => removeEventListener('resize', measure))
    const command = isMac() ? '⌘' : 'Ctrl+'
    return (
        <div ref={card} class="you-menu" role="menu" aria-label={t('nav.you')} style={place()} onKeyDown={onKey}>
            <div class="you-summary">
                <span class="you-heading">{t('profile.heading')}</span>
                <div class="you-counts">
                    <Count value={counts()?.watching ?? 0} label={t('profile.watching')} onClick={() => go('/history/continue')} />
                    <Count value={counts()?.watched ?? 0} label={t('profile.watched')} onClick={() => go('/history/watched')} />
                    <Count value={starredIds().size} label={t('profile.starred')} onClick={() => go('/favourites')} />
                </div>
            </div>
            <hr />
            <Row icon={StarIcon} label={t('browse.tab_favourites')} badge={starredIds().size || undefined} onClick={() => go('/favourites')} />
            <Row icon={History} label={t('browse.tab_history')} onClick={() => go('/history')} />
            <Row icon={Clock01Icon} label={t('browse.tab_watch_later')} onClick={() => go('/history/later')} />
            <hr />
            <Row icon={Settings01Icon} label={t('nav.settings')} shortcut={form() === 'desktop' ? `${command},` : undefined} onClick={() => go('/settings')} />
        </div>
    )
}

function Count(props: { value: number; label: string; onClick: () => void }) {
    return (
        <button type="button" class="you-count" role="menuitem" aria-label={`${props.value} ${props.label}`} onClick={props.onClick}>
            <strong>{props.value}</strong>
            <span>{props.label}</span>
        </button>
    )
}

function Row(props: { icon: IconData; label: string; badge?: number; shortcut?: string; onClick: () => void }) {
    return (
        <button type="button" class="you-row" role="menuitem" onClick={props.onClick}>
            <Icon icon={props.icon} size={20} />
            <span class="you-label">{props.label}</span>
            <Show when={props.shortcut}><span class="you-shortcut">{props.shortcut}</span></Show>
            <Show when={props.badge}><span class="you-badge">{props.badge}</span></Show>
        </button>
    )
}
