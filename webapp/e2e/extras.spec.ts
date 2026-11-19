// The player's extras (session 13): chapters on the bar and their keys, the skip offer, A-B repeat,
// bookmarks, the sleep timer and "Still watching?", autoplay's countdown, subtitle timing, the key
// sheet and Watched in the menu. The 20 s chaptered show ("Lighthouse") is e2e/media/chapters.mkv.
import { expect, test, type Page } from '@playwright/test'
import { choice, noSizeFloor, openCard, playsPast } from './helpers'

test.skip(({ browserName }) => browserName !== 'chromium', 'the extras run in Chromium')

const time = (page: Page) => page.locator('video').evaluate((v: HTMLVideoElement) => v.currentTime)
const seek = (page: Page, s: number) => page.locator('video').evaluate((v: HTMLVideoElement, to) => (v.currentTime = to), s)

/** Opens the first Lighthouse episode and waits for it to play. */
async function lighthouse(page: Page) {
    await noSizeFloor(page)
    await page.goto('/chat/-1006')
    await openCard(page, 'Lighthouse')
    await playsPast(page, 0.3)
    // The chapters are read once the file has opened.
    await expect(page.locator('.pc-mark')).toHaveCount(2)
}

const openMore = async (page: Page) => {
    await page.mouse.move(400, 300)
    await page.locator('[data-shot=more]').first().click()
}

test('chapters mark the bar, the command key steps through them, and the card names the chapter', async ({ page }) => {
    await lighthouse(page)
    await page.keyboard.press('Control+ArrowRight')
    await expect(page.locator('.pc-note')).toHaveText('Chapter 2 of 3: The Harbour')
    expect(await time(page)).toBeGreaterThan(4.9)
    await page.keyboard.press('Control+ArrowRight')
    await expect(page.locator('.pc-note')).toHaveText('Chapter 3 of 3: Credits')
    await page.keyboard.press('Control+ArrowRight')
    await expect(page.locator('.pc-note')).toHaveText('This is the last chapter')
    // Just into the last chapter, back goes to the one before.
    await page.keyboard.press('Control+ArrowLeft')
    await expect(page.locator('.pc-note')).toHaveText('Chapter 2 of 3: The Harbour')
})

test('the intro is offered to skip, and skipping lands on the next chapter', async ({ page }) => {
    await lighthouse(page)
    await seek(page, 1)
    const pill = page.locator('.pc-corner.skip')
    await expect(pill).toHaveText('Skip intro')
    await pill.click()
    expect(await time(page)).toBeGreaterThan(4.9)
    await expect(pill).toHaveCount(0)
})

test('an anime AniList matched gets AniSkip\'s recap beside the chapters, asked by episode and length, and skipping it lands at its end', async ({ page }) => {
    const asks: string[] = []
    await page.route('**/tmdb/**', (route) => {
        const url = new URL(route.request().url())
        if (url.pathname === '/tmdb/poster') return route.fulfill({ json: { match: true, id: '20', kind: 'show', title: 'Lighthouse', year: 2019, poster: null, backdrop: null, overview: '', source: 'anilist' } })
        if (url.pathname === '/tmdb/skip') {
            asks.push(url.search)
            return route.fulfill({ json: { segments: [{ kind: 'recap', start: 8, end: 12 }, { kind: 'opening', start: 6, end: 9 }] } })
        }
        return route.fulfill({ json: { found: false } })
    })
    await lighthouse(page)
    expect(asks).toEqual(['?id=20&e=1&len=20'])
    // The database's second opening beside the file's own intro chapter is not offered; its recap is.
    await seek(page, 7)
    await expect(page.locator('.pc-corner.skip')).toHaveCount(0)
    await seek(page, 9)
    const pill = page.locator('.pc-corner.skip')
    await expect(pill).toHaveText('Skip recap')
    await pill.click()
    expect(await time(page)).toBeGreaterThan(11.9)
    // And the chapters' own intro is still offered first.
    await seek(page, 1)
    await expect(pill).toHaveText('Skip intro')
})

