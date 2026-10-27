// Plan 8.5: the TV layout walked with keys only (the remote's arrows, OK and Back), at both TV
// sizes: focus comes back to the item that was open after Back, after a menu and after a dialog.
import { expect, test, type Page } from '@playwright/test'
import { fakeCase } from './helpers'

/** The layout set to TV and two chats starred, written where the app keeps them before it starts. */
async function tv(page: Page) {
    await page.goto('/__e2e/sizes')
    await page.evaluate(
        () =>
            new Promise<void>((resolve, reject) => {
                localStorage.setItem('tmplayer-layout', 'tv')
                const open = indexedDB.open('tmplayer-app')
                open.onupgradeneeded = () => open.result.createObjectStore('kv')
                open.onerror = () => reject(open.error)
                open.onsuccess = () => {
                    const tx = open.result.transaction('kv', 'readwrite')
                    const kv = tx.objectStore('kv')
                    kv.put({ sizeMin: 0 }, 'settings')
                    kv.put({ chatId: -1001, time: 1 }, 'star:-1001')
                    kv.put({ chatId: -1002, time: 2 }, 'star:-1002')
                    tx.oncomplete = () => (open.result.close(), resolve())
                }
            }),
    )
}

/**
 * How far, in px, `inner` sits from the middle of `outer` along one axis; with no `inner`, the words'
 * row against the button's (0 when the button is a single centred row).
 */
const offCentre = (page: Page, outer: string, inner: string, axis: 'x' | 'y') =>
    page.locator(outer).first().evaluate((el, [inner, axis]) => {
        const mid = (r: DOMRect) => (axis === 'x' ? r.left + r.width / 2 : r.top + r.height / 2)
        if (!inner) return getComputedStyle(el).display.endsWith('flex') && getComputedStyle(el).alignItems === 'center' ? 0 : 99
        return Math.abs(mid(el.getBoundingClientRect()) - mid(el.querySelector(inner)!.getBoundingClientRect()))
    }, [inner, axis] as const)

/** What has the focus: its address, label or words. */
const focused = (page: Page) =>
    page.evaluate(() => {
        const a = document.activeElement as HTMLElement | null
        return a ? `${a.classList[0] ?? a.tagName}|${a.getAttribute('href') ?? a.getAttribute('aria-label') ?? ''}` : ''
    })

async function press(page: Page, key: string, times = 1) {
    for (let i = 0; i < times; i++) {
        await page.keyboard.press(key)
        await page.waitForTimeout(150)
    }
}

