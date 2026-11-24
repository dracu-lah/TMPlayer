// "Who's watching?" (HX88 phase B3): the profiles as round pictures with their names under them and
// Add account last, at launch when the browser has two or more (never with one), and from the
// profile menu in Settings. A focus group on every form: the first picture takes the focus, the
// arrows move along the row (the TV's remote through tv/focus.ts, which treats the picker as a
// screen of its own), Enter or OK opens, Escape or Back closes it when it was opened from the menu.
// A long press, a right click or the context menu key opens a profile's options (rename, PIN, sign
// out); Manage profiles does the same for a touch screen that has no long press. A PIN profile asks
// its PIN every time it is opened here, and before its options; 'lock' (a minute hidden) cannot be
// closed without choosing.
import { createEffect, createSignal, For, onCleanup, onMount, Show } from 'solid-js'
import { render } from 'solid-js/web'
import { Add01Icon, Cancel01Icon, LockKeyholeIcon, Logout05Icon, UserGroupIcon, UserSwitchIcon } from '@hugeicons/core-free-icons'
import * as B from './accountBook'
import { activeId, book, closePicker, closeTopLayer, many, openPicker, photoOf, photosVersion, picker } from '../accounts'
import { addAccount, changePin, forgetVerified, pinOpened, pinsWork, pinVerified, removeAll, removeAllNeeds, removeProfile, renameProfile, signInAgain, signOutProfile, switchingId, switchTo, tryPin } from '../accountFlows'
import { me } from '../session'
import { t } from '../i18n'
import { form } from '../form'
import { askConfirm } from './Confirm'
import { closeModal, holdClose, openModal } from './confirmCore'
import { showToast } from './Toast'
import { Icon } from './Icon'
import { Initial } from './PageBar'
import Keypad from '../tv/Keypad'
import './profiles.css'

/** The name a profile shows: its own, Telegram's for the one in use, else "Profile 2". */
function nameOf(p: B.Profile): string {
    if (p.name) return p.name
    if (p.id === activeId && me()?.name) return me()!.name!
    return t('accounts.unnamed', { number: B.profileNumber(book(), p.id) })
}

type Sheet =
    | { kind: 'open'; id: string }
    | { kind: 'unlock'; id: string }
    | { kind: 'options'; id: string }
    | { kind: 'rename'; id: string }
    | { kind: 'newPin'; id: string }

