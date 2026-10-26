// The sheets behind the player's menu that are more than a list of choices: bookmarks (the app's
// PlayerBookmarkSheets), subtitle style and timing (the "Subtitle style and timing" page), the
// desktop's "?" key sheet (ShortcutSheet) and "Still watching?". The picker, menu and sheet
// frames are sheets.tsx's.
import { createSignal, For, Show } from 'solid-js'
import { Add01Icon, Cancel01Icon, Delete02Icon, MinusSignIcon, PencilEdit02Icon } from '@hugeicons/core-free-icons'
import { Icon } from '../ui/Icon'
import { t } from '../i18n'
import { PhoneSheet } from './sheets'
import { COLOUR_HEX, DEFAULT_LOOK, MAX_NOTE, type Bookmark, type SubtitleLook } from './keep'

/** SeekMath.clock, passed in so this file does not import the controls that import it. */
type Clock = (seconds: number) => string

export function BookmarksSheet(props: {
    list: () => Bookmark[]
    clock: Clock
    onHere: () => void
    onPlay: (b: Bookmark) => void
    onNote: (b: Bookmark, note: string) => void
    onDelete: (b: Bookmark) => void
    onClose: () => void
}) {
    const [editing, setEditing] = createSignal<number | null>(null)
    let input: HTMLInputElement | undefined
    const save = (b: Bookmark) => {
        props.onNote(b, input?.value ?? '')
        setEditing(null)
    }
    return (
        <PhoneSheet title={t('player.bookmarks')} note={t('player.bookmarks_count', { count: props.list().length })} onClose={props.onClose} class="bookmarks">
            <button type="button" class="pc-choice" onClick={props.onHere}>
                <span class="pc-choice-icon"><Icon icon={Add01Icon} size={22} /></span>
                <span>{t('player.add_bookmark')}</span>
            </button>
            <Show when={props.list().length} fallback={<p class="pc-empty">{t('player.bookmarks_empty')}</p>}>
                <ul class="pc-bookmarks">
                    <For each={props.list()}>
                        {(b) => (
                            <li>
                                <Show
                                    when={editing() === b.id}
                                    fallback={
                                        <>
                                            <button type="button" class="pc-bookmark" onClick={() => props.onPlay(b)} aria-label={`${t('player.bookmark_play')}: ${props.clock(b.at)}`}>
                                                <strong>{props.clock(b.at)}</strong>
                                                <span>{b.note || t('player.bookmark_add_note')}</span>
                                            </button>
                                            <button type="button" class="pc-round small" aria-label={b.note ? t('player.bookmark_edit_note') : t('player.bookmark_add_note')} onClick={() => setEditing(b.id)}>
                                                <Icon icon={PencilEdit02Icon} size={20} />
                                            </button>
                                            <button type="button" class="pc-round small" aria-label={t('player.bookmark_delete')} onClick={() => props.onDelete(b)}>
                                                <Icon icon={Delete02Icon} size={20} />
                                            </button>
                                        </>
                                    }
                                >
                                    <form
                                        class="pc-note-edit"
                                        onSubmit={(e) => {
                                            e.preventDefault()
                                            save(b)
                                        }}
                                    >
                                        <label>
                                            <span>{t('player.bookmark_note_title', { time: props.clock(b.at) })}</span>
                                            <input ref={(el) => (input = el, queueMicrotask(() => el.focus()))} type="text" maxLength={MAX_NOTE} value={b.note} placeholder={t('player.bookmark_note_hint')} />
                                        </label>
                                        <button type="submit" class="pc-sheet-button primary">{t('player.bookmark_save_note')}</button>
                                    </form>
                                </Show>
                            </li>
                        )}
                    </For>
                </ul>
            </Show>
        </PhoneSheet>
    )
}

const SIZES: SubtitleLook['size'][] = ['small', 'medium', 'large', 'extra_large']
const COLOURS: SubtitleLook['colour'][] = ['white', 'yellow', 'cyan', 'green', 'pink']
const EDGES: SubtitleLook['edge'][] = ['outline', 'shadow', 'none']
const BOXES: SubtitleLook['box'][] = [0, 35, 60, 80, 100]
const POSITIONS: SubtitleLook['position'][] = ['bottom', 'raised', 'high']
export { SIZES }

