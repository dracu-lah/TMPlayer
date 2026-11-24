import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { ANNOUNCE_MS, GATE_MS, HOLD_EVERY, HOLD_LARGE_FROM, HOLD_MEDIUM_FROM, SeekStepper, holdCapMs, holdStepMs } from './seekStepper.ts'

// The rule is core's RemoteQuirks.holdStepMs; this file reads its source so the two cannot drift.
const kotlin = readFileSync(new URL('../../../core/src/pureMain/kotlin/com/tmplayer/data/FireOs.kt', import.meta.url), 'utf8')
const constant = (name: string) => Number(new RegExp(`const val ${name} = (\\d+)`).exec(kotlin)?.[1])

test('the ramp constants are core\'s', () => {
    assert.equal(HOLD_EVERY, constant('HOLD_EVERY'))
    assert.equal(HOLD_MEDIUM_FROM, constant('HOLD_MEDIUM_FROM'))
    assert.equal(HOLD_LARGE_FROM, constant('HOLD_LARGE_FROM'))
    assert.match(kotlin, /HOLD_CAP_SHARE = 200L/)
    assert.match(kotlin, /HOLD_CAP_MIN_MS = 10_000L/)
    assert.match(kotlin, /HOLD_CAP_MAX_MS = 120_000L/)
})

test('holdStepMs gives the numbers core\'s FireOsTest pins', () => {
    const base = 10_000
    assert.equal(holdStepMs(0, base), base)
    assert.equal(holdStepMs(1, base), 0)
    assert.equal(holdStepMs(3, base), 0)
    assert.equal(holdStepMs(4, base), base)
    assert.equal(holdStepMs(40, base), base * 3)
    assert.equal(holdStepMs(100, base), base * 6)
    assert.equal(holdStepMs(101, base), 0)
    const twenty = 20 * 60_000
    const two = 120 * 60_000
    const ten = 600 * 60_000
    assert.equal(holdStepMs(0, base, twenty), base)
    assert.equal(holdStepMs(40, base, twenty), base)
    assert.equal(holdStepMs(100, base, twenty), base)
    assert.equal(holdStepMs(40, base, two), 30_000)
    assert.equal(holdStepMs(100, base, two), 36_000)
    assert.equal(holdStepMs(100, base, ten), 60_000)
    assert.equal(holdCapMs(ten), 120_000)
    assert.equal(holdStepMs(100, base, 0), base * 6)
    assert.equal(holdStepMs(100, base, -1), base * 6)
    assert.equal(holdStepMs(101, base, two), 0)
    // The values FireOsTest pins are in the Kotlin test file too.
    const test = readFileSync(new URL('../../../core/src/jvmTest/kotlin/com/tmplayer/data/FireOsTest.kt', import.meta.url), 'utf8')
    for (const line of ['assertEquals(30_000L, RemoteQuirks.holdStepMs(40, base, twoHours))', 'assertEquals(36_000L, RemoteQuirks.holdStepMs(100, base, twoHours))', 'assertEquals(60_000L, RemoteQuirks.holdStepMs(100, base, tenHours))']) assert.ok(test.includes(line), line)
})

/** A key held down: the first press, then a repeat every `periodMs` for `ms`. */
function hold(s: SeekStepper, o: { ms: number; periodMs: number; baseSec: number; durationSec?: number; forward?: boolean }) {
    const moves: { at: number; by: number; total: number; announce: boolean }[] = []
    for (let at = 0; at <= o.ms; at += o.periodMs) {
        const r = s.press({ forward: o.forward ?? true, repeat: at > 0, now: 1_000 + at, baseSec: o.baseSec, durationSec: o.durationSec ?? 0 })
        if (r) moves.push({ at, ...r })
    }
    return moves
}

test('a tap is the base step, 10 s on a TV layout and 5 s on a desktop keyboard', () => {
    assert.equal(new SeekStepper().press({ forward: true, repeat: false, now: 0, baseSec: 10, durationSec: 0 })!.by, 10)
    assert.equal(new SeekStepper().press({ forward: false, repeat: false, now: 0, baseSec: 5, durationSec: 0 })!.by, -5)
})

