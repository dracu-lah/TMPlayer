import { Show, type JSX } from 'solid-js'
import { ViewIcon, ViewOffSlashIcon } from '@hugeicons/core-free-icons'
import { iosBrowser } from '../../install'
import { t } from '../../i18n'
import { Icon } from '../../ui/Icon'
import { HomeScreenSteps } from '../../ui/HomeScreenSteps'

// The parts the steps of sign in share.

/** A step's heading and what it asks for. */
export function StepHead(props: { title: string; blurb: string }) {
    return (
        <>
            <h1>{props.title}</h1>
            <p class="muted">{props.blurb}</p>
        </>
    )
}

/** The last thing that went wrong, under the form it belongs to. */
export function Problem(props: { error: string | null }) {
    return (
        <Show when={props.error}>
            <p class="error" role="alert">{props.error}</p>
        </Show>
    )
}

/** The ways out of the code and the email steps: another number, or the very start. */
export function StepLinks(props: { onChangeNumber: () => void; onStartOver: () => void }) {
    return (
        <div class="signin-links">
            <button class="link" onClick={props.onChangeNumber}>{t('login.wrong_number')}</button>
            <button class="link" onClick={props.onStartOver}>{t('login.start_over')}</button>
        </div>
    )
}

/** The two-step password with its eye button; whether it is shown is kept by the caller, so it survives Start over. */
export function PasswordField(props: { value: string; onInput: (value: string) => void; shown: boolean; onToggle: () => void }) {
    return (
        <div class="password-field">
            <input
                type={props.shown ? 'text' : 'password'}
                aria-label={t('login.password')}
                placeholder={t('login.password')}
                autocomplete="current-password"
                value={props.value}
                onInput={(e) => props.onInput(e.currentTarget.value)}
                autofocus
            />
            <button
                type="button"
                class="icon-button"
                aria-label={props.shown ? t('login.hide_password') : t('login.show_password')}
                onClick={() => props.onToggle()}
            >
                <Icon icon={props.shown ? ViewOffSlashIcon : ViewIcon} size={20} />
            </button>
        </div>
    )
}

/** Under every step: how to add the app to an iPhone first, and what TMPlayer is not. */
export function SignInFooter(): JSX.Element {
    return (
        <footer class="signin-foot small muted">
            {/* Safari and a Home Screen app keep separate data: adding it first saves signing in twice. */}
            <Show when={iosBrowser}>
                <details class="signin-install">
                    <summary>{t('web.install_ios_short')}</summary>
                    <p>{t('web.install_ios_before_sign_in')}</p>
                    <HomeScreenSteps />
                </details>
            </Show>
            <p>{t('auth.unofficial')}</p>
            <a class="link" href="https://tmplayer.org/legal" target="_blank" rel="noopener">{t('login.legal')}</a>
        </footer>
    )
}
