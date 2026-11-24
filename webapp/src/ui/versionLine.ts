// The version line (WP-104): "TMPlayer web (a1b2c3d, 2026-11-24)" and the update state beside it,
// in the account menu, at the top of Settings and in About, and the one line a press copies for
// support. Pure, so `node --test` reads it; the signals it is fed from live in install.ts and the
// component in VersionFooter.tsx. The apps' VersionLine (core) does the same on Android, iOS and
// the desktop. The copied text takes the three facts and nothing else (no account, name, chat or
// path), so none can leak into it.
import { browserLine, cleanLine, cut } from './feedback.ts'

export const VERSION_MAX = 40
export const BUILD_MAX = 20
export const DEVICE_MAX = 80

/** The version as built ("web" here), its build ("a1b2c3d, 2026-11-24") and the browser and system. */
export interface VersionFacts {
    version: string
    build: string
    device: string
}

/**
 * What stands beside the version: nothing yet (never a false "Up to date"), a look in progress, a
 * completed look that found nothing, a new version waiting for Reload, or no way to know (the TV
 * build, a browser without a service worker, a worker that failed to register).
 */
export type VersionState = 'none' | 'checking' | 'upToDate' | 'available'

const part = (text: string, max: number): string => cut(cleanLine(typeof text === 'string' ? text : ''), max).trim()

/** The facts as the line shows them: one line each, cut. */
export function shown(f: VersionFacts): VersionFacts {
    return { version: part(f.version, VERSION_MAX), build: part(f.build, BUILD_MAX), device: part(f.device, DEVICE_MAX) }
}

/** "TMPlayer web (a1b2c3d, 2026-11-24) Safari 17 on iPhone", the support line. Each part is cut and holds no line break. */
export function copyText(f: VersionFacts): string {
    return `TMPlayer ${part(f.version, VERSION_MAX)} (${part(f.build, BUILD_MAX)}) ${part(f.device, DEVICE_MAX)}`.trimEnd()
}

/** The web's facts: version "web", the build and its day, and the browser as the feedback report names it. */
export function webVersionFacts(build: string, built: string, ua: string, installed: boolean): VersionFacts {
    return { version: 'web', build: `${build}, ${built}`, device: browserLine(ua, installed) }
}

/**
 * The state from the service worker's own news (install.ts). [updateReady] wins (a waiting worker is
 * a new version), then a look in progress, then a completed look; a page that never had a worker
 * to ask says nothing.
 */
export function versionState(input: { updateReady: boolean; checking: boolean; lastChecked: number | null }): VersionState {
    if (input.updateReady) return 'available'
    if (input.checking) return 'checking'
    if (input.lastChecked !== null && input.lastChecked > 0) return 'upToDate'
    return 'none'
}

/**
 * The compact release number the menus show: "v1.28.7" from "1.28.7", "v1.28.7-beta.2" or a tag as
 * the build read it (vite.config.ts appRelease). A build suffix is dropped (the full detail stays
 * in Settings, About); anything that is not a number of the form X.Y or X.Y.Z gives '' and the
 * caller shows "web" in its place, never a made up number.
 */
export function compactVersion(raw: unknown): string {
    if (typeof raw !== 'string') return ''
    const m = /^v?(\d{1,4}\.\d{1,4}(?:\.\d{1,4})?)(?![\d.])/.exec(raw.trim())
    return m ? `v${m[1]}` : ''
}

/** True when a drag down of [dy] px over [ms] ms (the sheet's handle) is a dismissal: far enough, or a quick flick. */
export function dragCloses(dy: number, ms: number): boolean {
    if (!Number.isFinite(dy) || !Number.isFinite(ms) || dy <= 0) return false
    return dy >= 96 || (dy >= 32 && ms > 0 && dy / ms >= 0.6)
}
