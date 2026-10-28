// End to end tests: the real page and worker over a fake Telegram (e2e/vite.e2e.ts), in Chromium
// and WebKit. `pnpm e2e`; CHROMIUM_PATH runs a system Chromium in place of Playwright's own.
import { defineConfig, devices } from '@playwright/test'

// E2E_PORT runs a second checkout's tests beside another's (8097 by default).
const PORT = Number(process.env.E2E_PORT) || 8097

/** A Chromium run as a phone (a touch screen, which is what gives the phone layout) or a desktop (layout set to Desktop, as Settings would). */
function form(layout: 'phone' | 'desktop', width: number, height: number) {
    return {
        ...devices['Desktop Chrome'],
        viewport: { width, height },
        hasTouch: layout === 'phone',
        isMobile: layout === 'phone',
        launchOptions: { executablePath: process.env.CHROMIUM_PATH || undefined },
        storageState: {
            cookies: [],
            origins: layout === 'phone' ? [] : [{ origin: `http://127.0.0.1:${PORT}`, localStorage: [{ name: 'tmplayer-layout', value: layout }] }],
        },
    }
}
// The flows that must hold in every form (session 22).
const FORM_SPECS = /(library|extras|pages|detail|a11y|titlelogo)\.spec\.ts/

export default defineConfig({
    testDir: 'e2e',
    timeout: 30_000,
    expect: { timeout: 10_000 },
    // Two at a time: each runs a Telegram worker and a player.
    workers: 2,
    retries: process.env.CI ? 1 : 0,
    reporter: process.env.CI ? 'github' : 'list',
    use: { baseURL: `http://127.0.0.1:${PORT}`, trace: 'retain-on-failure' },
    projects: [
        {
            name: 'chromium',
            use: { ...devices['Desktop Chrome'], launchOptions: { executablePath: process.env.CHROMIUM_PATH || undefined } },
        },
        // Safari on a Mac (a desktop), and an iPhone: the whole suite in the first, the flows that must hold on a phone in the second.
        // WebKit's page.route misses what the page's service worker answers, so a mocked TMDB would never be seen: the worker is blocked there.
        { name: 'webkit', use: { ...devices['Desktop Safari'], serviceWorkers: 'block' } },
        { name: 'webkit-iphone', testMatch: /(player|autoplay|posters|app|library|pages|extras)\.spec\.ts/, use: { ...devices['iPhone 13'], serviceWorkers: 'block' } },
        { name: 'phone-390', testMatch: FORM_SPECS, use: form('phone', 390, 844) },
        { name: 'phone-412', testMatch: FORM_SPECS, use: form('phone', 412, 915) },
        { name: 'desktop-1280', testMatch: FORM_SPECS, use: form('desktop', 1280, 800) },
    ],
    webServer: {
        command: 'pnpm exec vite --config e2e/vite.e2e.ts',
        url: `http://127.0.0.1:${PORT}/__e2e/sizes`,
        reuseExistingServer: !process.env.CI,
    },
})