export function Profiles() {
    const [editing, setEditing] = createSignal(false)
    const [sheet, setSheet] = createSignal<Sheet | null>(null)
    let row!: HTMLUListElement
    const from = document.activeElement as HTMLElement | null
    const tiles = () => [...row.querySelectorAll<HTMLButtonElement>('.profile-tile')]
    let root!: HTMLDialogElement
    // A modal dialog in the browser's top layer: nothing of the page stands above it, and nothing behind
    // it takes the keyboard or the screen reader. Without one (an old browser) the page behind is inert.
    const held: Element[] = []
    onMount(() => {
        closeTopLayer(root)
        // At launch and when locked it cannot be closed without choosing: Escape and the TV's Back do nothing.
        holdClose(root, () => picker() !== 'menu')
        if (!openModal(root))
            for (const el of root.parentElement?.children ?? []) if (el !== root && !el.hasAttribute('inert')) (el.setAttribute('inert', ''), held.push(el))
        queueMicrotask(() => tiles()[0]?.focus({ preventScroll: true }))
    })
    onCleanup(() => {
        held.forEach((el) => el.removeAttribute('inert'))
        try {
            if (root.hasAttribute('open')) closeModal(root)
        } catch {}
    })
    // Back where the viewer was when it closes; a PIN entered for options counts no longer.
    onCleanup(() => {
        forgetVerified()
        try {
            if (from?.isConnected) from.focus({ preventScroll: true })
        } catch {}
    })

    const profileOf = (id: string) => B.find(book(), id)
    // A profile that went (another tab removed it) closes what was open about it.
    createEffect(() => {
        const s = sheet()
        if (s && !profileOf(s.id)) setSheet(null)
    })

    const open = (p: B.Profile) => {
        if (editing() || p.state === 'attention') return options(p)
        // A PIN profile always asks, the one in use too.
        if (p.pin) return setSheet({ kind: 'open', id: p.id })
        void switchTo(p.id)
    }
    const options = (p: B.Profile) => setSheet(p.pin ? { kind: 'unlock', id: p.id } : { kind: 'options', id: p.id })

    const onRowKey = (e: KeyboardEvent) => {
        // The TV's remote moves the focus itself (tv/focus.ts); this is the keyboard's.
        if (form() === 'tv') return
        const all = tiles()
        const at = all.indexOf(document.activeElement as HTMLButtonElement)
        if (at < 0) return
        const rtl = getComputedStyle(row).direction === 'rtl'
        const step = { ArrowRight: rtl ? -1 : 1, ArrowLeft: rtl ? 1 : -1, ArrowDown: 1, ArrowUp: -1 }[e.key]
        let next = step === undefined ? -1 : Math.min(all.length - 1, Math.max(0, at + step))
        if (e.key === 'Home') next = 0
        if (e.key === 'End') next = all.length - 1
        if (next < 0) return
        e.preventDefault()
        all[next].focus()
    }

    const onKey = (e: KeyboardEvent) => {
        if ((e.target as Element).closest?.('dialog:not(.profiles)')) return
        if (e.key === 'Escape' && picker() === 'menu') {
            e.preventDefault()
            closePicker()
        }
    }

    return (
        <dialog
            class="profiles"
            ref={root}
            aria-labelledby="profiles-title"
            onKeyDown={onKey}
            onCancel={(e) => picker() !== 'menu' && e.preventDefault()}
            onClose={() => picker() === 'menu' && closePicker()}
        >
            <Show when={picker() === 'menu'}>
                <button class="round-button profiles-close" type="button" data-picker-close aria-label={t('accounts.close_picker')} title={t('accounts.close_picker')} onClick={closePicker}>
                    <Icon icon={Cancel01Icon} size={22} />
                </button>
            </Show>
            <img class="boot-mark profiles-mark" src="/icon.svg" alt="" width="48" height="48" />
            <h1 id="profiles-title">{t('accounts.title')}</h1>
            <p class="visually-hidden" role="status" aria-live="polite">
                <Show when={switchingId()}>{(id) => { const target = profileOf(id()); return target ? t('profiles.switching', { name: nameOf(target) }) : '' }}</Show>
            </p>
            <Show when={editing()}>
                <p class="muted profiles-hint" role="status">{t('accounts.manage_hint')}</p>
            </Show>
            <ul class="profile-row" ref={row} onKeyDown={onRowKey}>
                <For each={book().profiles}>
                    {(p) => (
                        <li>
                            <ProfileTile profile={p} name={nameOf(p)} editing={editing()} onOpen={() => open(p)} onOptions={() => options(p)} />
                        </li>
                    )}
                </For>
                <Show when={B.canAdd(book())}>
                    <li>
                        <button class="profile-tile profile-add" type="button" onClick={() => void addAccount()}>
                            <span class="profile-avatar" aria-hidden="true">
                                <Icon icon={Add01Icon} size={40} />
                            </span>
                            <span class="profile-name">{t('accounts.add')}</span>
                        </button>
                    </li>
                </Show>
            </ul>
            <Show when={!B.canAdd(book())}>
                <p class="muted small profiles-limit">{t('accounts.limit_line', { count: B.MAX_PROFILES })}</p>
            </Show>
            <button class="outline-button profiles-manage" type="button" aria-pressed={editing()} onClick={() => setEditing(!editing())}>
                {editing() ? t('accounts.manage_done') : t('accounts.manage')}
            </button>
            <Show when={sheet()} keyed>
                {(s) => {
                    const p = profileOf(s.id)
                    if (!p) return null
                    const close = () => setSheet(null)
                    if (s.kind === 'open' || s.kind === 'unlock')
                        return (
                            <PinDialog
                                title={t('accounts.pin_enter', { name: nameOf(p) })}
                                entering
                                onPin={async (pin) => {
                                    const answer = await tryPin(p.id, pin)
                                    if ('locked' in answer) return { locked: answer.locked }
                                    if (!answer.ok) return { error: t('accounts.pin_wrong', { left: answer.left }) }
                                    if (s.kind === 'open') {
                                        // This tab may open it now, and only it.
                                        pinOpened(p.id)
                                        close()
                                        void switchTo(p.id)
                                    } else {
                                        // Its options (rename, PIN, sign out) for as long as the picker is up.
                                        pinVerified(p.id)
                                        setSheet({ kind: 'options', id: p.id })
                                    }
                                    return null
                                }}
                                lockedUntil={B.lockLeft(p, Date.now()) > 0 ? Date.now() + B.lockLeft(p, Date.now()) : 0}
                                onClose={close}
                            />
                        )
                    if (s.kind === 'newPin') return <NewPinDialog name={nameOf(p)} onClose={close} onPin={(pin) => void changePin(p.id, pin).then(() => (close(), showToast({ kind: 'success', text: t('accounts.pin_set_toast') })))} />
                    if (s.kind === 'rename') return <RenameDialog name={nameOf(p)} onClose={close} onSave={(name) => (renameProfile(p.id, name), close())} />
                    return (
                        <OptionsDialog
                            profile={p}
                            name={nameOf(p)}
                            onClose={close}
                            onRename={() => setSheet({ kind: 'rename', id: p.id })}
                            onNewPin={() => setSheet({ kind: 'newPin', id: p.id })}
                        />
                    )
                }}
            </Show>
        </dialog>
    )
}

