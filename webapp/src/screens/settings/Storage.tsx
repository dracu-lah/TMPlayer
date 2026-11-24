import { createResource, createSignal, onMount, Show } from 'solid-js'
import { storageWorks } from '../../store'
import { t } from '../../i18n'
import { askConfirm } from '../../ui/Confirm'
import { size } from '../VideoList'
import { checkKept, install, installed, installPrompt, iosBrowser, keepData, kept, refused } from '../../install'
import { cacheSizes, clearCaches } from '../../cacheBounds'
import { rowDomId } from '../SettingsFind'
import { BackupBlock } from './Backup'
import { HomeScreenSteps } from '../../ui/HomeScreenSteps'
import { SafariClearsRow } from '../SafariClearsCard'

export function StoragePage() {
    return (
        <>
            <Install />
            <Show when={iosBrowser && !installed()}>
                <div class="set-card set-blocks">
                    <SafariClearsRow />
                </div>
            </Show>
            <div class="set-card set-blocks">
                <StorageBlock />
            </div>
            <div class="set-card set-blocks">
                <BackupBlock />
            </div>
        </>
    )
}

/** Installing: the browser's own prompt where it has one, the Share menu's steps on an iPhone or iPad; gone once installed. */
export function Install() {
    return (
        <Show when={!installed() && (installPrompt() || iosBrowser)}>
            <div class="set-card set-blocks">
                <div class="set-block" id={rowDomId('install')}>
                    <Show
                        when={installPrompt()}
                        fallback={
                            <>
                                <h3 class="set-block-title">{t('web.install_ios_title')}</h3>
                                <p class="muted small">{t('web.install_ios_body')}</p>
                                <HomeScreenSteps />
                                <p class="muted small">{t('web.install_ios_own_sign_in')}</p>
                            </>
                        }
                    >
                        <h3 class="set-block-title">{t('web.install_title')}</h3>
                        <p class="muted small">{t('web.install_pwa_body')}</p>
                        <button class="primary" style={{ 'align-self': 'start' }} type="button" onClick={install}>{t('web.install_button')}</button>
                    </Show>
                </div>
            </div>
        </Show>
    )
}

/** What the browser holds for TMPlayer, and asking it to keep that when the device runs low on space. */
const small = (bytes: number) => (bytes < 500_000 ? '0 MB' : size(bytes))

export function StorageBlock() {
    const [version, setVersion] = createSignal(0)
    const [estimate] = createResource(version, () => navigator.storage?.estimate?.().catch(() => null) ?? null)
    const [sizes] = createResource(version, () => cacheSizes())
    const [clearing, setClearing] = createSignal(false)
    onMount(() => void checkKept())
    // Thumbnails and posters only: the sign in, settings, places and watched stay.
    const clear = async () => {
        if (clearing()) return
        const held = (sizes()?.thumbs ?? 0) + (sizes()?.posters ?? 0)
        const yes = await askConfirm({
            title: t('settings.clear_pictures_title'),
            message: t('settings.clear_pictures_message', { size: size(held) }),
            detail: t('settings.clear_pictures_untouched'),
            confirm: t('web.storage_clear_cache'),
        })
        if (!yes) return
        setClearing(true)
        try {
            await clearCaches()
        } catch {}
        setClearing(false)
        setVersion((v) => v + 1)
    }
    return (
        <div class="set-block" id={rowDomId('storage')}>
            <h3 class="set-block-title">{t('web.storage_title')}</h3>
            <p class="muted small">{t('web.storage_body_stream')}</p>
            <Show when={!storageWorks()}>
                <p class="small caution-text">{t('web.storage_unavailable')}</p>
            </Show>
            <Show when={estimate()?.quota}>
                <p class="small">{t('web.storage_used', { used: size(estimate()!.usage ?? 0), quota: size(estimate()!.quota!) })}</p>
            </Show>
            <Show when={estimate()?.usage !== undefined && sizes()}>
                {(sz) => {
                    const app = () => Math.max(0, (estimate()!.usage ?? 0) - sz().thumbs - sz().posters)
                    return (
                        <>
                            <p class="muted small">
                                {t('web.storage_breakdown', { thumbs: small(sz().thumbs), posters: small(sz().posters), app: small(app()) })}
                            </p>
                            <button class="link" id={rowDomId('clear_cache')} style={{ 'align-self': 'start' }} type="button" disabled={clearing()} onClick={clear}>{t('web.storage_clear_cache')}</button>
                            <p class="muted small">{t('web.storage_clear_cache_detail')}</p>
                        </>
                    )
                }}
            </Show>
            <Show when={kept() !== null}>
                <p class="muted small" id={rowDomId('keep_storage')}>{kept() ? t('web.keep_storage_on') : refused() ? t('web.keep_storage_refused') : t('web.keep_storage_off')}</p>
                <Show when={!kept()}>
                    <button class="link" style={{ 'align-self': 'start' }} type="button" onClick={keepData}>{t('web.keep_storage')}</button>
                </Show>
            </Show>
        </div>
    )
}
