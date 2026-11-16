# TV playback routes: LG webOS (and Samsung Tizen)

Started 2026-11-06 from a tester report: LG QNED919PA, webOS 6.5.3-47 (SW 03.53.45), 4K HEVC MP4
fails with "cannot handle HEVC", while the TV's IPTV app plays 4K. Plan for every route that gets
their files playing, best first. Research sources at the end.

## Progress / handoff

| Step | State |
| --- | --- |
| R1 Media Source HEVC names (ladder, stand-ins, trust on webOS/Tizen) | done, local commits `b2590a7`, `a2e8147`; not pushed; not checked on an LG TV |
| R2 DLNA cast from the phone app to the LG TV | exists (`DlnaCastService.kt`); ask the tester to try it |
| R3 Service Worker `/stream/` on webOS (hosted app) | built without a TV (commit below): stream-only `/tv/sw.js` at scope `/` on TV agents, TV player tried when the container plays and every codec is on the TV list (`player/tvnative.ts`), two misses (worker never asked) turn it off on that TV; falls back to the repack |
| R4 JS service with a 127.0.0.1 server (`.ipk` only) | service, wrapper and packaging built and machine-tested (local commit `a82f7a2`, not pushed); page side built 2026-11-06 (`src/stream/tvservice.ts`, wired in `main.tsx`, `Player.tsx`, `engines.ts`; `tv/test/tvservice.test.ts`, 7 tests against `bridge.js`); local commits not pushed; not run on a TV |

R4 done so far (2026-11-06): `webapp/tv/webos/service/org.tmplayer.tv.service/` (`bridge.js` HTTP core
and lease, `service.js` Luna `start({origin})` answering `{port, token}` and holding keepAlive,
`services.json`, `package.json`), wrapper `tv/shell/index.html` (calls `start`, opens
`ADDRESS#tvsvc=<port>.<token>`, 3 s timeout, plain address on any failure and on Samsung),
`package.sh` packs the service into the `.ipk` (checked: `usr/palm/services/org.tmplayer.tv.service/`),
`pnpm test:tv` (`tv/test/bridge.test.cjs`, 20 tests: fake page + fake TV player, wrapper in a vm).
Protocol (page, all with `Origin` = page origin and `token`): `GET /ping`; `GET /poll?session=` held 20 s
returns `{requests:[{id,key,start,end}], cancel:[id], replaced?}`; `POST /reply?id=` body = the bytes
(Content-Length must equal end-start+1), or `&error=gone|fail`; 410 = request already gone, ignore.
TV: `<video src="http://127.0.0.1:<port>/stream/<key>?token=&size=&mime=">`.
Page keeps one `session` id per page load (a new one makes the bridge ask again for waiting reads).

Page side done (2026-11-06): `tvservice.ts` reads `#tvsvc=port.token`, strips it, pings (3 s), long-polls and
answers from `streams` (a closed request's bytes are dropped; 5 failed polls in a row turn the bridge off and the
page falls back to R3/R1). `Player.tsx` gives `<video>` `tvServiceUrl(...)` before the `/stream/` address;
`engines.ts` counts `tvServiceUp()` as controlled; the miss counter accepts `bridgeAsked`. Not done: `TgFile.read`
has no abort, so a cancelled read runs to its end (bytes dropped).
Next: check on a TV (Chromium 79 loopback http from an https page, webOS killing the service after 5 s without
the page, `ares-inspect --service`), then push R1 to R4 (user runs it; release cadence guard first).

2026-11-06: details line now names the route (`fc4cd8c`). `.ipk` rebuilt for the live `https://web.tmplayer.org/` (user chose no test address)
(`webapp/tv/out/TMPlayer-tv.ipk`); user pushes, runs webapp.yml with deploy=production, sends the `.ipk`. Gotcha: never deploy a lone build:tv dist (base /tv/, CI moves it to dist/tv beside the main build).
Next: read the tester's details line. Then Sentry for the web app (opt-in, failed playbacks too, TV first), not started.

Previous next-step note: build R4 (below) from this file, Sonnet medium. Then push R1 to R4 together, the tester retries and sends the player's details line (it now says `HEVC as ...` or
`HEVC sent to the TV decoder unasked`). Only if it still fails: R3 spike, then R4.

