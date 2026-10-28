// The SoftwareApplication JSON-LD the download and Android TV pages carry, so a search engine or
// an assistant reading only that page still learns what TMPlayer is, the newest version
// and where to get it. The text is the home page's own, so its ld.* catalog
// strings already exist in every language.
import { releases } from './releases';

export const LATEST_VERSION = releases[0]?.version;

export function appLd(): Record<string, unknown> {
  return {
    '@context': 'https://schema.org',
    '@type': 'SoftwareApplication',
    name: 'TMPlayer',
    description:
      'A free, open source Telegram video player for Android TV, Android phones, Windows, macOS and Linux that streams the videos in your own chats.',
    url: 'https://tmplayer.org/',
    image: 'https://tmplayer.org/og.png?v=4',
    applicationCategory: 'MultimediaApplication',
    applicationSubCategory: 'Video player',
    operatingSystem:
      'Android 6.0 and above (phones, Android TV, Google TV and Fire TV), Windows 10 and 11 (64-bit), Linux (x86_64), macOS 26 and newer on Apple Silicon, a web browser (web.tmplayer.org, a beta)',
    ...(LATEST_VERSION ? { softwareVersion: LATEST_VERSION } : {}),
    downloadUrl: 'https://tmplayer.org/download/',
    installUrl: 'https://tmplayer.org/install/',
    license: 'https://www.gnu.org/licenses/gpl-3.0.html',
    isAccessibleForFree: true,
    offers: { '@type': 'Offer', price: '0', priceCurrency: 'USD' },
    sameAs: ['https://github.com/dracu-lah/TMPlayer'],
  };
}
