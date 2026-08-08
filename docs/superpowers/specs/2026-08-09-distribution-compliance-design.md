# Distribution Compliance and Promotional Media Design

## Problem

TMPlayer is distributed as a sideloaded APK from GitHub and promoted through the static site in
`site/`. It is not intended for Google Play or another app store. Four issues need to be fixed
before the project is promoted widely:

- The APK bundles the GPL-3.0 `nextlib-media3ext` library, while the project and release material
  previously claimed Apache-2.0. The working tree already changes the project to GPL-3.0, but the
  release process also needs to publish the matching source and notices.
- Telegram's API terms require clients that expose channel content to support official sponsored
  messages. TMPlayer currently requests only playable media and never asks TDLib for sponsored
  messages.
- Public screenshots expose a real account name and username, real Telegram chat and channel
  names, commercial film posters, actor portraits, release-style filenames, third-party app and
  service logos, and uploader or piracy watermarks. The affected images appear on the website,
  in the GitHub README, and inside the app's onboarding walkthrough.
- The website has no privacy policy or focused lawful-use statement. Its TMDB attribution has the
  required sentence but does not show the approved TMDB mark on the website.

The connected capture target is a MiTV-AESP0 running Android API 28 at 1920x1080. It will be used
to capture the real application UI after debug-only fictional fixtures are installed.

## Goals

- Keep the static website and GitHub release distribution online without presenting TMPlayer as a
  source or index of films.
- Make GPL-3.0 the clear licence for the combined distributed application and publish adequate
  source and third-party notices with each new APK.
- Support Telegram sponsored messages in channel media listings using TDLib's required retrieval,
  presentation, view, click, and report behavior.
- Replace every public or bundled screenshot that contains real accounts, chats, commercial film
  material, unrelated launcher content, or release watermarks.
- Capture the actual TMPlayer UI using fictional debug-only data rather than generating fake UI.
- Keep all demo fixtures and standalone generated art out of release APKs.
- Explain accurately what is stored locally, which remote services receive requests, and how a
  viewer clears local data.

## Non-goals

- Publishing TMPlayer in Google Play or another app store.
- Hosting, indexing, recommending, or discovering Telegram channels or media.
- Adding advertising of TMPlayer's own. Only official sponsored messages returned by Telegram are
  in scope.
- Editing old screenshots by blurring or inpainting over copyrighted material.
- Using another commercial film as a replacement reference.
- Claiming that a privacy policy, disclaimer, or GPL change prevents all copyright complaints.

## Work streams

The implementation has three bounded work streams. They are delivered together, but each has an
independent acceptance gate:

1. Public distribution and website disclosures.
2. Telegram sponsored-message support.
3. Fictional promo fixtures, generated art, and real-device screenshot capture.

## Public distribution and website disclosures

### Licence and source delivery

The project is GPL-3.0 from the relicensing commit onward. Earlier Apache-2.0 grants for TMPlayer's
own code remain valid and are not described as revoked. The combined APK and all new release pages
are labeled GPL-3.0.

`THIRD_PARTY_NOTICES.md` will identify at least the shipped native and media components, their
versions, licences, copyright holders where published, and source locations. It will call out
`io.github.anilbeesetti:nextlib-media3ext:1.8.0-0.9.0` and its upstream tag
`1.8.0-0.9.0` at commit `1b69e9490c172c42f73f7a6f1b9ffd1c8be4a060`.

The release workflow will attach these artifacts for the same tagged revision as the APK:

- The signed universal APK.
- The R8 mapping file.
- A TMPlayer source archive from the release tag.
- A corresponding-source archive containing the exact NextLib source tag and the source or build
  recipes for the native FFmpeg libraries included by that dependency.
- The GPL-3.0 licence and third-party notices.

The workflow fails before publishing if the licence, notices, or source archives are absent. The
release body links to the tag rather than to the moving `main` branch. The local implementation
prepares superseded-release wording, but it does not alter an existing live GitHub release. That
external action is taken only after a corrected release exists and the repository owner confirms
the exact releases to update.

### Privacy policy

`site/privacy.html` will use the same typography, header, footer, and responsive shell as the home
page. It will state, in plain language:

- TMPlayer has no developer-operated server, account, analytics SDK, advertising SDK, or crash
  reporting service.
- TDLib stores the Telegram session, cached account and chat data, thumbnails, and downloaded
  media inside the Android application sandbox on the TV.
- TMPlayer stores settings, favourites, watch history, cached TMDB responses and artwork, and a
  temporary update APK locally.
- Telegram receives the requests needed to sign in, list the viewer's own chats, retrieve files,
  and display official sponsored messages where applicable.
- TMDB receives film or episode search terms derived from a selected filename when a TMDB key is
  configured. GitHub receives an update check. YouTube or the installed browser receives a trailer
  action only when the viewer chooses it. The TV's installed speech-recognition provider handles
  voice search when the viewer invokes it.
- The static website sets no application cookies and includes no analytics script. Hosting and
  network providers may retain ordinary connection logs under their own policies.
