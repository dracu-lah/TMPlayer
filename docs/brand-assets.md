# Brand assets and service logos (HX93)

Where TMPlayer names an outside service on a screen, it shows a small tile with the service's logo,
then the name (`ServiceBadge` in `ui/src/shared/.../components/ServiceBadge.kt`, and the web app's
`webapp/src/ui/ServiceBadge.tsx`). The tile uses the service's own mark only where the service's
press or brand page offers one to download and its rules let an app like this show it. Everywhere
else the tile is the service's initial in its `NameColour`. A mark is never copied from a third
party's site, redrawn from memory or traced from a screenshot.

Assets are bundled (vector, no network fetch at run time), unmodified, and never recoloured,
stretched, turned or mirrored. They were fetched with `curl` into an empty directory, inspected as
untrusted input (no `script`, `foreignObject`, `href` or external reference: only the two XML
namespaces) and only then copied in.

## Real logos

| Service | Asset | Source | Rules | Fetched |
| --- | --- | --- | --- | --- |
| TMDB | `webapp/src/ui/brand/tmdb-blue-square-1.svg` ("blue square 1"; the Kotlin path in `Service.kt` is the same path and gradient). The wide "primary short" mark in `TmdbLogo.kt` and `webapp/src/ui/TmdbMark.tsx` was added earlier from the same page. | https://www.themoviedb.org/assets/v4/logos/v2/blue_square_1-5bdc75aaebeb75dc7ae79426ddd9be3b2be1e342510f8202baf6bffa71d7f5c4.svg | https://www.themoviedb.org/about/logos-attribution and the API terms: attribute TMDB, logo unaltered, less prominent than the app's own mark, in an About or Credits area with "This product uses the TMDB API but is not endorsed or certified by TMDB." | 2026-11-20 |

TMDB's tile is 24 to 32 dp, so it stays well under TMPlayer's own mark. About, Credits and the
posters section keep the notice line beside the wide mark.

## Initial badges (no mark used) and why

| Service | Why no logo | Where checked | Date |
| --- | --- | --- | --- |
| Telegram | The API terms, 2.4: "You must not use the official Telegram logo for your app." TMPlayer is also "unofficial" and never uses the paper plane (`docs/agents/telegram-terms.md`). | https://core.telegram.org/api/terms | 2026-11-20 |
| YouTube | Its brand site (https://brand.youtube/) serves the icon only through a script driven download after accepting its guidelines, which a fetch with `curl` cannot reach. Terms unclear for an in-app tile, so the initial is used until the icon is downloaded by hand from that page and its rules are read. | https://brand.youtube/ | 2026-11-20 |
| Sentry | https://sentry.io/branding/ builds its SVG in the page's script ("Copy SVG"), the brand guide it points to could not be fetched, so the rules are unknown. | https://sentry.io/branding/ | 2026-11-20 |
| OpenSubtitles | Its site answers 403 to `curl`; no brand kit was found. | https://www.opensubtitles.com | 2026-11-20 |
| SubDL | Its site answers 403 to `curl`; no brand kit was found. | https://subdl.com | 2026-11-20 |
| TVmaze | https://www.tvmaze.com/api asks for attribution under CC BY-SA, offers no logo kit. | https://www.tvmaze.com/api | 2026-11-20 |
| AniList | No brand kit on https://anilist.co or https://docs.anilist.co. The AniList square in `ScoreLogos.kt` came from Wikimedia Commons, not from AniList, so the badge does not use it. | https://anilist.co/terms | 2026-11-20 |
| MSone, SubMaker | A volunteer site and the viewer's own self-hosted addon: no brand page. | n/a | 2026-11-20 |
| TheIntroDB, AniSkip, Anime-Skip, arm.haglund.dev | Skip time and id mapping services: no brand kit found. | n/a | 2026-11-20 |
| OMDb, Wikidata | OMDb offers no kit; Wikimedia's marks are trademarks under their own policy, not the CC0 data licence. | https://www.omdbapi.com, https://foundation.wikimedia.org/wiki/Policy:Trademark_policy | 2026-11-20 |

To add a logo later: download it from the service's own brand page into an empty directory, inspect
it, keep the file unmodified under `webapp/src/ui/brand/`, add its path to `Service.kt` and
`webapp/src/ui/services.ts`, and add a row to the first table with the rules' URL and the date.
