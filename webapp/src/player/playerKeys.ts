// The web player's keyboard (HX17): the desktop app's PlayerKeys.actionFor, the part a browser
// can do, as one pure table so playerKeys.test.ts can read every key back, and the "?" sheet's
// rows (sheetGroups) are checked against it: the sheet cannot promise a key the player lacks.
//
// What differs from the desktop app, and why: Ctrl+- and Ctrl+= stay the browser's page zoom
// (the native <video> has no sound delay to move, the sheet says so); Cmd+Q, Cmd+W, Cmd+T and
// Cmd+N stay the browser's; T (always on top) has no browser twin; Cmd or Ctrl+P is picture in
// picture where the browser has it, the web's small window, instead of the desktop's mini player
// (Chrome.tsx lets print through where it has not). Pure: no DOM, no strings, so node --test runs it.

export interface Press {
    /** KeyboardEvent.key */
    key: string
    /** KeyboardEvent.code: the zoom keys read it, since Option+= on a Mac types '≠'. */
    code?: string
    shift?: boolean
    ctrl?: boolean
    alt?: boolean
    meta?: boolean
}

export interface KeyContext {
    mac: boolean
    /** The file has chapters: the command key with an arrow steps through them, else it is a minute. */
    chapters: boolean
    paused: boolean
    speedNormal: boolean
    fullscreen: boolean
    /** A skip offer (intro, recap, credits) is up: G takes it. */
    skipShown: boolean
}

export type Action =
    | { do: 'togglePlay' }
    | { do: 'seek'; by: number }
    | { do: 'chapter'; forward: boolean }
    | { do: 'volume'; by: number }
    | { do: 'mute' }
    | { do: 'episode'; next: boolean }
    | { do: 'speed'; up: boolean }
    | { do: 'speedReset' }
    | { do: 'frame'; forward: boolean }
    | { do: 'subtitles'; back: boolean }
    | { do: 'subtitlesToggle' }
    | { do: 'audio'; back: boolean }
    | { do: 'subtitleDelay'; by: number }
    | { do: 'subtitleSize'; by: 1 | -1 }
    | { do: 'fullscreen' }
    | { do: 'exitFullscreen' }
    | { do: 'shape' }
    | { do: 'rotate' }
    | { do: 'zoom'; steps: 1 | -1 }
    | { do: 'zoomReset' }
    | { do: 'screenshot'; subtitles: boolean }
    | { do: 'repeat' }
    | { do: 'bookmark' }
    | { do: 'skip' }
    | { do: 'goToTime' }
    | { do: 'undoSeek' }
    | { do: 'pip' }
    | { do: 'details' }
    | { do: 'sheet' }
    | { do: 'jump'; tenth: number }
    | { do: 'end' }
    | { do: 'back' }

export const SEEK_SHORT = 5
export const SEEK_MEDIUM = 10
export const SEEK_LONG = 60
export const SEEK_HUGE = 300
export const VOLUME_STEP = 0.05
/** A frame at 30 frames a second: the browser cannot say a video's own rate. */
export const FRAME = 1 / 30

const DIGIT_CODE = /^(?:Digit|Numpad)([0-9])$/

/**
 * Whether the player hears a key at all: not one a control already took (`handled`), not one
 * typed into a field, and nothing while a confirm question is up, so its Escape answers it and
 * never closes the player behind.
 */
export const playerHearsKeys = (s: { handled: boolean; typing: boolean; confirm: boolean }) => !s.handled && !s.typing && !s.confirm

