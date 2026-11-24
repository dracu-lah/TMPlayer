// "Report a problem" and "Request a feature" written in the page (WB11, HX64): one dialog on every
// form (a full screen sheet on a phone, a window on the desktop, a form with a QR code beside it on
// the TV), with the steps the apps have: the form (kind, message with a counter, an optional way to
// reply), a confirm step that lists word for word what is sent, then the code, Kept for later with
// Try again, or Not sent with Edit. The logic is in ui/feedback.ts; this file is the screen.
//
// The page sends no logs and no account. What is typed is kept on the device 1.5 s after the last
// change, again on closing, and goes when the report is filed. GitHub and e-mail stay as the ways
// round (with the typed words in them), on every step that can fail. Where the build has no inbox
// address the form is not offered, and on the TV only the QR code and the e-mail line are shown.
//
// Drawn into the body on the first ask, as Confirm.tsx is, so a page needs no host for it.
import { createEffect, createMemo, createSignal, For, Match, onCleanup, onMount, Show, Switch, untrack } from 'solid-js'
import { render } from 'solid-js/web'
import { renderSVG } from 'uqr'
import { t } from '../i18n'
import { installed } from '../install'
import { form } from '../form'
import { sizeText } from '../logs'
import { showToast } from './Toast'
import { closeModal, holdClose, isOpen, openModal } from './confirmCore'
import {
    EMAIL,
    KEEP_DAYS,
    MAX_CONTACT,
    MAX_MESSAGE,
    blankDraft,
    cleanLine,
    cut,
    copyText,
    edited,
    featureUrl,
    inboxUrl,
    issueUrl,
    localDraftStore,
    logsFor,
    mailto,
    makeReport,
    messageLength,
    poster,
    problem,
    reviewLines,
    saver,
    send,
    setFormOpen,
    webFacts,
    type Draft,
    type Facts,
    type Kind,
    type Problem,
    type Step,
} from './feedback'
import './feedback.css'

type View =
    | { step: 'editing'; problem: Problem | null }
    | { step: 'review' }
    | { step: 'sending' }
    | { step: 'sent'; code: string }
    | { step: 'queued'; wait: number }
    | { step: 'refused'; status: number }
    /** No inbox in this build: only the other ways (the TV's QR code and the e-mail line). */
    | { step: 'ways' }

interface Open {
    kind: Kind
    opener: HTMLElement | null
    /** True where there is no inbox: the dialog is only the ways round. */
    waysOnly: boolean
}

const [open, setOpen] = createSignal<Open | null>(null)
let drawn = false

/** The facts the report carries: the browser, the language, nothing else. */
export const feedbackFacts = (): Facts => webFacts(navigator.userAgent, document.documentElement.lang || navigator.language || 'en', installed())

/** True while the dialog is on screen (a page's own keys step aside for it). */
export const feedbackOpen = () => open() !== null

/**
 * Opens the form for [kind] over the draft kept on this device. With no inbox in the build the
 * dialog shows only the ways round (the QR code for the TV, which cannot open a second window).
 */
export function openFeedback(kind: Kind, opener: HTMLElement | null = null) {
    if (open()) return
    if (!drawn) {
        drawn = true
        render(() => <FeedbackDialog />, document.body)
    }
    setOpen({ kind, opener, waysOnly: inboxUrl() === null })
}

function FeedbackDialog() {
    return <Show when={open()} keyed>{(o) => <Window o={o} />}</Show>
}

const MESSAGE_ID = 'fb-message'
const CONTACT_ID = 'fb-contact'

