// web.tmplayer.org on a TV: LG's and Samsung's browsers (Chromium 68 to 79) cannot run the site's
// build, so a page load from one is answered with the TV build's page (dist-tv, deployed under
// /tv/ by the Web player workflow). Its routes are the site's own, so only the page changes. The
// TV wrappers (webapp/tv) open the site's root. ?tv=1 picks the TV build on any browser and ?tv=0
// goes back, both kept in a cookie for a year.
const TV_BROWSER = /Web0S|webOS|Tizen|SMART-TV|SmartTV/i
const COOKIE = 'tmplayer-tv'

interface Context {
    request: Request
    env: { ASSETS: { fetch(request: Request): Promise<Response> } }
    next(): Promise<Response>
}

export const onRequest = async ({ request, env, next }: Context): Promise<Response> => {
    const url = new URL(request.url)
    const page =
        request.method === 'GET' &&
        (request.headers.get('Accept') ?? '').includes('text/html') &&
        !url.pathname.startsWith('/tmdb/') &&
        !url.pathname.startsWith('/tv/')
    if (!page) return next()
    const asked = url.searchParams.get('tv')
    const kept = new RegExp(`(?:^|;\\s*)${COOKIE}=([01])`).exec(request.headers.get('Cookie') ?? '')?.[1]
    const choice = asked === '1' || asked === '0' ? asked : kept
    const tv = choice ? choice === '1' : TV_BROWSER.test(request.headers.get('User-Agent') ?? '')
    const answer = tv ? await env.ASSETS.fetch(new Request(new URL('/tv/', url), request)) : await next()
    const out = new Response(answer.body, answer)
    out.headers.append('Vary', 'User-Agent, Cookie')
    if (asked === '1' || asked === '0') out.headers.append('Set-Cookie', `${COOKIE}=${asked}; Path=/; Max-Age=31536000; Secure; SameSite=Lax`)
    return out
}
