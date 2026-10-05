# Distribution Compliance and Promotional Media Design

## Product position

TMPlayer is a sideloaded Android TV media player for videos already available through a viewer's
own Telegram account. It is not an app-store product, media service, channel directory, catalogue,
or source of videos. The primary promise is progressive playback: a video can start before its
Telegram download completes, and seeking repositions the partial download.

The public website, repository copy, app copy, structured data, social card, onboarding, and
screenshots use `media` and `video` language. Selecting a video starts playback directly. The old
external details UI and artwork lookups are removed from current source and builds.

## Risks being addressed

- The APK bundles GPL-3.0 media components while older project material claimed Apache-2.0.
- Telegram's API terms require official sponsored-message behavior in relevant channels.
- Existing public images expose real account data, real chat names, third-party media artwork,
  filenames, watermarks, and unrelated Android TV launcher content.
- The website lacks focused privacy and lawful-use pages.
- Existing promotional framing resembles an entertainment catalogue rather than a personal
  Telegram media player.

## Required outcomes

1. License new releases under GPL-3.0 and ship source archives and third-party notices with the
   exact APK revision.
2. Add accurate Privacy and Lawful use pages, link them from the app and website, and state that
   TMPlayer provides no media or channels.
3. Support Telegram-provided sponsored messages with retrieval, complete presentation, view,
   click, and report behavior where TDLib returns them.
4. Capture the real app UI on the connected 1920x1080 Android TV using isolated fictional data.
5. Replace every website, README, portfolio, and onboarding screenshot that contains real or
   third-party content.
6. Publish a clean v1.0.0 baseline after verification and remove superseded GitHub releases only
   after that replacement is ready.

## GPL release delivery

`THIRD_PARTY_NOTICES.md` identifies shipped dependencies, their versions, licences, and source
locations. It specifically records `nextlib-media3ext:1.8.0-0.9.0`, upstream tag
`1.8.0-0.9.0`, and commit `1b69e9490c172c42f73f7a6f1b9ffd1c8be4a060`.

Every tagged release publishes:

- The signed universal APK.
- The R8 mapping file.
- TMPlayer source for the release tag.
- Corresponding source and build material for the bundled GPL media extension and native codecs.
- `LICENSE` and `THIRD_PARTY_NOTICES.md`.

The workflow fails before publishing if a required licence or source artifact is missing. Release
links use the immutable tag, not the moving default branch.

## Privacy and lawful use

The Privacy page states that there is no developer-operated server, TMPlayer account, analytics,
advertising SDK, or crash-reporting service. It explains local TDLib session and chat data,
thumbnails, downloaded media, settings, favourites, watch positions, and update APKs.

It identifies Telegram, GitHub release checks, and user-invoked system speech recognition as the
current external services. The static website uses no application cookies or analytics script;
ordinary hosting logs may still exist. Settings links to both Privacy and Lawful use.

The Lawful use page states that TMPlayer hosts, uploads, sells, indexes, recommends, and discovers
nothing. Viewers must use media they own or are authorized to access. It provides a rights-holder
contact for a specific website URL or repository path without claiming that a disclaimer removes
the developer's obligations.

## Telegram sponsored messages

### Data boundary

A small repository wraps TDLib's sponsored-message operations and maps raw DTOs to an app model
containing identifier, label, title, full text, action label, sponsor information, reportability,
supported preview data, and minimum ordinary-message spacing. Results are scoped to the current
authorized session generation so stale-account data cannot enter a new session.

### Placement and behavior

A sponsored message is a visually distinct full-width information card, never a playable video
tile. It does not contribute to media counts, search results, Continue watching, episode
navigation, download policy, or playback.

The card displays its complete text without truncation. It is marked viewed once, and only after
the complete text is visible. D-pad focus is predictable. Selecting the action invokes TDLib's
click operation before opening the returned destination. The optional report flow follows every
server-provided option until completion.

An empty or failed sponsored request does not block ordinary media. Unsupported sponsored content
still shows full text and an action, but never enters the media player.

## Isolated promotional capture

A debuggable `promo` variant contains an ADB-launchable capture activity, fictional fixtures, and
standalone generated media thumbnails. Release variants cannot compile or package that activity,
fixture data, or source artwork. The promo application does not initialize Telegram, check
updates, install an APK, or read real preferences.

Production screens and components are reused directly where possible:

- Chat browsing receives a supplied account, tabs, chats, favourites, and watch history.
- The media grid receives supplied video items and layout state.
- Settings reads only the promo package's isolated default preferences. The promo application
  skips TDLib and network-monitor initialization.

### Fixture identity and content

- Account: `Demo account` with the fictional local identifier `local_demo` and no phone number.
- Primary chat: `Weekend Clips`.
- Supporting chats: `Home Projects`, `Recipe Notes`, `Travel Diary`, `Design Study`, and
  `Family Archive`.
- Filenames use ordinary descriptive examples such as `coast-walk-day-2-1080p.mp4` and
  `small-shelf-part-1-1080p.mkv`.
- No real Telegram handle, channel branding, personal portrait, third-party logo, media title,
  celebrity, watermark, or piracy-style release name appears.

Generated raster assets depict generic fictional scenes for those user-owned demo videos. They
contain no text, logo, signature, recognizable public figure, copyrighted character, or imitation
of an existing franchise. Each asset is visually inspected before it enters a capture.

## Capture set

ADB captures lossless 1920x1080 PNGs from the connected MiTV-AESP0 for:

- Chat browsing.
- Video grid.
- Settings.
- Chat and video browsing walkthrough images derived from the same clean captures.

The existing sign-in walkthrough uses an intentionally blurred, non-working QR illustration and
contains no account information.

There is no details-page capture. The website hero is derived from the clean video-grid capture.
README and portfolio images use clean real-UI captures. Onboarding images are resized to 960x540.

Final PNG/WebP assets are stripped and compressed with ImageMagick. Screens are inspected at full
resolution and scanned with OCR for old account names, handles, titles, watermarks, unrelated app
names, and working login tokens.

## Verification

- Unit tests cover sponsored placement and complete-text visibility. Retrieval, view, click,
  report, failure isolation, and stale-session guards are exercised through the typed TDLib
  integration and variant builds.
- Debug, release, and promo variants compile with JDK 21.
- The release APK contains no promo activity, fixture string, or standalone generated artwork.
- Website JSON-LD, manifest JSON, sitemap XML, privacy links, and lawful-use links validate.
- The site, README, app copy, OG card, portfolio entry, and public images consistently describe a
  Telegram media player.
- All generated and captured image dimensions are verified after ImageMagick compression.
- The corrected v1.0.0 release contains GPL/source artifacts before older releases are removed.