- Clearing cache removes local media and metadata caches. Signing out clears TMPlayer preferences,
  history, favourites, cached TMDB data and art, and asks TDLib to log out and clear its account
  database.
- A contact address and effective date are present.

The app Settings help section and the website footer will link to this policy.

### Lawful use and copyright contact

`site/legal.html` will combine three short topics: lawful use, open-source licensing, and rights
holder contact. The home page will also place this sentence in a visible callout near the existing
"What it is" section:

> TMPlayer does not provide media. Use it only with content you own or are authorized to access.

The legal page will avoid claiming that user responsibility alone removes the developer's
obligations. It will explain that TMPlayer does not host, upload, index, sell, or recommend media
or channels. It will provide the existing project email address for a rights holder to identify a
specific website asset or repository file.

The footer will link to Privacy, Lawful use, Source, and GPL-3.0. The website's TMDB notice will
show the existing approved TMDB mark beside the required disclaimer. The mark will be recreated as
a web SVG from the already tracked official geometry without recoloring or modifying it.

## Telegram sponsored messages

### Data boundary

A `SponsoredMessageRepository` will wrap only these TDLib operations:

- `getChatSponsoredMessages(chatId)` to retrieve messages for a channel or bot chat.
- `viewMessages(chatId, messageIds, source, forceRead)` after the entire sponsored text is visible.
- `clickChatSponsoredMessage(...)` for sponsor, media, and action-button interaction.
- `reportChatSponsoredMessage(...)` for the optional report flow.

The wrapper maps TDLib DTOs into a small application model containing identifier, label
(`Sponsored` or `Recommended`), title, complete text, button text, sponsor information, optional
supported media, reportability, and the minimum ordinary-message spacing. Raw TDLib DTOs do not
leak into the Compose UI.

Sponsored-message loading is scoped to the current authorized TDLib session generation. Results
from an old account or closed client are discarded by the same session rules as other remote
loads.

### Placement and presentation

Sponsored messages are part of a chat's media listing but are not playable films. A visually
distinct, full-width card is inserted into the list according to TDLib's `messagesBetween` value.
When that value is zero, one sponsored card appears after the ordinary items. The card never
pretends to be a poster tile and never contributes to film count, filtering, episode navigation,
Continue watching, or cache decisions.

The card shows the complete text without truncation before it can be marked viewed. It includes:

- A `Sponsored` or `Recommended` label.
- Sponsor/title and the supported text or media preview.
- The exact action-button text supplied by Telegram.
- Additional sponsor information when non-empty.
- A report action only when Telegram marks the message reportable.

Focus order remains predictable for a D-pad. Selecting the card body or button calls the
appropriate TDLib click operation first, then opens the returned destination. Media-specific and
fullscreen flags follow TDLib's documented click semantics. The card is marked viewed only after
Compose layout confirms that the entire text is on screen, as required by TDLib.

### Failure behavior

An empty sponsored-message result produces no card. A temporary retrieval failure preserves the
film list and is logged without a full-screen error. Click or report failures show a scoped toast.
Unsupported sponsored content still renders its complete text and action button, but does not
attempt unsupported playback. Loading sponsored content never blocks ordinary film browsing.

Report flows honor TDLib's multi-step report options. A successful report removes or refreshes the
affected card only when the server response calls for it.

## Fictional promotional fixtures

### Source-set isolation

A `promo` build type, initialized from `debug`, will be added. Its manifest exposes an
ADB-launchable `PromoCaptureActivity` only in that debuggable variant, and its source set contains
all fixture data and standalone generated artwork. Release builds do not compile or package the
activity, fixtures, or standalone source artwork. The clean onboarding captures derived from the
art remain ordinary production screenshot resources.

Production screens will be split only where necessary into stateful wrappers and stateless content
composables:

- `BrowseScreen` is already suitable for supplied fake state.
- `MediaGridScreen` retains its production view model wrapper and delegates rendering to a
  stateless media-grid content composable.
- `FilmDetailsPanel` retains its TMDB-loading wrapper and delegates rendering to a stateless detail
  composable supplied with a `FilmLookup`.
- `SettingsScreen` receives a stateless content boundary for capture without touching real local
  preferences or TDLib storage.

The promo activity uses those same content composables. It does not start TDLib, call TMDB, check
GitHub, open YouTube, install updates, or read a real account. A clear `promo` source flag chooses a
no-network application initializer for this build type.

### Fictional catalogue

The hero title is `Signal Over Luma-9`, a fictional 2025 science-fiction mystery. Its art depicts
fictional people and places with no resemblance request, studio mark, franchise, celebrity,
trademark, or embedded text. Supporting titles use the same original universe or clearly distinct
fictional genres. All visible filenames end in `.Demo.mp4` and contain `TMPlayer.Demo` so they
cannot be mistaken for captured release files.

Visible identity and chat data are equally explicit fixtures:

- Account: `Demo Viewer`, with no username.
- Primary channel: `Open Screen Demo`.
- Other chats: neutral fictional demo channels, groups, and people.
- No Telegram handle, real phone number, personal portrait, third-party app logo, or existing
  channel branding is used.

