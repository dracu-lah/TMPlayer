// Policy (HX88): every page-wide input listener (the keyboard, the wheel, the pointer, the gamepad,
// clicks) goes through onGlobal (ui/globalInput.ts), which hears nothing while a profile is locked, so
// no key of a locked profile's page can play, skip or open its video. A new raw listener on window or
// document anywhere else fails here, as core's policy tests do; the few left are listed with why.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

const SRC = fileURLToPath(new URL('..', import.meta.url))

/** Files that may listen to the whole page themselves, and why. */
const ALLOWED: Record<string, string> = {
    'ui/globalInput.ts': 'the wrapper itself',
    'tv/focus.ts': "the TV remote's walk: while locked it moves only inside the topmost dialog (the picker), and its Play, colour and page Back keys check locked()",
    'crash.ts': "the crash card's own keys, drawn over everything with nothing of the profile",
    'ui/CardMenu.tsx': "turns the browser's own right click menu off; it opens nothing",
}

const EVENTS = String.raw`(?:key\w*|wheel|pointer\w*|mouse\w*|touch\w*|gamepad\w*|contextmenu|click|dblclick|auxclick)`
/** `window.addEventListener('keydown'`, `document.addEventListener('pointerdown'`, and a bare `addEventListener('keydown'` (the window's). */
const RAW = new RegExp(String.raw`(?:\b(?:window|document|doc|globalThis|self)\s*\.\s*|(?:^|[^.\w$]))addEventListener\(\s*['"\`]${EVENTS}['"\`]`, 'm')

function sources(dir: string): string[] {
    return readdirSync(dir).flatMap((name) => {
        const path = join(dir, name)
        if (statSync(path).isDirectory()) return name === 'rules' ? [] : sources(path)
        return /\.(ts|tsx)$/.test(name) && !/\.test\.ts$/.test(name) ? [path] : []
    })
}

test('no page-wide input listener outside onGlobal and the listed files', () => {
    const found: string[] = []
    for (const file of sources(SRC)) {
        const rel = relative(SRC, file).replaceAll('\\', '/')
        if (Object.hasOwn(ALLOWED, rel)) continue
        const text = readFileSync(file, 'utf8')
        text.split('\n').forEach((line, i) => {
            if (RAW.test(line)) found.push(`${rel}:${i + 1}: ${line.trim()}`)
        })
    }
    assert.deepEqual(found, [], `use onGlobal (ui/globalInput.ts) for these:\n${found.join('\n')}`)
})

test('the scan finds what it must', () => {
    for (const line of ["window.addEventListener('keydown', f)", "document.addEventListener('pointerdown', f, true)", "    addEventListener('keydown', keys)", 'doc.addEventListener("wheel", w)'])
        assert.ok(RAW.test(line), line)
    for (const line of ["video.addEventListener('play', f)", "el.addEventListener('keydown', f)", "window.addEventListener('resize', f)", "document.addEventListener('visibilitychange', f)"])
        assert.ok(!RAW.test(line), line)
})

test('every allowed file still exists and still needs it', () => {
    for (const rel of Object.keys(ALLOWED)) {
        const text = readFileSync(join(SRC, rel), 'utf8')
        assert.ok(rel === 'ui/globalInput.ts' || RAW.test(text), `${rel} no longer listens to the page: take it off the list`)
    }
})
