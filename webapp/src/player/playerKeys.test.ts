// The player's key table (HX17) and the "?" sheet read back through it: PlayerKeysTest for the web.
// `pnpm test:player`.
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { actionFor, playerHearsKeys, sheetGroups, type Action, type KeyContext, type Press } from './playerKeys.ts'
import { PAGE_STRINGS } from '../../strings.config.ts'

const pc: KeyContext = { mac: false, chapters: false, paused: false, speedNormal: true, fullscreen: false, skipShown: false }
const mac: KeyContext = { ...pc, mac: true }
const act = (p: Press, ctx: Partial<KeyContext> = {}) => actionFor(p, { ...pc, ...ctx })

test('the seek ladder: 5 s, 10 s with Alt or J and L, a minute with Shift or Ctrl, 5 min with Ctrl+Alt', () => {
    assert.deepEqual(act({ key: 'ArrowRight' }), { do: 'seek', by: 5 })
    assert.deepEqual(act({ key: 'ArrowLeft' }), { do: 'seek', by: -5 })
    assert.deepEqual(act({ key: 'ArrowRight', alt: true }), { do: 'seek', by: 10 })
    assert.deepEqual(act({ key: 'j' }), { do: 'seek', by: -10 })
    assert.deepEqual(act({ key: 'L', shift: true }), { do: 'seek', by: 10 })
    assert.deepEqual(act({ key: 'ArrowRight', shift: true }), { do: 'seek', by: 60 })
    assert.deepEqual(act({ key: 'ArrowLeft', ctrl: true }), { do: 'seek', by: -60 })
    assert.deepEqual(act({ key: 'ArrowRight', ctrl: true, alt: true }), { do: 'seek', by: 300 })
    // A Mac: Cmd+Shift+Option is the 5 minute step, Cmd alone the minute without chapters.
    assert.deepEqual(actionFor({ key: 'ArrowLeft', meta: true, shift: true, alt: true }, mac), { do: 'seek', by: -300 })
    assert.deepEqual(actionFor({ key: 'ArrowRight', meta: true }, mac), { do: 'seek', by: 60 })
})

test('the command key with an arrow steps through chapters where the file has them', () => {
    assert.deepEqual(act({ key: 'ArrowRight', ctrl: true }, { chapters: true }), { do: 'chapter', forward: true })
    assert.deepEqual(actionFor({ key: 'ArrowLeft', meta: true }, { ...mac, chapters: true }), { do: 'chapter', forward: false })
    // Ctrl on a Mac is not its command key: a minute, as Shift.
    assert.deepEqual(actionFor({ key: 'ArrowLeft', ctrl: true }, { ...mac, chapters: true }), { do: 'seek', by: -60 })
})

test('Backspace puts a changed speed back first, then leaves; Shift+Backspace undoes a long jump', () => {
    assert.deepEqual(act({ key: 'Backspace' }, { speedNormal: false }), { do: 'speedReset' })
    assert.deepEqual(act({ key: 'Backspace' }), { do: 'back' })
    assert.deepEqual(act({ key: 'Backspace', shift: true }), { do: 'undoSeek' })
})

test('Shift+3 is the next audio, a digit a tenth of the way in', () => {
    assert.deepEqual(act({ key: '#', code: 'Digit3', shift: true }), { do: 'audio', back: false })
    assert.deepEqual(act({ key: '£', code: 'Digit3', shift: true }), { do: 'audio', back: false })
    assert.deepEqual(act({ key: '3', code: 'Digit3' }), { do: 'jump', tenth: 3 })
    // A French keyboard: the digit row types & unshifted, digits with Shift.
    assert.deepEqual(act({ key: '&', code: 'Digit1' }), { do: 'jump', tenth: 1 })
    assert.deepEqual(act({ key: '7', code: 'Digit7', shift: true }), { do: 'jump', tenth: 7 })
    assert.deepEqual(act({ key: '0', code: 'Numpad0' }), { do: 'jump', tenth: 0 })
})

test('Cmd or Ctrl+G goes to a typed time, Cmd or Ctrl+P picture in picture', () => {
    assert.deepEqual(act({ key: 'g', ctrl: true }), { do: 'goToTime' })
    assert.deepEqual(actionFor({ key: 'g', meta: true }, mac), { do: 'goToTime' })
    assert.deepEqual(act({ key: 'p', ctrl: true }), { do: 'pip' })
    assert.deepEqual(actionFor({ key: 'p', meta: true }, mac), { do: 'pip' })
    // Ctrl+G on a Mac is not Cmd+G.
    assert.equal(actionFor({ key: 'g', ctrl: true }, mac), null)
})

