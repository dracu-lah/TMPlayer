// End to end tests: the real page and worker over a fake Telegram (e2e/vite.e2e.ts), in Chromium
// and WebKit. `pnpm e2e`; CHROMIUM_PATH runs a system Chromium in place of Playwright's own.
import { defineConfig, devices } from '@playwright/test'

// E2E_PORT runs a second checkout's tests beside another's (8097 by default).
const PORT = Number(process.env.E2E_PORT) || 8097

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
        { name: 'webkit', use: { ...devices['iPhone 13'] } },
    ],
    webServer: {
        command: 'pnpm exec vite --config e2e/vite.e2e.ts',
        url: `http://127.0.0.1:${PORT}/__e2e/sizes`,
        reuseExistingServer: !process.env.CI,
    },
})