test('a title TMDB matched asks AniSkip nothing', async ({ page }) => {
    const asks: string[] = []
    await page.route('**/tmdb/**', (route) => {
        const url = new URL(route.request().url())
        if (url.pathname === '/tmdb/poster') return route.fulfill({ json: { match: true, id: '20', kind: 'show', title: 'Lighthouse', year: 2019, poster: null, backdrop: null, overview: '' } })
        if (url.pathname === '/tmdb/skip') asks.push(url.search)
        return route.fulfill({ json: { found: false } })
    })
    await lighthouse(page)
    await page.waitForTimeout(500)
    expect(asks).toEqual([])
})

test('A-B repeat plays the stretch again and a third press ends it', async ({ page }) => {
    await lighthouse(page)
    await seek(page, 6)
    await page.keyboard.press('r')
    await expect(page.locator('.pc-chipline.loop')).toContainText(/Loop from|From 0:06/)
    await page.waitForTimeout(1200)
    await page.keyboard.press('r')
    await expect(page.locator('.pc-chipline.loop')).toContainText('Repeating')
    // Played into the end, it goes back to the start: the time never gets far past the end.
    await page.waitForTimeout(3500)
    expect(await time(page)).toBeLessThan(9.5)
    await page.keyboard.press('r')
    await expect(page.locator('.pc-chipline.loop')).toHaveCount(0)
})

test('a bookmark is saved at the time, listed, played from and deleted', async ({ page }) => {
    await lighthouse(page)
    await seek(page, 8)
    await page.keyboard.press('b')
    await expect(page.locator('.pc-note')).toContainText('Bookmark saved at 0:0')
    await seek(page, 2)
    await openMore(page)
    await page.getByRole('menuitem', { name: /^Bookmarks/ }).click()
    const row = page.locator('.pc-bookmark')
    await expect(row).toHaveCount(1)
    await row.click()
    await expect(page.locator('.pc-sheet')).toHaveCount(0)
    expect(await time(page)).toBeGreaterThan(7.5)
    // It is still there after the player is reopened: kept with the device's other notes.
    await page.reload()
    await expect(page.locator('video')).toHaveCount(1)
    await openMore(page)
    await page.getByRole('menuitem', { name: /^Bookmarks/ }).click()
    await expect(page.locator('.pc-bookmark')).toHaveCount(1)
    await page.getByRole('button', { name: 'Delete bookmark' }).click()
    await expect(page.locator('.pc-bookmark')).toHaveCount(0)
    await expect(page.getByText('No bookmarks yet')).toBeVisible()
})