function ProfileTile(props: { profile: B.Profile; name: string; editing: boolean; onOpen: () => void; onOptions: () => void }) {
    const p = () => props.profile
    const photo = () => (photosVersion(), photoOf(p().id))
    // Read once, as one name: the profile, then what is particular about it.
    const label = () =>
        [
            props.name,
            p().id === activeId && picker() !== 'launch' ? t('accounts.in_use') : '',
            p().state === 'attention' ? t('accounts.needs_attention') : p().state === 'adding' ? t('accounts.adding') : '',
            p().pin ? t('accounts.locked_with_pin') : '',
            props.editing ? t('accounts.options_hint') : '',
        ]
            .filter(Boolean)
            .join(', ')
    return (
        <button
            class="profile-tile"
            classList={{ editing: props.editing, attention: p().state === 'attention' }}
            type="button"
            data-profile={p().id}
            aria-label={label()}
            aria-current={p().id === activeId && picker() !== 'launch' ? 'true' : undefined}
            onClick={props.onOpen}
            onContextMenu={(e) => {
                e.preventDefault()
                e.stopPropagation()
                props.onOptions()
            }}
        >
            <span class="profile-avatar" aria-hidden="true">
                <Show when={photo()} fallback={<Initial name={props.name} class="profile-initial" />}>
                    <img src={photo()!} alt="" width="160" height="160" />
                </Show>
                <Show when={p().pin}>
                    <span class="profile-lock">
                        <Icon icon={LockKeyholeIcon} size={18} />
                    </span>
                </Show>
            </span>
            <span class="profile-name" aria-hidden="true">{props.name}</span>
            {/* The tile that is being opened says so (LS-30); the picker's status line speaks it. */}
            <Show when={switchingId() === p().id}>
                <span class="profile-note" aria-hidden="true">{t('profiles.switching', { name: props.name })}</span>
            </Show>
            <Show when={p().state !== 'ok' && switchingId() !== p().id}>
                <span class="profile-note" classList={{ warn: p().state === 'attention' }} aria-hidden="true">
                    {p().state === 'attention' ? t('accounts.needs_attention') : t('accounts.adding')}
                </span>
            </Show>
        </button>
    )
}