/** What a key does in the player, or null for a key it leaves to the page and the browser. */
export function actionFor(p: Press, ctx: KeyContext): Action | null {
    const { key, code = '' } = p
    const shift = !!p.shift
    const ctrl = !!p.ctrl
    const alt = !!p.alt
    const meta = !!p.meta
    // The command modifier: Cmd on a Mac, Ctrl elsewhere.
    const command = ctx.mac ? meta : ctrl
    const plain = !shift && !ctrl && !alt && !meta
    const lower = key.length === 1 ? key.toLowerCase() : key

    // Window level keys first, since they carry modifiers the rest would misread.
    if (command && !alt && lower === 'p') return { do: 'pip' }
    if (command && !shift && !alt && lower === 'g') return { do: 'goToTime' }
    if (alt && key === 'Enter') return { do: 'fullscreen' }
    // The zoom: mpv's Alt+= and Alt+- (Option on a Mac), Alt+0 and Alt+Backspace back to 100 per cent.
    if (alt && !ctrl && !meta) {
        if (code === 'Equal' || code === 'NumpadAdd') return { do: 'zoom', steps: 1 }
        if ((code === 'Minus' || code === 'NumpadSubtract') && !shift) return { do: 'zoom', steps: -1 }
        if ((code === 'Digit0' || code === 'Numpad0' || key === 'Backspace') && !shift) return { do: 'zoomReset' }
    }

    switch (key) {
        case 'ArrowLeft':
        case 'ArrowRight': {
            const forward = key === 'ArrowRight'
            // Chapters on the command key alone, as YouTube has them; a file without chapters seeks a minute.
            if (command && !shift && !alt) return ctx.chapters ? { do: 'chapter', forward } : { do: 'seek', by: forward ? SEEK_LONG : -SEEK_LONG }
            const huge = ctx.mac ? meta && shift && alt : ctrl && alt
            const s = huge ? SEEK_HUGE : shift || ctrl ? SEEK_LONG : alt ? SEEK_MEDIUM : SEEK_SHORT
            return { do: 'seek', by: forward ? s : -s }
        }
        case 'ArrowUp':
            return { do: 'volume', by: VOLUME_STEP }
        case 'ArrowDown':
            return { do: 'volume', by: -VOLUME_STEP }
        case 'MediaPlayPause':
            return { do: 'togglePlay' }
        case 'MediaTrackNext':
        case 'PageDown':
            return { do: 'episode', next: true }
        case 'MediaTrackPrevious':
        case 'PageUp':
            return { do: 'episode', next: false }
        case 'Escape':
            return ctx.fullscreen ? { do: 'exitFullscreen' } : { do: 'back' }
        // mpv's revert seek on Shift; Backspace alone puts a changed speed back, or leaves.
        case 'Backspace':
            if (shift && !ctrl && !alt && !meta) return { do: 'undoSeek' }
            if (!plain) return null
            return ctx.speedNormal ? { do: 'back' } : { do: 'speedReset' }
        case 'Home':
            return plain ? { do: 'jump', tenth: 0 } : null
        case 'End':
            return plain ? { do: 'end' } : null
    }
    // Every other chord with Ctrl, Alt or Cmd is the browser's or the system's (Ctrl+C, V, X, Z,
    // A, F, Ctrl+- and Ctrl+= among them), never a player key.
    if (ctrl || alt || meta) return null

    // A digit typed is a tenth of the way in, Shift or not (a French keyboard needs Shift for
    // one); the digit row unshifted is one too, whatever it types (& on that keyboard).
    if (/^[0-9]$/.test(key)) return { do: 'jump', tenth: Number(key) }
    // Shift+3 is '#' on most layouts (£ on a British one), mpv's audio cycle.
    if (key === '#' || (shift && code === 'Digit3')) return { do: 'audio', back: false }
    const row = DIGIT_CODE.exec(code)?.[1]
    if (row !== undefined && !shift) return { do: 'jump', tenth: Number(row) }

    switch (key) {
        case ' ':
        case 'Enter':
            return plain ? { do: 'togglePlay' } : null
        case '?':
            return { do: 'sheet' }
        // The bracket, minus and equal keys with Shift type } { _ and +: the same keys, as core's table reads them by their place.
        case ']':
        case '}':
        case '=':
        case '+':
        case '>':
            return { do: 'speed', up: true }
        case '[':
        case '{':
        case '-':
        case '_':
        case '<':
            return { do: 'speed', up: false }
        case ',':
            return ctx.paused ? { do: 'frame', forward: false } : null
        case '.':
            return ctx.paused ? { do: 'frame', forward: true } : null
    }
    switch (lower) {
        case 'k':
            return plain ? { do: 'togglePlay' } : null
        case 'p':
            return shift ? { do: 'episode', next: false } : { do: 'togglePlay' }
        case 'n':
            return shift ? { do: 'episode', next: true } : null
        case 'j':
            return { do: 'seek', by: -SEEK_MEDIUM }
        case 'l':
            return { do: 'seek', by: SEEK_MEDIUM }
        case 'm':
            return { do: 'mute' }
        // Shift+F and Shift+G: smaller and bigger subtitles, as in mpv; F alone is full screen.
        case 'f':
            return shift ? { do: 'subtitleSize', by: -1 } : { do: 'fullscreen' }
        case 'g':
            return shift ? { do: 'subtitleSize', by: 1 } : ctx.skipShown ? { do: 'skip' } : null
        // mpv's screenshot keys: s with the subtitles, S the bare picture.
        case 's':
            return { do: 'screenshot', subtitles: !shift }
        case 'v':
            return { do: 'subtitles', back: shift }
        case 'c':
            return { do: 'subtitlesToggle' }
        case 'a':
            return { do: 'audio', back: shift }
        // R is A-B repeat; Shift+R turns the picture a quarter (Rotate picture), PiP and a screenshot following the turn.
        case 'r':
            return shift ? { do: 'rotate' } : { do: 'repeat' }
        case 'b':
            return shift ? null : { do: 'bookmark' }
        case 'w':
            return shift ? null : { do: 'shape' }
        // mpv's subtitle delay keys: z earlier, Z and x later.
        case 'z':
            return { do: 'subtitleDelay', by: shift ? 0.1 : -0.1 }
        case 'x':
            return { do: 'subtitleDelay', by: 0.1 }
        case 'i':
            return { do: 'details' }
    }
    return null
}