test('the sleep timer set for the end of the video stops there and asks', async ({ page }) => {
    await lighthouse(page)
    await openMore(page)
    await page.getByRole('menuitem', { name: 'Playback options' }).click()
    await page.getByRole('menuitem', { name: /^Sleep timer/ }).click()
    await choice(page, 'Stops at the end of this video').click()
    await seek(page, 19)
    const card = page.getByRole('alertdialog', { name: 'Still watching?' })
    await expect(card).toBeVisible({ timeout: 10_000 })
    await expect(card).toContainText('The sleep timer stopped at the end of the video.')
    // Nothing started on its own: still on the first episode.
    await expect(page).toHaveURL(/\/watch\//)
    await card.getByRole('button', { name: 'Keep watching' }).click()
    await expect(card).toHaveCount(0)
})

test('a sleep timer in minutes pauses the video when it runs out', async ({ page }) => {
    await page.clock.install()
    await lighthouse(page)
    await openMore(page)
    await page.getByRole('menuitem', { name: 'Playback options' }).click()
    await page.getByRole('menuitem', { name: /^Sleep timer/ }).click()
    await choice(page, '15 minutes').click()
    await expect(page.locator('.pc-note')).toHaveText('Stops in 15 minutes')
    await page.clock.fastForward('15:05')
    await expect(page.getByRole('alertdialog', { name: 'Still watching?' })).toContainText('The sleep timer paused the video.')
    expect(await page.locator('video').evaluate((v: HTMLVideoElement) => v.paused)).toBe(true)
})

test('autoplay counts down on the next card and starts the next episode', async ({ page }) => {
    await lighthouse(page)
    await seek(page, 19.2)
    await expect(page.locator('.pc-next .pc-count')).toContainText('Next episode in')
    await expect(page).toHaveURL(/\/watch\/-1006\/\d+$/)
    const first = page.url()
    await expect.poll(() => page.url(), { timeout: 15_000 }).not.toBe(first)
    await playsPast(page, 0.3)
})

test('Hide on the countdown stops autoplay', async ({ page }) => {
    await lighthouse(page)
    await seek(page, 19.2)
    await page.locator('.pc-next').getByRole('button', { name: 'Cancel' }).click()
    const first = page.url()
    await page.waitForTimeout(6500)
    expect(page.url()).toBe(first)
})

test('subtitles move with the delay keys', async ({ page }) => {
    await lighthouse(page)
    await page.locator('input[type=file]').setInputFiles({ name: 'clip.srt', mimeType: 'text/plain', buffer: Buffer.from('1\n00:00:02,000 --> 00:00:04,000\nHello there\n') })
    await expect.poll(() => page.locator('video').evaluate((v: HTMLVideoElement) => v.textTracks[0]?.cues?.length ?? 0)).toBe(1)
    const start = () => page.locator('video').evaluate((v: HTMLVideoElement) => Math.round(((v.textTracks[0].cues![0] as VTTCue).startTime) * 100) / 100)
    expect(await start()).toBe(2)
    await page.keyboard.press('x')
    await expect(page.locator('.pc-note')).toHaveText('Subtitle delay +0.1 s')
    expect(await start()).toBe(2.1)
    await page.keyboard.press('z')
    await page.keyboard.press('z')
    expect(await start()).toBe(1.9)
})

test('the question mark opens the key sheet', async ({ page }) => {
    await lighthouse(page)
    await page.keyboard.press('Shift+?')
    const sheet = page.getByRole('dialog', { name: 'Keyboard shortcuts' })
    await expect(sheet).toBeVisible()
    await expect(sheet).toContainText('Previous, next chapter')
    await page.keyboard.press('Escape')
    await expect(sheet).toHaveCount(0)
})

test('Watched in the menu marks the video, and the entry turns into Unwatched', async ({ page }) => {
    await lighthouse(page)
    await openMore(page)
    await page.getByRole('menuitem', { name: 'Mark as watched' }).click()
    await expect(page.locator('.pc-note')).toHaveText('Marked as watched')
    await openMore(page)
    await expect(page.getByRole('menuitem', { name: 'Mark as unwatched' })).toBeVisible()
})

test('subtitle style and timing change the look', async ({ page }) => {
    await lighthouse(page)
    await openMore(page)
    await page.getByRole('menuitem', { name: 'Subtitle style and timing' }).click()
    const sheet = page.getByRole('dialog', { name: 'Subtitle style and timing' })
    await sheet.getByRole('radio', { name: 'Yellow' }).click()
    await sheet.getByRole('radio', { name: 'Large', exact: true }).click()
    const vars = await page.locator('video').evaluate((v: HTMLVideoElement) => [v.style.getPropertyValue('--cue-colour'), v.style.getPropertyValue('--cue-size')])
    expect(vars[0]).toBe('#FFE14D')
    expect(parseInt(vars[1])).toBeGreaterThan(10)
})

/** HX16: TMPLAYER_SHOTS=<dir> keeps the panel's pictures there, as the desktop's render tests do. */
const keep = async (page: Page, name: string) => {
    const dir = process.env.TMPLAYER_SHOTS
    if (!dir) return
    // After the panel's slide and the sheet's fade.
    await page.waitForTimeout(400)
    await page.screenshot({ path: `${dir}/web-${name}-${test.info().project.name}.png` })
}

test('More opens the side panel on one click, its pages inside it, and a ratio shapes the picture', async ({ page }) => {
    const phone = test.info().project.name.startsWith('phone')
    await lighthouse(page)
    await openMore(page)
    // The phone's sheet on a phone, the end edge panel everywhere else, the keyboard in it at once.
    const panel = page.locator(phone ? '.pc-sheet' : '.pc-panel')
    await expect(panel).toBeVisible()
    await expect(panel).toContainText('More options')
    expect(await page.evaluate(() => !!document.activeElement?.closest('.pc-panel, .pc-sheet'))).toBe(true)
    if (!phone) {
        // Once its 150 ms slide is over: against the end edge, the player's full height.
        const stage = (await page.locator('.player').boundingBox())!
        await expect.poll(async () => Math.round((await panel.boundingBox())!.x + (await panel.boundingBox())!.width)).toBe(Math.round(stage.x + stage.width))
        expect(Math.round((await panel.boundingBox())!.height)).toBe(Math.round(stage.height))
        await expect(panel.getByRole('menuitem', { name: /^Playback speed/ })).toContainText('1x')
    }
    await keep(page, 'more')
    await panel.getByRole('menuitem', { name: /^Picture shape/ }).click()
    await expect(page.locator(phone ? '.pc-sheet' : '.pc-panel')).toContainText('2.35:1')
    await keep(page, 'shape')
    await page.getByRole(phone ? 'radio' : 'menuitemradio', { name: '4:3' }).click()
    const shape = () => page.locator('video').evaluate((v: HTMLVideoElement) => ({ fit: v.style.objectFit, w: v.offsetWidth, h: v.offsetHeight }))
    await expect.poll(async () => (await shape()).fit).toBe('fill')
    const s = await shape()
    expect(Math.abs(s.w / s.h - 4 / 3)).toBeLessThan(0.01)
    // In the panel a pick leaves it open, with Back to the first page; on a phone the sheet closes.
    if (!phone) {
        await expect(page.locator('.pc-panel')).toBeVisible()
        await page.locator('.pc-panel-back').click()
        await expect(page.locator('.pc-panel')).toContainText('More options')
        await page.keyboard.press('w')
        await expect(page.locator('.pc-panel')).toBeVisible()
        expect((await shape()).fit).toBe('fill')
        await page.keyboard.press('Escape')
        await expect(page.locator('.pc-panel')).toHaveCount(0)
    } else {
        await expect(page.locator('.pc-sheet')).toHaveCount(0)
    }
    // W steps through 21:9, 2.35:1 and 1:1, then round to Original, which gives the box back.
    for (let i = 0; i < 4; i++) await page.keyboard.press('w')
    await expect.poll(async () => (await shape()).fit).toBe('contain')
    expect(await page.locator('video').evaluate((v: HTMLVideoElement) => v.style.width)).toBe('')
})

// WB15: Rotate picture, Volume boost and the speed kept per show and chat.

const panelOf = (page: Page) => page.locator(test.info().project.name.startsWith('phone') ? '.pc-sheet' : '.pc-panel')
const radio = (page: Page, name: string | RegExp) => page.getByRole(test.info().project.name.startsWith('phone') ? 'radio' : 'menuitemradio', { name, exact: typeof name === 'string' })

test('Rotate picture turns the picture a quarter at a time inside its box, and the next video starts upright', async ({ page }) => {
    await lighthouse(page)
    await openMore(page)
    await panelOf(page).getByRole('menuitem', { name: /^Rotate picture/ }).click()
    await expect(panelOf(page)).toContainText('Turns the video inside the screen')
    await keep(page, 'rotate-page')
    const state = () =>
        page.locator('video').evaluate((v: HTMLVideoElement) => {
            const stage = v.parentElement!.getBoundingClientRect()
            const r = v.getBoundingClientRect()
            return {
                transform: v.style.transform,
                turn: v.closest('.player')!.getAttribute('data-turn'),
                w: v.offsetWidth,
                h: v.offsetHeight,
                sw: stage.width,
                sh: stage.height,
                inside: r.left >= stage.left - 1 && r.right <= stage.right + 1 && r.top >= stage.top - 1 && r.bottom <= stage.bottom + 1,
            }
        })
    await radio(page, /^A quarter turn clockwise/).click()
    await expect.poll(async () => (await state()).transform).toBe('rotate(90deg)')
    const s = await state()
    expect(s.turn).toBe('90')
    // Sideways: the box trades the stage's width and height, so the turned box lands on the stage exactly.
    expect(Math.abs(s.w - s.sh)).toBeLessThan(2)
    expect(Math.abs(s.h - s.sw)).toBeLessThan(2)
    expect(s.inside).toBe(true)
    await keep(page, 'rotate-90')
    // Upside down keeps the box and turns it; the panel stays open on the desktop, the phone's sheet closed.
    if (test.info().project.name.startsWith('phone')) {
        await openMore(page)
        await panelOf(page).getByRole('menuitem', { name: /^Rotate picture/ }).click()
    }
    await radio(page, /^Upside down/).click()
    await expect.poll(async () => (await state()).transform).toBe('rotate(180deg)')
    const u = await state()
    expect(Math.abs(u.w - u.sw)).toBeLessThan(2)
    expect(u.inside).toBe(true)
    // The subtitles stay upright: while turned they are drawn in their own layer, the element's cue box is hidden.
    await expect(page.locator('.movi-cues')).toHaveCount(1)
    if (test.info().project.name.startsWith('phone')) {
        await openMore(page)
        await panelOf(page).getByRole('menuitem', { name: /^Rotate picture/ }).click()
    }
    await radio(page, 'Not rotated').click()
    await expect.poll(async () => (await state()).transform).toBe('')
    expect((await state()).turn).toBeNull()
    await expect(page.locator('.movi-cues')).toHaveCount(0)
    // Turned again and left: a new player starts upright.
    if (test.info().project.name.startsWith('phone')) {
        await openMore(page)
        await panelOf(page).getByRole('menuitem', { name: /^Rotate picture/ }).click()
    }
    await radio(page, /^Upside down/).click()
    await expect.poll(async () => (await state()).transform).toBe('rotate(180deg)')
    await page.goto('/chat/-1006')
    await openCard(page, 'Lighthouse')
    await playsPast(page, 0.3)
    expect((await state()).transform).toBe('')
})

test('Volume boost lifts the sound through the page\'s own graph and is kept for the next video', async ({ page }) => {
    await lighthouse(page)
    await openMore(page)
    await panelOf(page).getByRole('menuitem', { name: /^Volume boost/ }).click()
    await expect(panelOf(page)).toContainText('up to 200%')
    await keep(page, 'boost-page')
    await radio(page, '150%').click()
    const path = () => page.locator('video').evaluate((v: HTMLVideoElement) => ({ level: v.dataset.boost, path: v.dataset.boostPath, time: v.currentTime }))
    await expect.poll(async () => (await path()).path).toBe('element')
    expect((await path()).level).toBe('1.5')
    // Playing on through the graph: a context that did not run would have gone silent while the clock went on, so this checks the route only.
    const at = (await path()).time
    await expect.poll(async () => (await path()).time, { timeout: 8000 }).toBeGreaterThan(at + 0.3)
    await page.waitForTimeout(600)
    // Kept: the next video says where it stands, and opens with the level set.
    await page.goto('/chat/-1006')
    await openCard(page, 'Lighthouse')
    await playsPast(page, 0.3)
    await openMore(page)
    await expect(panelOf(page).getByRole('menuitem', { name: /^Volume boost/ })).toContainText('150%')
    await expect.poll(async () => (await path()).level, { timeout: 8000 }).toBe('1.5')
    // Off again.
    await panelOf(page).getByRole('menuitem', { name: /^Volume boost/ }).click()
    await radio(page, 'Volume boost off').click()
    await expect.poll(async () => (await path()).level).toBe('1')
})

test('on an iPhone the Volume boost entry says why it does nothing there, and Rotate picture stays', async ({ page }) => {
    await page.addInitScript(() => Object.defineProperty(navigator, 'userAgent', { get: () => 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1' }))
    await lighthouse(page)
    await openMore(page)
    // The iPhone user agent also gives the phone's layout.
    const menu = page.locator('.pc-sheet, .pc-panel')
    await expect(menu.getByRole('menuitem', { name: /^Rotate picture/ })).toBeVisible()
    await menu.getByRole('menuitem', { name: /^Volume boost/ }).click()
    await expect(menu).toContainText('not available in Safari on iPhone')
    await expect(menu.getByRole('radio')).toHaveCount(0)
})

test('Shift+R turns the picture a quarter, the zoom and the turn share one transform, and Alt+0 puts the zoom back without undoing the turn', async ({ page }) => {
    await lighthouse(page)
    const tf = () => page.locator('video').evaluate((v: HTMLVideoElement) => ({ transform: v.style.transform, cues: v.parentElement!.querySelectorAll('.movi-cues').length, zoomed: v.closest('.player')!.classList.contains('pc-zoomed') }))
    await page.keyboard.press('Shift+R')
    await expect.poll(async () => (await tf()).transform).toBe('rotate(90deg)')
    await expect(page.locator('.pc-note')).toContainText('Picture rotated 90')
    // Zoomed over the turn: both in the one string, the turn kept, one cue layer for the two.
    await page.keyboard.press('Alt+Equal')
    await page.keyboard.press('Alt+Equal')
    await expect.poll(async () => (await tf()).transform).toMatch(/^translate\(.*\) scale\([\d.]+\) rotate\(90deg\)$/)
    expect((await tf()).zoomed).toBe(true)
    expect((await tf()).cues).toBe(1)
    // Zoom reset: the turn stays.
    await page.keyboard.press('Alt+Digit0')
    await expect.poll(async () => (await tf()).transform).toBe('rotate(90deg)')
    expect((await tf()).zoomed).toBe(false)
    // A turn after a zoom starts the zoom again, and the last of four quarters is upright with no transform left over.
    await page.keyboard.press('Alt+Equal')
    await page.keyboard.press('Shift+R')
    await expect.poll(async () => (await tf()).transform).toBe('rotate(180deg)')
    await page.keyboard.press('Shift+R')
    await page.keyboard.press('Shift+R')
    await expect.poll(async () => (await tf()).transform).toBe('')
    expect((await tf()).cues).toBe(0)
    // Shift+R is on the key sheet.
    await page.keyboard.press('?')
    await expect(page.locator('.pc-shortcuts')).toContainText('Shift+R')
})

test('a speed is kept for the show, else the chat, and 1x forgets it', async ({ page }) => {
    // Opens four videos in a row: longer than one.
    test.setTimeout(90_000)
    const rate = () => page.locator('video').first().evaluate((v: HTMLVideoElement) => v.playbackRate)
    await lighthouse(page)
    expect(await rate()).toBe(1)
    await page.keyboard.press(']')
    await expect.poll(rate).toBe(1.25)
    // Written to the store, which saves after a moment.
    await page.waitForTimeout(600)
    // The same show opens at it again, once the show is read; another chat's video stays at 1x.
    await page.goto('/chat/-1006')
    await openCard(page, 'Lighthouse')
    await playsPast(page, 0.3)
    await expect.poll(rate).toBe(1.25)
    await page.goto('/chat/-1001')
    await openCard(page, 'Native Clip')
    await playsPast(page, 0.3)
    expect(await rate()).toBe(1)
    // 1x on the show forgets it: the next one opens at 1x too.
    await page.goto('/chat/-1006')
    await openCard(page, 'Lighthouse')
    await playsPast(page, 0.3)
    await expect.poll(rate).toBe(1.25)
    await page.keyboard.press('Backspace')
    await expect.poll(rate).toBe(1)
    await page.waitForTimeout(600)
    await page.goto('/chat/-1006')
    await openCard(page, 'Lighthouse')
    await playsPast(page, 0.3)
    await page.waitForTimeout(500)
    expect(await rate()).toBe(1)
})