function askSignOut(p: B.Profile, name: string) {
    return askConfirm({
        whileLocked: true,
        title: t('accounts.sign_out_title', { name }),
        message: t('accounts.sign_out_message', { name }),
        confirm: t('settings.sign_out_confirm'),
        busy: t('settings.signing_out'),
        run: () => signOutProfile(p.id),
    })
}

/** A modal dialog of the shared look: open on mount, closed by Escape, Back or the backdrop, focus kept inside by the browser. */
function useDialog(onClose: () => void) {
    let el!: HTMLDialogElement
    let closed = false
    const ref = (d: HTMLDialogElement) => (el = d)
    onMount(() => {
        if (!openModal(el)) el.setAttribute('aria-modal', 'true')
    })
    const close = () => {
        if (closed) return
        closed = true
        onClose()
    }
    // Gone from the page (the next step took its place): closed without telling anyone it was.
    onCleanup(() => {
        closed = true
        try {
            if (el.hasAttribute('open')) closeModal(el)
        } catch {}
    })
    return { ref, close, dialog: () => el }
}

function OptionsDialog(props: { profile: B.Profile; name: string; onClose: () => void; onRename: () => void; onNewPin: () => void }) {
    const d = useDialog(props.onClose)
    const p = props.profile
    let first!: HTMLButtonElement
    onMount(() => first?.focus())
    const then = (fn: () => void) => () => {
        d.close()
        fn()
    }
    return (
        <dialog class="confirm confirm-ask profile-options" ref={d.ref} aria-labelledby="profile-options-title" onClose={d.close} onClick={(e) => e.target === d.dialog() && d.close()}>
            <h2 id="profile-options-title">{props.name}</h2>
            <Show when={p.state === 'attention'}>
                <p class="muted">{t('accounts.needs_attention_detail')}</p>
            </Show>
            <div class="profile-actions">
                <Show when={p.state === 'attention'}>
                    <button class="primary" type="button" ref={first} onClick={then(() => void signInAgain(p.id))}>
                        {t('accounts.sign_in_again')}
                    </button>
                </Show>
                <Show when={p.state !== 'adding'}>
                    <button class="outline-button" type="button" ref={(el) => (first ??= el)} onClick={props.onRename}>
                        {t('accounts.rename')}
                    </button>
                </Show>
                <Show when={p.state === 'ok' && pinsWork()}>
                    <button class="outline-button" type="button" onClick={props.onNewPin}>
                        {p.pin ? t('accounts.pin_change') : t('accounts.pin_set')}
                    </button>
                    <Show when={p.pin}>
                        <button
                            class="outline-button"
                            type="button"
                            onClick={then(() => void changePin(p.id, null).then(() => showToast({ kind: 'success', text: t('accounts.pin_removed_toast') })))}
                        >
                            {t('accounts.pin_remove')}
                        </button>
                    </Show>
                </Show>
                <Show
                    when={p.state === 'ok'}
                    fallback={
                        <button
                            class="danger-button"
                            type="button"
                            ref={(el) => (first ??= el)}
                            onClick={then(
                                () =>
                                    void askConfirm({
                                        whileLocked: true,
                                        title: t('accounts.remove_title', { name: props.name }),
                                        message: t('accounts.remove_message', { name: props.name }),
                                        confirm: t('common.remove'),
                                        run: () => removeProfile(p.id),
                                    }),
                            )}
                        >
                            <Icon icon={Logout05Icon} size={20} />
                            {t('accounts.remove')}
                        </button>
                    }
                >
                    <button class="danger-button" type="button" onClick={then(() => void askSignOut(p, props.name))}>
                        <Icon icon={Logout05Icon} size={20} />
                        {t('accounts.sign_out')}
                    </button>
                </Show>
            </div>
            <div class="confirm-buttons">
                <button class="link" type="button" onClick={() => d.close()}>
                    {t('common.cancel')}
                </button>
            </div>
        </dialog>
    )
}