test('30 repeats a second write currentTime at most 7 times a second', () => {
    const moves = hold(new SeekStepper(), { ms: 1_000, periodMs: 33, baseSec: 10 })
    assert.ok(moves.length <= 7, `${moves.length} writes`)
    assert.ok(moves.length >= 5)
    for (let i = 1; i < moves.length; i++) assert.ok(moves[i]!.at - moves[i - 1]!.at >= GATE_MS)
})

test('a slow remote (a repeat every 250 ms) is never gated', () => {
    const moves = hold(new SeekStepper(), { ms: 1_000, periodMs: 250, baseSec: 10 })
    assert.equal(moves.length, 5)
})

test('held, the step grows: the base, x3 after two seconds, x6 after five, capped by the length', () => {
    const long = hold(new SeekStepper(), { ms: 6_000, periodMs: 33, baseSec: 10, durationSec: 3 * 3600 })
    assert.equal(long[1]!.by, 10)
    assert.equal(long.find((m) => m.at >= 2_100)!.by, 30)
    // 3 hours: a 200th is 54 s, so the x6 step (60 s) stops there.
    assert.equal(long.find((m) => m.at >= 5_100)!.by, 54)
    const short = hold(new SeekStepper(), { ms: 6_000, periodMs: 33, baseSec: 10, durationSec: 20 * 60 })
    assert.ok(short.every((m) => m.by === 10), 'a 20 minute video stays at the plain step')
})

test('the running total adds up a run and starts again after a pause or a turn', () => {
    const s = new SeekStepper()
    const at = (now: number, forward: boolean) => s.press({ forward, repeat: false, now, baseSec: 10, durationSec: 0 })!
    assert.equal(at(0, true).total, 10)
    assert.equal(at(300, true).total, 20)
    assert.equal(at(600, true).total, 30)
    assert.equal(at(2_000, true).total, 10)
    assert.equal(at(2_200, false).total, -10)
})

test('a screen reader hears one announcement per 800 ms, with the running total', () => {
    const moves = hold(new SeekStepper(), { ms: 3_000, periodMs: 33, baseSec: 10 })
    const said = moves.filter((m) => m.announce)
    assert.ok(said.length <= Math.ceil(3_000 / ANNOUNCE_MS) + 1, `${said.length} announcements`)
    assert.ok(said.length >= 3)
    assert.ok(said.at(-1)!.total > said[0]!.total)
})

test('a repeat with no press before it (focus arrived with the key down) moves the plain step, not nothing', () => {
    const s = new SeekStepper()
    const r = s.press({ forward: true, repeat: true, now: 5_000, baseSec: 10, durationSec: 0 })
    assert.ok(r && r.by >= 10)
})

test('a first press that arrives as a repeat, or a repeat after the key came up, takes the base step, never the 6x one (PB2-12)', () => {
    const two = 2 * 60 * 60
    const s = new SeekStepper()
    assert.equal(s.press({ forward: true, repeat: true, now: 7_000, baseSec: 10, durationSec: two })!.by, 10)
    // A long hold, the key up, then a repeat with no press of its own (a webOS remote that dropped it).
    const t = new SeekStepper()
    t.press({ forward: true, repeat: false, now: 0, baseSec: 10, durationSec: two })
    t.release()
    assert.equal(t.press({ forward: true, repeat: true, now: 60_000, baseSec: 10, durationSec: two })!.by, 10)
    // Useful use is unchanged: a hold from a real press still ramps.
    const u = new SeekStepper()
    u.press({ forward: true, repeat: false, now: 0, baseSec: 10, durationSec: two })
    assert.ok(u.press({ forward: true, repeat: true, now: 6_000, baseSec: 10, durationSec: two })!.by > 10)
    const chrome = readFileSync(new URL('./Chrome.tsx', import.meta.url), 'utf8')
    assert.match(chrome, /releaseStep: \(\) => stepper\.release\(\)/)
})
