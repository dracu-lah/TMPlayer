// The releases the changelog, the release pages, the feed and the sitemap are made from.
//
// src/data/releases.json is written by scripts/release-data.py (the release job runs it). Its
// notes are the markdown a person wrote in the tag message; inline() and markdown() below are a
// port of how the site has always rendered them, kept to code, links, bold, bullet lists
// and small headings, so the HTML stays what the pages always had. No markdown library: one
// would render the same notes differently.
import data from '../data/releases.json';

export const REPO = 'dracu-lah/TMPlayer';
export const SITE = 'https://tmplayer.org';
export const PAGE_URL = `${SITE}/changelog/`;
export const FEED_URL = `${SITE}/changelog/feed.xml`;
export const FEED_ITEMS = 20;
export const DESCRIPTION =
  'What changed in every TMPlayer release, newest first: Android TV, phones, ' +
  'Windows, macOS and Linux, all from one release.';

/** The first release with a Mac build; the ones before it name only the systems they shipped for. */
const FIRST_MAC = [1, 26, 0];

interface Raw { version: string; published: string; url: string; notes: string; downloads?: number }
export interface Release extends Raw {
  /** the publish time as a Date (UTC) */
  date: Date;
  /** 2026-10-14 */
  day: string;
  /** 2026-10-14T20:45:18Z */
  iso: string;
}

const versionKey = (v: string) => (v.match(/\d+/g) ?? []).map(Number);

function compareKeys(a: number[], b: number[]): number {
  for (let i = 0; i < Math.max(a.length, b.length); i++) {
    if (a[i] === undefined) return -1;
    if (b[i] === undefined) return 1;
    if (a[i] !== b[i]) return a[i] - b[i];
  }
  return 0;
}

/** Newest first by version, then by date, so a patch to an old line sits with its line. */
export const releases: Release[] = (data as Raw[])
  .map((r) => {
    const date = new Date(r.published);
    const iso = date.toISOString().replace(/\.\d{3}Z$/, 'Z');
    return { ...r, date, iso, day: iso.slice(0, 10) };
  })
  .sort((a, b) => compareKeys(versionKey(b.version), versionKey(a.version)) || b.date.getTime() - a.date.getTime());

// A release page's URL has no trailing slash: Vercel takes the dot in the version for a file
// extension and answers /changelog/1.26.0/ with a 308 to /changelog/1.26.0, so the canonical,
// the sitemap and every link name the address that answers 200.
export function releaseUrl(version: string): string {
  return `${PAGE_URL}${version}`;
}

export function hasMac(version: string): boolean {
  const v = version.split('.').slice(0, 3).map(Number);
  return compareKeys(v, FIRST_MAC) >= 0;
}

const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August',
  'September', 'October', 'November', 'December'];

/** 14 October 2026 */
export function humanDate(d: Date): string {
  return `${d.getUTCDate()} ${MONTHS[d.getUTCMonth()]} ${d.getUTCFullYear()}`;
}

/** Wed, 14 Oct 2026 20:45:18 GMT, as email.utils.format_datetime(usegmt=True) writes it. */
export function rfc822(d: Date): string {
  return d.toUTCString();
}

// ---------- A little markdown, safely ----------

const INLINE_CODE = /`([^`]+)`/g;
const LINK = /\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g;
const BOLD = /\*\*(.+?)\*\*/g;

/** Python's html.escape(s, quote=False). */
const esc = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
/** Python's html.escape(s), quotes included. */
export const escAttr = (s: string) => esc(s).replace(/"/g, '&quot;').replace(/'/g, '&#x27;');

/** Escapes everything first, then turns back on code, links and bold, and nothing else. */
export function inline(text: string): string {
  const codes: string[] = [];
  text = text.replace(INLINE_CODE, (_m, code: string) => {
    codes.push(`<code>${esc(code)}</code>`);
    return `\x00${codes.length - 1}\x00`;
  });
  const links: [string, string][] = [];
  text = text.replace(LINK, (_m, label: string, url: string) => {
    links.push([label, url]);
    return `\x01${links.length - 1}\x01`;
  });
  text = esc(text).replace(BOLD, '<strong>$1</strong>');
  text = text.replace(/\x01(\d+)\x01/g, (_m, i: string) => {
    const [label, url] = links[Number(i)];
    return `<a href="${escAttr(url)}" rel="noopener">${esc(label).replace(BOLD, '<strong>$1</strong>')}</a>`;
  });
  return text.replace(/\x00(\d+)\x00/g, (_m, i: string) => codes[Number(i)]);
}

const BULLET = /^\s*[-*] /;

/** Paragraphs, bullet lists and small headings. Tables are skipped: they are file lists. */
export function markdown(text: string, indent: string): string {
  const out: string[] = [];
  for (const block of text.trim().split(/\n\s*\n/)) {
    const lines = block.split('\n').filter((line) => line.trim());
    if (!lines.length || lines.every((line) => line.trimStart().startsWith('|'))) continue;
    if (lines.every((line) => BULLET.test(line) || line.startsWith('  ')) && BULLET.test(lines[0])) {
      const items: string[] = [];
      for (const line of lines) {
        const m = /^\s*[-*] (.*)/.exec(line);
        if (m) items.push(m[1].trim());
        else items[items.length - 1] += ' ' + line.trim();
      }
      out.push(`${indent}<ul>`);
      for (const item of items) out.push(`${indent}  <li>${inline(item)}</li>`);
      out.push(`${indent}</ul>`);
    } else if (/^#{1,6} /.test(lines[0]) && lines.length === 1) {
      out.push(`${indent}<h4>${inline(lines[0].replace(/^#+/, '').trim())}</h4>`);
    } else {
      out.push(`${indent}<p>${inline(lines.map((line) => line.trim()).join(' '))}</p>`);
    }
  }
  return out.join('\n');
}

/** The notes as one line of plain text, for a feed reader's summary. */
export function plain(text: string): string {
  text = text.replace(INLINE_CODE, '$1').replace(LINK, '$1').replace(BOLD, '$1');
  text = text.replace(/^\s*[-*] /gm, '');
  return text.split(/\s+/).filter(Boolean).join(' ');
}

/** The notes as HTML, or the line that says there are none. */
export function notesHtml(r: Release, indent: string): string {
  return r.notes ? markdown(r.notes, indent) : (
    `${indent}<p class="muted">No notes were written for this release. ` +
    `<a href="${r.url}" rel="noopener">The release on GitHub</a> lists its files.</p>`);
}

/** The notes cut at a word to a search snippet's length, for the description tag. */
export function summary(r: Release, limit = 155): string {
  // One sentence per note line, so a snippet reads "A. B." and not "A B" run together.
  const text = r.notes.split('\n').map((line) => plain(line).replace(/[,;:.\s]+$/, '')).filter(Boolean)
    .map((line, i, all) => (i < all.length - 1 ? `${line}.` : line)).join(' ');
  if (!text) return `TMPlayer ${r.version}, released ${humanDate(r.date)}, a release of the free Telegram video player. No notes were written for it; its files are on GitHub.`;
  const chars = Array.from(text);
  if (chars.length <= limit) return text;
  const cut = chars.slice(0, limit).join('');
  const i = cut.lastIndexOf(' ');
  return (i >= 0 ? cut.slice(0, i) : cut).replace(/[,;:.]+$/, '') + '...';
}