function RenameDialog(props: { name: string; onClose: () => void; onSave: (name: string) => void }) {
    const d = useDialog(props.onClose)
    const [name, setName] = createSignal(props.name)
    let input!: HTMLInputElement
    onMount(() => {
        input.focus()
        input.select()
    })
    const save = (e: Event) => {
        e.preventDefault()
        if (B.cleanName(name())) props.onSave(name())
    }
    return (
        <dialog class="confirm confirm-ask" ref={d.ref} aria-labelledby="profile-rename-title" onClose={d.close} onClick={(e) => e.target === d.dialog() && d.close()}>
            <h2 id="profile-rename-title">{t('accounts.rename')}</h2>
            <form class="profile-rename" onSubmit={save}>
                <input ref={input} aria-label={t('accounts.rename_label')} maxLength={B.NAME_MAX} value={name()} onInput={(e) => setName(e.currentTarget.value)} />
                <div class="confirm-buttons">
                    <button class="link" type="button" onClick={() => d.close()}>
                        {t('common.cancel')}
                    </button>
                    <button class="primary" type="submit" disabled={!B.cleanName(name())}>
                        {t('accounts.rename_save')}
                    </button>
                </div>
            </form>
        </dialog>
    )
}

type PinReply = { error: string } | { locked: number } | null

/** Four digits by the pad, the keyboard or the remote's number keys; the dots say how many, never which. */
function PinDialog(props: { title: string; onPin: (pin: string) => Promise<PinReply>; lockedUntil: number; onClose: () => void; note?: string; entering?: boolean }) {
    const d = useDialog(props.onClose)
    const [digits, setDigits] = createSignal('')
    const [error, setError] = createSignal<string | null>(null)
    const [until, setUntil] = createSignal(props.lockedUntil)
    const [now, setNow] = createSignal(Date.now())
    const [busy, setBusy] = createSignal(false)
    const tick = window.setInterval(() => setNow(Date.now()), 500)
    onCleanup(() => clearInterval(tick))
    const left = () => Math.max(0, Math.ceil((until() - now()) / 1000))
    const locked = () => left() > 0
    const line = () => (locked() ? t('accounts.locked', { seconds: left() }) : error())

    const digit = (n: string) => {
        if (locked() || busy() || digits().length >= 4) return
        const next = digits() + n
        setDigits(next)
        setError(null)
        if (next.length === 4) void submit(next)
    }
    const erase = () => !busy() && setDigits(digits().slice(0, -1))
    const submit = async (pin: string) => {
        setBusy(true)
        try {
            const reply = await props.onPin(pin)
            if (reply && 'locked' in reply) setUntil(reply.locked)
            if (reply && 'error' in reply) setError(reply.error)
        } finally {
            setBusy(false)
            setDigits('')
        }
    }
    const onKey = (e: KeyboardEvent) => {
        if (/^[0-9]$/.test(e.key)) {
            e.preventDefault()
            digit(e.key)
        } else if (e.key === 'Backspace') {
            e.preventDefault()
            erase()
        }
    }
    return (
        <dialog class="confirm confirm-ask pin-dialog" ref={d.ref} aria-labelledby="pin-title" aria-describedby="pin-honest" onClose={d.close} onKeyDown={onKey}>
            <h2 id="pin-title">{props.title}</h2>
            <div class="pin-dots" role="img" aria-label={t('accounts.pin_typed', { count: digits().length })}>
                <For each={[0, 1, 2, 3]}>{(i) => <span classList={{ filled: digits().length > i }} />}</For>
            </div>
            <p class="pin-line" classList={{ error: !!line() }} role="status" aria-live="polite">
                {line() ?? props.note ?? ''}
            </p>
            <div class="pin-pad" classList={{ held: locked() }}>
                <Keypad label={t('accounts.pin_digits')} onDigit={digit} onDelete={erase} />
            </div>
            <p class="muted small" id="pin-honest">{t('accounts.pin_honest')}</p>
            <Show when={!props.entering}>
                <p class="muted small pin-relock">{t('accounts.pin_relock')}</p>
            </Show>
            <Show when={props.entering}>
                <p class="muted small pin-forgot">{t('accounts.pin_forgot')}</p>
            </Show>
            <div class="confirm-buttons">
                <button class="link" type="button" onClick={() => d.close()}>
                    {t('common.cancel')}
                </button>
            </div>
        </dialog>
    )
}

