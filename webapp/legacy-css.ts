// The TV build's CSS (vite.config.ts, `--mode tv`) for the engines webOS 5 and 6 and Tizen 2020 run
// (Chromium 68 to 79). Lightning CSS already lowers what it can (inset, nesting); this adds what it
// cannot, each only where the engine lacks it, so a newer TV draws the same page as a browser:
//
// - min(), max() and clamp() (Chromium 79), env() (69) and dvh (108): the declaration again inside
//   `@supports not`, with min and max taking their first argument, clamp its middle one, env its
//   fallback or 0 and dvh as vh. A custom property needs this form: an older engine keeps a
//   `--dp: min(...)` it cannot compute, so a plain fallback before it would lose.
// - aspect-ratio (Chromium 88): a floated ::before of the same proportion holds the height.
// - gap in a flex box (Chromium 84): margins between the children while index.html's test has put
//   `no-flex-gap` on <html>; grids keep their gap, which these engines have.
// - :focus-visible (Chromium 86) becomes :focus, which on a TV is the same thing, so a selector list
//   holding it is not dropped whole.
// - :has(... :focus) (Chromium 105), the TV's focus fill on a field or a choice, gets a :focus-within
//   copy while index.html's test has put `no-has` on <html>.
//
// Not filled: any other :has() (such a rule does nothing on these engines, so the app's own markup
// uses classes where it matters) and color-mix() (its tints fall back to the property's previous
// value).

type Node = any

