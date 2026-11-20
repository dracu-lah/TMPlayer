import { For, Show } from 'solid-js'
import { browserLanguage, language, languageChoice, languageFailed, languageLoading, languageName, retryLanguage, setLanguage, t } from '../../i18n'
import { LANGUAGES } from '../../ui/language'
import { rowDomId } from '../SettingsFind'
import '../language.css'

/**
 * Language (WB12): Automatic, which follows the browser, and the apps' 22 languages by their own
 * names. A press switches at once and in place, so a video playing in the other tab of the layout
 * plays on. Buttons, not radios: the arrow keys and the TV remote walk the list without switching
 * language at every step.
 */
export function LanguageBlock() {
    const detail = () => (languageChoice() ? languageName(language()) : t('settings.language_detail_system', { language: languageName(browserLanguage()) }))
    return (
        <div class="set-block" id={rowDomId('language')}>
            <h3 class="set-block-title" id="language-label">{t('settings.language')}</h3>
            <p class="muted small">{detail()}</p>
            <div class="lang-list" role="group" aria-labelledby="language-label">
                <button type="button" class="lang-option" aria-pressed={languageChoice() === ''} onClick={() => void setLanguage('')}>
                    <span>
                        {t('settings.language_automatic')}
                        <span class="lang-sub" lang={browserLanguage()} dir="auto">{languageName(browserLanguage())}</span>
                    </span>
                </button>
                <For each={LANGUAGES}>
                    {(l) => (
                        <button type="button" class="lang-option" aria-pressed={languageChoice() === l.tag} onClick={() => void setLanguage(l.tag)}>
                            <span lang={l.tag} dir="auto">{l.name}</span>
                        </button>
                    )}
                </For>
            </div>
            <Show when={languageLoading()}>
                {(tag) => <p class="muted small lang-state" role="status">{t('web.language_loading', { language: languageName(tag()) })}</p>}
            </Show>
            <Show when={languageFailed()}>
                {(tag) => (
                    <p class="small caution-text lang-state" role="alert">
                        {t('web.language_failed', { language: languageName(tag()) })}
                        <button class="link" type="button" onClick={() => void retryLanguage()}>{t('common.try_again')}</button>
                    </p>
                )}
            </Show>
            <p class="muted small">{t('language.picker_note')}</p>
        </div>
    )
}
