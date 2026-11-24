import { createResource, createSignal, onMount, Show } from 'solid-js'
import { TranslateIcon } from '@hugeicons/core-free-icons'
import { settings, setSetting } from '../../store'
import { api } from '../../tg/rpc'
import { t } from '../../i18n'
import { stateLine } from '../../msoneFlag'
import { health, knownHealth, type Health } from '../../player/msone'
import { ActionRow, Choices, TvPanel } from '../../ui/SettingsParts'
import { removeSubmaker, setSubmakerBase, submakerSet } from '../../submakerBase'
import { showToast } from '../../ui/Toast'
import { rowDomId } from '../SettingsFind'

export function Playback() {
    return (
        <>
            <div class="set-card set-blocks">
                <Rewind />
            </div>
        </>
    )
}

/**
 * SubMaker, the viewer's own subtitles addon: they paste its install link, which is a secret, so the page
 * keeps only the cleaned address (submakerBase.ts) and says nothing of it but Set or Not set. The field is a
 * password field and is emptied the moment the link is taken.
 */
export function SubmakerBlock() {
    const [text, setText] = createSignal('')
    const [invalid, setInvalid] = createSignal(false)
    const save = (e: Event) => {
        e.preventDefault()
        if (!text().trim()) return
        if (setSubmakerBase(text())) {
            setText('')
            setInvalid(false)
            showToast({ kind: 'success', text: t('submaker.settings_saved') })
        } else setInvalid(true)
    }
    return (
        <form class="set-block" id={rowDomId('submaker_base')} onSubmit={save} autocomplete="off">
            <h3 class="set-block-title" id="submaker-label">{t('submaker.settings_title')}</h3>
            <p class="muted small">{t('submaker.settings_message')}</p>
            <p class="set-sub" role="status">{submakerSet() ? t('submaker.settings_set') : t('submaker.settings_none')}</p>
            <input
                type="password"
                name="submaker-install-link"
                autocomplete="off"
                autocapitalize="off"
                spellcheck={false}
                inputmode="url"
                aria-labelledby="submaker-label"
                aria-invalid={invalid() || undefined}
                placeholder="stremio://"
                value={text()}
                onInput={(e) => (setText(e.currentTarget.value), setInvalid(false))}
            />
            <Show when={invalid()}>
                <p class="muted small" role="alert">{t('submaker.settings_invalid')}</p>
            </Show>
            <div style={{ display: 'flex', gap: '12px', 'flex-wrap': 'wrap' }}>
                <button class="primary" type="submit" disabled={!text().trim()}>{t('common.done')}</button>
                <Show when={submakerSet()}>
                    <button class="outline-button" type="button" onClick={() => (removeSubmaker(), setText(''), setInvalid(false), showToast({ kind: 'success', text: t('submaker.settings_removed') }))}>{t('submaker.settings_remove')}</button>
                </Show>
            </div>
        </form>
    )
}

/** Rewind when resuming: the app's choices, Off first. */
export function Rewind() {
    const [choices] = createResource(() => api.rewindChoices())
    const current = () => settings().rewindMs ?? choices()?.fallback ?? 0
    return (
        <div class="set-block" id={rowDomId('resume_rewind')}>
            <h3 class="set-block-title" id="rewind-label">{t('settings.resume_rewind')}</h3>
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

/**
 * MSone subtitles, behind the hidden flag (msoneFlag.ts): the state the gateway last gave, with
 * its message, and a press checks again (at most once a minute, msone.ts holds that).
 */
function useMsoneHealth() {
    const [state, setState] = createSignal<Health>(knownHealth())
    const [checking, setChecking] = createSignal(false)
    const check = async (force: boolean) => {
        if (checking()) return
        setChecking(true)
        // health() never throws: a failed check keeps the last state.
        setState(await health(force))
        setChecking(false)
    }
    onMount(() => void check(false))
    return { state, checking, check }
}

export function MsoneRow() {
    const m = useMsoneHealth()
    return (
        <ActionRow
            row="msone"
            icon={TranslateIcon}
            title={t('msone.title')}
            subtitle={m.checking() ? t('msone.checking') : stateLine(m.state().state)}
            note={m.checking() ? undefined : m.state().message ?? undefined}
            busy={m.checking()}
            onClick={() => void m.check(true)}
        />
    )
}

export function TvMsone() {
    const m = useMsoneHealth()
    return (
        <TvPanel title={t('msone.title')} id="msone-label" anchor={rowDomId('msone')}>
            <p class="muted small" role="status">{m.checking() ? t('msone.checking') : stateLine(m.state().state)}</p>
            <Show when={!m.checking() && m.state().message}>
                <p class="muted small">{m.state().message}</p>
            </Show>
            <button class="outline-button" style={{ 'align-self': 'start' }} type="button" aria-labelledby="msone-label" onClick={() => void m.check(true)}>
                {t('msone.check_again')}
            </button>
        </TvPanel>
    )
}