function Window(props: { o: Open }) {
    const store = localDraftStore()
    const keep = saver(store)
    const facts = feedbackFacts()
    const url = inboxUrl()
    const post = url ? poster(url) : null
    let dialog!: HTMLDialogElement
    let message: HTMLTextAreaElement | undefined
    let contact: HTMLInputElement | undefined
    let primary: HTMLButtonElement | undefined
    const stored = props.o.waysOnly ? null : store.read()
    // A stored draft with nothing typed takes the kind asked for; one with words in it is the viewer's to finish.
    const start: Draft = !stored ? blankDraft(props.o.kind) : stored.message.trim() === '' && stored.pending === null ? { ...stored, kind: props.o.kind } : stored
    const [draft, setDraft] = createSignal<Draft>(start)
    const [view, setView] = createSignal<View>(props.o.waysOnly ? { step: 'ways' } : start.pending !== null ? { step: 'queued', wait: 0 } : { step: 'editing', problem: null })
    const [native, setNative] = createSignal(true)
    const [busy, setBusy] = createSignal(false)
    const tv = () => form() === 'tv'
    const kind = () => (props.o.waysOnly ? props.o.kind : draft().kind)
    // A memo, so the focus and the branches move only when the step does, not on each fix of a red line.
    const step = createMemo(() => view().step)
    const shown = createMemo(() => {
        const r = makeReport(draft().id, draft().kind, draft().message, draft().contact, facts)
        return r ? reviewLines(r) : null
    })
    /** The problem the last press of Next found, until the viewer types a fix. */
    const flagged = () => {
        const v = view()
        return v.step === 'editing' ? v.problem : null
    }
    const wait = () => {
        const v = view()
        return v.step === 'queued' ? v.wait : 0
    }
    const refusedStatus = () => {
        const v = view()
        return v.step === 'refused' ? v.status : 0
    }
    const code = () => {
        const v = view()
        return v.step === 'sent' ? v.code : ''
    }
    const messageBad = () => flagged() === 'empty' || flagged() === 'too_long' || messageLength(draft().message) > MAX_MESSAGE
    const contactBad = () => cleanLine(draft().contact).length > MAX_CONTACT

    const change = (next: Partial<Draft>) => {
        const was = draft()
        const now = edited(was, { ...was, ...next })
        setDraft(now)
        keep.change(now)
        // A typed fix takes the red off at once; the next press of Next checks again.
        if (flagged()) setView({ step: 'editing', problem: problem(now.message, now.contact) })
    }

    const finish = () => {
        // What is typed stays on this device; a filed report left nothing to keep.
        if (step() === 'sent') keep.cancel()
        else keep.now()
        setFormOpen(false)
        const was = props.o
        setOpen(null)
        queueMicrotask(() => was.opener?.isConnected && was.opener.focus({ preventScroll: true }))
    }
    const hide = () => {
        if (document.visibilityState === 'hidden') keep.now()
    }

    onMount(() => {
        setFormOpen(true)
        holdClose(dialog, busy)
        setNative(openModal(dialog))
        if (!isOpen(dialog)) finish()
        document.addEventListener('visibilitychange', hide)
        addEventListener('pagehide', keep.now)
    })
    onCleanup(() => {
        document.removeEventListener('visibilitychange', hide)
        removeEventListener('pagehide', keep.now)
    })

    // The focus goes where the step starts: the message while typing, the safe button elsewhere.
    createEffect(() => {
        const at = step()
        queueMicrotask(() => {
            try {
                if (at === 'editing') message?.focus({ preventScroll: true })
                else primary?.focus({ preventScroll: true })
            } catch {}
        })
    })

    const next = () => {
        const p = problem(draft().message, draft().contact)
        if (p) {
            setView({ step: 'editing', problem: p })
            queueMicrotask(() => (p === 'contact_too_long' ? contact : message)?.focus({ preventScroll: true }))
            return
        }
        keep.now()
        setView({ step: 'review' })
    }

    const submit = async () => {
        // A second press while it goes does nothing: one draft, one id, one report.
        if (busy() || !post) return
        setBusy(true)
        setView({ step: 'sending' })
        keep.cancel()
        const done: Step = await send(draft(), facts, post, store)
        setBusy(false)
        if (done.step === 'sent') setView({ step: 'sent', code: done.code })
        else if (done.step === 'queued') {
            setDraft(done.draft)
            setView({ step: 'queued', wait: done.wait })
        } else if (done.step === 'refused') {
            setDraft(done.draft)
            setView({ step: 'refused', status: done.status })
        } else setView({ step: 'editing', problem: done.problem })
    }

    /** To the form; changing a draft that was tried gives it a new id (feedback.edited). */
    const back = () => {
        keep.now()
        setView({ step: 'editing', problem: null })
    }

    const title = () => {
        const s = step()
        if (s === 'review' || s === 'sending') return t('feedback.review_title')
        if (s === 'sent') return t('feedback.sent_title')
        if (s === 'queued') return t('feedback.queued_title')
        if (s === 'refused') return t('feedback.refused_title')
        return kind() === 'feature' ? t('feedback.feature_title') : t('feedback.title')
    }
    const countLine = () => {
        const count = messageLength(draft().message)
        if (flagged() === 'empty') return t('feedback.message_empty')
        if (count > MAX_MESSAGE) return t('feedback.message_too_long', { count, max: MAX_MESSAGE })
        return t('feedback.message_count', { count, max: MAX_MESSAGE })
    }

    return (
        <dialog
            classList={{ 'confirm confirm-ask feedback': true, 'confirm-fallback': !native(), 'feedback-tv': tv() }}
            ref={dialog}
            aria-labelledby="fb-title"
            aria-busy={busy()}
            // Escape while it goes is held, so the window cannot close on a report half way.
            onCancel={(e) => busy() && e.preventDefault()}
            onClose={finish}
            onKeyDown={(e) => {
                if (native()) return
                if (e.key === 'Escape') return void closeModal(dialog)
                if (e.key !== 'Tab') return
                const items = [...dialog.querySelectorAll<HTMLElement>('button, a[href], input, textarea')].filter((b) => !(b as HTMLButtonElement).disabled)
                if (!items.length) return
                const at = items.indexOf(document.activeElement as HTMLElement)
                const target = items[(at + (e.shiftKey ? items.length - 1 : 1)) % items.length]
                e.preventDefault()
                target.focus()
            }}
        >
            <h2 id="fb-title">{title()}</h2>
            <div class="fb-layout">
                <div class="fb-main">
                    <Switch>
                        <Match when={step() === 'editing'}>
                            <p class="muted fb-intro">{t('feedback.form_body')}</p>
                            <div class="segmented fb-kind" role="radiogroup" aria-label={t('feedback.kind_label')}>
                                <For each={['problem', 'feature'] as const}>
                                    {(k) => (
                                        <label classList={{ on: draft().kind === k }}>
                                            <input type="radio" name="fb-kind" checked={draft().kind === k} onChange={() => change({ kind: k })} />
                                            {k === 'problem' ? t('feedback.kind_problem') : t('feedback.kind_feature')}
                                        </label>
                                    )}
                                </For>
                            </div>
                            <div class="fb-field">
                                <label for={MESSAGE_ID}>{draft().kind === 'feature' ? t('feedback.message_feature') : t('feedback.message_problem')}</label>
                                <textarea
                                    id={MESSAGE_ID}
                                    ref={message}
                                    rows="6"
                                    value={untrack(() => draft().message)}
                                    autocapitalize="sentences"
                                    aria-invalid={messageBad()}
                                    aria-describedby="fb-count"
                                    onInput={(e) => change({ message: cut(e.currentTarget.value, MAX_MESSAGE * 2) })}
                                />
                                <p id="fb-count" classList={{ small: true, muted: !messageBad(), error: messageBad() }} role={messageBad() ? 'alert' : undefined}>
                                    {countLine()}
                                </p>
                            </div>
                            <div class="fb-field">
                                <label for={CONTACT_ID}>{t('feedback.contact_label')}</label>
                                <input
                                    id={CONTACT_ID}
                                    ref={contact}
                                    type="text"
                                    value={untrack(() => draft().contact)}
                                    autocomplete="off"
                                    autocapitalize="off"
                                    spellcheck={false}
                                    inputmode="email"
                                    enterkeyhint="done"
                                    aria-invalid={contactBad()}
                                    aria-describedby="fb-contact-hint"
                                    onInput={(e) => change({ contact: cut(e.currentTarget.value, MAX_CONTACT * 2) })}
                                />
                                <p id="fb-contact-hint" classList={{ small: true, muted: !contactBad(), error: contactBad() }}>
                                    {contactBad() ? t('feedback.contact_too_long', { max: MAX_CONTACT }) : t('feedback.contact_hint')}
                                </p>
                            </div>
                        </Match>
                        <Match when={step() === 'review'}>
                            <p class="muted">{t('feedback.review_body')}</p>
                            <Show when={shown()}>
                                {(l) => (
                                    <dl class="fb-review">
                                        <Line label={t('feedback.kind_label')} value={l().kind === 'feature' ? t('feedback.kind_feature') : t('feedback.kind_problem')} />
                                        <Line label={t('feedback.message_label')} value={l().message} long />
                                        <Line label={t('feedback.contact_label')} value={l().contact ?? t('feedback.contact_none')} />
                                        <Line label={t('feedback.about_device')} value={l().about} />
                                        <Line label={t('feedback.logs_label')} value={t('feedback.logs_none')} />
                                    </dl>
                                )}
                            </Show>
                        </Match>
                        <Match when={step() === 'sending'}>
                            <p class="fb-busy" role="status">
                                <span class="fb-spinner" aria-hidden="true" />
                                {t('feedback.sending')}
                            </p>
                        </Match>
                        <Match when={step() === 'sent'}>
                            <p class="fb-code" data-testid="fb-code" role="status" dir="ltr">{code()}</p>
                            <p class="muted">{t('feedback.sent_body', { days: KEEP_DAYS })}</p>
                        </Match>
                        <Match when={step() === 'queued'}>
                            <p>{wait() > 0 ? t('feedback.queued_slow') : t('feedback.queued_body')}</p>
                        </Match>
                        <Match when={step() === 'refused'}>
                            <p>{t('feedback.refused_body', { status: refusedStatus() })}</p>
                        </Match>
                        <Match when={step() === 'ways'}>
                            <p class="muted">{kind() === 'feature' ? t('feedback.feature_body_tv') : t('feedback.body_tv')}</p>
                            <p class="muted small">{t('feedback.email_detail_tv', { email: EMAIL, version: facts.version })}</p>
                        </Match>
                    </Switch>
                    <Show when={step() === 'editing' || step() === 'queued' || step() === 'refused'}>
                        <Ways facts={facts} kind={kind()} message={draft().message} tv={tv()} />
                    </Show>
                </div>
                <Show when={tv() && (step() === 'editing' || step() === 'ways')}>
                    <Qr kind={kind()} facts={facts} />
                </Show>
            </div>
            <div class="confirm-buttons">
                <Switch>
                    <Match when={step() === 'editing'}>
                        <button class="link" type="button" onClick={() => dialog.close()}>{t('common.cancel')}</button>
                        <button class="primary" type="button" onClick={next}>{t('feedback.next')}</button>
                    </Match>
                    <Match when={step() === 'review'}>
                        <button class="link" type="button" ref={primary} onClick={back}>{t('common.back')}</button>
                        <button class="primary" type="button" disabled={busy()} onClick={() => void submit()}>{t('feedback.send')}</button>
                    </Match>
                    <Match when={step() === 'sending'}>
                        <button class="link" type="button" disabled>{t('common.cancel')}</button>
                        <button class="primary" type="button" disabled>{t('feedback.sending')}</button>
                    </Match>
                    <Match when={step() === 'sent' || step() === 'ways'}>
                        <button class="primary" type="button" ref={primary} onClick={() => dialog.close()}>{t('common.close')}</button>
                    </Match>
                    <Match when={step() === 'queued'}>
                        <button class="link" type="button" onClick={back}>{t('feedback.edit')}</button>
                        <button class="link" type="button" onClick={() => dialog.close()}>{t('common.close')}</button>
                        <button class="primary" type="button" ref={primary} disabled={busy()} onClick={() => void submit()}>{t('common.try_again')}</button>
                    </Match>
                    <Match when={step() === 'refused'}>
                        <button class="link" type="button" onClick={() => dialog.close()}>{t('common.close')}</button>
                        <button class="primary" type="button" ref={primary} onClick={back}>{t('feedback.edit')}</button>
                    </Match>
                </Switch>
            </div>
        </dialog>
    )
}

