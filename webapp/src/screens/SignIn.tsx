import { onGlobal } from '../ui/globalInput'
import { batch, createMemo, createSignal, lazy, Match, onCleanup, onMount, Show, Suspense, Switch } from 'solid-js'
import { renderSVG } from 'uqr'
import { ArrowDown01Icon } from '@hugeicons/core-free-icons'
import { api, call } from '../tg/rpc'
import { sessionEnded, signedIn } from '../session'
import { many, openPicker } from '../accounts'
import { addingHere, cancelAdding } from '../accountFlows'
import { form } from '../form'
import { clock, t } from '../i18n'
import { failure } from '../errors'
import { Icon } from '../ui/Icon'
import * as phoneRules from './phone'
import { canResend, dialChip } from './phone'
import { CountryPicker } from './signin/CountryPicker'
import { PasswordField, Problem, SignInFooter, StepHead, StepLinks } from './signin/Parts'
import { QrStep, type QrView } from './signin/QrStep'
import { codeBlurb, resendLabel, typedAsDigits } from './signin/words'
import type { Country, Me, QrEvent, SentCode } from '../tg/types'

type Step =
    | { kind: 'qr' }
    | { kind: 'phone' }
    /** A code from the app, a text, a call, or an email (`email`, `email_setup`). */
    | { kind: 'code'; sent: SentCode }
    /** An account Telegram signs in by email, with no address set yet. */
    | { kind: 'email'; hash: string }
    | { kind: 'password'; hint: string | null }

/** The TV's number pad (session 21): fetched with the TV layout only. */
const Keypad = lazy(() => import('../tv/Keypad'))

/**
 * Sign in, as the app does it: on a phone the number first (the QR code needs a second device),
 * on a wider screen the QR code first; either way the other is one press away. Then the code,
 * an email address and code where Telegram asks for one, and the two-step password.
 */