Gotchas: the TV build never registers a Service Worker (`webapp/src/main.tsx:23`), so `native` is
false on every TV and all files go through Media Source. Chromium 79 has no WebCodecs, so movi and
hevc.js never run on webOS 6: Media Source is the only engine there today.

## What the best webOS apps do

- **bili-webos** (MIT, 167 stars, webOS 5+): 4K, HDR, Dolby Vision HEVC and E-AC-3 through Shaka
  on Media Source. Its TV run logs show `hev1.1.6.L153.90` at 3996x2160 playing. Its JS service
  (Node 8 compatible ES5, `compat.js` shims for Node 0.12 on webOS 4) proxies the CDN on
  127.0.0.1 for headers and CORS, not for decoding. So Media Source is a proven 4K HEVC path.
- **Jellyfin web** (`browserDeviceProfile.js` `canPlayHevc`): takes HEVC on `web0s` and `tizen`
  without asking the browser; direct play gives `<video>` an http URL so the TV's own player
  decodes (MKV, HEVC, AC-3 and more).
- **Telesera** (Telegram client for webOS): Rust engine on 127.0.0.1:3190 with a random token, the
  TV's `<video>` plays from it. Needs a rooted TV (native binary), so not our route as is.
- **webos-tv-app-skill**: JS service with two channels, Luna for JSON and a 127.0.0.1 HTTP proxy
  for media; keepAlive through `activityManager`, `uncaughtException` guard, EADDRINUSE next port.
- **smart-tv-telegram**: says many LG TVs have a broken UPnP renderer, so R2 may not work on all.

## R3 Service Worker spike (about 1 hour on a TV)

1. TV build registers a stream-only worker (`/tv/sw.js` from `public/sw.js`, VERSION stays
   `dev`, so it answers only `/stream/`) at scope `/`, with `Service-Worker-Allowed: /` for
   `/tv/sw.js` in `public/_headers`. Only on a TV agent, so `?tv=1` on a desktop keeps the site's
   own worker.
2. On the TV: does `navigator.serviceWorker.controller` appear, and does `<video src="/stream/...">`
   reach the worker (webOS may hand the URL to its media server, which would fetch it itself)?
3. If yes: on a TV agent, `native` also when `canPlayType` says '' for the codec string but the
   container is MP4/MOV/MKV with a codec on LG's list; `onVideoError` already falls back to remux.

## R4 JS service (only if R1 and R3 fall short)

- `webapp/tv/webos/service/org.tmplayer.tv.service/` (services.json, package.json, service.js,
  ES5, Node 8.12 on webOS 5 and 6, no dependencies: long-poll HTTP instead of `ws`).
- The wrapper's local `index.html` (file://, has `webOS.service.request`) starts it and passes
  `port` and a random `token` to the hosted page in the URL fragment.
- The page (https) talks to `http://127.0.0.1:<port>` (Chromium 53+ exempts loopback from mixed
  content; check on 79). The TV's `<video src="http://127.0.0.1:<port>/stream/<key>?token=">`
  asks the service for a range, the service asks the page, the page reads it through `TgFile`
  (the same answer `serveStreams` gives the Service Worker).
- Edge cases: idle 5 s timer (activity kept while the page is connected, completed 60 s after it
  goes), port taken (next port), page reload (reconnect), several ranges at once (bounded), a
  seek (closed request cancels its read), no bytes held beyond the chunk in flight, idle CPU 0
  when nothing plays (measure), token and Origin checked, 127.0.0.1 only.
- Browser-only users (no `.ipk`) keep R1. Samsung has no equivalent for web apps: R1 only.

## Sources

- https://webostv.developer.lge.com/develop/specifications/video-audio-60
- https://webostv.developer.lge.com/develop/guides/js-service-faq
- https://github.com/asdf17128/bili-webos (service/com.biliwebos.app.service, app/src/player/mediaSelection.js)
- https://github.com/jellyfin/jellyfin-web (src/scripts/browserDeviceProfile.js)
- https://github.com/ECuteri/telesera (README, docs/playback.md)
- https://github.com/asdf17128/webos-tv-app-skill (reference/service-and-proxy.md)
- https://github.com/andrew-ld/smart-tv-telegram
- https://shaka-project.github.io/shaka-player/docs/api/lib_device_webos.js.html
