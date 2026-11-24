// The volume boost's limiter chain, measured through real Web Audio nodes (an OfflineAudioContext in
// Chromium): the level asked is the level heard, the compressor's own makeup gain is taken back out,
// and no sample leaves above full scale however hard it is lifted. Night mode's compressor is measured the same
// way: a loud scene ends quieter than a quiet one by far less than it went in, and the track's level is not changed under its threshold.
import { expect, test } from '@playwright/test'

test.skip(({ browserName }) => browserName !== 'chromium', 'measured in Chromium')

test('125 and 200 per cent play at the gain asked, and a lifted peak never goes above 1.0', async ({ page }) => {
    await page.goto('/')
    const out = await page.evaluate(async () => {
        const at = '/src/player/boost.ts'
        const { limiterBetween } = (await import(/* @vite-ignore */ at)) as typeof import('../src/player/boost')
        const RATE = 48000
        /** A 440 Hz sine of `amp`, lifted by `level` through the chain; the rms and peak of its last half second. */
        const run = async (amp: number, level: number) => {
            const ctx = new OfflineAudioContext(1, RATE, RATE)
            const buffer = ctx.createBuffer(1, RATE, RATE)
            const data = buffer.getChannelData(0)
            for (let i = 0; i < RATE; i++) data[i] = amp * Math.sin((2 * Math.PI * 440 * i) / RATE)
            const source = ctx.createBufferSource()
            source.buffer = buffer
            const gain = ctx.createGain()
            gain.gain.value = level
            source.connect(gain)
            limiterBetween(ctx, gain, ctx.destination).set(level > 1)
            source.start()
            const rendered = (await ctx.startRendering()).getChannelData(0).subarray(RATE / 2)
            let sum = 0
            let peak = 0
            for (const x of rendered) {
                sum += x * x
                peak = Math.max(peak, Math.abs(x))
            }
            return { rms: Math.sqrt(sum / rendered.length), peak }
        }
        const rmsIn = (amp: number) => amp / Math.SQRT2
        const gainOut = async (amp: number, level: number) => (await run(amp, level)).rms / rmsIn(amp)
        return {
            unity: await gainOut(0.2, 1),
            quarter: await gainOut(0.2, 1.25),
            twice: await gainOut(0.2, 2),
            hot: await run(0.9, 2),
            full: await run(1, 2),
            hotQuarter: await run(0.9, 1.25),
        }
    })
    // The gain heard is the gain asked, under the limiter's threshold: no +1.7 dB the compressor adds by itself.
    expect(Math.abs(out.unity - 1)).toBeLessThan(0.02)
    expect(Math.abs(out.quarter - 1.25)).toBeLessThan(0.03)
    expect(Math.abs(out.twice - 2)).toBeLessThan(0.05)
    // Hot sound lifted hard is held back and never clips.
    expect(out.hot.peak).toBeLessThanOrEqual(1)
    expect(out.full.peak).toBeLessThanOrEqual(1)
    expect(out.hotQuarter.peak).toBeLessThanOrEqual(1)
    expect(out.hot.peak).toBeGreaterThan(0.5)
})

test('Night mode holds a loud scene back and lifts dialogue, as the apps do', async ({ page }) => {
    await page.goto('/')
    const out = await page.evaluate(async () => {
        const at = '/src/player/boost.ts'
        const { squeezeBetween, NIGHT, NIGHT_LIFT } = (await import(/* @vite-ignore */ at)) as typeof import('../src/player/boost')
        const RATE = 48000
        /** The rms of the last half second of a 440 Hz sine of `amp`, through the lift and the squeeze (or past them). */
        const rms = async (amp: number, on: boolean) => {
            const ctx = new OfflineAudioContext(1, RATE, RATE)
            const buffer = ctx.createBuffer(1, RATE, RATE)
            const data = buffer.getChannelData(0)
            for (let i = 0; i < RATE; i++) data[i] = amp * Math.sin((2 * Math.PI * 440 * i) / RATE)
            const source = ctx.createBufferSource()
            source.buffer = buffer
            const lift = ctx.createGain()
            lift.gain.value = on ? NIGHT_LIFT : 1
            source.connect(lift)
            squeezeBetween(ctx, lift, ctx.destination, NIGHT, false).set(on)
            source.start()
            const rendered = (await ctx.startRendering()).getChannelData(0).subarray(RATE / 2)
            let sum = 0
            for (const x of rendered) sum += x * x
            return Math.sqrt(sum / rendered.length)
        }
        const dialogue = 0.03
        const loud = 0.8
        return {
            dialogueOff: await rms(dialogue, false),
            dialogueOn: await rms(dialogue, true),
            loudOff: await rms(loud, false),
            loudOn: await rms(loud, true),
        }
    })
    // Dialogue under the threshold comes up by the lift (about 6 dB), the compressor adding nothing.
    expect(Math.abs(out.dialogueOn / out.dialogueOff - 2)).toBeLessThan(0.15)
    // A loud scene is squeezed: it rises by far less than the lift, so the range between the two is smaller.
    expect(out.loudOn / out.loudOff).toBeLessThan(1.2)
    expect(out.loudOn / out.dialogueOn).toBeLessThan(out.loudOff / out.dialogueOff)
})