test('the zoom reads the physical key, since Option+= on a Mac types another character', () => {
    assert.deepEqual(act({ key: '=', code: 'Equal', alt: true }), { do: 'zoom', steps: 1 })
    assert.deepEqual(actionFor({ key: '≠', code: 'Equal', alt: true }, mac), { do: 'zoom', steps: 1 })
    assert.deepEqual(act({ key: '+', code: 'Equal', alt: true, shift: true }), { do: 'zoom', steps: 1 })
    assert.deepEqual(act({ key: '-', code: 'Minus', alt: true }), { do: 'zoom', steps: -1 })
    assert.deepEqual(act({ key: '-', code: 'NumpadSubtract', alt: true }), { do: 'zoom', steps: -1 })
    assert.deepEqual(act({ key: '0', code: 'Digit0', alt: true }), { do: 'zoomReset' })
    assert.deepEqual(actionFor({ key: 'º', code: 'Digit0', alt: true }, mac), { do: 'zoomReset' })
    assert.deepEqual(act({ key: 'Backspace', alt: true }), { do: 'zoomReset' })
})

test('S takes a screenshot with the subtitles, Shift+S without', () => {
    assert.deepEqual(act({ key: 's', code: 'KeyS' }), { do: 'screenshot', subtitles: true })
    assert.deepEqual(act({ key: 'S', code: 'KeyS', shift: true }), { do: 'screenshot', subtitles: false })
})

test('the keys everyone owns are never taken: copy, paste, undo, select all, find, the page zoom, closing the tab', () => {
    for (const key of ['c', 'v', 'x', 'z', 'a', 'y', 'f', '-', '=', '+', '0', 'w', 't', 'n', 'q', 'r', 'l']) {
        assert.equal(act({ key, ctrl: true }), null, `Ctrl+${key}`)
        assert.equal(actionFor({ key, meta: true }, mac), null, `Cmd+${key}`)
    }
    // Alt+F and Alt+D open the browser's menu and address bar on Windows and Linux.
    assert.equal(act({ key: 'f', alt: true }), null)
    assert.equal(act({ key: 'd', alt: true }), null)
    assert.equal(act({ key: 'Tab' }), null)
    assert.equal(act({ key: 'F5' }), null)
})

test('frame steps only while paused, G only while a skip is offered', () => {
    assert.equal(act({ key: ',' }), null)
    assert.deepEqual(act({ key: ',' }, { paused: true }), { do: 'frame', forward: false })
    assert.deepEqual(act({ key: '.' }, { paused: true }), { do: 'frame', forward: true })
    assert.equal(act({ key: 'g' }), null)
    assert.deepEqual(act({ key: 'g' }, { skipShown: true }), { do: 'skip' })
})

test('a confirm question, a field being typed in or a control that took the key: the player hears nothing', () => {
    assert.equal(playerHearsKeys({ handled: false, typing: false, confirm: false }), true)
    // Escape answers the question; it never reaches the player's Back behind it.
    assert.equal(playerHearsKeys({ handled: false, typing: false, confirm: true }), false)
    assert.equal(playerHearsKeys({ handled: false, typing: true, confirm: false }), false)
    assert.equal(playerHearsKeys({ handled: true, typing: false, confirm: false }), false)
})

test('Escape leaves full screen first, then the player', () => {
    assert.deepEqual(act({ key: 'Escape' }, { fullscreen: true }), { do: 'exitFullscreen' })
    assert.deepEqual(act({ key: 'Escape' }), { do: 'back' })
})

// Core's catalog, flattened to section.key, and the page's picks of it.
const catalog = JSON.parse(readFileSync(new URL('../../../core/src/commonMain/resources/i18n/en.json', import.meta.url), 'utf8')) as Record<string, Record<string, string>>
const has = (k: string) => typeof catalog[k.split('.')[0]]?.[k.slice(k.indexOf('.') + 1)] === 'string'
const onPage = (k: string) => (PAGE_STRINGS as readonly string[]).includes(k) || (PAGE_STRINGS as readonly string[]).includes(k.split('.')[0])