function NewPinDialog(props: { name: string; onPin: (pin: string) => void; onClose: () => void }) {
    const [first, setFirst] = createSignal<string | null>(null)
    const [note, setNote] = createSignal<string | undefined>(undefined)
    return (
        <Show when={first() === null ? 'one' : 'two'} keyed>
            {(phase) => (
                <PinDialog
                    title={phase === 'one' ? t('accounts.pin_new', { name: props.name }) : t('accounts.pin_repeat')}
                    note={phase === 'one' ? note() : undefined}
                    lockedUntil={0}
                    onClose={props.onClose}
                    onPin={async (pin) => {
                        if (phase === 'one') {
                            setNote(undefined)
                            setFirst(pin)
                            return null
                        }
                        if (pin !== first()) {
                            setNote(t('accounts.pin_mismatch'))
                            setFirst(null)
                            return null
                        }
                        props.onPin(pin)
                        return null
                    }}
                />
            )}
        </Show>
    )
}

/** Settings' profile menu: Switch profile with two or more, Add account with one. */
export function ProfileMenuButton() {
    const openIt = () => (many() ? openPicker() : void addAccount())
    return (
        <button class="outline-button set-profiles" type="button" onClick={openIt}>
            <Icon icon={many() ? UserSwitchIcon : UserGroupIcon} size={20} />
            {many() ? t('accounts.switch') : t('accounts.add')}
        </button>
    )
}

/** Asks one profile's PIN on a pad of its own over the page; true once it was right, false for Cancel. */
function askPin(p: B.Profile, name: string): Promise<boolean> {
    return new Promise((resolve) => {
        let dispose = () => {}
        let answered = false
        // A host of its own: disposing a root empties the element it was drawn into.
        const host = document.body.appendChild(document.createElement('div'))
        const done = (yes: boolean) => {
            if (answered) return
            answered = true
            queueMicrotask(() => (dispose(), host.remove()))
            resolve(yes)
        }
        dispose = render(
            () => (
                <PinDialog
                    title={t('accounts.pin_enter', { name })}
                    entering
                    lockedUntil={B.lockLeft(p, Date.now()) > 0 ? Date.now() + B.lockLeft(p, Date.now()) : 0}
                    onClose={() => done(false)}
                    onPin={async (pin) => {
                        const answer = await tryPin(p.id, pin)
                        if ('locked' in answer) return { locked: answer.locked }
                        if (!answer.ok) return { error: t('accounts.pin_wrong', { left: answer.left }) }
                        pinVerified(p.id)
                        done(true)
                        return null
                    }}
                />
            ),
            host,
        )
    })
}

/** Remove all accounts: only where there is more than one. Every locked profile's PIN first: one left out and nothing is wiped. */
export function RemoveAllButton() {
    const start = async () => {
        try {
            for (const p of removeAllNeeds()) if (!(await askPin(p, p.name || t('accounts.unnamed', { number: B.profileNumber(book(), p.id) })))) return
            await removeAll()
        } finally {
            // Cancelled or done: the PINs entered for it count for nothing after.
            forgetVerified()
        }
    }
    return (
        <Show when={many()}>
            <button class="danger-button set-signout set-remove-all" type="button" onClick={() => void start()}>
                <Icon icon={Logout05Icon} size={20} />
                <span class="set-text">
                    <span>{t('accounts.remove_all')}</span>
                </span>
            </button>
        </Show>
    )
}
