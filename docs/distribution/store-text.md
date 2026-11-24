# Store text: one source for every listing

Written 2026-11-16 (FQ15 follow-up, plan "store text"). **Draft, nothing submitted.** The user
pastes or uploads these; Claude never submits to a store. It covers what the app does since the
UX pass: one question setup, Save space and Keep video, the Downloads tab, offline Home, series
and anime pages, skip sources, format badges, keep my place on all devices through your own Saved
Messages, and supporting through Buy Me a Coffee or UPI.

The rules every line follows (docs/agents/telegram-terms.md, AGENTS.md "Writing"): no "Telegram"
in a title, "unofficial, not affiliated with Telegram" in every description, "video" never "film"
or "movie", no real titles, no logos of other brands (badges are plain words), no em or en dashes.
Dolby, DTS and HDR names appear only as words describing what a file declares.

| Store | Where the text lives | Limits |
| --- | --- | --- |
| F-Droid style clients and Obtainium pages | `fastlane/metadata/android/en-US/` (`title.txt`, `short_description.txt`, `full_description.txt`, `changelogs/<versionCode>.txt`) | title 30, short 80, full 4,000, changelog 500 characters |
| Google Play (set aside, see [play.md](play.md)) | the same fastlane files, pasted in the console | title 30, short 80, full 4,000, what's new 500 |
| Amazon Appstore | [amazon.md](amazon.md) | short 2,000 bytes (Fire TV shows the first 200 characters), long 4,000, 3 to 5 bullets |
| Microsoft Store (not planned yet) | this file | description 10,000, short description 1,000, release notes 1,500, up to 20 feature lines of 200 characters (checked from memory, confirm in Partner Center) |
| winget | [winget/](winget/) | ShortDescription 256, Description 10,000, ReleaseNotes 10,000 |
| AlternativeTo, Uptodown | [telegram-apps.md](telegram-apps.md) | none that matter |

## Short description (80 characters, Play, F-Droid)

`Play the videos in your Telegram chats on Android TV, phone and PC. Unofficial.` (79)

"Telegram" is fine here: it is not the title, and the sentence says it is unofficial.

## Full description

The F-Droid file `fastlane/metadata/android/en-US/full_description.txt` is the master (3,992
characters of 4,000). Play takes it as it is. Microsoft Store takes it too: it asks for a line per
feature, so use the bullets below.

## What's new (500 characters, one per release, the release job copies the tag's list)

Draft for the release that carries this work (put it in `changelogs/<versionCode>.txt` when the
version is known; RELEASING.md says how). 478 characters:

```
One question after sign in: which chats hold your videos.
Save space or Keep video while streaming, and a Downloads tab with Free up space.
Home works offline: what is on your phone comes first.
Series and anime pages with every season, and skip intro, recap and credits buttons.
Badges for 4K, HDR, Dolby Vision and Atmos.
Sync across my devices, through your own Saved Messages.
If the picture stops while the sound goes on, TMPlayer tries another decoder by itself.
```

For a store that shows one line only (Microsoft's "What's new" is a short paragraph), the first
two lines are enough.

## Feature lines (Microsoft Store, Amazon bullets, any "highlights" box)

- Starts playing within seconds while the video is still downloading
- One question after sign in: which chats hold your videos
- Save space or Keep video, and a Downloads tab with Free up space
- Home that works offline, with what is on your phone first
- Series and anime pages with every season, plus skip intro, recap and credits
- Badges for 4K, 1080p, HDR, Dolby Vision, Atmos and 5.1
- Another decoder or player tried by itself when the picture freezes
- Sync across my devices through your own Saved Messages
- Free and open source, no ads of its own, no analytics

## Support line (where a listing allows a link or a sentence)

> Free, with no ads of its own. If it is useful to you, you can support it through Buy Me a
> Coffee or UPI. Nothing is locked behind it.

Check each store's rules before putting the link in: Google Play and Amazon may want donation
links left out of the listing (the sentence without a link is safe). F-Droid takes no payment link
in the description; the Donate and Liberapay-style fields in its metadata are separate.

## Microsoft Store description (if the user ever lists it)

Use the full description above. Title `TMPlayer`, category Photos and video, privacy URL
<https://tmplayer.org/privacy/>. A Windows package from the store would need the signing and
update decisions in [winget.md](winget.md) first; this is the text only.

## Checks before pasting

- Character counts: `wc -m` on each file; the short description stays under 80.
- `grep -rInP '\x{2014}|\x{2013}'` finds no dash; no "film", "movie" or "uploader".
- "Unofficial" and "not affiliated with Telegram" are in the description's last paragraph and its
  first sentence.
- The skip sources and lookups are named on the privacy page, not in the store text, which says
  only "a title or an id".
