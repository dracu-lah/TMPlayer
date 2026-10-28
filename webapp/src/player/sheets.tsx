// The pieces every player menu and sheet is made of: the desktop's dropdown (Menu, Entry), the
// side panel More options opens on the desktop and the TV (Panel), the phone's full-screen sheet
// (PhoneSheet, ChoiceRow) and Picker, which is one or the other by skin.
import { Show, createEffect, on, onCleanup, onMount, type JSX } from 'solid-js'
import { ArrowLeft02Icon, Cancel01Icon, Tick02Icon } from '@hugeicons/core-free-icons'
import { Icon, type IconData } from '../ui/Icon'
import { t } from '../i18n'
import { form } from '../form'

/** The skin in use: the phone's, the desktop's or the TV's. */
export const variant = () => (form() === 'phone' ? 'phone' : form() === 'tv' ? 'tv' : 'desktop')

/**
 * A dropdown under or over the button that opened it (TmDropdownMenu: Floating.MenuShape, the
 * menu tone and its hairline), or on a phone the overflow's popup at the top end. A click outside
 * or Escape closes it.
 */
export function Menu(props: { anchor: DOMRect | null; root: HTMLElement; below?: boolean; onClose: () => void; children: JSX.Element }) {
    let el!: HTMLDivElement
    const place = () => {
        const r = props.root.getBoundingClientRect()
        const a = props.anchor
        if (!a) return {}
        const right = Math.max(8, r.right - a.right)
        if (props.below) return { top: `${a.bottom - r.top + 4}px`, right: `${right}px` }
        const left = Math.max(8, Math.min(a.left - r.left, r.width - 248))
        return { bottom: `${r.bottom - a.top + 8}px`, left: `${left}px` }
    }
    onMount(() => (el.querySelector<HTMLElement>(variant() === 'tv' ? '[aria-checked=true]:not(:disabled), button:not(:disabled)' : 'button:not(:disabled)')?.focus({ preventScroll: true })))
    return (
        <>
            <div class="pc-dismiss" onClick={props.onClose} />
            <div ref={el} class="pc-menu" role="menu" style={place()} onKeyDown={(e) => menuKeys(e, el)}>
                {props.children}
            </div>
        </>
    )
}

/** When a panel page last went, so the next one knows whether it opens or follows it. */
let panelGone = 0

/**
 * More options as the desktop app has it (HX16): a panel docked to the player's end edge (the
 * left in a right to left language) at its full height, sliding in over 150 ms (in place where
 * motion is reduced), its pages shown inside it with a Back line instead of another menu. A click
 * outside or Escape closes it; the keyboard starts on its first line (the ticked one on a TV) and
 * Up and Down walk the lines. `page` is a key that moves the keyboard back to the top on a new page.
 */
export function Panel(props: { title: string; page: string; onClose: () => void; onBack?: () => void; note?: string; children: JSX.Element }) {
    let el!: HTMLDivElement
    let body!: HTMLDivElement
    // A page of the panel replaces the one before in the same moment: only an opening slides in.
    const opening = performance.now() - panelGone > 50
    onCleanup(() => (panelGone = performance.now()))
    createEffect(
        on(
            () => props.page,
            () =>
                queueMicrotask(() => {
                    body.scrollTop = 0
                    const pick = variant() === 'tv' ? '[aria-checked=true]:not(:disabled), button:not(:disabled)' : 'button:not(:disabled)'
                    ;(body.querySelector<HTMLElement>(pick) ?? el.querySelector<HTMLElement>('button'))?.focus({ preventScroll: true })
                }),
        ),
    )
    return (
        <>
            <div class="pc-dismiss" onClick={props.onClose} onContextMenu={(e) => (e.preventDefault(), props.onClose())} />
            <div ref={el} class="pc-panel" classList={{ opening }} role="dialog" aria-label={props.title} data-shot="panel" onKeyDown={(e) => menuKeys(e, el)}>
                <div class="pc-panel-head">
                    <Show when={props.onBack} fallback={<h2>{props.title}</h2>}>
                        <button type="button" class="pc-panel-back" aria-label={t('common.back')} onClick={props.onBack!}>
                            <Icon icon={ArrowLeft02Icon} size={22} />
                            <h2>{props.title}</h2>
                        </button>
                    </Show>
                    <button type="button" class="pc-sheet-close" aria-label={t('common.close')} onClick={props.onClose}>
                        <Icon icon={Cancel01Icon} size={22} />
                    </button>
                </div>
                <div ref={body} class="pc-panel-body">
                    {props.children}
                    <Show when={props.note}><p class="pc-menu-note">{props.note}</p></Show>
                </div>
            </div>
        </>
    )
}

/** The remote's arrow on a screen of buttons: to the nearest one that way (a list or the status sheet). */
export function walk(root: ParentNode | null, dir: 'up' | 'down' | 'left' | 'right', from: HTMLElement | null) {
    const items = Array.from(root?.querySelectorAll<HTMLElement>('button:not(:disabled), a[href]') ?? []).filter((el) => el.getClientRects().length > 0)
    if (!items.length) return
    if (!from || !items.includes(from)) return void items[0].focus()
    const a = from.getBoundingClientRect()
    let best: HTMLElement | null = null
    let bestScore = Infinity
    for (const el of items) {
        if (el === from) continue
        const b = el.getBoundingClientRect()
        const dx = b.left + b.width / 2 - (a.left + a.width / 2)
        const dy = b.top + b.height / 2 - (a.top + a.height / 2)
        const along = dir === 'left' ? -dx : dir === 'right' ? dx : dir === 'up' ? -dy : dy
        if (along <= 1) continue
        const score = along + Math.abs(dir === 'left' || dir === 'right' ? dy : dx) * 2.5
        if (score < bestScore) (best = el, (bestScore = score))
    }
    best?.focus()
}

