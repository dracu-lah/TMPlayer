import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { skipKindOf, skipSegments } from './chapters.ts'
import { NOT_A_SKIP, SKIP_RULES } from './skipTable.ts'

// The same titles core's SkipChaptersTest checks: the page and the apps name the same stretches.
const table: Record<string, string[]> = {
    intro: ['Intro', 'INTRO', 'Cabecera', 'Apertura', 'Générique', 'Vorspann', 'Sigla', 'Abertura', 'Заставка', 'Czołówka', 'Açılış Jeneriği', '片头', '인트로', 'المقدمة', 'Znělka', 'Mở đầu'],
    opening: ['Opening', 'OP', 'OP1', 'NCOP2', 'Opening Credits', 'Title Sequence', 'オープニング', '오프닝', 'Опенинг'],
    recap: ['Recap', 'Previously on', 'The story so far', 'Resumen', 'Précédemment', 'Rückblick', 'Riassunto', 'Eerder', 'Ранее', 'Poprzednio', 'Önceki Bölüm', '前回のあらすじ', '前情提要', '지난 이야기', 'ملخص', 'Tóm tắt'],
    credits: ['Credits', 'End Credits', 'Outro', 'Créditos', 'Générique de fin', 'Abspann', 'Titoli di coda', 'Aftiteling', 'Титры', 'Napisy końcowe', 'クレジット', '片尾', '크레딧', 'Eftertexter', 'Závěrečné titulky'],
    ending: ['Ending', 'ED', 'ED2', 'NCED', 'Ending Theme', 'エンディング', '엔딩', 'Эндинг'],
    preview: ['Preview', 'Next Time', 'Coming up', 'Próximo capítulo', 'Prochainement', 'Vorschau', 'Anteprima', 'Prévia', 'Анонс', 'Zapowiedź', 'Sonraki Bölüm', '次回予告', '预告', '예고', 'Nästa avsnitt'],
}

test('titles in many languages name their kind', () => {
    const wrong = Object.entries(table).flatMap(([kind, titles]) =>
        titles.filter((t) => skipKindOf(t) !== kind).map((t) => `${t}: wanted ${kind}, got ${skipKindOf(t)}`),
    )
    assert.deepEqual(wrong, [])
})

test('ordinary chapter titles are not skips', () => {
    for (const t of ['Chapter 1', 'Scene 3', 'Opening scene', 'Escena de apertura', 'The Beginning', 'Act II', 'Introduction to Algebra', 'Opera', 'Editor', 'Introspection', '', ' ', '第一章', 'Глава 1']) {
        assert.equal(skipKindOf(t), null, t)
    }
    assert.equal(skipKindOf(null), null)
    assert.equal(skipKindOf('PRÓXIMO CAPÍTULO'), 'preview')
    assert.equal(skipKindOf('Próximo capítulo'), 'preview')
})

test('stretches too short, too long or open ended are not skips', () => {
    assert.deepEqual(skipSegments([{ start: 0, title: 'Intro' }, { start: 4, title: 'Story' }], 1200), [])
    assert.deepEqual(skipSegments([{ start: 0, title: 'Intro' }, { start: 600, title: 'Story' }], 1200), [])
    assert.deepEqual(skipSegments([{ start: 900, title: 'Intro' }], 0), [])
    assert.deepEqual(skipSegments([{ start: 90, title: 'Story' }, { start: -5, title: 'Intro' }, { start: 10, title: 'Intro' }], 1200), [
        { kind: 'intro', start: 10, end: 90 },
    ])
})

test('garbage titles never throw', () => {
    for (const t of ['\u0000', '\ud800', '😀'.repeat(100), 'a'.repeat(100000), 'OP'.repeat(5000), 'ß'.repeat(300), '%%%']) skipKindOf(t)
})

test('the page table is the one core has', () => {
    const kotlin = readFileSync(new URL('../../../core/src/pureMain/kotlin/com/tmplayer/online/SkipChapters.kt', import.meta.url), 'utf8')
    const rules = kotlin.slice(kotlin.indexOf('private val RULES'))
    const phrases = [...rules.matchAll(/"((?:[^"\\]|\\.)*)"/g)].map((m) => m[1])
    assert.deepEqual(phrases, SKIP_RULES.flatMap(([, list]) => list), 'run scripts/gen-skip-table.py')
    const negative = kotlin.slice(kotlin.indexOf('private val NOT_A_SKIP'), kotlin.indexOf('.map { fold(it) }'))
    assert.deepEqual([...negative.matchAll(/"((?:[^"\\]|\\.)*)"/g)].map((m) => m[1]), NOT_A_SKIP)
})
