// The app's navigation, as the phone and the desktop draw it: one rounded pill floating at the foot
// of the page, holding Home, Chats, Search, History and the account's picture, which opens the
// account menu (WP-103: the account, Settings, the viewer's own lists and Sign out). The five items
// share the pill's width equally and one label size and line height (CSS), with roomy padding; the
// account is the picture alone (a long name never fits a label: it is the button's name and tooltip). A
// pointer gets a short name under each icon; a phone held sideways, where height is short, gets the
// compact bar of round icon buttons (CSS). One highlight slides to the chosen place. On a phone width
// the account menu is a bottom sheet (a modal dialog, so Back and the swipe back close it, WP-54);
// on wider windows it is a card over the bar.
import { onGlobal } from './globalInput'
import { createResource, createSignal, For, Match, onCleanup, onMount, Show, Switch } from 'solid-js'
import { A, useLocation, useNavigate } from '@solidjs/router'
import {
    Clock01Icon,
    History,
    Home03Icon,
    Message01Icon,
    Search01Icon,
    Settings01Icon,
    StarIcon,
    UserAdd01Icon,
    UserIcon,
    UserSwitchIcon,
} from '@hugeicons/core-free-icons'
import { Icon, type IconData } from './Icon'
import { nameArt } from './colour'
import { me } from '../session'
import { t } from '../i18n'
import { form } from '../form'
import { isMac } from './shellKeys'
import { resumes, starredIds, watchedList } from '../store'
import { activeId, activeProfile, many, openPicker, photoOf, photosVersion } from '../accounts'
import { addAccount } from '../accountFlows'
import { accountName, accountPicture } from './accountPicture'
import { VersionFooter } from './VersionFooter'
import { dragCloses } from './versionLine'

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
    // The fourth place was Downloads, a card that said the browser cannot keep videos: History holds the slot until it can (WP-59, user default D7).
    { href: '/history', label: () => t('browse.tab_history'), icon: History, on: (p) => p === '/history' || p.startsWith('/history/'), key: '' },
]

/** A window this narrow, or a phone, gets the account menu as a bottom sheet. */
const sheetWidth = () => form() === 'phone' || (typeof matchMedia === 'function' && matchMedia('(max-width: 599px)').matches)

/** The card's width (POPOVER_WIDTH). */
const WIDTH = 264

/** The pages the "You" menu leads to: the picture is lit on them. */
const YOURS = /^\/(settings|favourites)(\/|$)/

/** "Search (Ctrl+K)" where a keyboard is the way in (a desktop), the bare name on a phone. */
const shortcutTitle = (label: string, key: string) => (form() === 'desktop' && key ? `${label} (${isMac() ? '⌘' : 'Ctrl+'}${key})` : label)

export function Nav() {
    const location = useLocation()
    const chosen = () => PLACES.findIndex((p) => p.on(location.pathname))
    const inProfile = () => YOURS.test(location.pathname)
    const name = () => accountName(me()?.name, activeProfile()?.name)
    // "Account, Asha", or "Account" while no name is known: what a screen reader and the tooltip say.
    const label = () => (name() ? t('nav.account_named', { name: name() }) : t('nav.account'))
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
        <nav class="nav" id="main-nav" aria-label="TMPlayer">
            <div class="nav-cells nav-pill" style={{ '--i': Math.max(0, chosen()) }}>
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
                    aria-label={label()}
                    title={label()}
                    onClick={() => setOpen(!open())}
                >
                    <span class="nav-glyph">
                        <AccountPicture name={name()} class="nav-picture" ringed={inProfile() || open()} />
                    </span>
                </button>
                <Show when={open()}>
                    <YouMenu anchor={you} sheet={sheetWidth()} onClose={() => setOpen(false)} />
                </Show>
            </div>
        </nav>
    )
}

/**
 * The account's round picture (WP-103): the profile's saved photo (read again when one is kept or
 * dropped), else the first letter on the name's colour, else the person glyph, never a blank disc.
 * Drawn only: the button or the menu's head says the name. A photo the browser cannot draw falls
 * back to the letter until another photo is kept.
 */
function AccountPicture(props: { name: string; class: string; ringed?: boolean }) {
    const [broken, setBroken] = createSignal<string | null>(null)
    const art = () => {
        photosVersion()
        const photo = photoOf(activeId)
        return accountPicture(photo && photo !== broken() ? photo : null, props.name)
    }
    return (
        <span
            class={props.class}
            classList={{ ringed: !!props.ringed }}
            data-picture={art().kind}
            aria-hidden="true"
            style={art().kind === 'initial' ? { background: nameArt(props.name), color: '#fff' } : undefined}
        >
            <Switch>
                <Match when={art().kind === 'photo'}>
                    <img src={(art() as { src: string }).src} alt="" onError={(e) => setBroken(e.currentTarget.src)} />
                </Match>
                <Match when={art().kind === 'initial'}>{(art() as { letter: string }).letter}</Match>
                <Match when={art().kind === 'glyph'}>
                    <Icon icon={UserIcon} size={16} />
                </Match>
            </Switch>
        </span>
    )
}

