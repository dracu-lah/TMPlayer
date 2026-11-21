// Policy (HX88): every page-wide input listener (the keyboard, the wheel, the pointer, the gamepad,
// clicks) goes through onGlobal (ui/globalInput.ts), which hears nothing while a profile is locked, so
// no key of a locked profile's page can play, skip or open its video. A new raw listener on window or
// document anywhere else fails here, as core's policy tests do; the few left are listed with why.
// The scan parses each file with the TypeScript compiler, so a call split over several lines, an event
// name held in a variable or a loop, and document.body / documentElement are all seen.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'
import ts from 'typescript'

const SRC = fileURLToPath(new URL('..', import.meta.url))

/** Files that may listen to the whole page themselves, and why. */
const ALLOWED: Record<string, string> = {
    'ui/globalInput.ts': 'the wrapper itself',
    'tv/focus.ts': "the TV remote's walk: while locked it moves only inside the topmost dialog (the picker), and its Play, colour and page Back keys check locked()",
    'crash.ts': "the crash card's own keys, drawn over everything with nothing of the profile",
    'ui/CardMenu.tsx': "turns the browser's own right click menu off; it opens nothing",
}

/** Events that carry input. */
const EVENT = /^(?:key\w*|wheel|pointer\w*|mouse\w*|touch\w*|gamepad\w*|contextmenu|click|dblclick|auxclick)$/
/** What names the whole page (or the window's bare calls). */
const PAGE = new Set(['window', 'document', 'doc', 'globalThis', 'self', 'top', 'parent'])

function sources(dir: string): string[] {
    return readdirSync(dir).flatMap((name) => {
        const path = join(dir, name)
        if (statSync(path).isDirectory()) return name === 'rules' ? [] : sources(path)
        return /\.(ts|tsx)$/.test(name) && !/\.test\.ts$/.test(name) ? [path] : []
    })
}

const unwrap = (e: ts.Expression): ts.Expression => (ts.isParenthesizedExpression(e) || ts.isAsExpression(e) || ts.isNonNullExpression(e) || ts.isSatisfiesExpression(e) ? unwrap(e.expression) : e)

/** True for window, document, `document.body`, `document.documentElement`, `window.document` and the like. */
function isPage(e: ts.Expression): boolean {
    e = unwrap(e)
    if (ts.isIdentifier(e)) return PAGE.has(e.text)
    return ts.isPropertyAccessExpression(e) && ((isPage(e.expression) && ['document', 'body', 'documentElement', 'window'].includes(e.name.text)) || e.name.text === 'ownerDocument')
}

/** The strings an event argument can be: a literal, or a name read from a literal, a const or a `for (const e of [...])`. Null when unknown. */
function eventNames(arg: ts.Expression, file: ts.SourceFile): string[] | null {
    arg = unwrap(arg)
    if (ts.isStringLiteralLike(arg)) return [arg.text]
    if (!ts.isIdentifier(arg)) return null
    const name = arg.text
    let found: string[] | null = null
    const strings = (e: ts.Expression | undefined): string[] | null => {
        if (!e) return null
        e = unwrap(e)
        if (ts.isStringLiteralLike(e)) return [e.text]
        if (ts.isArrayLiteralExpression(e) && e.elements.every((x) => ts.isStringLiteralLike(unwrap(x)))) return e.elements.map((x) => (unwrap(x) as ts.StringLiteralLike).text)
        return null
    }
    // A name declared more than once in the file (another `e` elsewhere) cannot be told apart by scope here: unknown, so it counts as input.
    let declared = 0
    const visit = (n: ts.Node) => {
        if (ts.isVariableDeclaration(n) && ts.isIdentifier(n.name) && n.name.text === name) {
            declared++
            const loop = n.parent?.parent
            found = loop && ts.isForOfStatement(loop) ? strings(loop.expression) : strings(n.initializer)
        }
        ts.forEachChild(n, visit)
    }
    visit(file)
    return declared > 1 ? null : found
}

/** Every page-wide input listener in `text`, as `line: source`; an event name it cannot resolve counts as input. */
export function pageListeners(text: string, fileName = 'x.ts'): string[] {
    const file = ts.createSourceFile(fileName, text, ts.ScriptTarget.Latest, true, fileName.endsWith('x') ? ts.ScriptKind.TSX : ts.ScriptKind.TS)
    const hits: string[] = []
    const note = (n: ts.Node) => {
        const line = file.getLineAndCharacterOfPosition(n.getStart(file)).line + 1
        hits.push(`${line}: ${n.getText(file).split('\n')[0].trim()}`)
    }
    const visit = (n: ts.Node) => {
        if (ts.isCallExpression(n)) {
            const f = unwrap(n.expression)
            const bare = ts.isIdentifier(f) && f.text === 'addEventListener'
            const onPage = ts.isPropertyAccessExpression(f) && f.name.text === 'addEventListener' && isPage(f.expression)
            if ((bare || onPage) && n.arguments.length) {
                const names = eventNames(n.arguments[0], file)
                if (names === null || names.some((x) => EVENT.test(x))) note(n)
            }
        } else if (ts.isBinaryExpression(n) && n.operatorToken.kind === ts.SyntaxKind.EqualsToken && ts.isPropertyAccessExpression(n.left)) {
            // window.onkeydown = ...
            if (/^on(?:key|wheel|pointer|mouse|touch|gamepad|contextmenu|click|dblclick|auxclick)/.test(n.left.name.text) && isPage(n.left.expression)) note(n)
        }
        ts.forEachChild(n, visit)
    }
    visit(file)
    return hits
}

test('no page-wide input listener outside onGlobal and the listed files', () => {
    const found: string[] = []
    for (const file of sources(SRC)) {
        const rel = relative(SRC, file).replaceAll('\\', '/')
        if (Object.hasOwn(ALLOWED, rel)) continue
        for (const hit of pageListeners(readFileSync(file, 'utf8'), file)) found.push(`${rel}:${hit}`)
    }
    assert.deepEqual(found, [], `use onGlobal (ui/globalInput.ts) for these:\n${found.join('\n')}`)
})

test('the scan finds what it must', () => {
    const must = [
        "window.addEventListener('keydown', f)",
        "document.addEventListener('pointerdown', f, true)",
        "    addEventListener('keydown', keys)",
        'doc.addEventListener("wheel", w)',
        "document.body.addEventListener('keydown', f)",
        "document.documentElement.addEventListener('click', f)",
        "window.addEventListener(\n  'keydown',\n  f,\n)",
        "const e = 'keydown'\nwindow.addEventListener(e, f)",
        "for (const e of ['pointerdown', 'wheel'] as const) addEventListener(e, f, { passive: true })",
        'window.addEventListener(name, f)',
        'window.onkeydown = f',
    ]
    for (const text of must) assert.equal(pageListeners(text).length, 1, text)
    const mustNot = [
        "video.addEventListener('play', f)",
        "el.addEventListener('keydown', f)",
        "window.addEventListener('resize', f)",
        "document.addEventListener('visibilitychange', f)",
        "document.body.addEventListener('transitionend', f)",
        "for (const e of ['resize', 'blur'] as const) addEventListener(e, f)",
    ]
    for (const text of mustNot) assert.deepEqual(pageListeners(text), [], text)
})

test('every allowed file still exists and still needs it', () => {
    for (const rel of Object.keys(ALLOWED)) {
        const text = readFileSync(join(SRC, rel), 'utf8')
        assert.ok(rel === 'ui/globalInput.ts' || pageListeners(text, rel).length > 0, `${rel} no longer listens to the page: take it off the list`)
    }
})
