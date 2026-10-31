// The figures in the home page's stats row and the "new in" pill above its headline (user,
// 2026-10-20, after devb.io). Everything is worked out when the site is built, so the page draws
// the final numbers in its first paint: no call to GitHub from the reader's browser, no figure
// arriving late and moving the hero, and no use of the 60 unauthenticated calls an hour.
//
// Downloads are the app files downloaded from GitHub across every release (scripts/release-data.py
// says which files count). The build asks GitHub for the current figure, since Vercel builds on
// every push to main; when that call fails or is rate limited it adds up the counts the release job
// stored in releases.json, which are at most one release old. The in-app updater fetches the same
// files, so the page says "downloads", never users or installs.
//
// Since workers/download-mirror started serving most of those files from Cloudflare's edge cache
// instead of github.com, GitHub's own count only covers the first request for each file; every
// request the mirror answers from cache is invisible to it. mirrorHits() reads the mirror's own
// running total of those and it's added in, so DOWNLOADS keeps covering every real download.
import { releases } from './releases';
import { published } from '../i18n/t';

interface Asset { name: string; download_count: number }
interface GitHubRelease { draft: boolean; prerelease: boolean; assets: Asset[] }

// The same files as APP_FILE in scripts/release-data.py.
const APP_FILE = /^tmplayer(?!.*-source\.)(?:[-_.].*)?\.(apk|msi|dmg|appimage|deb|rpm|flatpak|zip|tar\.xz|tar\.gz)$/i;

/** Android phones, Android TV, Fire TV, Windows, macOS, Linux and the web app (a beta, since 2026-10-22). */
export const PLATFORMS = 7;

async function liveDownloads(): Promise<number | null> {
  const headers: Record<string, string> = { Accept: 'application/vnd.github+json' };
  const token = process.env.GITHUB_TOKEN || process.env.GH_TOKEN;
  if (token) headers.Authorization = `Bearer ${token}`;
  try {
    const res = await fetch('https://api.github.com/repos/dracu-lah/TMPlayer/releases?per_page=100', {
      headers,
      signal: AbortSignal.timeout(8000),
    });
    if (!res.ok) return null;
    const list = (await res.json()) as GitHubRelease[];
    return list
      .filter((r) => !r.draft && !r.prerelease)
      .flatMap((r) => r.assets)
      .filter((a) => APP_FILE.test(a.name))
      .reduce((total, a) => total + a.download_count, 0);
  } catch {
    return null;
  }
}

async function mirrorHits(): Promise<number> {
  try {
    const res = await fetch('https://tmplayer-download-mirror.dracu.workers.dev/stats', {
      signal: AbortSignal.timeout(8000),
    });
    if (!res.ok) return 0;
    const body = (await res.json()) as { hits?: number };
    return body.hits ?? 0;
  } catch {
    return 0;
  }
}

const stored = releases.reduce((total, r) => total + (r.downloads ?? 0), 0);
const [live, hits] = await Promise.all([liveDownloads(), mirrorHits()]);

/** Never below what the release job last stored, in case GitHub answers with a partial list. */
export const DOWNLOADS = Math.max(live ?? 0, stored) + hits;
console.log(`[tm-stats] ${DOWNLOADS} downloads (${live === null ? 'GitHub did not answer, stored counts' : 'from GitHub'} + ${hits} from the mirror)`);
export const LANGUAGES = published.length;
export const LATEST = releases[0];

/** 1.7K, 1,7 k, 1.7万: the reader's own short form of a number. */
export function compact(n: number, tag: string): string {
  return new Intl.NumberFormat(tag, { notation: 'compact', maximumFractionDigits: 1 }).format(n);
}
