// The screens and the edge cases of plan sections 4 and 5, over the fake Telegram.
import { expect, test } from '@playwright/test'
import { fakeCase, noSizeFloor, openClips } from './helpers'

test('signed out: sign in, with the unofficial line and the legal link', async ({ page, context }) => {
    await fakeCase(context, 'signedout')
    await page.goto('/chats')
    await expect(page).toHaveURL(/\/signin\?next=%2Fchats/)
    await expect(page).toHaveTitle('TMPlayer')
    await expect(page.getByText('TMPlayer is an unofficial app that uses the Telegram API. It is not made by Telegram.')).toBeVisible()
    await expect(page.locator('a[href="https://tmplayer.org/legal"]')).toBeVisible()
})

test('a session ended on another device goes back to sign in', async ({ page, context }) => {
    await fakeCase(context, 'revoked')
    await page.goto('/')
    await expect(page).toHaveURL(/\/signin/)
})

test('a chat is arranged by the app rules, with what Telegram restricts left out', async ({ page }) => {
    // Tall enough for the whole grid: a virtual grid draws only the rows near the screen.
    await page.setViewportSize({ width: page.viewportSize()!.width, height: 2400 })
    await noSizeFloor(page)
    await openClips(page)
    const names = page.locator('.card')
    for (const name of ['Native Clip', 'Tail Index', 'Matroska Clip', 'Transport Clip', 'Surround Clip', 'Webm Clip', 'Old Clip', 'Xvid Clip', 'Divx Clip', 'Avc Avi Clip', 'Windows Clip', 'Broken Clip'])
        await expect(names.filter({ hasText: name })).toHaveCount(1)
    // Self-destructing and withheld videos are never listed; the first is counted.
    await expect(names.filter({ hasText: 'Burning Clip' })).toHaveCount(0)
    await expect(names.filter({ hasText: 'Withheld Clip' })).toHaveCount(0)
    await expect(page.getByText('1 self-destructing video is not shown: open it in Telegram.')).toBeVisible()
    // A channel shows Telegram's sponsored message, never as a video.
    await expect(page.getByText('Lantern Maps')).toBeVisible()
    await expect(page.getByText('Sponsored', { exact: true })).toBeVisible()
})

test('the default size limits hide the small test files and say so', async ({ page }) => {
    await openClips(page)
    // 400 MB is the default minimum, as in the apps, and the chat's chip counts what it hides.
    await expect(page.getByText('No videos here of 400 MB or more.')).toBeVisible()
    await expect(page.getByRole('button', { name: /^15 videos are hidden by their size/ })).toHaveText('15 hidden')
})

test('thousands of chats stay a short list on the page', async ({ page }) => {
    await page.goto('/chats')
    await expect(page.getByText('Group 0', { exact: true })).toBeVisible()
    // 1,505 chats, but only the rows near the screen are drawn.
    expect(await page.locator('a[href^="/chat/"]').count()).toBeLessThan(80)
})

test('Back walks back through the pages', async ({ page }) => {
    await noSizeFloor(page)
    await page.goto('/')
    await page.locator('nav a[href="/chats"]').click()
    await page.getByRole('link', { name: /Test Clips/ }).first().click()
    await expect(page).toHaveURL(/\/chat\//)
    await page.goBack()
    await expect(page).toHaveURL(/\/chats$/)
    await page.goBack()
    await expect(page).toHaveURL(/\/$/)
})

test('settings survive a reload, and sign out clears them', async ({ page }) => {
    // A phone draws Settings as a list of pages: the theme is on the Appearance page, Sign out on the list.
    await page.goto('/settings/appearance')
    await page.locator('label').filter({ hasText: /^Light$/ }).click()
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'light')
    await page.reload()
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'light')
    await page.goto('/settings')
    await page.getByRole('button', { name: 'Sign out of Telegram' }).click()
    await page.getByRole('button', { name: 'Sign out', exact: true }).click()
    await expect(page).toHaveURL(/\/signin/)
    await expect(page.locator('html')).not.toHaveAttribute('data-theme', 'light')
    const kept = await page.evaluate(
        () =>
            new Promise<number>((resolve) => {
                const open = indexedDB.open('tmplayer-app')
                open.onsuccess = () => {
                    const db = open.result
                    if (!db.objectStoreNames.contains('kv')) return resolve(0)
                    const count = db.transaction('kv').objectStore('kv').count()
                    count.onsuccess = () => resolve(count.result)
                }
            }),
    )
    expect(kept).toBe(0)
})

