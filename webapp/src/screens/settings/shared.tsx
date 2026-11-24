import { addedIds, addedMode, settings, setSetting, type Theme } from '../../store'
import { rowDomId } from '../SettingsFind'
import { t } from '../../i18n'
import { clearLayout, layout, savedLayout, setLayout, tabletClass, type Layout } from '../../form'
import { Choices } from '../../ui/SettingsParts'

const THEMES: Theme[] = ['system', 'dark', 'light', 'black']
const LAYOUTS: Layout[] = ['desktop', 'tv']

/** The build's own notices: the TV build's (under /tv/) also name core-js. */
export const LICENCES = `${import.meta.env.BASE_URL}licenses.txt`

/** What the chosen chats row says: how many, or, for an answer from before choosing was required, every chat. */
export const chosenDetail = () => (addedMode() === 'all' ? t('picker.row_detail_all') : t('picker.row_detail', { count: addedMode() === 'picked' ? (addedIds()?.size ?? 0) : 0 }))

/** The theme's choices, labelled by the heading `theme-label`. */
export function ThemeChoices() {
    return (
        <Choices
            label="theme-label"
            name="theme"
            options={THEMES.map((v) => ({ value: v, label: t(`theme.${v}`) }))}
            value={settings().theme}
            onPick={(v) => setSetting('theme', v)}
        />
    )
}

/** The layout's choices, labelled by the heading `layout-label`, and the line that says what Desktop is. A tablet also has Automatic: the window decides again. */
export function LayoutChoices() {
    const options = () => {
        const base = LAYOUTS.map((v) => ({ value: v as Layout | 'auto', label: t(`settings.layout_${v}`) }))
        return tabletClass() ? [{ value: 'auto' as Layout | 'auto', label: t('interface.layout_auto') }, ...base] : base
    }
    // Automatic is picked while the choice is not stored.
    const value = (): Layout | 'auto' => (tabletClass() && savedLayout() === null ? 'auto' : layout())
    return (
        <>
            <Choices label="layout-label" name="layout" options={options()} value={value()} onPick={(v) => (v === 'auto' ? clearLayout() : setLayout(v))} />
            <p class="muted small">{t('settings.layout_detail_desktop')}</p>
        </>
    )
}

type Word = 'auto' | 'on' | 'off'
const words = (): { value: Word; label: string }[] => [
    { value: 'auto', label: t('interface.layout_auto') },
    { value: 'on', label: t('common.on') },
    { value: 'off', label: t('common.off') },
]

/**
 * Reduce motion and High contrast (WP-60): Automatic follows what the system asks for (the browser's
 * prefers-reduced-motion and prefers-contrast), On and Off are the viewer's own word and win over it.
 */
export function LookChoices() {
    const systemReduces = () => typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches
    const reduced = () => (settings().reduceMotion === 'on' ? true : settings().reduceMotion === 'off' ? false : systemReduces())
    return (
        <>
            <div class="set-block" id={rowDomId('reduce_motion')}>
                <h3 class="set-block-title" id="motion-label">{t('settings.reduce_motion')}</h3>
                <p class="muted small">{reduced() ? t('settings.reduce_motion_on') : t('settings.reduce_motion_off')}</p>
                <Choices label="motion-label" name="motion" options={words()} value={settings().reduceMotion} onPick={(v) => setSetting('reduceMotion', v)} />
            </div>
            <div class="set-block" id={rowDomId('high_contrast')}>
                <h3 class="set-block-title" id="contrast-label">{t('settings.high_contrast')}</h3>
                <p class="muted small">{t('settings.high_contrast_detail')}</p>
                <Choices label="contrast-label" name="contrast" options={words()} value={settings().contrast} onPick={(v) => setSetting('contrast', v)} />
            </div>
        </>
    )
}
