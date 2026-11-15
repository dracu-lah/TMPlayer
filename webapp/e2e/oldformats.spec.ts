// The formats libav.js plays beyond AVI and WMV (player/libav.ts), from the fake Telegram's
// `oldformats` chat (/chat/-1008), whose clips are real files in e2e/media (make-media.sh): FLV,
// RealMedia and VOB (mediabunny does not open them), MPEG-2 in MPEG-TS and Theora in Ogg (it finds
// no picture), MPEG-4 Part 2 in Matroska (a picture it cannot name), and H.264 with TrueHD sound
// (sound it cannot name: the picture is copied and the sound decoded).
import { expect, test } from '@playwright/test'
import { fakeCase, noSizeFloor, openCard, playsPast } from './helpers'

test.beforeEach(async ({ page, context }) => {
    await fakeCase(context, 'oldformats')
    await noSizeFloor(page)
    await page.goto('/chat/-1008')
    await expect(page.locator('.card').first()).toBeVisible()
})

for (const name of ['Flash Clip', 'Real Clip', 'Dvd Clip', 'Broadcast Clip', 'Part Two Clip', 'Theora Clip']) {
    test(`${name} plays through libav.js`, async ({ page }) => {
        await openCard(page, name)
        await playsPast(page, 1)
        await expect(page.getByText('This video needs the TMPlayer app')).toHaveCount(0)
    })
}

test('H.264 with TrueHD sound plays with its sound', async ({ page }) => {
    await openCard(page, 'Lossless Sound Clip')
    await playsPast(page, 1)
    // libav.js played it, so the sound is in the stream, not left out with a notice.
    const tracks = await page.locator('video').first().evaluate((v: HTMLVideoElement) => {
        const ms = v as HTMLVideoElement & { audioTracks?: { length: number }; webkitAudioDecodedByteCount?: number }
        return ms.webkitAudioDecodedByteCount ?? ms.audioTracks?.length ?? 1
    })
    expect(tracks).toBeGreaterThan(0)
    await expect(page.getByText(/no sound|without sound/i)).toHaveCount(0)
})

for (const name of ['Dvd Clip', 'Flash Clip', 'Broadcast Clip']) {
    test(`seeking in ${name} lands near where it was told, and back to the start`, async ({ page }) => {
        await openCard(page, name)
        await playsPast(page, 0.5)
        const at = await page.locator('video').first().evaluate(async (v: HTMLVideoElement) => {
            v.currentTime = 3
            await new Promise((r) => v.addEventListener('seeked', r, { once: true }))
            return v.currentTime
        })
        expect(at).toBeGreaterThan(2.4)
        await playsPast(page, 3.2)
        // FLV keeps no index: back to 0 goes by bytes and must still find the first picture.
        await page.locator('video').first().evaluate(async (v: HTMLVideoElement) => {
            v.currentTime = 0
            await new Promise((r) => v.addEventListener('seeked', r, { once: true }))
        })
        await playsPast(page, 0.5)
    })
}