test('only one tab uses the session; the other offers Use it here', async ({ page, context }) => {
    await page.goto('/')
    await expect(page.locator('nav.nav')).toBeVisible()
    const second = await context.newPage()
    await second.goto('/')
    await expect(second.getByText('TMPlayer is open in another tab')).toBeVisible()
    await second.getByRole('button', { name: 'Use it here' }).click()
    await expect(second.locator('nav.nav')).toBeVisible()
    await expect(second.getByText('TMPlayer is open in another tab')).toHaveCount(0)
    await expect(page.getByText('TMPlayer is open in another tab')).toBeVisible()
    // Once that tab closes, this one carries on by itself.
    await second.close()
    await expect(page.getByText('TMPlayer is open in another tab')).toHaveCount(0)
    await expect(page.locator('nav.nav')).toBeVisible()
})

test('offline shows a line, which goes once the connection is back', async ({ page, context }) => {
    await page.goto('/')
    await expect(page.locator('nav.nav')).toBeVisible()
    await context.setOffline(true)
    await expect(page.getByText('Offline. TMPlayer carries on once the connection is back.')).toBeVisible()
    await context.setOffline(false)
    await expect(page.getByText('Offline. TMPlayer carries on once the connection is back.')).toHaveCount(0)
})

test('a browser that refuses storage still works, and Settings says so', async ({ page }) => {
    await page.addInitScript(() => {
        IDBFactory.prototype.open = () => {
            throw new DOMException('refused', 'InvalidStateError')
        }
    })
    await page.goto('/settings/storage')
    await expect(page.getByText(/This browser is not keeping TMPlayer's data/)).toBeVisible()
    await page.goto('/settings/appearance')
    await page.locator('label').filter({ hasText: /^Dark$/ }).click()
    await page.locator('nav a[href="/chats"]').click()
    await expect(page.getByRole('link', { name: /Test Clips/ }).first()).toBeVisible()
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
})

test('a browser too old for the app gets a sentence and the apps', async ({ page }) => {
    await page.addInitScript(() => delete (window as { structuredClone?: unknown }).structuredClone)
    await page.goto('/')
    await expect(page.getByRole('heading', { name: 'This browser is too old for TMPlayer' })).toBeVisible()
    await expect(page.locator('a[href="https://tmplayer.org/#download"]')).toBeVisible()
})

test('an address with nothing at it', async ({ page }) => {
    await page.goto('/nowhere')
    await expect(page.getByText('There is nothing at this address.')).toBeVisible()
})

test('a chat that is gone says so', async ({ page }) => {
    await page.goto('/chat/-777')
    await expect(page.getByText("That chat isn't there any more. It was deleted, or you left it.")).toBeVisible()
})

test('?msone=1 says MSone is on only when it turns it on, not at every launch with the flag', async ({ page }) => {
    // The TV app opens every launch with ?msone=1: a toast on Home each time is noise.
    await page.goto('/?msone=1')
    await expect(page.locator('.toast').filter({ hasText: 'MSone search is on' })).toBeVisible()
    await page.goto('/?msone=1')
    await page.waitForTimeout(1500)
    await expect(page.locator('.toast').filter({ hasText: 'MSone' })).toHaveCount(0)
    await page.goto('/?msone=0')
    await expect(page.locator('.toast').filter({ hasText: 'MSone search is off' })).toBeVisible()
})