/** Up and Down walk a menu's lines. */
export function menuKeys(e: KeyboardEvent, el: HTMLElement) {
    if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return
    const items = [...el.querySelectorAll<HTMLElement>('button:not(:disabled)')]
    const at = items.indexOf(document.activeElement as HTMLElement)
    items[(at + (e.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length]?.focus()
    e.preventDefault()
    e.stopPropagation()
}

/** One line of a desktop menu: an 18 px slot for the tick or an icon, the words (a quieter line under them if given), a quiet figure at the end. */
export function Entry(props: { text: string; detail?: string; trailing?: string; icon?: IconData; checked?: boolean; disabled?: boolean; onClick: () => void }) {
    return (
        <button type="button" role={props.checked !== undefined ? 'menuitemradio' : 'menuitem'} aria-checked={props.checked} class="pc-entry" disabled={props.disabled} onClick={props.onClick}>
            <span class="pc-entry-lead">
                <Show when={props.checked} fallback={props.icon ? <Icon icon={props.icon} size={18} /> : null}>
                    <Icon icon={Tick02Icon} size={18} />
                </Show>
            </span>
            <Show when={props.detail} fallback={<span class="pc-entry-text">{props.text}</span>}>
                <span class="pc-entry-text pc-entry-two">
                    {props.text}
                    <small class="pc-entry-detail">{props.detail}</small>
                </span>
            </Show>
            <Show when={props.trailing}><span class="pc-entry-trail">{props.trailing}</span></Show>
        </button>
    )
}

/**
 * A choice: on a phone the pickers' sheet (ChoiceSheet: the whole screen under 600 dp, the
 * headline and Close at the top, radio rows), on the desktop the menu over its button.
 */
export function Picker(props: { title: string; note?: string; phone: boolean; anchor: DOMRect | null; root: HTMLElement; onClose: () => void; onBack?: () => void; panel?: boolean; children: JSX.Element }) {
    return (
        <Show
            when={props.phone}
            fallback={
                <Show
                    when={props.panel}
                    fallback={
                <Menu anchor={props.anchor} root={props.root} below={props.anchor ? props.anchor.top < props.root.getBoundingClientRect().top + props.root.clientHeight / 2 : false} onClose={props.onClose}>
                    <Show when={props.onBack}>
                        <Entry text={t('common.back')} onClick={props.onBack!} />
                        <hr />
                    </Show>
                    {props.children}
                    <Show when={props.note}><p class="pc-menu-note">{props.note}</p></Show>
                </Menu>
                    }
                >
                    <Panel title={props.title} page={props.title} note={props.note} onClose={props.onClose} onBack={props.onBack}>
                        {props.children}
                    </Panel>
                </Show>
            }
        >
            <PhoneSheet title={props.title} note={props.note} onClose={props.onClose} onBack={props.onBack}>
                {props.children}
            </PhoneSheet>
        </Show>
    )
}

/** The phone's picker sheet: the whole screen on a phone, a dialog in the middle on a wider one. */
export function PhoneSheet(props: { title: string; note?: string; onClose: () => void; onBack?: () => void; children: JSX.Element; class?: string }) {
    let el!: HTMLDivElement
    // Again on each page: More options' pages are shown in the same sheet.
    createEffect(on(() => props.title, () => queueMicrotask(() => el.querySelector<HTMLElement>('.pc-sheet-body [aria-checked=true], .pc-sheet-body button, button')?.focus({ preventScroll: true }))))
    return (
        <div class="pc-sheet-scrim" onClick={(e) => e.target === e.currentTarget && props.onClose()}>
            <div ref={el} class={`pc-sheet ${props.class ?? ''}`} role="dialog" aria-label={props.title}>
                <div class="pc-sheet-head">
                    <Show when={props.onBack}>
                        <button type="button" class="pc-sheet-close" aria-label={t('common.back')} onClick={props.onBack!}>
                            <Icon icon={ArrowLeft02Icon} size={24} />
                        </button>
                    </Show>
                    <div>
                        <h2>{props.title}</h2>
                        <Show when={props.note}><p>{props.note}</p></Show>
                    </div>
                    <button type="button" class="pc-sheet-close" aria-label={t('common.close')} onClick={props.onClose}>
                        <Icon icon={Cancel01Icon} size={24} />
                    </button>
                </div>
                <div class="pc-sheet-body" role="radiogroup">{props.children}</div>
            </div>
        </div>
    )
}

/** One line of a picker: ChoiceRow on a phone (radio, 56 px), Entry on the desktop. */
export function Choice(props: { text: string; detail?: string; on?: boolean; icon?: IconData; phone: boolean; disabled?: boolean; onClick: () => void }) {
    return (
        <Show when={props.phone} fallback={<Entry text={props.text} detail={props.detail} checked={props.icon ? undefined : !!props.on} icon={props.icon} disabled={props.disabled} onClick={props.onClick} />}>
            <button type="button" class="pc-choice" role={props.icon ? 'button' : 'radio'} aria-checked={props.icon ? undefined : !!props.on} disabled={props.disabled} onClick={props.onClick}>
                <Show when={props.icon} fallback={<span class="pc-radio" />}>
                    <span class="pc-choice-icon"><Icon icon={props.icon!} size={22} /></span>
                </Show>
                <Show when={props.detail} fallback={<span>{props.text}</span>}>
                    <span class="pc-choice-text">
                        {props.text}
                        <small>{props.detail}</small>
                    </span>
                </Show>
            </button>
        </Show>
    )
}