Fictional metadata is represented as `DetailsSource.Demo`, distinct from `DetailsSource.Tmdb`.
TMDB artwork, cache, API calls, logo, and disclaimer are shown only for TMDB-sourced details. The
promo detail screen uses generated local resources and a small `Fictional demo content` marker,
not TMDB attribution.

### Generated visual assets

The built-in image-generation tool will create project-bound raster assets for the fictional
catalogue. Prompts require no text, logo, watermark, recognizable actor, real film reference, or
existing franchise style. A consistent restrained cinematic palette will fit TMPlayer's dark UI
without copying the current film art.

Each final generated asset is inspected before use. Assets with text fragments, signatures,
watermarks, malformed faces, duplicated people, or recognizable intellectual property are
rejected and regenerated. Final selected assets are saved only in the `promo` source set. The
captured UI images derived from them are the only generated media copied into production
onboarding resources.

### Capture and replacement

The promo APK is installed on the connected MiTV-AESP0 through ADB. `PromoCaptureActivity` accepts
an explicit screen extra so capture is deterministic. ADB captures lossless 1920x1080 PNGs for:

- Chat browsing.
- Film grid.
- Film details.
- Settings.
- Sign-in walkthrough illustration.
- Playback/download walkthrough illustration.

The capture process verifies that no notification, account overlay, launcher row, clock, QR login
token, or unrelated app is visible. Website WebP files are derived from the captures at their
current dimensions. `site/screenshots/hero.webp` is a deliberate crop of the clean details
capture. README images are clean 1920x1080 captures. The five onboarding images are resized to
960x540 with no additional content edits.

The following tracked files are replaced or derived:

- `site/screenshots/chats.webp`
- `site/screenshots/grid.webp`
- `site/screenshots/details.webp`
- `site/screenshots/hero.webp`
- `docs/screenshots/browse.webp`
- `docs/screenshots/settings.webp`
- `app/src/main/res/drawable-nodpi/overview_chats.webp`
- `app/src/main/res/drawable-nodpi/overview_details.webp`
- `app/src/main/res/drawable-nodpi/overview_films.webp`
- `app/src/main/res/drawable-nodpi/overview_playing.webp`
- `app/src/main/res/drawable-nodpi/overview_signin.webp`

`site/og.png`, icons, and the TMPlayer vector logo remain because the audit found no account or
film content in them. Alt text and captions are updated to match the fictional demo screens.

## Error handling and safety

- Promo mode cannot be selected in a release variant and has no release signing configuration.
- Promo captures never use a valid Telegram QR token or a real Telegram session.
- Generated art is treated as untrusted until visually inspected.
- A missing promo asset fails the promo build instead of silently falling back to Telegram or
  TMDB content.
- Sponsored-message errors never hide or replace ordinary media.
- Website claims are limited to behavior verified in code.
- No historical release asset is removed until a corrected replacement release exists.

## Testing

### Unit tests

- Sponsored DTO mapping preserves complete text, labels, sponsor information, button text,
  reportability, content type, and spacing.
- Sponsored placement honors `messagesBetween == 0` and positive spacing without changing film
  counts.
- A sponsored item never enters playback, resume history, episode navigation, or cache policy.
- View tracking is sent only after the full text has been laid out visibly and is sent once per
  message presentation.
- Click flags and report option sequences map to the correct repository calls.
- Results from a stale TDLib session generation are ignored.
- Retrieval, click, and report failures preserve ordinary content.
- TMDB attribution is present for TMDB details and absent for demo details.
- The promo variant cannot initialize Telegram, TMDB, GitHub updates, or a real SettingsStore.
- Homepage JSON-LD remains valid and points to GPL-3.0.
- Privacy and legal pages contain their required headings, contact, and homepage/footer links.
- Release workflow checks reject missing GPL, notices, and corresponding-source archives.

### Device checks

- Install the promo APK through ADB and open every capture route at 1920x1080.
- Confirm D-pad focus is visible and never trapped by a sponsored card.
- Confirm every sponsored card shows its complete text before view tracking.
- Confirm action and report controls produce scoped feedback on failure.
- Capture every required screen and inspect it at original resolution.
- Run OCR and filename scans over final screenshots for old account, channel, film, uploader, and
  app-brand terms.
- Install a release build afterward and confirm no promo activity, fixture title, or generated
  source artwork exists in the APK.

## Acceptance criteria

- All new public release material consistently says GPL-3.0 and ships matching source and notices.
- The static website has accurate Privacy and Lawful use pages linked from the home page and app.
- The homepage carries a visible authorized-content statement and approved TMDB attribution.
- Channel media screens request and correctly present official Telegram sponsored messages.
- Sponsored messages support required view, click, and report behavior without behaving like
  films.
- No public, README, or onboarding screenshot contains Uyir, another real commercial film,
  commercial poster or cast photography, real account data, real chat branding, piracy-style
  filenames, uploader marks, unrelated launcher content, or a working Telegram login token.
- All replacement screenshots come from the real TMPlayer UI on the connected Android TV.
- Generated source artwork and fixtures are absent from release APKs.
- Unit tests, release build, promo build, website validation, screenshot audit, and connected-TV
  checks pass.
