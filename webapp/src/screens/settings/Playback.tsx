import { createResource, createSignal, onMount, Show } from 'solid-js'
import { TranslateIcon } from '@hugeicons/core-free-icons'
import { settings, setSetting } from '../../store'
import { api } from '../../tg/rpc'
import { t } from '../../i18n'
import { msoneOn, stateLine } from '../../msoneFlag'
import { health, knownHealth, type Health } from '../../player/msone'
import { ActionRow, Choices, TvPanel } from '../../ui/SettingsParts'
import { rowDomId } from '../SettingsFind'

export function Playback() {
    return (
        <>
            <div class="set-card set-blocks">
                <Rewind />
            </div>
            <Show when={msoneOn()}>
                <div class="set-card">
                    <MsoneRow />
                </div>
            </Show>
        </>
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

function MsoneRow() {
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
