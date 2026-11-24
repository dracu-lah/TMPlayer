// `node --test src/ui/loadStates.test.ts` (pnpm test:ui): WP-99's pure parts. Home's kept failure and
// failed rows, one read at a time with a cancel the worker sees, and one profile switch at a time.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createLatest, createSwitchGuard, isAbort, keptFailure, rowsFailure } from './loadStates.ts'
import { statusWords, type Say } from './workStatus.ts'

const say: Say = (key, args) => (args ? `${key} ${JSON.stringify(args)}` : key)
const time = (at: number) => `T${at}`

test('a failed Home load with rows on screen is the Failed line, kept since the last answer', () => {
    const s = keptFailure({ failed: true, online: true, rows: 3, keptAt: 1000 })
    assert.deepEqual(s, { kind: 'failed', message: '', keptAt: 1000 })
    const w = statusWords(s, 0, say, time)
    assert.equal(w?.text, 'state.failed_kept {"time":"T1000"}')
    assert.equal(w?.actions, true)
})

test('no line without rows, offline, or a failure', () => {
    assert.equal(keptFailure({ failed: true, online: true, rows: 0, keptAt: null }).kind, 'idle')
    assert.equal(keptFailure({ failed: true, online: false, rows: 3, keptAt: 5 }).kind, 'idle')
    assert.equal(keptFailure({ failed: false, online: true, rows: 3, keptAt: 5 }).kind, 'idle')
})

test('rows that could not be read leave and the foot counts them, with Try again', () => {
    const s = rowsFailure(2, 5, (n) => `${n} chats could not be read for now.`)
    assert.equal(s.kind, 'partial')
    const w = statusWords(s, 0, say, time)
    assert.equal(w?.text, '2 chats could not be read for now.')
    assert.equal(w?.actions, true)
    assert.equal(rowsFailure(0, 5, String).kind, 'idle')
    assert.equal(rowsFailure(Number.NaN, 5, String).kind, 'idle')
    assert.equal(rowsFailure(-1, 5, String).kind, 'idle')
})

test('a new read aborts the one before it, which is no longer current', () => {
    const latest = createLatest()
    const first = latest.begin()
    assert.equal(first.current(), true)
    const second = latest.begin()
    assert.equal(first.signal.aborted, true, 'the worker is told to cancel the first read')
    assert.equal(first.current(), false)
    assert.equal(second.signal.aborted, false)
    assert.equal(second.current(), true)
})

test('a change of chat cancels the read in flight and none is current', () => {
    const latest = createLatest()
    const read = latest.begin()
    latest.cancel()
    assert.equal(read.signal.aborted, true)
    assert.equal(read.current(), false)
    latest.cancel()
    assert.equal(latest.begin().current(), true)
})

test('an abort is told from a failure', () => {
    assert.equal(isAbort(new DOMException('aborted', 'AbortError')), true)
    assert.equal(isAbort(new Error('x')), false)
    assert.equal(isAbort(null), false)
})

test('a second switch while one runs is refused, and the next may start once it is released', () => {
    const seen: (string | null)[] = []
    const guard = createSwitchGuard((id) => seen.push(id))
    assert.equal(guard.start('a'), true)
    assert.equal(guard.start('a'), false, 'a double click')
    assert.equal(guard.start('b'), false, 'another tile')
    assert.equal(guard.current(), 'a')
    guard.release()
    guard.release()
    assert.equal(guard.start('b'), true)
    assert.deepEqual(seen, ['a', null, 'b'])
})

test('a switch whose reload never came lets go after the wait, and a later switch is not released by it', () => {
    const runs: (() => void)[] = []
    const guard = createSwitchGuard()
    assert.equal(guard.start('a'), true)
    guard.releaseAfter(10_000, (run) => runs.push(run))
    assert.equal(guard.start('b'), false, 'held while the reload may still come')
    runs[0]()
    assert.equal(guard.current(), null)
    assert.equal(guard.start('b'), true, 'the next press works again')
    guard.release()
    assert.equal(guard.start('c'), true)
    runs[0]()
    assert.equal(guard.current(), 'c', "the first switch's timer does not release a newer one")
})
