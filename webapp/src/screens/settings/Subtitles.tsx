import { createSignal, onMount, Show } from 'solid-js'
import { t } from '../../i18n'
import { msoneOn } from '../../msoneFlag'
import { Choices } from '../../ui/SettingsParts'
import { COLOUR_HEX, DEFAULT_LOOK, readLook, writeLook, type SubtitleLook } from '../../player/keep'
import { MsoneRow, SubmakerBlock } from './Playback'
import { rowDomId } from '../SettingsFind'

const SIZES: SubtitleLook['size'][] = ['small', 'medium', 'large', 'extra_large']
const COLOURS: SubtitleLook['colour'][] = ['white', 'yellow', 'cyan', 'green', 'pink']
const EDGES: SubtitleLook['edge'][] = ['outline', 'shadow', 'none']
const BOXES: SubtitleLook['box'][] = [0, 35, 60, 80, 100]
const POSITIONS: SubtitleLook['position'][] = ['bottom', 'raised', 'high']
/** The sizes the preview draws, in rem so the page's text size counts (the player's own are of the video). */
const PREVIEW = { small: 0.9375, medium: 1.125, large: 1.375, extra_large: 1.625 }

/** What the preview shows: the player's edge, colour and box over the page's own ground. */
export function previewStyle(l: SubtitleLook) {
    const edge = l.edge === 'outline' ? '0 0 3px #000, 0 0 3px #000, 1px 1px 2px #000, -1px -1px 2px #000' : l.edge === 'shadow' ? '2px 2px 4px rgba(0,0,0,.9)' : 'none'
    return { 'font-size': `${PREVIEW[l.size]}rem`, color: COLOUR_HEX[l.colour], background: `rgba(0,0,0,${l.box / 100})`, 'text-shadow': edge }
}

/**
 * Subtitles and audio (WP-60, the apps' page of that name): how subtitles look, the same choices the
 * player's "Subtitle style and timing" holds and the same stored look, and the online subtitles the
 * viewer's own addon gives. The web player has no stored language list, so none is shown here.
 */
export function SubtitlesPage() {
    return (
        <>
            <div class="set-card set-blocks">
                <SubtitleStyle />
            </div>
            <Show when={msoneOn()}>
                <div class="set-card">
                    <MsoneRow />
                </div>
            </Show>
            <div class="set-card set-blocks">
                <h3 class="set-card-title">{t('online.title')}</h3>
                <SubmakerBlock />
            </div>
        </>
    )
}

function SubtitleStyle() {
    const [look, setLook] = createSignal<SubtitleLook>(DEFAULT_LOOK)
    onMount(() => void readLook().then(setLook))
    const set = <K extends keyof SubtitleLook>(key: K, value: SubtitleLook[K]) => {
        const next = { ...look(), [key]: value }
        setLook(next)
        writeLook(next)
    }
    const isDefault = () => JSON.stringify(look()) === JSON.stringify(DEFAULT_LOOK)
    const row = <K extends keyof SubtitleLook>(key: K, title: string, values: SubtitleLook[K][], text: (v: SubtitleLook[K]) => string) => (
        <div class="set-field">
            <h4 class="set-field-title" id={`look-${key}-label`}>{title}</h4>
            <Choices label={`look-${key}-label`} name={`look-${key}`} options={values.map((v) => ({ value: v, label: text(v) }))} value={look()[key]} onPick={(v) => set(key, v)} />
        </div>
    )
    return (
        <div class="set-block" id={rowDomId('subtitle_style')}>
            <h3 class="set-block-title">{t('settings.subtitle_style')}</h3>
            <div class="look-preview" aria-hidden="true">
                <span style={previewStyle(look())}>{t('subtitles.preview')}</span>
            </div>
            {row('size', t('subtitles.size'), SIZES, (v) => t(`subtitles.size_${v}`))}
            {row('colour', t('subtitles.colour'), COLOURS, (v) => t(`subtitles.colour_${v}`))}
            {row('edge', t('subtitles.edge'), EDGES, (v) => t(`subtitles.edge_${v}`))}
            {row('box', t('subtitles.box'), BOXES, (v) => (v === 0 ? t('common.off') : t('subtitles.box_percent', { percent: v })))}
            {row('position', t('subtitles.position'), POSITIONS, (v) => t(`subtitles.position_${v}`))}
            <Show when={!isDefault()}>
                <button class="outline-button" style={{ 'align-self': 'start' }} type="button" onClick={() => (setLook(DEFAULT_LOOK), writeLook(DEFAULT_LOOK))}>{t('subtitles.default_look')}</button>
            </Show>
        </div>
    )
}
