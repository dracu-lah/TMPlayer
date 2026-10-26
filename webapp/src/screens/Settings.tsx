import { createResource, createSignal, For, Show } from 'solid-js'
import { settings, setSetting, SIZE_DEFAULTS, storageWorks, type Theme } from '../store'
import { me, signOut } from '../session'
import { api } from '../tg/rpc'
import { t } from '../i18n'
import { sizeScale } from './sizes'
import { size } from './VideoList'
import { layout, setLayout, type Layout } from '../form'
import { install, installed, installPrompt, iosBrowser, keepData, kept, refused } from '../install'

const THEMES: Theme[] = ['system', 'dark', 'light', 'black']
const LAYOUTS: Layout[] = ['auto', 'phone', 'desktop', 'tv']

/** Theme, the size limits every list keeps to, the rewind on resume, the account and the licence notices. */
export function Settings() {
    return (
        <section class="settings">
            <h1 class="page-title">{t('nav.settings')}</h1>
            <div class="panel">
                <h2 class="panel-title" id="theme-label">{t('settings.theme')}</h2>
                <Choices
                    label="theme-label"
                    name="theme"
                    options={THEMES.map((v) => ({ value: v, label: t(`theme.${v}`) }))}
                    value={settings().theme}
                    onPick={(v) => setSetting('theme', v)}
                />
            </div>
            <div class="panel">
                <h2 class="panel-title" id="layout-label">{t('settings.layout')}</h2>
                <Choices
                    label="layout-label"
                    name="layout"
                    options={LAYOUTS.map((v) => ({ value: v, label: t(`settings.layout_${v}`) }))}
                    value={layout()}
                    onPick={setLayout}
                />
                <p class="muted small">{t('settings.layout_detail')}</p>
            </div>
            <SizeLimits />
            <Rewind />
            <Install />
            <Storage />
            <Account />
            <Notices />
        </section>
    )
}

function Choices<T extends string | number>(props: { label: string; name: string; options: { value: T; label: string }[]; value: T; onPick: (v: T) => void }) {
    return (
        <div class="segmented" role="radiogroup" aria-labelledby={props.label} style={{ 'grid-template-columns': `repeat(${props.options.length}, 1fr)` }}>
            <For each={props.options}>
                {(o) => (
                    <label classList={{ on: props.value === o.value }}>
                        <input type="radio" name={props.name} checked={props.value === o.value} onChange={() => props.onPick(o.value)} />
                        {o.label}
                    </label>
                )}
            </For>
        </div>
    )
}

/**
 * The app's two thumb size slider: No minimum, 50 MB, then every 100 MB up to No limit, on its
 * uneven scale (the first gigabyte takes almost half the track). The arrow keys move one stop,
 * Page Up and Down ten; the thumbs never cross.
 */
function SizeLimits() {
    const stops = () => sizeScale()?.stops ?? []
    const indexOf = (bytes: number) => {
        const s = stops()
        let best = 0
        for (let i = 1; i < s.length; i++) if (Math.abs(s[i].bytes - bytes) < Math.abs(s[best].bytes - bytes)) best = i
        return best
    }
    const minI = () => indexOf(settings().sizeMin)
    const maxI = () => indexOf(settings().sizeMax)
    const nearest = (at: number) => {
        const s = stops()
        let best = 0
        for (let i = 1; i < s.length; i++) if (Math.abs(s[i].at - at) < Math.abs(s[best].at - at)) best = i
        return best
    }
    const pick = (which: 'min' | 'max', i: number) => {
        const last = stops().length - 1
        if (which === 'min') setSetting('sizeMin', stops()[Math.max(0, Math.min(i, maxI() - 1))].bytes)
        else setSetting('sizeMax', stops()[Math.min(last, Math.max(i, minI() + 1))].bytes)
    }
    const keys = (which: 'min' | 'max', e: KeyboardEvent) => {
        const at = which === 'min' ? minI() : maxI()
        const step = { ArrowLeft: -1, ArrowDown: -1, ArrowRight: 1, ArrowUp: 1, PageDown: -10, PageUp: 10, Home: -1e4, End: 1e4 }[e.key]
        if (step === undefined) return
        e.preventDefault()
        pick(which, at + step)
    }
    const [range] = createResource(
        () => [settings().sizeMin, settings().sizeMax] as const,
        ([min, max]) => api.sizeRange(min, max),
    )
    const isDefault = () => settings().sizeMin === SIZE_DEFAULTS.sizeMin && settings().sizeMax >= SIZE_DEFAULTS.sizeMax
    const at = (i: number) => (stops()[i]?.at ?? 0) * 100
    return (
        <div class="panel" id="size">
            <h2 class="panel-title" id="size-label">{t('settings.size_limits')}</h2>
            <Show when={stops().length}>
                <div class="size-readout" aria-hidden="true">
                    <span>{stops()[minI()].label}</span>
                    <span>{stops()[maxI()].label}</span>
                </div>
                <div class="dual-range" style={{ '--from': `${at(minI())}%`, '--to': `${at(maxI())}%` }}>
                    <div class="dual-track" />
                    <input
                        type="range"
                        min="0"
                        max="1000"
                        // Above the top thumb once past half way, so two thumbs side by side can both be taken.
                        classList={{ above: at(minI()) > 50 }}
                        value={Math.round(at(minI()) * 10)}
                        aria-label={t('size.smallest')}
                        aria-valuetext={stops()[minI()].label}
                        onInput={(e) => pick('min', nearest(Number(e.currentTarget.value) / 1000))}
                        onKeyDown={(e) => keys('min', e)}
                    />
                    <input
                        type="range"
                        min="0"
                        max="1000"
                        value={Math.round(at(maxI()) * 10)}
                        aria-label={t('size.largest')}
                        aria-valuetext={stops()[maxI()].label}
                        onInput={(e) => pick('max', nearest(Number(e.currentTarget.value) / 1000))}
                        onKeyDown={(e) => keys('max', e)}
                    />
                </div>
            </Show>
            <p class="muted" aria-live="polite">{range.latest ? t('settings.size_limits_note', { range: range.latest }) : ''}</p>
            <Show when={!isDefault()}>
                <button
                    class="link"
                    type="button"
                    onClick={() => {
                        setSetting('sizeMin', SIZE_DEFAULTS.sizeMin)
                        setSetting('sizeMax', SIZE_DEFAULTS.sizeMax)
                    }}
                >
                    {t('common.reset')}
                </button>
            </Show>
        </div>
    )
}

