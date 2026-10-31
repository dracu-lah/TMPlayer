/* Caches TMPlayer's own GitHub release assets at Cloudflare's edge, so a slow route to GitHub's
   asset CDN doesn't make every download slow. The first request for a given file still reaches
   github.com (so its download_count stays accurate; see site/AGENTS.md "Home page pill and
   stats"), and Cloudflare serves every request after from its edge cache.

   Only ever proxies TMPlayer's own release assets: the path has to match PATH below, which pins
   the owner/repo and the file naming scripts/write-latest-json.py already uses. Anything else,
   or a method other than GET/HEAD, is refused before a subrequest is made. */

const REPO = 'dracu-lah/TMPlayer'
const PATH =
  /^\/download\/(v\d+\.\d+\.\d+(?:-beta\.\d+)?)\/(TMPlayer-[A-Za-z0-9._-]+\.(?:apk|msi|dmg|AppImage|tar\.xz))$/

export default {
  async fetch(request: Request): Promise<Response> {
    if (request.method !== 'GET' && request.method !== 'HEAD') {
      return new Response('Method not allowed', { status: 405, headers: { Allow: 'GET, HEAD' } })
    }

    const url = new URL(request.url)
    const match = PATH.exec(url.pathname)
    if (!match) {
      return new Response('Not found', { status: 404 })
    }
    const [, tag, name] = match
    const origin = `https://github.com/${REPO}/releases/download/${tag}/${name}`

    // Range and If-Range carry through so a resumed download still works once the file is cached.
    const headers = new Headers()
    const range = request.headers.get('Range')
    if (range) headers.set('Range', range)
    const ifRange = request.headers.get('If-Range')
    if (ifRange) headers.set('If-Range', ifRange)

    const upstream = await fetch(origin, {
      method: request.method,
      headers,
      redirect: 'follow',
      // Tells Cloudflare's normal edge cache to keep this response a year: release files are
      // named with their version (TMPlayer-1.28.3-universal.apk), so a given URL never changes
      // under us. cacheEverything also applies to a cross-origin subrequest like this one.
      cf: { cacheEverything: true, cacheTtl: 31536000 },
    })

    if (upstream.status >= 400) {
      return new Response('Upstream error', { status: upstream.status === 404 ? 404 : 502 })
    }

    const out = new Headers(upstream.headers)
    out.set('Cache-Control', 'public, max-age=31536000, immutable')
    out.set('Access-Control-Allow-Origin', '*')
    out.delete('Set-Cookie')

    return new Response(upstream.body, { status: upstream.status, headers: out })
  },
}