/**
 * The account menu behind the picture (ProfilePopover, WP-103), kept short: a head (the picture, the
 * name, the @username, and with two or more accounts a switch button), the library as one row of three
 * tiles (Favourites with its starred count, History with how many are being watched, Watch later), two
 * plain rows (Settings, Add account) and the compact version, which opens About. Sign out lives in
 * Settings; the watched count is in History's heading. On a phone width it is a bottom sheet (a modal
 * dialog: the swipe back and Back close it, a drag on the grip too, focus stays inside); on a wider
 * window a card over the bar. A click outside, Escape or picking a line closes it; Up and Down walk
 * the rows, Left and Right the tiles, and focus starts on the first tile.
 */
function YouMenu(props: { anchor: HTMLElement; sheet: boolean; onClose: () => void }) {
    let dialog: HTMLDialogElement | undefined
    // The sheet is a dialog: closing it goes through close(), so the history entry it took (modalHistory) is given back.
    const close = () => {
        if (dialog?.open) dialog.close()
        props.onClose()
    }
    // Children mount first and the dialog is not showing yet: AccountMenu takes its focus a microtask later, after this opens it.
    onMount(() => dialog?.showModal())
    onCleanup(() => dialog?.open && dialog.close())
    return (
        <Show when={props.sheet} fallback={<AccountMenu anchor={props.anchor} onClose={props.onClose} />}>
            <dialog
                ref={dialog}
                class="you-sheet"
                aria-label={t('nav.account')}
                onClose={() => props.onClose()}
                onClick={(e) => e.target === dialog && close()}
            >
                <SheetHandle onClose={close} dialog={() => dialog} />
                <AccountMenu anchor={props.anchor} onClose={close} sheet />
            </dialog>
        </Show>
    )
}

/**
 * The sheet's grip: a drag down on it (or on the head under it) follows the finger and closes the sheet
 * when far or quick enough (dragCloses), else springs back. A keyboard has Escape, a screen reader the
 * Close button of the sheet's last line is not needed: the dialog's own Escape and Back do it.
 */
function SheetHandle(props: { onClose: () => void; dialog: () => HTMLDialogElement | undefined }) {
    let from: { y: number; at: number } | null = null
    const move = (e: PointerEvent) => {
        const d = props.dialog()
        if (!from || !d) return
        d.style.transform = `translateY(${Math.max(0, e.clientY - from.y)}px)`
    }
    const end = (e: PointerEvent) => {
        const d = props.dialog()
        const start = from
        from = null
        if (!d || !start) return
        d.style.transition = ''
        const dy = e.clientY - start.y
        if (e.type !== 'pointercancel' && dragCloses(dy, e.timeStamp - start.at)) props.onClose()
        else d.style.transform = ''
    }
    return (
        <div
            class="sheet-handle"
            aria-hidden="true"
            onPointerDown={(e) => {
                from = { y: e.clientY, at: e.timeStamp }
                props.dialog()!.style.transition = 'none'
                e.currentTarget.setPointerCapture?.(e.pointerId)
            }}
            onPointerMove={move}
            onPointerUp={end}
            onPointerCancel={end}
        >
            <span />
        </div>
    )
}

