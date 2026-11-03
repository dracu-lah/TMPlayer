# DLNA fixtures (HX40)

Real answers from two software UPnP MediaRenderers, captured with `scripts/dlna/capture.py`
(`--play` on, `--notify` on) on 2026-11-03. Names, UUIDs, MACs and addresses are scrubbed
(`192.0.2.x`, `uuid:00000000-0000-0000-0000-000000000001`), so `Content-Length` headers no longer
match the bodies: read bodies, never trust the length.

| Folder | Renderer | Stack | What it adds |
| --- | --- | --- | --- |
| `gmrender/` | gmrender-resurrect 0.1 (Ubuntu `gmediarender`) | libupnp 17.1.9 + GStreamer | A 500 UPnPError for a bad state (501), HTTP 200 with no error for a bad InstanceID or seek target, `H:MM:SS` times, a huge Sink list of `*` profiles |
| `rygel/` | Rygel 0.42.5 Playbin | GUPnP 1.6.6 + GStreamer | Real fault codes (401, 711, 718), `H:MM:SS.mmm` times, `X_DLNA_*` transport actions, `DLNA.ORG_PN` Sink entries, lower case `Location` header |

Files per renderer: `ssdp-replies.txt` (M-SEARCH answers, `----` between), `ssdp-notify.txt`
(alive and byebye), `description.http`, `scpd-*.http`, and `soap/<Action>.request.xml` with
`soap/<Action>.response.http` (status line, headers, blank line, body). A `Fault-` prefix marks a
call made to fail, a `Playing-`, `Paused-`, `Stopped-` prefix the state it was asked in.

Not here, and why:
- **VLC 3.0.24** (installed) is a control point only, not a renderer; VLC 4 adds renderer
  discovery, still no MediaRenderer role.
- **Kodi** has a UPnP renderer, but the Ubuntu 24.04 build in a container never opened it (the
  setting was on, no SSDP socket; three attempts, 2026-11-03). Retry on a desktop with a real
  Kodi if wanted.
- **Samsung, LG, Sony TVs:** nobody on the project owns one. Phase 4 needs someone who does to run
  `scripts/dlna/capture.py` and send the folder; their quirks are not guessed at in the tests.

Reproduce: `scripts/dlna/Containerfile.gmrender` and `scripts/dlna/Containerfile.rygel`.
