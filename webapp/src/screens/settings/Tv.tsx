import { For, Show } from 'solid-js'
import { A } from '@solidjs/router'
import { Clock01Icon, StarIcon } from '@hugeicons/core-free-icons'
import { settings, setSetting } from '../../store'
import { me } from '../../session'
import { t } from '../../i18n'
import { clearHistoryWithUndo } from '../../clearHistory'
import { layoutFixed } from '../../form'
import { msoneOn } from '../../msoneFlag'
import { setSyncOn, statusLine, syncNow, syncOn, syncRunning } from '../../sync'
import { openFeedback } from '../../ui/FeedbackForm'
import { TmdbCredit, TvPanel, TvSwitch } from '../../ui/SettingsParts'
import { AboutNews, rowDomId } from '../SettingsFind'
import { HOME_SWITCHES, HomeOrder } from './Home'
import { LanguageBlock } from './Language'
import { Rewind, TvMsone } from './Playback'
import { SignOut } from './SignOut'
import { VersionFooter } from '../../ui/VersionFooter'
import { ProfileMenuButton, RemoveAllButton } from '../../ui/Profiles'
import { Install, StorageBlock } from './Storage'
import { chosenDetail, LayoutChoices, LICENCES, ThemeChoices } from './shared'

/** The TV layout: one long page of choices, as session 20 styled it (src/tv/tv.css) and its remote walks. */
export function TvSettings() {
    return (
        <section class="settings">
            <h1 class="page-title">{t('nav.settings')}</h1>
            {/* The version, the build and the update state, read only: the account menu's last row is the TV's copy target (WP-104). */}
            <VersionFooter look="text" />
            <TvPanel title={t('picker.row_title')} id="add-chats-label" anchor={rowDomId('chosen_chats')}>
                <p class="muted small">{chosenDetail()}</p>
                <A class="outline-button" style={{ 'align-self': 'start' }} href="/added-chats">{t('picker.choose_chats')}</A>
            </TvPanel>
            <TvPanel title={t('settings.theme')} id="theme-label" anchor={rowDomId('theme')}>
                <ThemeChoices />
            </TvPanel>
            <Show when={!layoutFixed()}>
                <TvPanel title={t('settings.layout')} id="layout-label" anchor={rowDomId('layout')}>
                    <LayoutChoices />
                </TvPanel>
            </Show>
            <div class="panel">
                <LanguageBlock />
            </div>
            <For each={HOME_SWITCHES}>
                {(s) => <TvSwitch id={s.id} row={s.row} title={t(s.title)} on={settings()[s.key]} onText={t(s.on)} offText={t(s.off)} onPick={(on) => setSetting(s.key, on)} />}
            </For>
            <p class="muted small">{t('settings.home_follows_added')}</p>
            <HomeOrder />
            <TvSwitch id="sync" row="resume_sync" title={t('settings.resume_sync')} on={syncOn()} onText={t('settings.resume_sync_on')} offText={t('settings.resume_sync_off')} onPick={(on) => void setSyncOn(on)} />
            <p class="muted small">{t('syncplace.privacy')}</p>
            <Show when={syncOn()}>
                <p class="muted small" role="status">{statusLine()}</p>
                <button class="outline-button" style={{ 'align-self': 'start' }} type="button" disabled={syncRunning()} onClick={() => void syncNow()}>{t('settings.resume_sync_now')}</button>
            </Show>
            <TvPanel title={t('browse.clear_history')} id="history-label" anchor={rowDomId('clear_history')}>
                <p class="muted small">{t('browse.clear_history_detail')}</p>
                <button class="outline-button" style={{ 'align-self': 'start' }} type="button" aria-labelledby="history-label" onClick={() => void clearHistoryWithUndo()}>
                    {t('browse.clear_history')}
                </button>
            </TvPanel>
            <TvPanel title="" id="rewind-panel">
                <Rewind />
            </TvPanel>
            <Show when={msoneOn()}>
                <TvMsone />
            </Show>
            <TvSwitch id="posters" row="posters" title={t('web.posters_title')} on={settings().posters} onText={t('web.posters_on_detail')} offText={t('metadata.toggle_off')} onPick={(on) => setSetting('posters', on)}>
                <TmdbCredit />
            </TvSwitch>
            <TvSwitch id="trailers" row="trailers_in_app" title={t('metadata.trailers_in_app')} on={settings().trailersInApp} onText={t('metadata.trailers_in_app_detail')} offText={t('metadata.trailers_in_app_detail')} onPick={(on) => setSetting('trailersInApp', on)} />
            <Install />
            <div class="panel">
                <StorageBlock />
            </div>
            <div class="panel">
                <h2 class="panel-title">{t('settings.account')}</h2>
                <Show when={me()?.name}>
                    <p>{t('settings.signed_in_as', { name: me()!.name! })}</p>
                </Show>
                <p class="muted small">{t('auth.unofficial')}</p>
                <a class="link" href="https://tmplayer.org/legal" target="_blank" rel="noopener">{t('login.legal')}</a>
                <ProfileMenuButton />
                <SignOut />
                <RemoveAllButton />
            </div>
            <TvPanel title={t('feedback.title')} id="feedback-label" anchor={rowDomId('report_problem')}>
                <p class="muted small">{t('feedback.tv_phone_hint')}</p>
                <button class="outline-button" style={{ 'align-self': 'start' }} type="button" onClick={(e) => openFeedback('problem', e.currentTarget)}>
                    {t('settings.report_problem')}
                </button>
                <button class="outline-button" id={rowDomId('request_feature')} style={{ 'align-self': 'start' }} type="button" onClick={(e) => openFeedback('feature', e.currentTarget)}>
                    {t('web.request_feature')}
                </button>
            </TvPanel>
            <div class="panel">
                <h2 class="panel-title">{t('about.notices_title')}</h2>
                <VersionFooter look="text" />
                <p class="muted small">{t('about.licence_line')}</p>
                <p class="muted small">{t('about.notices_detail')}</p>
                <a class="link" id={rowDomId('notices')} href={LICENCES} target="_blank" rel="noopener">{t('about.read')}</a>
                <AboutNews variant="tv" icon={StarIcon} changelogIcon={Clock01Icon} />
            </div>
        </section>
    )
}
