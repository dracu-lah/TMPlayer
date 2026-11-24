// A chat's filters on the Sort menu (U6) and Play random (U16), over the fake Telegram, in every form.
import { expect, test, type Page } from '@playwright/test'
import { noSizeFloor, openDetail } from './helpers'

test.beforeEach(async ({ page }) => {
    test.setTimeout(90_000)
    await noSizeFloor(page)
})

const sortButton = (page: Page) => page.getByRole('button', { name: /^Sort (videos by|and filter, a filter is on)$/ }).first()
async function openMenu(page: Page) {
    await sortButton(page).click()
    await expect(page.getByRole('menu')).toBeVisible()
}
const chip = (page: Page, name: string) => page.getByRole('menu').getByRole('menuitemradio', { name, exact: true })

/** The shot a person looks at, into design/web-gaps/WB13 (E2E_SHOTS=1). */
async function shot(page: Page, name: string, info: { project: { name: string } }) {
    if (!process.env.E2E_SHOTS) return
    await page.screenshot({ path: `../design/web-gaps/WB13/${name}-${info.project.name}.png` })
}

test('the Sort menu lists the order, then the filters as chips; a pick applies at once and is kept for this chat only', async ({ page }, info) => {
    await page.goto('/chat/-1001')
    await expect(page.locator('.card').first()).toBeVisible()
    await openMenu(page)
    for (const group of ['Show', 'Length', 'Picture', 'With', 'Added']) await expect(page.getByRole('group', { name: group, exact: true })).toBeVisible()
    await expect(page.getByRole('menuitemradio', { name: 'Last played' })).toBeVisible()
    await shot(page, 'menu', info)
    await chip(page, 'Shows').click()
    // Every video of this chat is a single one: the filter hides them all, and says so with the way out.
    await expect(chip(page, 'Shows')).toHaveAttribute('aria-checked', 'true')
    await page.keyboard.press('Escape')
    await expect(page.getByText(/^Shows hides all \d+ videos loaded so far\.$/)).toBeVisible()
    await expect(page.locator('.card')).toHaveCount(0)
    await expect(sortButton(page)).toHaveAttribute('aria-label', 'Sort and filter, a filter is on')
    await shot(page, 'hides-all', info)
    // Kept for the chat: a fresh visit is still filtered, another chat is not.
    await page.reload()
    await expect(page.getByText(/^Shows hides all \d+ videos loaded so far\.$/)).toBeVisible()
    await page.goto('/chat/-1002')
    await expect(page.locator('.card').first()).toBeVisible()
    await expect(sortButton(page)).toHaveAttribute('aria-label', 'Sort videos by')
    await page.goto('/chat/-1001')
    await page.getByRole('button', { name: 'Clear filter' }).click()
    await expect(page.locator('.card').first()).toBeVisible()
    await expect(sortButton(page)).toHaveAttribute('aria-label', 'Sort videos by')
    await page.reload()
    await expect(page.locator('.card').first()).toBeVisible()
    await expect(page.getByText(/hides all/)).toHaveCount(0)
})

test('filters by what a video is combine; a video that states no length is kept after the matches and counted', async ({ page }, info) => {
    await page.goto('/chat/-1001')
    await expect(page.locator('.card').first()).toBeVisible()
    await openMenu(page)
    await chip(page, 'Under 20 minutes').click()
    await page.keyboard.press('Escape')
    // Native Clip is a video that says 4 seconds; the files that state no length stay, after it, with a note.
    await expect(page.locator('.card').first()).toContainText('Native Clip')
    await expect(page.getByText(/of them have no stated length or picture size/)).toBeVisible()
    await shot(page, 'unknown', info)
    // HDR is only known from a name that says it: none here does, so everything is hidden and the label names both filters.
    await openMenu(page)
    await page.getByRole('menu').getByRole('menuitemcheckbox', { name: 'HDR' }).click()
    await expect(page.getByRole('menu').getByRole('menuitemcheckbox', { name: 'HDR' })).toHaveAttribute('aria-checked', 'true')
    await page.keyboard.press('Escape')
    await expect(page.getByText(/^Under 20 minutes, HDR hides all \d+ videos loaded so far\.$/)).toBeVisible()
    await page.getByRole('button', { name: 'Clear filter' }).click()
    await expect(page.locator('.card').first()).toBeVisible()
    await expect(page.getByText(/hides all|hidden by your filter/)).toHaveCount(0)
})

