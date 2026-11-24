// `node --test src/ui/workStatus.test.ts` (pnpm test:ui): the status line's states to words, the
// merge order, and the link line's settle timers (reach.ts) on a clock the test drives.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { CONNECTING_MS, createLinkLine, UPDATING_MS, type LinkLine, type Timers } from './reach.ts'
import { idle, mergeStatus, secondsLeft, statusWords, type Say, type WorkStatus } from './workStatus.ts'

const say: Say = (key, args) => (args ? `${key} ${JSON.stringify(args)}` : key)
const time = (at: number) => `T${at}`
const words = (s: WorkStatus, now = 0) => statusWords(s, now, say, time)

test('Idle and Offline draw nothing: the offline line speaks for itself', () => {
    assert.equal(words(idle), null)
    assert.equal(words({ kind: 'offline', keptAt: 5 }), null)
})

test('Loading says its label, busy, with no buttons', () => {
    const w = words({ kind: 'loading', what: 'Loading your chats' })
    assert.deepEqual(w, { text: 'Loading your chats', busy: true, actions: false, progress: null, ticking: false })
})

test('Syncing says Updating when it has no words, and a bar only with a real total', () => {
    assert.equal(words({ kind: 'syncing', what: '' })?.text, 'state.updating')
    assert.equal(words({ kind: 'syncing', what: '' })?.progress, null)
    assert.equal(words({ kind: 'syncing', what: 'Loading your chats: 312 so far', done: 312 })?.progress, null)
    assert.equal(words({ kind: 'syncing', what: 'x', done: 3, total: 0 })?.progress, null)
    assert.deepEqual(words({ kind: 'syncing', what: 'x', done: 3, total: 10 })?.progress, { done: 3, total: 10 })
    assert.deepEqual(words({ kind: 'syncing', what: 'x', done: 30, total: 10 })?.progress, { done: 10, total: 10 })
    assert.equal(words({ kind: 'syncing', what: 'x' })?.busy, true)
})

test('Updated says it quietly, not busy', () => {
    assert.deepEqual(words({ kind: 'updated', at: 1 }), { text: 'state.updated', busy: false, actions: false, progress: null, ticking: false })
})

test('Partial says its sentence with Try again and Send logs', () => {
    const w = words({ kind: 'partial', okCount: 8, failedCount: 2, what: '2 rows could not be read for now.' })
    assert.equal(w?.text, '2 rows could not be read for now.')
    assert.equal(w?.actions, true)
    assert.equal(w?.busy, false)
})

test('Failed with content kept names the time; with nothing kept it says the message', () => {
    assert.equal(words({ kind: 'failed', message: 'It broke', keptAt: 1700 })?.text, 'state.failed_kept {"time":"T1700"}')
    assert.equal(words({ kind: 'failed', message: 'It broke', keptAt: null })?.text, 'It broke')
    assert.equal(words({ kind: 'failed', message: 'It broke', keptAt: 1 })?.actions, true)
    assert.equal(words({ kind: 'failed', message: '', keptAt: null }), null)
})

test('a flood wait counts whole seconds down, ticks, and goes when it ends', () => {
    const wait: WorkStatus = { kind: 'rateLimited', untilMs: 42_000 }
    assert.equal(words(wait, 0)?.text, 'state.rate_limited {"seconds":42}')
    assert.equal(words(wait, 0)?.ticking, true)
    assert.equal(words(wait, 41_001)?.text, 'state.rate_limited {"seconds":1}')
    assert.equal(words(wait, 42_000), null)
    assert.equal(words(wait, 90_000), null)
    assert.equal(secondsLeft(10_500, 0), 11)
    assert.equal(secondsLeft(0, 5000), 0)
})

test('merge: Failed beats RateLimited beats Offline beats Partial beats Syncing beats Updated beats Idle', () => {
    const order: WorkStatus[] = [
        { kind: 'failed', message: 'm', keptAt: null },
        { kind: 'rateLimited', untilMs: 1 },
        { kind: 'offline', keptAt: null },
        { kind: 'partial', okCount: 1, failedCount: 1, what: 'p' },
        { kind: 'syncing', what: 's' },
        { kind: 'updated', at: 1 },
        idle,
    ]
    for (let i = 0; i < order.length; i++) {
        for (let j = 0; j < order.length; j++) {
            if (i < j) {
                assert.equal(mergeStatus(order[i]!, order[j]!), order[i])
                assert.equal(mergeStatus(order[j]!, order[i]!), order[i])
            }
        }
    }
    const a: WorkStatus = { kind: 'syncing', what: 'a' }
    const b: WorkStatus = { kind: 'syncing', what: 'b' }
    assert.equal(mergeStatus(a, b), a)
})

function rig() {
    let now = 0
    let next = 1
    const timers = new Map<number, { at: number; fn: () => void }>()
    const clock: Timers = {
        set: (fn, ms) => (timers.set(next, { at: now + ms, fn }), next++),
        clear: (h) => void timers.delete(h),
    }
    const log: LinkLine[] = []
    const line = createLinkLine((l) => log.push(l), clock)
    const advance = (ms: number) => {
        const end = now + ms
        for (;;) {
            const due = [...timers.entries()].filter(([, t]) => t.at <= end).sort((a, b) => a[1].at - b[1].at)[0]
            if (!due) break
            now = due[1].at
            timers.delete(due[0])
            due[1].fn()
        }
        now = end
    }
    return { line, advance, log, pending: () => timers.size }
}

test('Connecting shows after 3 s, not a moment before, and goes the moment the line is back', () => {
    const { line, advance, log } = rig()
    line.update('connecting')
    advance(CONNECTING_MS - 1)
    assert.deepEqual(log, [])
    advance(1)
    assert.deepEqual(log, ['connecting'])
    line.update('connected')
    assert.deepEqual(log, ['connecting', null])
})

test('a blink of Connecting or Updating says nothing', () => {
    const { line, advance, log, pending } = rig()
    line.update('connecting')
    advance(2000)
    line.update('connected')
    line.update('updating')
    advance(UPDATING_MS - 1)
    line.update('connected')
    advance(10_000)
    assert.deepEqual(log, [])
    assert.equal(pending(), 0)
})

test('Updating shows after a 2 s settle; one timer at a time', () => {
    const { line, advance, log, pending } = rig()
    line.update('updating')
    assert.equal(pending(), 1)
    advance(UPDATING_MS)
    assert.deepEqual(log, ['updating'])
    line.update('updating')
    assert.equal(log.length, 1)
    line.update('connecting')
    assert.deepEqual(log, ['updating', null])
    assert.equal(pending(), 1)
    advance(CONNECTING_MS)
    assert.deepEqual(log, ['updating', null, 'connecting'])
})

test('offline, not told yet and a stopped watch show no line', () => {
    const { line, advance, log, pending } = rig()
    line.update('offline')
    line.update(null)
    advance(60_000)
    assert.deepEqual(log, [])
    line.update('connecting')
    line.stop()
    assert.equal(pending(), 0)
    advance(60_000)
    assert.deepEqual(log, [])
})
