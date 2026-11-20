import { addedIds, addedMode, settings, setSetting, type Theme } from '../../store'
import { t } from '../../i18n'
import { layout, setLayout, type Layout } from '../../form'
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

/** The layout's choices, labelled by the heading `layout-label`, and the line that says what Desktop is. */
export function LayoutChoices() {
    return (
        <>
            <Choices
                label="layout-label"
                name="layout"
                options={LAYOUTS.map((v) => ({ value: v, label: t(`settings.layout_${v}`) }))}
                value={layout()}
                onPick={setLayout}
            />
            <p class="muted small">{t('settings.layout_detail_desktop')}</p>
        </>
    )
}