/** One line of the "?" sheet: catalog keys for its words and its keys, and the presses it promises. */
export interface SheetRow {
    /** The catalog key of what the line does. */
    what: string
    /** The catalog key of the keys' text, with its parameters (each a catalog key too: Cmd or Ctrl, Option or Alt). */
    keys?: { key: string; params?: Record<string, string> }
    /** Or the keys spelt as they are, after `mod` (a catalog key, Cmd or Ctrl) and a plus where given. */
    text?: string
    mod?: string
    /** Every press the line names, read back through actionFor by the test. */
    presses: Press[]
}

/** The sheet's groups, as the desktop's PlayerKeys.sheetGroups orders them, of the keys the web has. */
export function sheetGroups(mac: boolean): { group: string; rows: SheetRow[]; note?: string }[] {
    const cmd = mac ? 'keys.cmd' : 'keys.ctrl'
    const c = mac ? { meta: true } : { ctrl: true }
    const altWord = mac ? 'keys.option' : 'keys.alt'
    return [
        {
            group: 'keys.group_playback',
            rows: [
                { what: 'keys.row_play_pause', keys: { key: 'keys.space_k' }, presses: [{ key: ' ' }, { key: 'k' }] },
                { what: 'keys.row_seek_5s', keys: { key: 'keys.left_right' }, presses: [{ key: 'ArrowLeft' }, { key: 'ArrowRight' }] },
                { what: 'keys.row_seek_10s', text: 'J, L', presses: [{ key: 'j' }, { key: 'l' }] },
                { what: 'keys.row_seek_1min', keys: { key: 'keys.shift_left_right' }, presses: [{ key: 'ArrowLeft', shift: true }, { key: 'ArrowRight', shift: true }] },
                { what: 'keys.row_speed', keys: { key: 'keys.speed_keys' }, presses: [{ key: ']' }, { key: '[' }, { key: 'Backspace' }] },
                { what: 'keys.row_episodes', keys: { key: 'keys.episodes_keys' }, presses: [{ key: 'N', shift: true }, { key: 'P', shift: true }] },
                { what: 'player.skip_intro', text: 'G', presses: [{ key: 'g' }] },
                { what: 'keys.row_jump', keys: { key: 'keys.digits' }, presses: [{ key: '0', code: 'Digit0' }, { key: '9', code: 'Digit9' }] },
                { what: 'player.go_to_time', mod: cmd, text: 'G', presses: [{ key: 'g', ...c }] },
                { what: 'keys.row_undo_seek', keys: { key: 'keys.shift_backspace' }, presses: [{ key: 'Backspace', shift: true }] },
                { what: 'keys.row_start_end', keys: { key: 'keys.home_end' }, presses: [{ key: 'Home' }, { key: 'End' }] },
            ],
        },
        {
            group: 'keys.group_audio_subtitles',
            rows: [
                { what: 'keys.row_volume', keys: { key: 'keys.volume_wheel' }, presses: [{ key: 'ArrowUp' }, { key: 'ArrowDown' }] },
                { what: 'keys.row_mute', text: 'M', presses: [{ key: 'm' }] },
                { what: 'keys.row_subtitles', keys: { key: 'keys.subtitles_keys' }, presses: [{ key: 'v' }, { key: 'V', shift: true }, { key: 'c' }] },
                { what: 'keys.row_audio', keys: { key: 'keys.audio_keys' }, presses: [{ key: 'a' }, { key: 'A', shift: true }] },
                { what: 'keys.row_subtitle_delay', text: 'Z, X', presses: [{ key: 'z' }, { key: 'x' }] },
                { what: 'keys.row_subtitle_size', keys: { key: 'keys.subtitle_size_keys' }, presses: [{ key: 'F', shift: true }, { key: 'G', shift: true }] },
            ],
            // Ctrl+- and Ctrl+= move the sound in the apps; the browser's <video> cannot, and the keys stay its page zoom.
            note: 'web.audio_delay_unavailable',
        },
        {
            group: 'keys.group_window',
            rows: [
                { what: 'keys.row_fullscreen', keys: { key: 'keys.fullscreen_keys' }, presses: [{ key: 'f' }] },
                { what: 'player.picture_shape', text: 'W', presses: [{ key: 'w' }] },
                { what: 'player.rotate_picture', keys: { key: 'keys.shift_r' }, presses: [{ key: 'R', shift: true }] },
                {
                    what: 'keys.row_zoom',
                    keys: { key: 'keys.zoom_keys', params: { alt: altWord } },
                    presses: [{ key: '=', code: 'Equal', alt: true }, { key: '-', code: 'Minus', alt: true }, { key: '0', code: 'Digit0', alt: true }],
                },
                { what: 'player.picture_in_picture', mod: cmd, text: 'P', presses: [{ key: 'p', ...c }] },
                { what: 'keys.row_screenshot', keys: { key: 'keys.screenshot_keys' }, presses: [{ key: 's' }, { key: 'S', shift: true }] },
                { what: 'keys.row_back', keys: { key: 'keys.back_keys' }, presses: [{ key: 'Escape' }, { key: 'Backspace' }] },
                { what: 'keys.row_this_sheet', text: '?', presses: [{ key: '?', shift: true }] },
            ],
        },
        {
            group: 'keys.group_other',
            rows: [
                {
                    what: 'keys.row_seek_5min',
                    keys: { key: mac ? 'keys.seek_5min_mac' : 'keys.seek_5min' },
                    presses: mac ? [{ key: 'ArrowLeft', meta: true, shift: true, alt: true }] : [{ key: 'ArrowRight', ctrl: true, alt: true }],
                },
                { what: 'keys.row_chapter', keys: { key: 'keys.chapter', params: { cmd } }, presses: [{ key: 'ArrowLeft', ...c }, { key: 'ArrowRight', ...c }] },
                { what: 'keys.row_frame_step', keys: { key: 'keys.comma_period' }, presses: [{ key: ',' }, { key: '.' }] },
                { what: 'keys.row_ab_repeat', text: 'R', presses: [{ key: 'r' }] },
                { what: 'keys.row_bookmark', text: 'B', presses: [{ key: 'b' }] },
                { what: 'keys.row_wheel_seek', keys: { key: 'keys.shift_wheel' }, presses: [] },
                { what: 'keys.row_details', text: 'I', presses: [{ key: 'i' }] },
            ],
        },
    ]
}