for (const size of [
    { width: 1920, height: 1080 },
    { width: 1280, height: 720 },
]) {
    test.describe(`TV layout at ${size.width} x ${size.height}`, () => {
        test.skip(({ browserName }) => browserName !== 'chromium', 'the TV walk runs in Chromium')
        test.use({ viewport: size })
        test.beforeEach(async ({ page, context }) => {
            await fakeCase(context)
            await tv(page)
        })

        // The fake account's files are under 400 MB, so Home has no carousel and opens on its first card.
        test('Home: no carousel to show opens on the first card, rows, OK opens the page on Play, Back returns to Play and then the card, Back goes to the bar', async ({ page }) => {
            await page.goto('/')
            await expect(page.locator('html')).toHaveAttribute('data-form', 'tv')
            await expect.poll(() => focused(page)).toContain('card|/video/')
            await expect(page.locator('.tv-hero')).toHaveCount(0)
            await press(page, 'ArrowDown', 2)
            await press(page, 'ArrowRight', 2)
            const card = await focused(page)
            expect(card).toContain('card|/video/')
            // OK opens the video's page with the remote on Play, as the app's Open a video's page first.
            await press(page, 'Enter')
            await expect(page).toHaveURL(/\/video\//)
            await expect.poll(() => focused(page)).toBe('detail-play|')
            await expect(page.locator('.tv-bar, .detail-close:visible')).toHaveCount(0)
            await press(page, 'Enter')
            await expect(page).toHaveURL(/\/watch\//)
            await press(page, 'Backspace')
            await expect(page).toHaveURL(/\/video\//)
            await expect.poll(() => focused(page)).toBe('detail-play|')
            await press(page, 'Backspace')
            await expect(page).toHaveURL(/\/$/)
            await expect.poll(() => focused(page)).toBe(card)
            // The remote's Play key plays a card at once, past its page.
            await press(page, 'MediaPlayPause')
            await expect(page).toHaveURL(/\/watch\//)
            await press(page, 'Backspace')
            await expect(page).toHaveURL(/\/$/)
            await expect.poll(() => focused(page)).toBe(card)
            await press(page, 'Escape')
            expect(await focused(page)).toBe('tv-tab|/')
            // Up from the bar goes nowhere; Down goes back into the page where the remote was.
            await press(page, 'ArrowUp')
            expect(await focused(page)).toBe('tv-tab|/')
        })

        test('Chats and a chat: the bar keeps the remote, a chat opens on its first card, the sort menu and Back', async ({ page }) => {
            await page.goto('/')
            await expect.poll(() => focused(page)).toContain('card|/video/')
            await press(page, 'ArrowUp')
            await press(page, 'ArrowRight')
            await press(page, 'Enter')
            await expect(page).toHaveURL(/\/chats$/)
            await expect.poll(() => focused(page)).toBe('tv-tab|/chats')
            await press(page, 'ArrowDown', 2)
            await press(page, 'ArrowRight', 2)
            const row = await focused(page)
            expect(row).toBe('chat-link|/chat/-1001')
            await press(page, 'Enter')
            await expect(page).toHaveURL(/\/chat\/-1001/)
            await expect.poll(() => focused(page)).toContain('card|/video/-1001/')
            // Up from the first card is Back; Right passes the title to the chat's star, then Sort.
            await press(page, 'ArrowUp')
            expect(await focused(page)).toBe('round-button|Back')
            await press(page, 'ArrowRight', 2)
            expect(await focused(page)).toContain('Sort')
            await press(page, 'Enter')
            await press(page, 'ArrowDown')
            expect(await page.evaluate(() => !!document.activeElement?.closest('[role=menu]'))).toBe(true)
            await press(page, 'Backspace')
            await expect(page.locator('[role=menu]')).toHaveCount(0)
            expect(await focused(page)).toContain('Sort')
            await press(page, 'Backspace')
            await expect(page).toHaveURL(/\/chats$/)
            await expect.poll(() => focused(page)).toBe(row)
        })

        test('Settings: a choice is made with OK, not by moving; Sign out asks, Back closes the question onto it', async ({ page }) => {
            await page.goto('/')
            await expect.poll(() => focused(page)).toContain('card|/video/')
            await press(page, 'ArrowUp')
            await press(page, 'ArrowLeft', 3)
            await press(page, 'Enter')
            await expect(page).toHaveURL(/\/settings$/)
            await expect.poll(() => focused(page)).toContain('tv-picture')
            await press(page, 'ArrowDown')
            await press(page, 'ArrowRight')
            expect(await page.evaluate(() => document.documentElement.dataset.theme ?? 'system')).toBe('system')
            await press(page, 'Enter')
            expect(await page.evaluate(() => document.documentElement.dataset.theme)).toBe('dark')
            for (let i = 0; i < 30 && !(await focused(page)).includes('danger-button'); i++) await press(page, 'ArrowDown')
            expect(await focused(page)).toContain('danger-button')
            // Its icon beside the words, not on a line above them (the TV's Settings is not .set).
            expect(await offCentre(page, '.danger-button', 'svg', 'y')).toBeLessThanOrEqual(1)
            expect(await offCentre(page, '.outline-button', '', 'y')).toBe(0)
            await press(page, 'Enter')
            await expect(page.locator('dialog[open]')).toHaveCount(1)
            expect(await page.evaluate(() => !!document.activeElement?.closest('dialog[open]'))).toBe(true)
            await press(page, 'Backspace')
            await expect(page.locator('dialog[open]')).toHaveCount(0)
            expect(await focused(page)).toContain('danger-button')
        })

        test('Settings: choosing Desktop with the remote takes the TV bar and keys away at once', async ({ page }) => {
            await page.goto('/settings')
            await expect.poll(() => focused(page)).not.toBe('')
            for (let i = 0; i < 6 && !(await focused(page)).startsWith('INPUT|'); i++) await press(page, 'ArrowDown')
            await press(page, 'ArrowDown')
            for (let i = 0; i < 4 && (await page.evaluate(() => document.activeElement?.closest('label')?.textContent?.trim())) !== 'Desktop'; i++) await press(page, 'ArrowLeft')
            await press(page, 'Enter')
            await expect(page.locator('html')).toHaveAttribute('data-form', 'desktop')
            await expect(page.locator('.tv-bar')).toHaveCount(0)
            await expect(page.locator('nav.nav')).toBeVisible()
            expect(await page.evaluate(() => document.documentElement.dataset.tvKeys)).toBeUndefined()
        })

        // ---- The player (session 21): everything with the remote's keys ----

        /** Opens a chat's first card with the remote and waits for the picture to play. */
        async function watch(page: Page, chat: string) {
            await page.goto(`/chat/${chat}`)
            await expect.poll(() => focused(page)).toContain('card|/video/')
            await press(page, 'Enter')
            await expect.poll(() => focused(page)).toBe('detail-play|')
            await press(page, 'Enter')
            await expect(page).toHaveURL(/\/watch\//)
            await expect(page.locator('.pc[data-variant=tv]')).toHaveCount(1)
            await expect.poll(() => page.locator('video').evaluate((v: HTMLVideoElement) => v.currentTime)).toBeGreaterThan(0.2)
        }
        const controlsUp = (page: Page) => page.evaluate(() => document.querySelector('.pc')?.classList.contains('shown') ?? false)
        const paused = (page: Page) => page.locator('video').evaluate((v: HTMLVideoElement) => v.paused)
        const onControl = (page: Page) =>
            page.evaluate(() => {
                const a = document.activeElement as HTMLElement | null
                if (!a) return ''
                if (a.getAttribute('role') === 'slider') return 'bar'
                if (a.classList.contains('pc-play')) return 'disc'
                return a.getAttribute('aria-label') ?? a.textContent?.trim() ?? a.tagName
            })
        /** A key the TV sends by code alone (webOS Back 461, Pause 19, Play 415). */
        const code = (page: Page, keyCode: number, key = 'Unidentified') =>
            page.evaluate(([c, k]) => (document.activeElement ?? document.body).dispatchEvent(new KeyboardEvent('keydown', { key: k as string, keyCode: c as number, bubbles: true, cancelable: true })), [keyCode, key])

        test('Player: the picture starts bare, Left jumps, Up raises the row on the bar, Back peels the row and then the video', async ({ page }) => {
            await page.goto('/chat/-1001')
            await expect.poll(() => focused(page)).toContain('card|/video/')
            const card = await focused(page)
            await press(page, 'Enter')
            await expect.poll(() => focused(page)).toBe('detail-play|')
            await press(page, 'Enter')
            await expect(page).toHaveURL(/\/watch\//)
            await expect(page.locator('.pc[data-variant=tv]')).toHaveCount(1)
            expect(await controlsUp(page)).toBe(false)
            // Left over the bare picture (the test clip is 4 s long): ten seconds at once, the figure on the left, no row.
            await press(page, 'ArrowLeft')
            await expect(page.locator('.pc-ripple.left')).toBeVisible()
            expect(await controlsUp(page)).toBe(false)
            await press(page, 'ArrowUp')
            expect(await controlsUp(page)).toBe(true)
            expect(await onControl(page)).toBe('bar')
            // Back puts the row away, the next Back leaves the video for its page, the next the page, and the card has the remote again.
            await press(page, 'Backspace')
            expect(await controlsUp(page)).toBe(false)
            await expect(page).toHaveURL(/\/watch\//)
            await press(page, 'Backspace')
            await expect(page).toHaveURL(/\/video\//)
            await expect.poll(() => focused(page)).toBe('detail-play|')
            await press(page, 'Backspace')
            await expect(page).toHaveURL(/\/chat\/-1001$/)
            await expect.poll(() => focused(page)).toBe(card)
        })

        test('Player: OK pauses with the remote on the disc, arrows walk disc, bar and buttons, menus close back onto their button', async ({ page }) => {
            await watch(page, '-1001')
            await press(page, 'Enter')
            await expect.poll(() => paused(page)).toBe(true)
            expect(await controlsUp(page)).toBe(true)
            expect(await onControl(page)).toBe('disc')
            // The glyph in the middle of its disc: the TV's text-pill padding on button.tonal pushed it 7 px right.
            expect(await offCentre(page, '.pc-play', '.pc-play-glyph', 'x')).toBeLessThanOrEqual(1)
            // OK on the disc plays again (the 4 s test clip loops so the walk below has time).
            await press(page, 'Enter')
            await expect.poll(() => paused(page)).toBe(false)
            await page.locator('video').evaluate((v: HTMLVideoElement) => (v.loop = true))
            await press(page, 'ArrowDown')
            expect(await onControl(page)).toBe('bar')
            // On the bar Left seeks at once.
            await press(page, 'ArrowLeft')
            await expect.poll(() => page.locator('video').evaluate((v: HTMLVideoElement) => v.currentTime)).toBeLessThan(1)
            await press(page, 'ArrowDown')
            expect(await onControl(page)).toBe('Subtitles')
            await press(page, 'ArrowRight')
            expect(await onControl(page)).toBe('Audio')
            await press(page, 'Enter')
            await expect(page.locator('.pc-menu')).toHaveCount(1)
            expect(await page.evaluate(() => !!document.activeElement?.closest('.pc-menu'))).toBe(true)
            await press(page, 'Backspace')
            await expect(page.locator('.pc-menu')).toHaveCount(0)
            expect(await onControl(page)).toBe('Audio')
            await press(page, 'ArrowRight')
            expect(await onControl(page)).toBe('Playback speed')
            await press(page, 'Enter')
            await press(page, 'ArrowDown', 4)
            await press(page, 'Enter')
            await expect(page.locator('.pc-menu')).toHaveCount(0)
            expect(await page.locator('video').evaluate((v: HTMLVideoElement) => v.playbackRate)).toBe(1.5)
            expect(await onControl(page)).toBe('Playback speed')
            // Shape steps with a press and More opens its menu, both reached with the arrows.
            await press(page, 'ArrowRight')
            expect(await onControl(page)).toContain('Picture shape')
            await press(page, 'Enter')
            expect(await onControl(page)).toContain('Picture shape: Crop')
            await press(page, 'ArrowRight')
            expect(await onControl(page)).toBe('More')
            await press(page, 'Enter')
            await expect(page.locator('.pc-menu')).toHaveCount(1)
            await press(page, 'Backspace')
            expect(await onControl(page)).toBe('More')
            // Up from the buttons is the bar, Up again the disc; Back puts the row away.
            await press(page, 'ArrowUp')
            expect(await onControl(page)).toBe('bar')
            await press(page, 'ArrowUp')
            expect(await onControl(page)).toBe('disc')
            await press(page, 'Backspace')
            expect(await controlsUp(page)).toBe(false)
        })

        test('Player: Episodes opens on the playing one, Down and OK start the next, Back closes it onto its pill', async ({ page }) => {
            await watch(page, '-1002')
            await press(page, 'ArrowUp')
            await press(page, 'ArrowDown')
            // Right along the row to the Episodes pill.
            for (let i = 0; i < 8 && (await onControl(page)) !== 'Episodes'; i++) await press(page, 'ArrowRight')
            expect(await onControl(page)).toBe('Episodes')
            await press(page, 'Enter')
            await expect(page.locator('.pc-episodes')).toBeVisible()
            expect(await page.evaluate(() => document.activeElement?.classList.contains('playing'))).toBe(true)
            await press(page, 'Backspace')
            await expect(page.locator('.pc-episodes')).toHaveCount(0)
            expect(await onControl(page)).toBe('Episodes')
            await press(page, 'Enter')
            const first = page.url()
            await press(page, 'ArrowDown')
            expect(await page.evaluate(() => document.activeElement?.classList.contains('pc-episode'))).toBe(true)
            await press(page, 'Enter')
            await expect(page.locator('.pc-episodes')).toHaveCount(0)
            await expect.poll(() => page.url()).not.toBe(first)
        })

        test('Player: the media keys by code (Pause 19, Play 415, Rewind 412, Back 461) work without a name', async ({ page }) => {
            await watch(page, '-1001')
            await code(page, 19)
            await expect.poll(() => paused(page)).toBe(true)
            expect(await onControl(page)).toBe('disc')
            await code(page, 415)
            await expect.poll(() => paused(page)).toBe(false)
            await page.locator('video').evaluate((v: HTMLVideoElement) => (v.loop = true))
            await code(page, 412)
            await expect(page.locator('.pc-ripple.left')).toBeVisible()
            // Back peels the row first (it is up for a few seconds after a key), then leaves.
            const rowUp = await controlsUp(page)
            await code(page, 461, 'GoBack')
            if (rowUp) await code(page, 461, 'GoBack')
            await expect(page).toHaveURL(/\/video\/-1001\//)
        })

        test('Player: a file that needs the app puts the remote on its sheet, and Back leaves', async ({ page }) => {
            await page.goto('/chat/-1001')
            await expect.poll(() => focused(page)).toContain('card|/video/')
            // Toward the WMV with the arrows, whatever the columns: the sponsored card after the third video breaks the first row.
            for (let i = 0; i < 30; i++) {
                const way = await page.evaluate(() => {
                    const at = document.activeElement
                    const goal = [...document.querySelectorAll('a.card')].find((c) => c.textContent?.includes('Windows Clip'))
                    if (!at || !goal || at === goal) return ''
                    const f = at.getBoundingClientRect()
                    const g = goal.getBoundingClientRect()
                    if (g.top >= f.bottom) return 'ArrowDown'
                    if (g.bottom <= f.top) return 'ArrowUp'
                    return g.left > f.left ? 'ArrowRight' : 'ArrowLeft'
                })
                if (!way) break
                await press(page, way)
            }
            expect(await focused(page)).toBe('card|/video/-1001/11')
            await press(page, 'Enter')
            await expect.poll(() => focused(page)).toBe('detail-play|')
            await press(page, 'Enter')
            await expect(page.getByText('This video needs the TMPlayer app')).toBeVisible()
            expect(await page.evaluate(() => !!document.activeElement?.closest('.pc-status'))).toBe(true)
            await press(page, 'Backspace')
            await expect(page).toHaveURL(/\/video\/-1001\/11/)
        })

        test('Player: a paused picture with the row away stops the page\'s own timers', async ({ page }) => {
            await watch(page, '-1001')
            await code(page, 19)
            await press(page, 'Backspace')
            expect(await controlsUp(page)).toBe(false)
            // The container's own idle flag settles a few seconds after the last key; after that the save and
            // stall timers are cleared by the pause and nothing the page owns wakes in the next 2.5 s.
            await page.waitForTimeout(5000)
            const woke = await page.evaluate(
                () =>
                    new Promise<string[]>((resolve) => {
                        const seen: string[] = []
                        const mo = new MutationObserver((list) => list.forEach((m) => seen.push(`${m.type}:${(m.target as HTMLElement).className ?? ''}:${m.attributeName ?? ''}`)))
                        mo.observe(document.body, { subtree: true, childList: true, attributes: true, characterData: true })
                        setTimeout(() => (mo.disconnect(), resolve(seen)), 2500)
                    }),
            )
            expect(woke).toEqual([])
        })

        test('Search: the field opens for typing on OK only, Down goes to the results, Up to the bar', async ({ page }) => {
            await page.goto('/search')
            await expect.poll(() => page.evaluate(() => document.activeElement?.tagName)).toBe('INPUT')
            expect(await page.evaluate(() => (document.activeElement as HTMLInputElement).readOnly)).toBe(true)
            await press(page, 'Enter')
            await page.keyboard.type('Clip')
            await expect(page).toHaveURL(/q=Clip/)
            await expect(page.locator('.card').first()).toBeVisible()
            await press(page, 'ArrowDown')
            expect(await focused(page)).toContain('card|/video/')
            await press(page, 'ArrowUp')
            expect(await focused(page)).toBe('INPUT|Search videos and chats')
            await press(page, 'ArrowUp')
            expect(await focused(page)).toBe('tv-tab|/search')
        })

        test('keys only, every screen: the remote lands on something, never falls to the page, and Back always gets out', async ({ page }) => {
            test.setTimeout(150_000)
            const onBody = () => page.evaluate(() => !document.activeElement || document.activeElement === document.body || document.activeElement === document.documentElement)
            for (const path of ['/', '/chats', '/chat/-1001', '/video/-1001/1', '/search', '/settings', '/history', '/history/later', '/history/watched']) {
                await page.goto(path)
                await expect(page.locator('html')).toHaveAttribute('data-form', 'tv')
                await expect.poll(() => onBody(), { message: `${path}: focus starts on a control` }).toBe(false)
                const seen = new Set<string>([await focused(page)])
                for (const key of ['ArrowDown', 'ArrowDown', 'ArrowRight', 'ArrowDown', 'ArrowLeft', 'ArrowUp', 'ArrowRight', 'ArrowRight', 'ArrowDown', 'ArrowUp', 'ArrowUp', 'ArrowLeft', 'ArrowLeft']) {
                    await press(page, key)
                    expect(await onBody(), `${path}: ${key} dropped the focus`).toBe(false)
                    seen.add(await focused(page))
                }
                expect(seen.size, `${path}: the arrows reach more than one control`).toBeGreaterThan(1)
                // Back, over and over, leaves every screen for the bar and stays there.
                for (let i = 0; i < 6; i++) await press(page, 'Backspace')
                expect(await onBody(), `${path}: Back dropped the focus`).toBe(false)
            }
        })
    })
}

test.describe('TV sign in', () => {
    test.skip(({ browserName }) => browserName !== 'chromium', 'the TV walk runs in Chromium')
    test.use({ viewport: { width: 1920, height: 1080 } })
    test.beforeEach(async ({ page, context }) => {
        await fakeCase(context, 'signedout')
        await tv(page)
    })

    test('the QR code first, the number by the pad and the remote\'s number keys, no field takes the keyboard', async ({ page }) => {
        await page.goto('/')
        await expect(page).toHaveURL(/\/signin/)
        await expect(page.locator('.signin.is-qr .qr')).toBeVisible()
        // Down to "Use my phone number", OK: the pad takes the remote on its first digit.
        for (let i = 0; i < 6 && !(await page.evaluate(() => document.activeElement?.textContent === 'Use my phone number')); i++) await press(page, 'ArrowDown')
        await press(page, 'Enter')
        await expect(page.locator('.keypad')).toBeVisible()
        expect(await focused(page)).toBe('key|')
        const field = page.getByRole('textbox', { name: 'Phone number' })
        expect(await field.evaluate((el: HTMLInputElement) => el.readOnly)).toBe(true)
        // 5 then 7 by the pad (Right, OK); 3 by the remote's number key; Delete takes the last away.
        await press(page, 'ArrowDown')
        await press(page, 'ArrowRight')
        expect(await page.evaluate(() => document.activeElement?.textContent)).toBe('5')
        await press(page, 'Enter')
        await press(page, 'ArrowDown')
        await press(page, 'ArrowLeft')
        expect(await page.evaluate(() => document.activeElement?.textContent)).toBe('7')
        await press(page, 'Enter')
        await page.keyboard.press('3')
        await expect(field).toHaveValue(/573/)
        await press(page, 'ArrowDown')
        expect(await page.evaluate(() => document.activeElement?.getAttribute('aria-label'))).toBe('Delete')
        await press(page, 'Enter')
        await expect(field).toHaveValue(/57$/)
        // No editable field is ever the one the remote is on.
        expect(await page.evaluate(() => (document.activeElement as HTMLInputElement | null)?.readOnly ?? true)).toBe(true)
    })
})
