// One question before something that cannot be undone (HX59): a modal `<dialog>` that says what goes,
// what stays, and puts focus on Cancel, so a stray Enter (or a TV remote's OK) keeps everything.
// Escape and the TV's Back close it as Cancel (the browser does Escape, tv/focus.ts the remote's
// Back), and so does a press on the backdrop. Only one is ever open: a second ask while it is up
// gets the first one's answer. The page keeps no host for it: it is drawn into the body on the first
// ask and its markup comes out again when the answer is in.
import { createEffect, createSignal, onMount, Show } from 'solid-js'
import { render } from 'solid-js/web'
import { t } from '../i18n'
import './confirm.css'

export interface ConfirmOptions {
    title: string
    /** What goes. */
    message: string
    /** What stays (one line, muted). */
    detail?: string
    /** The button that does it. */
    confirm: string
    /** Its words while `run` works. */
    busy?: string
    /** Work done behind the button, with the buttons held until it ends; a failure counts as done. */
    run?: () => unknown
    /** False for a question that is not about losing anything; the button is not red then. */
    danger?: boolean
    /** Closes the question as Cancel when this turns true (the list it is about emptied, went). */
    closeWhen?: () => boolean
}

interface Pending {
    options: ConfirmOptions
    answer: Promise<boolean>
    settle: (yes: boolean) => void
}

const [pending, setPending] = createSignal<Pending | null>(null)
const [working, setWorking] = createSignal(false)
let drawn = false

/** True while a question is on screen (a page's own keys step aside for it). */
export const confirmOpen = () => pending() !== null

/** Asks; resolves true when the viewer confirmed (and `run` has ended), false for anything else. */
export function askConfirm(options: ConfirmOptions): Promise<boolean> {
    const open = pending()
    if (open) return open.answer
    if (!drawn) {
        drawn = true
        render(() => <ConfirmDialog />, document.body)
    }
    let settle!: (yes: boolean) => void
    const answer = new Promise<boolean>((resolve) => (settle = resolve))
    setWorking(false)
    setPending({ options, answer, settle })
    return answer
}

function ConfirmDialog() {
    return <Show when={pending()} keyed>{(p) => <Question p={p} />}</Show>
}

function Question(props: { p: Pending }) {
    const o = props.p.options
    let dialog!: HTMLDialogElement
    let cancel!: HTMLButtonElement
    let yes = false
    onMount(() => {
        dialog.showModal()
        // Cancel first, on every form: a key press or the remote's OK on the open dialog keeps everything.
        cancel.focus()
    })
    createEffect(() => {
        if (o.closeWhen?.() && dialog.open) dialog.close()
    })
    const confirm = async () => {
        if (working()) return
        if (o.run) {
            setWorking(true)
            try {
                await o.run()
            } catch {}
        }
        yes = true
        if (dialog.open) dialog.close()
        else finish()
    }
    let done = false
    const finish = () => {
        if (done) return
        done = true
        setPending(null)
        props.p.settle(yes)
    }
    return (
        <dialog
            class="confirm confirm-ask"
            ref={dialog}
            aria-labelledby="confirm-title"
            aria-describedby="confirm-message"
            // Escape while the work runs is held, so the question cannot close on it half way.
            onCancel={(e) => working() && e.preventDefault()}
            onClose={finish}
            onClick={(e) => e.target === dialog && !working() && dialog.close()}
        >
            <h2 id="confirm-title">{o.title}</h2>
            <p id="confirm-message">{o.message}</p>
            <Show when={o.detail}>
                <p class="muted">{o.detail}</p>
            </Show>
            <div class="confirm-buttons">
                <button class="link" type="button" ref={cancel} disabled={working()} onClick={() => dialog.close()}>
                    {t('common.cancel')}
                </button>
                <button class={o.danger === false ? 'primary' : 'primary danger'} type="button" disabled={working()} onClick={confirm}>
                    {working() && o.busy ? o.busy : o.confirm}
                </button>
            </div>
        </dialog>
    )
}