/** Rewind when resuming: the app's choices, Off first. */
function Rewind() {
    const [choices] = createResource(() => api.rewindChoices())
    const current = () => settings().rewindMs ?? choices()?.fallback ?? 0
    return (
        <div class="panel">
            <h2 class="panel-title" id="rewind-label">{t('settings.resume_rewind')}</h2>
            <p class="muted small">{t('settings.resume_rewind_detail')}</p>
            <Show when={choices()}>
                <Choices
                    label="rewind-label"
                    name="rewind"
                    options={choices()!.choices.map((ms) => ({ value: ms, label: ms ? t('format.seconds_short', { seconds: ms / 1000 }) : t('common.off') }))}
                    value={current()}
                    onPick={(v) => setSetting('rewindMs', v)}
                />
            </Show>
        </div>
    )
}

/** Installing: the browser's own prompt where it has one, the Share menu's steps on an iPhone or iPad; gone once installed. */
function Install() {
    return (
        <Show when={!installed() && (installPrompt() || iosBrowser)}>
            <div class="panel">
                <Show
                    when={installPrompt()}
                    fallback={
                        <>
                            <h2 class="panel-title">{t('web.install_ios_title')}</h2>
                            <p class="muted small">{t('web.install_ios_body')}</p>
                            <ol class="install-steps">
                                <li>{t('web.install_ios_step_share')}</li>
                                <li>{t('web.install_ios_step_add')}</li>
                            </ol>
                            <p class="muted small">{t('web.install_ios_own_sign_in')}</p>
                        </>
                    }
                >
                    <h2 class="panel-title">{t('web.install_title')}</h2>
                    <p class="muted small">{t('web.install_pwa_body')}</p>
                    <button class="primary" style={{ 'align-self': 'start' }} type="button" onClick={install}>{t('web.install_button')}</button>
                </Show>
            </div>
        </Show>
    )
}

/** What the browser holds for TMPlayer, and asking it to keep that when the device runs low on space. */
function Storage() {
    const [estimate] = createResource(() => navigator.storage?.estimate?.().catch(() => null) ?? null)
    return (
        <div class="panel">
            <h2 class="panel-title">{t('web.storage_title')}</h2>
            <p class="muted small">{t('web.storage_body_stream')}</p>
            <Show when={!storageWorks()}>
                <p class="small caution-text">{t('web.storage_unavailable')}</p>
            </Show>
            <Show when={estimate()?.quota}>
                <p class="small">{t('web.storage_used', { used: size(estimate()!.usage ?? 0), quota: size(estimate()!.quota!) })}</p>
            </Show>
            <Show when={kept() !== null}>
                <p class="muted small">{kept() ? t('web.keep_storage_on') : refused() ? t('web.keep_storage_refused') : t('web.keep_storage_off')}</p>
                <Show when={!kept()}>
                    <button class="link" style={{ 'align-self': 'start' }} type="button" onClick={keepData}>{t('web.keep_storage')}</button>
                </Show>
            </Show>
        </div>
    )
}

/** The licence, and the notices for every library inside, built into /licenses.txt (notices.config.ts). */
function Notices() {
    return (
        <div class="panel">
            <h2 class="panel-title">{t('about.notices_title')}</h2>
            <p class="muted small">{t('about.licence_line')}</p>
            <p class="muted small">{t('about.notices_detail')}</p>
            <a class="link" href="/licenses.txt" target="_blank" rel="noopener">{t('about.read')}</a>
        </div>
    )
}

function Account() {
    let dialog!: HTMLDialogElement
    const [leaving, setLeaving] = createSignal(false)
    const confirm = async () => {
        setLeaving(true)
        await signOut()
    }
    return (
        <div class="panel">
            <h2 class="panel-title">{t('settings.account')}</h2>
            <Show when={me()?.name}>
                <p>{t('settings.signed_in_as', { name: me()!.name! })}</p>
            </Show>
            <p class="muted small">{t('auth.unofficial')}</p>
            <a class="link" href="https://tmplayer.org/legal" target="_blank" rel="noopener">{t('login.legal')}</a>
            <button class="danger-button" type="button" onClick={() => dialog.showModal()}>{t('settings.sign_out')}</button>
            <dialog class="confirm" ref={dialog} aria-labelledby="sign-out-title">
                <h2 id="sign-out-title">{t('settings.sign_out_title')}</h2>
                <p class="muted">{t('settings.sign_out_message')}</p>
                <div class="confirm-buttons">
                    <button class="link" type="button" disabled={leaving()} onClick={() => dialog.close()}>{t('common.cancel')}</button>
                    <button class="primary danger" type="button" disabled={leaving()} onClick={confirm}>
                        {leaving() ? t('settings.signing_out') : t('settings.sign_out_confirm')}
                    </button>
                </div>
            </dialog>
        </div>
    )
}
