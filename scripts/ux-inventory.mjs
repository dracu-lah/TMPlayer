#!/usr/bin/env node
// Lists every interactive control in the app, one checkbox per control, grouped by source file and
// cut into review chunks (private/ux-inventory/<chunk>.md). A reviewer ticks each line after
// checking it on every dimension in private/UX-INTERACTION-PLAN.md.
// Usage: node scripts/ux-inventory.mjs   (re-run it after big changes; it overwrites the chunks)
import { readdirSync, readFileSync, writeFileSync, mkdirSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'

const root = new URL('..', import.meta.url).pathname
const out = join(root, 'private', 'ux-inventory')
const PAT = /onClick\s*=|onClick=|clickable|combinedClickable|IconButton|Button\(|TextButton|FilterChip|AssistChip|Switch\(|Checkbox|RadioButton|Slider\(|onKeyEvent|onPreviewKeyEvent|detectTapGestures|detectDragGestures|draggable|swipeable|anchoredDraggable|TextField\(|BasicTextField|DropdownMenuItem|BackHandler|onDismissRequest|onInput|onKeyDown|onPointerDown|<input|<button|<select/
const EXT = /\.(kt|tsx)$/

const groups = {
  'desktop-ui': ['desktop/src/main/kotlin'],
  'phone-browse': ['app/src/main/java/com/tmplayer/ui/browse', 'ui/src/shared/kotlin/com/tmplayer/ui/browse'],
  settings: ['app/src/main/java/com/tmplayer/ui/settings', 'ui/src/shared/kotlin/com/tmplayer/ui/settings'],
  components: ['app/src/main/java/com/tmplayer/ui/components', 'ui/src/shared/kotlin/com/tmplayer/ui/components', 'ui/src/androidMain', 'ui/src/jvmMain'],
  player: ['app/src/main/java/com/tmplayer/player', 'ui/src/shared/kotlin/com/tmplayer/ui/player', 'app/src/main/java/com/tmplayer/cast'],
  flows: [
    'app/src/main/java/com/tmplayer/ui/auth', 'app/src/main/java/com/tmplayer/ui/downloads', 'app/src/main/java/com/tmplayer/ui/update',
    'ui/src/shared/kotlin/com/tmplayer/ui/onboarding', 'ui/src/shared/kotlin/com/tmplayer/ui/auth', 'ui/src/shared/kotlin/com/tmplayer/ui/connect',
    'ui/src/shared/kotlin/com/tmplayer/ui/lock', 'ui/src/shared/kotlin/com/tmplayer/ui/online', 'app/src/main/java/com/tmplayer/MainActivity.kt',
  ],
  web: ['webapp/src'],
}
const MAX = 260 // controls per chunk

function files(p) {
  const abs = join(root, p)
  let st
  try { st = statSync(abs) } catch { return [] }
  if (st.isFile()) return [abs]
  return readdirSync(abs, { recursive: true }).map((f) => join(abs, f)).filter((f) => EXT.test(f) && !/(\/build\/|node_modules|\.test\.|Test\.kt)/.test(f))
}

mkdirSync(out, { recursive: true })
const index = []
for (const [name, dirs] of Object.entries(groups)) {
  const per = []
  for (const f of [...new Set(dirs.flatMap(files))].sort()) {
    const lines = readFileSync(f, 'utf8').split('\n')
    const hits = []
    lines.forEach((l, i) => { if (PAT.test(l) && !l.trim().startsWith('import ') && !l.trim().startsWith('//')) hits.push(`- [ ] L${i + 1} \`${l.trim().slice(0, 110).replace(/`/g, "'")}\``) })
    if (hits.length) per.push({ file: relative(root, f), hits })
  }
  const total = per.reduce((n, p) => n + p.hits.length, 0)
  const parts = []
  let cur = [], n = 0
  for (const p of per) { if (n && n + p.hits.length > MAX) { parts.push(cur); cur = []; n = 0 } cur.push(p); n += p.hits.length }
  if (cur.length) parts.push(cur)
  parts.forEach((ps, i) => {
    const id = parts.length > 1 ? `${name}-${i + 1}` : name
    const count = ps.reduce((a, p) => a + p.hits.length, 0)
    const body = [`# ${id}: ${count} controls in ${ps.length} files`, '', 'Tick a line only after every dimension in private/UX-INTERACTION-PLAN.md was checked for it.', '']
    for (const p of ps) body.push(`## ${p.file} (${p.hits.length})`, ...p.hits, '')
    writeFileSync(join(out, `${id}.md`), body.join('\n'))
    index.push(`| ${id} | ${count} | ${ps.length} |`)
  })
  console.log(name, total)
}
writeFileSync(join(out, 'INDEX.md'), ['# UX inventory chunks', '', '| chunk | controls | files |', '| --- | --- | --- |', ...index, ''].join('\n'))