const MIN_MAX = /\b(min|max|clamp)\(/
const ENV = /\benv\(/
const DVH = /\b\d*\.?\d+(dvh|svh|lvh)\b/

/** The text of the call starting at `open` (the index of its "("), split at its top level commas, and where it ends. */
function args(value: string, open: number): { parts: string[]; end: number } {
    const parts: string[] = []
    let depth = 0
    let start = open + 1
    for (let i = open; i < value.length; i++) {
        const c = value[i]
        if (c === '(') depth++
        else if (c === ')') {
            depth--
            if (depth === 0) {
                parts.push(value.slice(start, i).trim())
                return { parts, end: i + 1 }
            }
        } else if (c === ',' && depth === 1) {
            parts.push(value.slice(start, i).trim())
            start = i + 1
        }
    }
    return { parts: [value.slice(open + 1)], end: value.length }
}

/** `value` with every min, max, clamp and env call replaced by what an old engine can read. */
export function flatten(value: string): string {
    const call = /\b(min|max|clamp|env)\(/g
    let out = ''
    let from = 0
    for (let m = call.exec(value); m; m = call.exec(value)) {
        const open = m.index + m[1].length
        const { parts, end } = args(value, open)
        let pick: string
        if (m[1] === 'env') pick = parts[1] ?? '0px'
        else if (m[1] === 'clamp') pick = parts[1] ?? parts[0]
        else pick = parts.find((p) => !ENV.test(p)) ?? parts[0]
        // A bare sum left by the pick needs calc() to stay a value.
        const inner = flatten(pick)
        out += value.slice(from, m.index) + (/[+\-*/]\s/.test(inner) && !/^calc\(/.test(inner) ? `calc(${inner})` : inner)
        from = end
        call.lastIndex = end
    }
    return (out + value.slice(from)).replace(/\b(\d*\.?\d+)(dvh|svh|lvh)\b/g, '$1vh')
}

/** `value` split at spaces outside parentheses: `calc(var(--dp) * 8) 4px` is two words. */
function words(value: string): string[] {
    const out: string[] = []
    let depth = 0
    let word = ''
    for (const c of value.trim()) {
        if (c === '(') depth++
        if (c === ')') depth--
        if (/\s/.test(c) && depth === 0) {
            if (word) out.push(word)
            word = ''
        } else word += c
    }
    if (word) out.push(word)
    return out
}

function ratio(value: string): number | null {
    const [w, h] = value.split('/').map((p) => Number(p.trim()))
    if (!(w > 0)) return null
    const r = (h > 0 ? h : 1) / w
    return Number.isFinite(r) ? r : null
}

function selectors(rule: Node): string[] {
    return rule.selectors as string[]
}

export function legacyCss() {
    return {
        postcssPlugin: 'tmplayer-legacy-css',
        Once(root: Node, { postcss }: { postcss: Node }) {
            // Which selectors are flex boxes, and which way, from every rule in this file.
            const flex = new Map<string, 'row' | 'column'>()
            const grid = new Set<string>()
            root.walkRules((rule: Node) => {
                let display: string | undefined
                let direction: string | undefined
                rule.each((d: Node) => {
                    if (d.type !== 'decl') return
                    if (d.prop === 'display') display = d.value
                    if (d.prop === 'flex-direction') direction = d.value
                    if (d.prop === 'flex-flow') direction = d.value.split(/\s+/)[0]
                })
                for (const s of selectors(rule)) {
                    if (display && /flex/.test(display)) flex.set(s, direction?.startsWith('column') ? 'column' : 'row')
                    else if (display && /grid/.test(display)) grid.add(s)
                    else if (direction && flex.has(s)) flex.set(s, direction.startsWith('column') ? 'column' : 'row')
                }
            })

            // Collected first, so the rules added below are not visited again.
            const rules: Node[] = []
            root.walkRules((rule: Node) => void rules.push(rule))
            for (const rule of rules) {
                if (rule.parent?.type === 'atrule' && /keyframes$/.test(rule.parent.name)) continue
                if (rule.selector.includes(':focus-visible')) rule.selector = rule.selector.replace(/:focus-visible/g, ':focus')
                // A focus fill drawn through :has(input:focus) gets a :focus-within copy for engines without :has.
                const focusHas = selectors(rule).filter((sel) => /:has\([^()]*:focus\)/.test(sel))
                if (focusHas.length) {
                    const copy = rule.clone({
                        selectors: focusHas.map((sel) => {
                            const within = sel.replace(/:has\([^()]*:focus\)/g, ':focus-within')
                            return /^html\b/.test(within) ? within.replace(/^html/, 'html.no-has') : `html.no-has ${within}`
                        }),
                    })
                    rule.after(copy)
                }

                const later: Node[] = []
                const noCalc: Node[] = []
                const noDvh: Node[] = []
                rule.each((d: Node) => {
                    if (d.type !== 'decl') return
                    if (MIN_MAX.test(d.value) || ENV.test(d.value)) noCalc.push(postcss.decl({ prop: d.prop, value: flatten(d.value), important: d.important }))
                    else if (DVH.test(d.value)) noDvh.push(postcss.decl({ prop: d.prop, value: flatten(d.value), important: d.important }))

                    if (d.prop === 'aspect-ratio') {
                        const r = ratio(d.value)
                        if (r == null) return
                        const pad = `${+(r * 100).toFixed(4)}%`
                        const box = postcss.atRule({ name: 'supports', params: 'not (aspect-ratio: 1 / 1)' })
                        box.append(postcss.rule({ selectors: selectors(rule).map((s) => `${s}::before`) }).append({ prop: 'content', value: '""' }, { prop: 'float', value: 'left' }, { prop: 'padding-top', value: pad }))
                        box.append(postcss.rule({ selectors: selectors(rule).map((s) => `${s}::after`) }).append({ prop: 'content', value: '""' }, { prop: 'display', value: 'block' }, { prop: 'clear', value: 'both' }))
                        later.push(box)
                    }

                    if (d.prop === 'gap' || d.prop === 'row-gap' || d.prop === 'column-gap') {
                        const own = selectors(rule)
                        if (own.some((s) => grid.has(s))) return
                        const way = own.map((s) => flex.get(s)).find(Boolean)
                        if (!way) return
                        const [row, col = row] = words(flatten(d.value))
                        const space = d.prop === 'row-gap' ? row : d.prop === 'column-gap' ? col : way === 'row' ? col : row
                        if (way === 'row' && d.prop === 'row-gap') return
                        if (way === 'column' && d.prop === 'column-gap') return
                        const side = way === 'row' ? 'margin-left' : 'margin-top'
                        later.push(postcss.rule({ selectors: own.map((s) => `html.no-flex-gap ${s} > * + *`) }).append({ prop: side, value: space }))
                    }
                })
                if (noCalc.length) later.push(postcss.atRule({ name: 'supports', params: 'not (width: min(1px, 2px))' }).append(postcss.rule({ selector: rule.selector }).append(noCalc)))
                if (noDvh.length) later.push(postcss.atRule({ name: 'supports', params: 'not (height: 1dvh)' }).append(postcss.rule({ selector: rule.selector }).append(noDvh)))
                if (later.length) rule.after(later)
            }
        },
    }
}
legacyCss.postcss = true