export function SignIn() {
    // A phone starts with the number (the QR code needs a second device), a computer or a TV with the QR code.
    // A TV types with its pad: no field takes the remote's keyboard.
    const tv = form() === 'tv'
    const wide = form() !== 'phone'
    const first = (): Step => (wide ? { kind: 'qr' } : { kind: 'phone' })
    const [step, setStep] = createSignal<Step>(first())
    const [error, setError] = createSignal<string | null>(null)
    const [busy, setBusy] = createSignal(false)
    // Telegram's flood wait: every send stays off until then.
    const [waitUntil, setWaitUntil] = createSignal(0)
    const [now, setNow] = createSignal(Date.now())
    const ticker = setInterval(() => setNow(Date.now()), 1000)
    onCleanup(() => clearInterval(ticker))
    const waitLeft = () => Math.max(0, Math.ceil((waitUntil() - now()) / 1000))

    const fail = (e: unknown) => {
        const f = failure(e)
        if (!f) return
        setError(f.text)
        if (f.wait) {
            setWaitUntil(Date.now() + f.wait * 1000)
            setNow(Date.now())
        }
    }

    const go = (s: Step) =>
        batch(() => {
            setError(null)
            setStep(s)
        })

    // The shell sees the user and leaves this page for the one asked for.
    const done = (user: Me) => signedIn(user)

    // ---- QR code ----
    const [qr, setQr] = createSignal<string | null>(null)
    const [qrExpires, setQrExpires] = createSignal(0)
    const [scanned, setScanned] = createSignal(false)
    // When the code on screen arrived, so the bar under it drains over its own life (the apps' QrLifeCue).
    const [qrArrived, setQrArrived] = createSignal(0)
    // The refresh pill spins for a few seconds after a press, so it shows before Telegram answers.
    const [refreshing, setRefreshing] = createSignal(false)
    let qrAbort: AbortController | null = null
    // The QR flow asks for the two-step password through this, resolved when the form is sent.
    let passwordWaiter: ((pw: string) => void) | null = null
    // mtcute fetches a new code by itself when one runs out; one still showing well after that
    // means the connection is not answering, and the viewer asks for a new one.
    const qrExpired = () => qrExpires() > 0 && now() > qrExpires() + 5000
    const qrLeft = () => (qrExpires() > 0 ? qrExpires() - now() : 0)
    const qrLife = () => Math.max(1, qrExpires() - qrArrived())
    const qrFraction = () => (qr() ? Math.min(1, Math.max(0, qrLeft() / qrLife())) : 0)
    // The last seconds of a code, and the moment between it and the next one: said, so a code that
    // changes under the camera is expected.
    const qrSoon = () => !!qr() && qrExpires() > 0 && qrLeft() <= 8000
    const refreshQr = () => {
        setRefreshing(true)
        setTimeout(() => setRefreshing(false), 4000)
        startQr()
    }

    const startQr = () => {
        qrAbort?.abort()
        passwordWaiter = null
        const abort = new AbortController()
        qrAbort = abort
        batch(() => {
            setQr(null)
            setQrExpires(0)
            setScanned(false)
            setError(null)
        })
        call('signInQr', [], {
            signal: abort.signal,
            onEvent: (data) => {
                const e = data as QrEvent
                if (e.kind === 'url') {
                    batch(() => {
                        setQr(renderSVG(e.url, { pixelSize: 6, border: 2, whiteColor: '#fff', blackColor: '#0e0f13' }))
                        setQrExpires(e.expires)
                        setQrArrived(Date.now())
                        setScanned(false)
                    })
                } else if (e.kind === 'scanned') setScanned(true)
                else if (e.kind === 'password') {
                    // Asked again after a wrong password: the step and its message stay.
                    if (step().kind !== 'password') go({ kind: 'password', hint: e.hint })
                    setBusy(false)
                    return new Promise<string>((resolve) => (passwordWaiter = resolve))
                } else if (e.kind === 'badPassword') {
                    setBusy(false)
                    setError(t('errors.wrong_password'))
                }
            },
        })
            .then(done)
            .catch((e) => {
                if (abort.signal.aborted) return
                setBusy(false)
                fail(e)
            })
    }

    const stopQr = () => {
        qrAbort?.abort()
        qrAbort = null
        passwordWaiter = null
    }
    onCleanup(stopQr)

    // ---- Phone number ----
    const [countries, setCountries] = createSignal<Country[]>([])
    const [guessing, setGuessing] = createSignal(true)
    const [dial, setDial] = createSignal('')
    const [nat, setNat] = createSignal('')
    const [chosenIso, setChosenIso] = createSignal<string | null>(null)
    let touched = false
    let natInput: HTMLInputElement | undefined
    let pickerDialog: HTMLDialogElement | undefined

    const country = createMemo(() => phoneRules.countryFor(countries(), dial(), nat(), chosenIso()))
    const pattern = () => phoneRules.patternFor(country(), dial(), nat())
    const shownNat = () => phoneRules.format(nat(), pattern())
    const full = () => `+${dial()}${nat()}`
    // Without Telegram's list (offline at first) any code is let through and Telegram judges it;
    // so is the test servers' 999 (worker.ts, testMode).
    const knownCode = () => !countries().length || !!country() || (import.meta.env.DEV && import.meta.env.VITE_TG_TEST === '1')
    const ready = () => knownCode() && (dial() + nat()).length >= phoneRules.MIN_DIGITS

    onMount(() => {
        if (wide) startQr()
        // Telegram's guess fills in the country whenever it lands, as long as nothing has been
        // typed or picked; the browser's language region stands in when Telegram has none. The
        // first connection's handshake can take seconds, so there is no limit but the touch.
        const list = api.countries().then(setCountries, (e) => (console.warn('[signin] countries', e), []))
        const guess = Promise.race([api.guessCountry().catch(() => null), new Promise<null>((r) => setTimeout(() => r(null), 10_000))])
        Promise.all([list, guess])
            .then(([found, iso]) => {
                if (touched || dial() || nat()) return
                const byIso = (code: string | null) => (code ? found.find((c) => c.iso === code.toUpperCase()) : undefined)
                const c = byIso(iso) ?? byIso(phoneRules.regionOf(navigator.languages))
                if (c) {
                    setChosenIso(c.iso)
                    setDial(c.codes[0].code)
                }
            })
            .finally(() => setGuessing(false))
    })

    /** A whole international number typed or pasted into either field, taken apart. */
    const takeWhole = (raw: string) => {
        const split = phoneRules.splitInternational(raw, countries())
        if (!split) return false
        batch(() => {
            setDial(split[0])
            setNat(phoneRules.national(split[1], split[0]))
        })
        return true
    }

    const onDial = (el: HTMLInputElement) => {
        touched = true
        setError(null)
        if (takeWhole(el.value)) {
            el.value = dial()
            natInput?.focus()
            return
        }
        const d = phoneRules.digits(el.value).slice(0, phoneRules.MAX_DIAL_DIGITS)
        setDial(d)
        el.value = d
        // A code no longer one could grow into is finished: on to the number.
        const all = countries().flatMap(phoneRules.dialCodes)
        if (all.includes(d) && !all.some((c) => c.length > d.length && c.startsWith(d))) natInput?.focus()
    }

    const onNat = (el: HTMLInputElement) => {
        touched = true
        setError(null)
        const raw = el.value
        const before = phoneRules.digits(raw.slice(0, el.selectionStart ?? raw.length)).length
        const whole = takeWhole(raw)
        if (!whole) setNat(phoneRules.national(phoneRules.digits(raw), dial()))
        const shown = shownNat()
        el.value = shown
        const at = whole ? shown.length : phoneRules.caretAfter(Math.min(before, nat().length), shown)
        el.setSelectionRange(at, at)
    }

    const pick = (c: Country) => {
        touched = true
        batch(() => {
            setChosenIso(c.iso)
            setDial(c.codes[0].code)
            setNat(phoneRules.national(nat(), c.codes[0].code))
        })
        pickerDialog?.close()
        natInput?.focus()
    }

    // ---- Code ----
    const [codeText, setCodeText] = createSignal('')
    const [resendAt, setResendAt] = createSignal(0)
    const resendLeft = () => Math.max(0, Math.ceil((resendAt() - now()) / 1000))
    let sentFor = ''
    let codeInput: HTMLInputElement | undefined

    const toCode = (sent: SentCode) => {
        batch(() => {
            setCodeText('')
            setResendAt(Date.now() + sent.timeout * 1000)
            setNow(Date.now())
            go(sent.type === 'email_required' ? { kind: 'email', hash: sent.hash } : { kind: 'code', sent })
        })
    }

    const afterSend = (res: { sent: SentCode } | { me: Me }) => ('me' in res ? done(res.me) : toCode(res.sent))

    const sendCode = async (e: Event) => {
        e.preventDefault()
        if (!ready() || busy() || waitLeft()) return
        setBusy(true)
        setError(null)
        try {
            sentFor = full()
            afterSend(await api.sendCode(sentFor))
        } catch (err) {
            fail(err)
        } finally {
            setBusy(false)
        }
    }

    const codeHash = () => {
        const s = step()
        return s.kind === 'code' ? s.sent.hash : s.kind === 'email' ? s.hash : ''
    }

    /** Back to the number, letting Telegram drop the code it sent. */
    const changeNumber = () => {
        const hash = codeHash()
        if (hash) api.cancelCode(sentFor, hash)
        go({ kind: 'phone' })
        queueMicrotask(() => natInput?.focus())
    }

    const startOver = () => {
        const hash = codeHash()
        if (hash) api.cancelCode(sentFor, hash)
        stopQr()
        batch(() => {
            setBusy(false)
            setCodeText('')
            setPassword('')
            go(first())
        })
        if (wide) startQr()
    }

    const sendSignIn = async (e?: Event) => {
        e?.preventDefault()
        const s = step()
        if (s.kind !== 'code' || busy() || waitLeft() || !codeText().trim()) return
        setBusy(true)
        setError(null)
        try {
            if (s.sent.type === 'email_setup') afterSend(await api.verifyEmail(sentFor, s.sent.hash, codeText().trim()))
            else done(await api.signIn(sentFor, s.sent.hash, codeText().trim(), s.sent.type === 'email'))
        } catch (err) {
            const text = (err as { text?: string }).text ?? ''
            if (text === 'SESSION_PASSWORD_NEEDED') {
                go({ kind: 'password', hint: await api.passwordHint().catch(() => null) })
            } else if (/AUTH_RESTART/.test(text)) {
                go({ kind: 'phone' })
                fail(err)
            } else {
                fail(err)
                // A wrong code is selected, so the next one types over it.
                queueMicrotask(() => codeInput?.select())
            }
        } finally {
            setBusy(false)
        }
    }

    const onCode = (el: HTMLInputElement) => {
        setError(null)
        const s = step()
        if (s.kind !== 'code') return
        const asDigits = typedAsDigits(s.sent.type)
        const v = asDigits ? phoneRules.digits(el.value).slice(0, s.sent.length || 8) : el.value
        setCodeText(v)
        if (asDigits) el.value = v
        // A full code goes at once, as Telegram's apps do.
        if (asDigits && s.sent.length && v.length === s.sent.length) sendSignIn()
    }

    // ---- The TV's pad: the national number and the code are typed a digit at a time. The country
    // comes from Telegram's guess or the picker, never from the pad: "1" alone could be the start
    // of a dozen codes. The remote's own number keys go through the same two functions.
    const padDigit = (d: string) => {
        const s = step()
        if (s.kind === 'phone') {
            touched = true
            setError(null)
            setNat(phoneRules.national(phoneRules.digits(nat() + d), dial()))
        } else if (s.kind === 'code' && typedAsDigits(s.sent.type)) {
            setError(null)
            const v = (codeText() + d).slice(0, s.sent.length || 8)
            setCodeText(v)
            if (s.sent.length && v.length === s.sent.length) sendSignIn()
        }
    }
    const padDelete = () => {
        const s = step()
        if (s.kind === 'phone') setNat(nat().slice(0, -1))
        else if (s.kind === 'code') setCodeText(codeText().slice(0, -1))
        setError(null)
    }
    const remoteDigit = (e: KeyboardEvent) => {
        if (!tv || e.ctrlKey || e.altKey || e.metaKey || !/^[0-9]$/.test(e.key)) return
        const target = e.target as HTMLElement | null
        if (target instanceof HTMLInputElement && !target.readOnly) return
        padDigit(e.key)
        e.preventDefault()
    }
    onMount(() => tv && onCleanup(onGlobal(window, 'keydown', remoteDigit)))

    const resend = async () => {
        const s = step()
        if (s.kind !== 'code' || busy() || resendLeft() || waitLeft()) return
        setBusy(true)
        setError(null)
        try {
            afterSend(await api.resendCode(sentFor, s.sent.hash))
        } catch (err) {
            fail(err)
            // Telegram has no other way to send it: the button goes, the code already sent stays.
            if (/SEND_CODE_UNAVAILABLE/.test((err as { text?: string }).text ?? '')) setStep({ kind: 'code', sent: { ...s.sent, next: 'none' } })
        } finally {
            setBusy(false)
        }
    }

    // ---- Email address ----
    const [email, setEmail] = createSignal('')
    const sendEmail = async (e: Event) => {
        e.preventDefault()
        const s = step()
        if (s.kind !== 'email' || busy() || waitLeft() || !email().includes('@')) return
        setBusy(true)
        setError(null)
        try {
            const sent = await api.sendEmailCode(sentFor, s.hash, email().trim())
            toCode({ hash: s.hash, type: 'email_setup', next: 'none', timeout: 0, length: sent.length, email: sent.email, beginning: null })
        } catch (err) {
            fail(err)
        } finally {
            setBusy(false)
        }
    }

    // ---- Two-step password ----
    const [password, setPassword] = createSignal('')
    const [showPassword, setShowPassword] = createSignal(false)
    const sendPassword = async (e: Event) => {
        e.preventDefault()
        if (!password() || busy()) return
        setBusy(true)
        setError(null)
        if (passwordWaiter) {
            // The QR flow is waiting for it and finishes the sign in itself.
            const resolve = passwordWaiter
            passwordWaiter = null
            resolve(password())
            return
        }
        try {
            done(await api.checkPassword(password()))
        } catch (err) {
            fail(err)
        } finally {
            setBusy(false)
        }
    }

    // ---- Words ----
    /** A send button's words: busy, held by a flood wait, or ready. */
    const sendWords = (ready: string, working: string) => (busy() ? working : waitLeft() ? t('login.try_again_in', { time: clock(waitLeft()) }) : ready)

    const countryLine = () => {
        if (guessing() && !touched) return t('login.finding_country')
        const c = country()
        if (c) return `${phoneRules.flag(c.iso)}  ${c.name}`
        return dial() && countries().length ? t('login.invalid_country_code') : t('login.choose_country')
    }

    const qrView: QrView = {
        get svg() { return qr() },
        get scanned() { return scanned() },
        get expired() { return qrExpired() },
        get soon() { return qrSoon() },
        get left() { return qrLeft() },
        get fraction() { return qrFraction() },
        get refreshing() { return refreshing() },
    }
    const useQr = () => (go({ kind: 'qr' }), startQr())

    return (
        <main class="signin" classList={{ "is-qr": step().kind === 'qr' }}>
            {/* The mark and the name beside it, as the apps open sign in (AppLogo). */}
            <div class="signin-brand">
                <img class="signin-mark" src="/icon.svg" alt="" width="42" height="42" />
                <span class="signin-word">TMPlayer</span>
            </div>
            {/* Telegram ended the session (HX23): why the sign in is back, as in the apps. */}
            <Show when={sessionEnded()}>
                <p class="signin-ended" role="status">{t('errors.signed_out')}</p>
            </Show>
            <Switch>
                <Match when={step().kind === 'qr'}>
                    <StepHead title={t('login.qr_title')} blurb={t('login.qr_blurb')} />
                    <QrStep qr={qrView} onRefresh={refreshQr} onUsePhone={() => (stopQr(), go({ kind: 'phone' }))} />
                    <Problem error={error()} />
                </Match>

                <Match when={step().kind === 'phone'}>
                    <StepHead title={t('login.phone_title')} blurb={t('login.phone_blurb')} />
                    <form onSubmit={sendCode}>
                        <button type="button" class="country-row" classList={{ invalid: !knownCode() && !!dial() }} onClick={() => pickerDialog?.showModal()}>
                            <span>{countryLine()}</span>
                            <span class="country-trail">
                                <Show when={dialChip(dial())}>{(chip) => <span class="dial-chip">{chip()}</span>}</Show>
                                <Icon icon={ArrowDown01Icon} size={20} />
                            </span>
                        </button>
                        <div class="phone-row">
                            <label class="dial">
                                <span aria-hidden="true">+</span>
                                <input
                                    aria-label={t('login.code')}
                                    inputmode={tv ? 'none' : 'tel'}
                                    autocomplete="tel-country-code"
                                    value={dial()}
                                    readOnly={tv}
                                    tabIndex={tv ? -1 : undefined}
                                    onInput={(e) => onDial(e.currentTarget)}
                                />
                            </label>
                            <input
                                ref={natInput}
                                aria-label={t('login.phone_number')}
                                placeholder={t('login.phone_number')}
                                type="tel"
                                inputmode={tv ? 'none' : 'tel'}
                                autocomplete="tel-national"
                                value={shownNat()}
                                readOnly={tv}
                                tabIndex={tv ? -1 : undefined}
                                onInput={(e) => onNat(e.currentTarget)}
                                autofocus={!tv}
                            />
                        </div>
                        <Show when={tv}>
                            <Suspense><Keypad label={t('login.phone_number')} onDigit={padDigit} onDelete={padDelete} /></Suspense>
                        </Show>
                        <button class="primary" disabled={!ready() || busy() || !!waitLeft()}>{sendWords(t('login.send_code'), t('login.sending_code'))}</button>
                    </form>
                    <Problem error={error()} />
                    <button class="link alt" onClick={useQr}>{t('login.use_qr')}</button>
                </Match>

                <Match when={step().kind === 'code' && (step() as { sent: SentCode }).sent}>
                    {(sent) => (
                        <>
                            <StepHead title={sent().type.startsWith('email') ? t('login.email_code_title') : t('login.code_title')} blurb={codeBlurb(sent(), `+${dial()} ${shownNat()}`)} />
                            <form onSubmit={sendSignIn}>
                                <input
                                    ref={codeInput}
                                    class="code-input"
                                    classList={{ digits: typedAsDigits(sent().type) }}
                                    aria-label={sent().type.startsWith('email') ? t('login.email_code_label') : t('login.code')}
                                    inputmode={tv && typedAsDigits(sent().type) ? 'none' : typedAsDigits(sent().type) ? 'numeric' : 'text'}
                                    readOnly={tv && typedAsDigits(sent().type)}
                                    tabIndex={tv && typedAsDigits(sent().type) ? -1 : undefined}
                                    autocomplete="one-time-code"
                                    maxLength={typedAsDigits(sent().type) && sent().length ? sent().length : undefined}
                                    placeholder={sent().beginning ? `${sent().beginning}…` : undefined}
                                    value={codeText()}
                                    onInput={(e) => onCode(e.currentTarget)}
                                    autofocus={!tv || !typedAsDigits(sent().type)}
                                />
                                <Show when={tv && typedAsDigits(sent().type)}>
                                    <Suspense><Keypad label={t('login.code')} onDigit={padDigit} onDelete={padDelete} /></Suspense>
                                </Show>
                                <button class="primary" disabled={busy() || !codeText().trim() || !!waitLeft()}>{sendWords(t('login.sign_in'), t('login.signing_in'))}</button>
                            </form>
                            <Problem error={error()} />
                            <Show
                                when={canResend(sent())}
                                fallback={<p class="muted small resend-none" role="status">{t('login.resend_none')}</p>}
                            >
                                <button class="link" disabled={busy() || !!resendLeft()} onClick={resend}>
                                    {resendLeft() ? t('login.resend_in', { time: clock(resendLeft()) }) : resendLabel(sent())}
                                </button>
                            </Show>
                            <StepLinks onChangeNumber={changeNumber} onStartOver={startOver} />
                        </>
                    )}
                </Match>

                <Match when={step().kind === 'email'}>
                    <StepHead title={t('login.email_address_title')} blurb={t('login.email_address_body')} />
                    <form onSubmit={sendEmail}>
                        <input
                            type="email"
                            aria-label={t('login.email_address_label')}
                            placeholder={t('login.email_address_label')}
                            autocomplete="email"
                            value={email()}
                            onInput={(e) => (setEmail(e.currentTarget.value), setError(null))}
                            autofocus
                        />
                        <button class="primary" disabled={busy() || !email().includes('@') || !!waitLeft()}>{sendWords(t('login.email_send'), t('login.sending_code'))}</button>
                    </form>
                    <Problem error={error()} />
                    <StepLinks onChangeNumber={changeNumber} onStartOver={startOver} />
                </Match>

                <Match when={step().kind === 'password' && (step() as { hint: string | null })}>
                    {(s) => (
                        <>
                            <StepHead title={t('login.telegram_password_title')} blurb={t('login.password_blurb')} />
                            <form onSubmit={sendPassword}>
                                <PasswordField value={password()} onInput={(v) => (setPassword(v), setError(null))} shown={showPassword()} onToggle={() => setShowPassword(!showPassword())} />
                                <Show when={s().hint}>
                                    <p class="muted">{t('login.password_hint', { hint: s().hint! })}</p>
                                </Show>
                                <button class="primary" disabled={busy() || !password() || !!waitLeft()}>{sendWords(t('login.sign_in'), t('login.signing_in'))}</button>
                            </form>
                            <Problem error={error()} />
                            <button class="link" onClick={startOver}>{t('login.start_over')}</button>
                        </>
                    )}
                </Match>
            </Switch>

            {/* Profiles (HX88): the sign in of an account being added goes back; another profile is a choice away. */}
            <Show when={addingHere()} fallback={<Show when={many()}><button class="link alt" type="button" onClick={openPicker}>{t('accounts.switch')}</button></Show>}>
                <button class="link alt" type="button" onClick={() => void cancelAdding()}>{t('accounts.add_cancel')}</button>
            </Show>

            <SignInFooter />

            <CountryPicker ref={(d) => (pickerDialog = d)} countries={countries()} onPick={pick} />
        </main>
    )
}
