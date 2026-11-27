// metaPlain (core/.../online/MetaApis.kt): a provider's description as plain text (line breaks kept,
// tags and entities gone). In its own file because the poster worker (tmdb/anilist.ts) and the page
// (animeGuide.ts) both read it. Both live in tmdb/ so the Cloudflare deploy bundles the poster proxy
// without webapp/src.
export function plain(html: string): string {
    return html
        .replace(/<br\s*\/?>/gi, '\n')
        .replace(/<[^>]*>/g, '')
        .replaceAll('&amp;', '&')
        .replaceAll('&quot;', '"')
        .replaceAll('&#39;', "'")
        .replaceAll('&lt;', '<')
        .replaceAll('&gt;', '>')
        .replaceAll('&nbsp;', ' ')
        .replace(/[ \t]+/g, ' ')
        .split('\n')
        .map((l) => l.trim())
        .join('\n')
        .replace(/\n{3,}/g, '\n\n')
        .trim()
}