function Line(props: { label: string; value: string; long?: boolean }) {
    return (
        <div class="fb-line">
            <dt>{props.label}</dt>
            <dd classList={{ 'fb-long': props.long }}>{props.value}</dd>
        </div>
    )
}

/**
 * The ways round: GitHub's prefilled page and an e-mail, each with the typed words in it, and Copy
 * details. Real links, so a blocked pop up still leaves the address to press. The TV has no second
 * window: it shows the e-mail line instead, and the QR code is beside the form.
 */
function Ways(props: { facts: Facts; kind: Kind; message: string; tv: boolean }) {
    const github = () => (props.kind === 'feature' ? featureUrl(props.message) : issueUrl(props.facts, props.message))
    // The page's own log, scrubbed, goes with Copy details when it has something to say (WB18): the viewer pastes it
    // into the issue or the mail. The line under the button says what it weighs; nothing is sent from here.
    const logs = props.kind === 'problem' ? logsFor(props.facts) : null
    const copy = async () => {
        try {
            await navigator.clipboard.writeText(copyText(props.facts, props.kind === 'problem'))
            showToast({ kind: 'success', text: t('player.details_copied') })
        } catch {
            // No clipboard, or the browser refused it: it says so, and the facts are in the e-mail and the issue anyway.
            showToast({ kind: 'error', text: t('web.copy_refused') })
        }
    }
    return (
        <div class="fb-ways">
            <h3 class="fb-ways-title">{t('feedback.other_ways')}</h3>
            <Show when={!props.tv} fallback={<p class="muted small">{t('feedback.email_detail_tv', { email: EMAIL, version: props.facts.version })}</p>}>
                <div class="fb-ways-row">
                    <a class="link" href={github()} target="_blank" rel="noopener">{t('feedback.open_github')}</a>
                    <a class="link" href={mailto(props.facts, props.kind, props.message)}>{t('feedback.email')}</a>
                    <Show when={typeof navigator !== 'undefined' && !!navigator.clipboard?.writeText}>
                        <button class="link" type="button" onClick={() => void copy()}>{t('player.copy_details')}</button>
                    </Show>
                </div>
                <p class="muted small">{t('feedback.email_detail', { email: EMAIL })}</p>
                <Show when={logs && typeof navigator !== 'undefined' && !!navigator.clipboard?.writeText}>
                    <p class="muted small">{t('feedback.logs_size', { size: sizeText(logs!.bytes) })}</p>
                </Show>
            </Show>
        </div>
    )
}

/** The TV's way to write it on a phone: a QR code of the prefilled page. */
function Qr(props: { kind: Kind; facts: Facts }) {
    const svg = createMemo(() => renderSVG(props.kind === 'feature' ? featureUrl() : issueUrl(props.facts), { pixelSize: 6, border: 2, whiteColor: '#fff', blackColor: '#0e0f13' }))
    return (
        <div class="fb-qr">
            <div class="fb-qr-code" role="img" aria-label={props.kind === 'feature' ? t('feedback.feature_qr') : t('feedback.qr')} innerHTML={svg()} />
            <p class="muted small">{t('feedback.tv_phone_hint')}</p>
        </div>
    )
}