function AccountMenu(props: { anchor: HTMLElement; onClose: () => void; sheet?: boolean }) {
    const navigate = useNavigate()
    const name = () => accountName(me()?.name, activeProfile()?.name)
    let card!: HTMLDivElement
    const [counts] = createResource(async () => (await resumes()).length)
    // Navigate first: the route change closes the dialog and takes its history entry over (modalHistory.write).
    const go = (href: string) => {
        navigate(href)
        props.onClose()
    }
    const items = () => [...card.querySelectorAll<HTMLElement>('[role="menuitem"]')]
    // The tile the keyboard was last on, where Up from the rows below comes back to.
    let lastCol = 0
    const onKey = (e: KeyboardEvent) => {
        if (e.key === 'Escape') {
            props.onClose()
            props.anchor.focus()
            e.preventDefault()
            return
        }
        // Tab leaves a card: it closes and the keyboard goes on from where the card was. A sheet keeps it inside (the dialog traps it).
        if (e.key === 'Tab') return props.sheet ? undefined : (props.anchor.focus(), props.onClose())
        if (!['ArrowDown', 'ArrowUp', 'ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(e.key)) return
        const all = items()
        const here = document.activeElement as HTMLElement
        const tiles = all.filter((el) => el.classList.contains('you-tile'))
        const col = tiles.indexOf(here)
        if (col >= 0) lastCol = col
        // The tiles are one row: Left and Right walk it (the end stays put, never wraps out of the row).
        if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') {
            if (col >= 0) tiles[Math.max(0, Math.min(tiles.length - 1, col + (e.key === 'ArrowRight' ? 1 : -1) * (isRtl() ? -1 : 1)))]?.focus()
            e.preventDefault()
            return
        }
        // The rows: the three tiles count as one, entered on the column the last row had.
        const rows = all.filter((el) => !el.classList.contains('you-tile') || el === tiles[0])
        const at = rows.indexOf(col >= 0 ? tiles[0] : here)
        const to = e.key === 'Home' ? 0 : e.key === 'End' ? rows.length - 1 : Math.max(0, Math.min(rows.length - 1, at + (e.key === 'ArrowDown' ? 1 : -1)))
        const next = rows[to]
        ;(next === tiles[0] ? (tiles[lastCol] ?? next) : next)?.focus()
        e.preventDefault()
    }
    const outside = (e: PointerEvent) => {
        if (!card.contains(e.target as Node) && !props.anchor.contains(e.target as Node)) props.onClose()
    }
    // Over the bar, centred on the picture as far as the window allows (the phone keeps 16 px from its edge).
    // A short window (a 200 percent zoom) scrolls the card rather than cutting its top off.
    const [place, setPlace] = createSignal<{ left: string; bottom: string; 'max-height': string }>()
    const measure = () => {
        const a = props.anchor.getBoundingClientRect()
        const bar = props.anchor.parentElement!.getBoundingClientRect()
        const left = Math.max(8, Math.min(innerWidth - WIDTH - 16, a.left + a.width / 2 - WIDTH / 2))
        setPlace({ left: `${left}px`, bottom: `${innerHeight - bar.top + 8}px`, 'max-height': `${Math.max(120, bar.top - 16)}px` })
    }
    onMount(() => {
        if (!props.sheet) {
            measure()
            addEventListener('resize', measure)
            onCleanup(onGlobal(document, 'pointerdown', outside, true))
        }
        const first = () => card.querySelector<HTMLElement>('.you-tile')?.focus({ preventScroll: true })
        if (props.sheet) queueMicrotask(first)
        else first()
    })
    onCleanup(() => removeEventListener('resize', measure))
    const command = isMac() ? '⌘' : 'Ctrl+'
    const starred = () => starredIds().size
    return (
        <div
            ref={card}
            class="you-menu"
            classList={{ 'in-sheet': !!props.sheet }}
            role="menu"
            aria-label={name() ? t('nav.account_named', { name: name() }) : t('nav.account')}
            style={props.sheet ? undefined : place()}
            onKeyDown={onKey}
        >
            <div class="you-head">
                <AccountPicture name={name()} class="you-picture" />
                <div class="you-who">
                    <strong>{name() || t('nav.account')}</strong>
                    <Show when={me()?.username?.trim()}>
                        <span>@{me()?.username?.trim().replace(/^@/, '')}</span>
                    </Show>
                </div>
                {/* Two or more accounts: the switch is one press from the head. */}
                <Show when={many()}>
                    <button
                        type="button"
                        class="you-icon"
                        role="menuitem"
                        aria-label={t('accounts.switch')}
                        title={t('accounts.switch')}
                        onClick={() => {
                            props.onClose()
                            openPicker()
                        }}
                    >
                        <Icon icon={UserSwitchIcon} size={22} />
                    </button>
                </Show>
            </div>
            {/* The library, one row of three equal tiles; the counts that were a strip are small lines on them. */}
            <div class="you-tiles" role="presentation">
                <Tile icon={StarIcon} label={t('browse.tab_favourites')} sub={`${t('profile.starred')} ${starred()}`} onClick={() => go('/favourites')} />
                <Tile icon={History} label={t('browse.tab_history')} sub={`${t('profile.watching')} ${counts() ?? 0}`} onClick={() => go('/history')} />
                <Tile icon={Clock01Icon} label={t('browse.tab_watch_later')} onClick={() => go('/history/later')} />
            </div>
            <Row icon={Settings01Icon} label={t('nav.settings')} shortcut={form() === 'desktop' ? `${command},` : undefined} onClick={() => go('/settings')} />
            <Row
                icon={UserAdd01Icon}
                label={t('accounts.add')}
                onClick={() => {
                    props.onClose()
                    void addAccount()
                }}
            />
            <hr />
            {/* The compact version, last: the icon and "v1.28.7"; a press opens About, where the full line is (WP-104). */}
            <VersionFooter look="menu" onOpen={props.onClose} />
        </div>
    )
}

/** The reading direction of the page, for Left and Right in the tiles. */
const isRtl = () => typeof document !== 'undefined' && getComputedStyle(document.documentElement).direction === 'rtl'

function Tile(props: { icon: IconData; label: string; sub?: string; onClick: () => void }) {
    return (
        <button type="button" class="you-tile" role="menuitem" aria-label={props.sub ? `${props.label}, ${props.sub}` : props.label} onClick={props.onClick}>
            <Icon icon={props.icon} size={24} />
            <span class="you-tile-label">{props.label}</span>
            <span class="you-tile-sub">{props.sub ?? ' '}</span>
        </button>
    )
}

function Row(props: { icon: IconData; label: string; shortcut?: string; onClick: () => void }) {
    return (
        <button type="button" class="you-row" role="menuitem" onClick={props.onClick}>
            <Icon icon={props.icon} size={20} />
            <span class="you-label">{props.label}</span>
            <Show when={props.shortcut}><span class="you-shortcut">{props.shortcut}</span></Show>
        </button>
    )
}