test('some videos hidden says how many, Show them lists all for this visit and Filter again puts the filter back', async ({ page }, info) => {
    // One video watched, then the chat filtered to Unwatched.
    await page.goto('/chat/-1001')
    await openDetail(page, 'Matroska Clip')
    await page.locator('.detail-round', { hasText: 'Watched' }).click()
    await expect(page.locator('.detail-watched')).toBeVisible()
    await page.goto('/chat/-1001')
    await expect(page.locator('.card').filter({ hasText: 'Matroska Clip' })).toHaveCount(1)
    await openMenu(page)
    await chip(page, 'Unwatched').click()
    await page.keyboard.press('Escape')
    await expect(page.getByText('1 video hidden by your filter')).toBeVisible()
    await expect(page.locator('.card').filter({ hasText: 'Matroska Clip' })).toHaveCount(0)
    await shot(page, 'hidden', info)
    await page.getByRole('button', { name: 'Show them' }).click()
    await expect(page.getByText('Your filter is off while this chat is open')).toBeVisible()
    await expect(page.locator('.card').filter({ hasText: 'Matroska Clip' })).toHaveCount(1)
    await page.getByRole('button', { name: 'Filter again' }).click()
    await expect(page.getByText('1 video hidden by your filter')).toBeVisible()
    await expect(page.locator('.card').filter({ hasText: 'Matroska Clip' })).toHaveCount(0)
    // Words list what Telegram found, whatever the filter says.
    await page.goto('/chat/-1001?q=Matroska')
    await expect(page.locator('.card').filter({ hasText: 'Matroska Clip' })).toHaveCount(1)
    await expect(page.getByText(/hidden by your filter/)).toHaveCount(0)
})

test('a sort that works on what is loaded says so, and Last played is among the orders', async ({ page }) => {
    await page.goto('/chat/-1001')
    await expect(page.locator('.card').first()).toBeVisible()
    await openMenu(page)
    await page.getByRole('menuitemradio', { name: 'Last played' }).click()
    await expect(page.getByRole('menuitemradio', { name: 'Last played' })).toHaveCount(0)
    await expect(page.locator('.card').first()).toBeVisible()
    await openMenu(page)
    await expect(page.getByRole('menuitemradio', { name: 'Last played' })).toHaveAttribute('aria-checked', 'true')
})

test('Last played lists the video watched last first, drawn once in that order, and the times are posted once, not with every view', async ({ page }) => {
    await page.goto('/chat/-1001')
    await openDetail(page, 'Matroska Clip')
    await page.locator('.detail-round', { hasText: 'Watched' }).click()
    await expect(page.locator('.detail-watched')).toBeVisible()
    await page.goto('/chat/-1001')
    await openMenu(page)
    await page.getByRole('menuitemradio', { name: 'Last played' }).click()
    await expect(page.locator('.card').first()).toContainText('Matroska Clip')
    // A fresh visit draws the first card once: the first card is never another video, not even for a moment.
    await page.addInitScript(() => {
        const seen: string[] = []
        ;(window as unknown as { firsts: string[] }).firsts = seen
        new MutationObserver(() => {
            const first = document.querySelector('.card')?.textContent ?? ''
            if (first && seen[seen.length - 1] !== first) seen.push(first)
        }).observe(document, { childList: true, subtree: true })
    })
    await page.reload()
    await expect(page.locator('.card').first()).toContainText('Matroska Clip')
    await page.waitForTimeout(800)
    const firsts = await page.evaluate(() => (window as unknown as { firsts: string[] }).firsts)
    expect(firsts.length).toBeGreaterThan(0)
    for (const f of firsts) expect(f).toContain('Matroska Clip')
})

test('Play random opens one of the loaded videos and never the same one twice in a row', async ({ page }) => {
    await page.goto('/chat/-1001')
    await expect(page.locator('.card').first()).toBeVisible()
    let last = ''
    for (let i = 0; i < 4; i++) {
        await openMenu(page)
        await page.getByRole('menuitem', { name: 'Play random' }).click()
        await expect(page).toHaveURL(/\/watch\/-1001\/\d+$/)
        const now = new URL(page.url()).pathname
        expect(now).not.toBe(last)
        last = now
        await page.goBack()
        await expect(page.locator('.card').first()).toBeVisible()
    }
})