/** "+0.3 s", "-1.2 s". */
export const delayText = (d: number) => `${d > 0 ? '+' : ''}${t('format.seconds_short', { seconds: d.toFixed(1) })}`

function Row<T extends string | number>(props: { label: string; values: T[]; current: T; text: (v: T) => string; onPick: (v: T) => void }) {
    return (
        <div class="pc-opt">
            <div class="pc-opt-label">{props.label}</div>
            <div class="pc-tiles opts" role="radiogroup" aria-label={props.label}>
                <For each={props.values}>
                    {(v) => (
                        <button type="button" class="pc-tile" role="radio" aria-checked={props.current === v} onClick={() => props.onPick(v)}>
                            {props.text(v)}
                        </button>
                    )}
                </For>
            </div>
        </div>
    )
}

export function TimingSheet(props: {
    delay: () => number
    onDelay: (d: number) => void
    look: () => SubtitleLook
    onLook: (l: SubtitleLook) => void
    onClose: () => void
}) {
    const step = (up: boolean) => props.onDelay(Math.round((props.delay() + (up ? 0.1 : -0.1)) * 10) / 10)
    const set = <K extends keyof SubtitleLook>(key: K, value: SubtitleLook[K]) => props.onLook({ ...props.look(), [key]: value })
    const isDefault = () => JSON.stringify(props.look()) === JSON.stringify(DEFAULT_LOOK)
    return (
        <PhoneSheet title={t('player.subtitle_style_and_timing')} onClose={props.onClose} class="timing">
            <div class="pc-fine">
                <div class="pc-fine-head">{t('tracks.section_timing')}</div>
                <div class="pc-fine-detail">{props.delay() === 0 ? t('tracks.delay_none') : t('tracks.delay_step', { delay: delayText(props.delay()) })}</div>
                <div class="pc-fine-row">
                    <button type="button" class="pc-round" aria-label={t('tracks.subtitles_earlier')} onClick={() => step(false)}>
                        <Icon icon={MinusSignIcon} size={24} />
                    </button>
                    <span class="pc-fine-value" aria-live="polite">{delayText(props.delay())}</span>
                    <button type="button" class="pc-round" aria-label={t('tracks.subtitles_later')} onClick={() => step(true)}>
                        <Icon icon={Add01Icon} size={24} />
                    </button>
                </div>
                <Show when={props.delay() !== 0}>
                    <button type="button" class="pc-sheet-button" onClick={() => props.onDelay(0)}>{t('tracks.reset_timing')}</button>
                </Show>
            </div>
            <div class="pc-fine">
                <div class="pc-fine-head">{t('tracks.section_look')}</div>
                <div class="pc-cue-preview" style={previewStyle(props.look())}>{t('subtitles.preview')}</div>
                <Row label={t('subtitles.size')} values={SIZES} current={props.look().size} text={(v) => t(`subtitles.size_${v}`)} onPick={(v) => set('size', v)} />
                <Row label={t('subtitles.colour')} values={COLOURS} current={props.look().colour} text={(v) => t(`subtitles.colour_${v}`)} onPick={(v) => set('colour', v)} />
                <Row label={t('subtitles.edge')} values={EDGES} current={props.look().edge} text={(v) => t(`subtitles.edge_${v}`)} onPick={(v) => set('edge', v)} />
                <Row label={t('subtitles.box')} values={BOXES} current={props.look().box} text={(v) => (v === 0 ? t('common.off') : t('subtitles.box_percent', { percent: v }))} onPick={(v) => set('box', v)} />
                <Row label={t('subtitles.position')} values={POSITIONS} current={props.look().position} text={(v) => t(`subtitles.position_${v}`)} onPick={(v) => set('position', v)} />
                <Show when={!isDefault()}>
                    <button type="button" class="pc-sheet-button" onClick={() => props.onLook(DEFAULT_LOOK)}>{t('subtitles.default_look')}</button>
                </Show>
            </div>
        </PhoneSheet>
    )
}