for (const isMac of [false, true]) {
    test(`every line of the sheet does what it says (${isMac ? 'Mac' : 'Windows and Linux'})`, () => {
        // Paused (frame steps), a skip offered (G), chapters in the file (the command arrows), speed normal (Backspace is Back).
        const ctx: KeyContext = { ...pc, mac: isMac, paused: true, skipShown: true, chapters: true }
        // Each line's presses, in order, and what each must do.
        const does: Record<string, Action[]> = {
            'keys.row_play_pause': [{ do: 'togglePlay' }, { do: 'togglePlay' }],
            'keys.row_seek_5s': [{ do: 'seek', by: -5 }, { do: 'seek', by: 5 }],
            'keys.row_seek_10s': [{ do: 'seek', by: -10 }, { do: 'seek', by: 10 }],
            'keys.row_seek_1min': [{ do: 'seek', by: -60 }, { do: 'seek', by: 60 }],
            'keys.row_speed': [{ do: 'speed', up: true }, { do: 'speed', up: false }, { do: 'speedReset' }],
            'keys.row_episodes': [{ do: 'episode', next: true }, { do: 'episode', next: false }],
            'player.skip_intro': [{ do: 'skip' }],
            'keys.row_jump': [{ do: 'jump', tenth: 0 }, { do: 'jump', tenth: 9 }],
            'player.go_to_time': [{ do: 'goToTime' }],
            'keys.row_undo_seek': [{ do: 'undoSeek' }],
            'keys.row_start_end': [{ do: 'jump', tenth: 0 }, { do: 'end' }],
            'keys.row_volume': [{ do: 'volume', by: 0.05 }, { do: 'volume', by: -0.05 }],
            'keys.row_mute': [{ do: 'mute' }],
            'keys.row_subtitles': [{ do: 'subtitles', back: false }, { do: 'subtitles', back: true }, { do: 'subtitlesToggle' }],
            'keys.row_audio': [{ do: 'audio', back: false }, { do: 'audio', back: true }],
            'keys.row_subtitle_delay': [{ do: 'subtitleDelay', by: -0.1 }, { do: 'subtitleDelay', by: 0.1 }],
            'keys.row_subtitle_size': [{ do: 'subtitleSize', by: -1 }, { do: 'subtitleSize', by: 1 }],
            'keys.row_fullscreen': [{ do: 'fullscreen' }],
            'player.picture_shape': [{ do: 'shape' }],
            'keys.row_zoom': [{ do: 'zoom', steps: 1 }, { do: 'zoom', steps: -1 }, { do: 'zoomReset' }],
            'player.picture_in_picture': [{ do: 'pip' }],
            'keys.row_screenshot': [{ do: 'screenshot', subtitles: true }, { do: 'screenshot', subtitles: false }],
            'keys.row_back': [{ do: 'back' }, { do: 'back' }],
            'keys.row_this_sheet': [{ do: 'sheet' }],
            'keys.row_seek_5min': [isMac ? { do: 'seek', by: -300 } : { do: 'seek', by: 300 }],
            'keys.row_chapter': [{ do: 'chapter', forward: false }, { do: 'chapter', forward: true }],
            'keys.row_frame_step': [{ do: 'frame', forward: false }, { do: 'frame', forward: true }],
            'player.rotate_picture': [{ do: 'rotate' }],
            'keys.row_ab_repeat': [{ do: 'repeat' }],
            'keys.row_bookmark': [{ do: 'bookmark' }],
            'keys.row_wheel_seek': [],
            'keys.row_details': [{ do: 'details' }],
        }
        // The speed line's Backspace is the reset: it is read with the speed changed.
        const changed: Partial<KeyContext> = { speedNormal: false }
        const groups = sheetGroups(isMac)
        assert.equal(groups.length, 4)
        const rows = groups.flatMap((g) => g.rows)
        assert.deepEqual(rows.map((r) => r.what).sort(), Object.keys(does).sort(), 'every line has its answers, and no more')
        for (const row of rows) {
            const at = { ...ctx, ...(row.what === 'keys.row_speed' ? changed : {}) }
            assert.deepEqual(row.presses.map((p) => actionFor(p, at)), does[row.what], row.what)
            assert.ok(row.keys || row.text, `${row.what} names its keys`)
        }
        // The wheel seek is the one line with no key: the wheel is read by the picture, not here.
        const keyless = groups.flatMap((g) => g.rows).filter((r) => !r.presses.length).map((r) => r.what)
        assert.deepEqual(keyless, ['keys.row_wheel_seek'])
    })

    test(`every word of the sheet is in the catalog and on the page (${isMac ? 'Mac' : 'Windows and Linux'})`, () => {
        const words = new Set<string>()
        for (const g of sheetGroups(isMac)) {
            words.add(g.group)
            if (g.note) words.add(g.note)
            for (const r of g.rows) {
                words.add(r.what)
                if (r.mod) words.add(r.mod)
                if (r.keys) {
                    words.add(r.keys.key)
                    for (const v of Object.values(r.keys.params ?? {})) words.add(v)
                }
            }
        }
        for (const w of words) {
            assert.ok(has(w), `${w} is in en.json`)
            assert.ok(onPage(w), `${w} is in strings.config.ts`)
        }
    })
}
