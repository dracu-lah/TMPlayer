#!/usr/bin/env node
// Groups name reports by shape and ranks the shapes by how many reports share them.
//
// IT ONLY GROUPS AND RANKS. It does not run the Kotlin parser (MediaName lives in the JVM build and
// this script has no dependencies), so it cannot say which names the parser gets wrong. To check a
// shape, copy a few of its rows into core/src/jvmTest/resources/names/ambiguous.tsv or corpus.tsv and
// run NameGoldenCorpusTest: it prints what the parser reads for each. See scripts/names/README.md.
//
// Usage: node scripts/names/ingest.mjs <folder> [--top N] [--examples N] [--min N]
//   <folder>      files of reports, one report per line: raw file name, TAB, the title the viewer chose.
//                 Lines starting with # and blank lines are skipped. Every file in the folder is read.
//   --top N       print the N most shared shapes (default 30)
//   --examples N  show N example reports per shape (default 3)
//   --min N       leave out shapes shared by fewer than N reports (default 1)
//
// The shape is the one TitleShape.kt computes: letters to a or A by case (x for a script with no
// case), digits to 9, a character past the basic plane to *, separators kept, runs collapsed.

import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'

const MAX_SHAPE = 160

/** Same rules as core/.../TitleShape.kt; keep the two in step. */
export function shapeOf(name) {
  let out = ''
  let last = ''
  for (const char of name) {
    if (out.length >= MAX_SHAPE) break
    // A vowel sign or an accent belongs to its letter.
    if (/^\p{M}$/u.test(char)) continue
    let symbol
    if (char.codePointAt(0) > 0xffff) symbol = '*'
    else if (/^\p{Lu}$|^\p{Lt}$/u.test(char)) symbol = 'A'
    else if (/^\p{Ll}$/u.test(char)) symbol = 'a'
    else if (/^\p{L}$/u.test(char)) symbol = 'x'
    else if (/^\p{Nd}$/u.test(char)) symbol = '9'
    else if (/^\s$/u.test(char)) symbol = ' '
    else symbol = char
    if (symbol === last) continue
    out += symbol
    last = symbol
  }
  return out
}

function parseArgs(argv) {
  const options = { top: 30, examples: 3, min: 1 }
  let folder = null
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i]
    if (arg === '--top' || arg === '--examples' || arg === '--min') {
      const value = Number.parseInt(argv[++i], 10)
      if (!Number.isFinite(value) || value < 0) fail(`${arg} needs a number`)
      options[arg.slice(2)] = value
    } else if (arg.startsWith('--')) {
      fail(`unknown option ${arg}`)
    } else if (folder === null) {
      folder = arg
    } else {
      fail('one folder only')
    }
  }
  if (folder === null) fail('usage: node scripts/names/ingest.mjs <folder> [--top N] [--examples N] [--min N]')
  return { folder, ...options }
}

function fail(message) {
  console.error(message)
  process.exit(2)
}

function filesOf(folder) {
  let stat
  try {
    stat = statSync(folder)
  } catch {
    fail(`cannot read ${folder}`)
  }
  if (!stat.isDirectory()) return [folder]
  return readdirSync(folder)
    .sort()
    .map((name) => join(folder, name))
    .filter((path) => statSync(path).isFile())
}

function main() {
  const { folder, top, examples, min } = parseArgs(process.argv.slice(2))
  const groups = new Map()
  const seen = new Set()
  let lines = 0
  let skipped = 0
  let repeated = 0
  for (const file of filesOf(folder)) {
    for (const line of readFileSync(file, 'utf8').split(/\r?\n/)) {
      if (line.trim() === '' || line.startsWith('#')) continue
      lines++
      const tab = line.indexOf('\t')
      const raw = (tab < 0 ? line : line.slice(0, tab)).trim()
      const title = tab < 0 ? '' : line.slice(tab + 1).trim()
      if (raw === '') {
        skipped++
        continue
      }
      // The same pair twice is one report (the inbox dedupes by hash; this does it by text).
      const key = `${raw.toLowerCase()}\u0000${title.toLowerCase()}`
      if (seen.has(key)) {
        repeated++
        continue
      }
      seen.add(key)
      const shape = shapeOf(raw)
      const group = groups.get(shape) ?? { shape, reports: [] }
      group.reports.push({ raw, title })
      groups.set(shape, group)
    }
  }

  const ranked = [...groups.values()]
    .filter((group) => group.reports.length >= min)
    .sort((a, b) => b.reports.length - a.reports.length || a.shape.localeCompare(b.shape))

  console.log(`${lines} lines, ${seen.size} reports after dropping ${repeated} repeats and ${skipped} without a name, in ${groups.size} shapes`)
  console.log('This script only groups and ranks by shape; it does not run the parser. Check a shape in NameGoldenCorpusTest.')
  console.log('')
  for (const group of ranked.slice(0, top)) {
    console.log(`${String(group.reports.length).padStart(5)}  ${group.shape}`)
    for (const report of group.reports.slice(0, examples)) {
      console.log(`         ${report.raw}${report.title ? `   ->   ${report.title}` : ''}`)
    }
  }
  if (ranked.length > top) console.log(`\n... and ${ranked.length - top} more shapes (--top ${ranked.length} shows them)`)
}

main()