test('Play all opens the first listed video as a run that ends with the watch page', async ({ page }) => {
    await page.goto('/chat/-1001')
    await expect(page.locator('.card').first()).toBeVisible()
    await openMenu(page)
    // It is the menu's first line, ahead of Play random.
    await expect(page.locator('[role^=menuitem]').first()).toHaveText('Play all')
    await page.getByRole('menuitem', { name: 'Play all' }).click()
    await expect(page).toHaveURL(/\/watch\/-1001\/\d+$/)
    const run = () => page.evaluate(() => sessionStorage.getItem('tmplayer.playall'))
    expect(JSON.parse((await run())!)).toMatchObject({ kind: 'chat', chatId: -1001 })
    await page.goBack()
    await expect(page.locator('.card').first()).toBeVisible()
    expect(await run()).toBeNull()
})

test('a list has Play all, which starts a run over its cards and goes on from the first that is there', async ({ page, isMobile }) => {
    test.skip(isMobile, 'the card menu opens with a long press on a phone (modal-history covers it)')
    await page.goto('/chat/-1001')
    await expect(page.locator('.card').first()).toBeVisible()
    await page.locator('.card').first().click({ button: 'right' })
    await page.locator('dialog.card-menu[open]').getByRole('button', { name: /Add to a list/ }).click()
    await page.locator('dialog[open][aria-label="Add to a list"]').getByRole('button', { name: /New list/ }).click()
    await page.getByLabel('List name').fill('Mix')
    await page.getByRole('button', { name: 'Create' }).click()
    await page.goto('/lists')
    await expect(page.getByRole('link', { name: 'Mix' })).toBeVisible()
    await page.getByRole('button', { name: 'Play all' }).click()
    await expect(page).toHaveURL(/\/watch\/-1001\/\d+$/)
    const run = JSON.parse((await page.evaluate(() => sessionStorage.getItem('tmplayer.playall')))!)
    expect(run.kind).toBe('list')
    expect(run.keys.length).toBe(1)
})

test('a run does not carry to a video that is not part of it', async ({ page }) => {
    await page.goto('/chat/-1001')
    await expect(page.locator('.card').first()).toBeVisible()
    await page.evaluate(() => sessionStorage.setItem('tmplayer.playall', JSON.stringify({ kind: 'chat', chatId: -999, at: Date.now() })))
    await page.goto('/watch/-1001/1')
    await expect.poll(() => page.evaluate(() => sessionStorage.getItem('tmplayer.playall'))).toBeNull()
})

test('Play random is left out where nothing is on screen', async ({ page }) => {
    await page.goto('/chat/-1001')
    await expect(page.locator('.card').first()).toBeVisible()
    await openMenu(page)
    await chip(page, 'Shows').click()
    await page.keyboard.press('Escape')
    await expect(page.locator('.card')).toHaveCount(0)
    await openMenu(page)
    await expect(page.getByRole('menuitem', { name: 'Play random' })).toHaveCount(0)
    await expect(page.getByRole('menuitem', { name: 'Play all' })).toHaveCount(0)
})

test('Watch later has Play random, and a list has it too', async ({ page }) => {
    await page.goto('/chat/-1001')
    await openDetail(page, 'Native Clip')
    await page.locator('.detail-round', { hasText: 'Watch later' }).click()
    await page.goto('/history/later')
    await expect(page.locator('.card')).toHaveCount(1)
    await page.getByRole('button', { name: 'Play random' }).click()
    await expect(page).toHaveURL(/\/watch\/-1001\/\d+$/)
    // A named list: the video added from its page, and one button over the list's cards.
    await page.goto('/lists')
    await page.getByRole('button', { name: 'New list' }).click()
    await page.getByLabel('List name').fill('Mix')
    await page.getByRole('button', { name: 'Create' }).click()
    await expect(page.getByRole('link', { name: 'Mix' })).toBeVisible()
    // Nothing in it yet: nothing to play.
    await expect(page.getByRole('button', { name: 'Play random' })).toHaveCount(0)
})