const previewSize = { small: 15, medium: 18, large: 22, extra_large: 26 }
function previewStyle(l: SubtitleLook) {
    const edge = l.edge === 'outline' ? '0 0 3px #000, 0 0 3px #000, 1px 1px 2px #000, -1px -1px 2px #000' : l.edge === 'shadow' ? '2px 2px 4px rgba(0,0,0,.9)' : 'none'
    return { 'font-size': `${previewSize[l.size]}px`, color: COLOUR_HEX[l.colour], background: `rgba(0,0,0,${l.box / 100})`, 'text-shadow': edge }
}

/** PlayerKeys.sheetGroups, for the keys this page answers. */
export function ShortcutsSheet(props: { mac: boolean; onClose: () => void }) {
    const cmd = t(props.mac ? 'keys.cmd' : 'keys.ctrl')
    const groups: [string, [string, string][]][] = [
        [
            t('keys.group_playback'),
            [
                [t('keys.row_play_pause'), t('keys.space_k')],
                [t('keys.row_seek_5s'), t('keys.left_right')],
                [t('keys.row_seek_10s'), 'J, L'],
                [t('keys.row_seek_1min'), t('keys.shift_left_right')],
                [t('keys.row_speed'), t('keys.speed_keys')],
                [t('keys.row_episodes'), t('keys.episodes_keys')],
                [t('player.skip_intro'), 'G'],
                [t('keys.row_jump'), t('keys.digits')],
                [t('keys.row_undo_seek'), t('keys.shift_backspace')],
                [t('keys.row_start_end'), t('keys.home_end')],
            ],
        ],
        [
            t('keys.group_audio_subtitles'),
            [
                [t('keys.row_volume'), t('keys.volume_wheel')],
                [t('keys.row_mute'), 'M'],
                [t('keys.row_subtitles'), t('keys.subtitles_keys')],
                [t('keys.row_audio'), t('keys.audio_keys')],
                [t('keys.row_subtitle_delay'), 'Z, X'],
                [t('keys.row_subtitle_size'), t('keys.subtitle_size_keys')],
            ],
        ],
        [
            t('keys.group_window'),
            [
                [t('keys.row_fullscreen'), t('keys.fullscreen_keys')],
                [t('player.picture_shape'), 'W'],
                [t('keys.row_back'), t('keys.back_keys')],
                [t('keys.row_this_sheet'), '?'],
            ],
        ],
        [
            t('keys.group_other'),
            [
                [t('keys.row_seek_5min'), t(props.mac ? 'keys.seek_5min_mac' : 'keys.seek_5min')],
                [t('keys.row_chapter'), t('keys.chapter', { cmd })],
                [t('keys.row_frame_step'), t('keys.comma_period')],
                [t('keys.row_ab_repeat'), 'R'],
                [t('keys.row_bookmark'), 'B'],
                [t('keys.row_wheel_seek'), t('keys.shift_wheel')],
                [t('keys.row_details'), 'I'],
            ],
        ],
    ]
    return (
        <div class="pc-sheet-scrim shortcuts" onClick={(e) => e.target === e.currentTarget && props.onClose()}>
            <div class="pc-sheet pc-shortcuts" role="dialog" aria-label={t('player.shortcuts')}>
                <div class="pc-sheet-head">
                    <div><h2>{t('player.shortcuts')}</h2></div>
                    <button type="button" class="pc-sheet-close" aria-label={t('common.close')} onClick={props.onClose}>
                        <Icon icon={Cancel01Icon} size={24} />
                    </button>
                </div>
                <div class="pc-shortcuts-body">
                    <For each={groups}>
                        {([name, rows]) => (
                            <section>
                                <h3>{name}</h3>
                                <For each={rows}>
                                    {([what, keys]) => (
                                        <div class="pc-key-row">
                                            <span>{what}</span>
                                            <kbd>{keys}</kbd>
                                        </div>
                                    )}
                                </For>
                            </section>
                        )}
                    </For>
                </div>
            </div>
        </div>
    )
}
