# Known end to end failures

None in Chromium (2026-11-23).

## What the HX88 B3 list (2026-11-20) turned out to be

That list compared the branch with `322da77`, which already held both causes, so every failure
looked older than it was. Against origin/main (`e674f78`), which passes, they came from two commits:

| Failures | Introduced by | Fixed by |
| --- | --- | --- |
| a11y Home, Chats, Search: `.nav-label` contrast 4.39 | `a1fa0ae` (axe 4.14 reads the `color-mix` highlight that 4.10 skipped) | the highlight at 12% (styles.css) |
| canvas and oldformats Dvd Clip, oldformats TrueHD sound | `a1fa0ae` (mediabunny 1.66 opens a VOB as MP3 and names TrueHD) | engines.ts: a known video header under an MP3 or ADTS answer goes to libav.js, and named but undecodable sound too |
| chats (3), choose-chats (2), home-settings line 30, tv and scale picker walks | `fe87972` (HX86: the stored mode `all` is the All chats switch on, ticks disabled) | the `picked` case in vite.e2e.ts for the tests that tick rows; the TV starts in the search while the switch is off; Left from a star is its row |
| setup line 14 | `fe87972` (a fresh visit starts with Saved Messages ticked) | the spec takes the tick off first |

posters line 105 clicked a Library link that `1e330ba` (WB20) folded into Home and library; it
opens that page now.

The rename spec's TV walk pressed Down before Right: it passed only when the chat's row below
Play had not loaded yet. It now walks Right along Play's line. Its second open was flaky too, a
code race: Escape's close event comes a task late, after Enter had opened the sheet again, and shut
it (RenameSheet.tsx now ignores a close event while the dialog is open).

feedback line 228 read the stored draft as soon as the dialog lost `[open]`, before its close
event (which keeps the words) ran: spec timing, it polls now.

WebKit does not start on this machine (`browserType.launch` fails), so the `webkit` and
`webkit-iphone` projects are left to the nightly run.
