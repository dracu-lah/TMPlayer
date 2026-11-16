# Raju, second pass on the final build (T7, 2026-11-16)

Render and focus tests only, no device. Walked on the current code (review waves R1 to R8, cast,
audio removal, web sync merged). The webapp is R9's and was not touched: its Raju rows (2.6, 4.10,
10.3) are listed under Left for the user. Screens and where to look: `design/raju-pass-2/index.html`.

## What I did

- New tests (all green), `app/src/test/java/com/tmplayer/ui/raju/` and one desktop class:
  - `RajuSignInPhoneTest` (8): number, wrong number, code, password and failed panes at 1.0 and 2.0 font;
    text asserted, every pressable thing at least 48 dp (`RajuPass.smallTargets`), a way on or out.
  - `RajuSignInTvTest` (6): QR page steps, and the remote reaching the way on or out of each pane.
  - `RajuFirstRunPhoneTest` (6) and `RajuFirstRunTvTest` (3): Home with nothing, no chat chosen, library off,
    empty Downloads; 48 dp, 200% font, one working button, the remote on it and OK pressing it.
  - `RajuSignInDesktopTest` (5): every pane in words, Tab reaches the way on or out, frames saved.
  - Shared helper `RajuPass.kt`: screenshot to `design/raju-pass-2/` and the 48 dp check on touch bounds.
- One real regression found and fixed (below). Everything else already covered is listed as reused.

## Fixed in this pass

- **TV sign in: the remote was trapped in the number, code and password box.** A single line text field
  keeps the D-pad for its cursor, so with the keyboard down no arrow left the field and the remote could
  not reach Sign in, Wrong number, Forgot password or Start over (the test walked 19 keys and stayed put;
  `TvSearchField` had the same bug fixed earlier with the same comment). New shared modifier
  `Modifier.dpadLeavesField()` (`app/.../ui/components/FieldDpad.kt`), applied to the TV branch of
  `AuthField` and to `CodeBoxes` off touch. While the TV keyboard is up it takes the keys first, so this
  only acts once it is down. Covered by `RajuSignInTvTest` (number, code, password).

## The journey, step by step: what Raju now sees and which test pins it

Reused tests were run by their own workers; this pass did not re-run them (brief: only the new tests).

| Step | Phone | TV | Desktop |
| --- | --- | --- | --- |
| 1 First open | `RajuSignInPhoneTest` (panes, 200%) | `RajuSignInTvTest` QR steps | `RajuSignInDesktopTest` |
| 2 Sign in (number, code, password, wrong, slow, QR) | new tests above, plus `LoginCountryAndResendTest` (chip, resend row) | new tests above, plus `LoginCountryAndResendTvTest` | new test above, plus `SignInCodeRowsTest`, `SignInPhoneRenderTest` |
| 3 Setup: one question | `AddChatsRenderTest` (required, Back asks), `ChooseChatsBarTest` | `AddChatsTvKeysTest` (first row, OK ticks, Right to Done, Back asks) | `OneQuestionSetupTest`, `SetupChoicesRenderTest` |
| 4 Home, Chats, Search | new `RajuFirstRunPhoneTest` (empty, none chosen, off), `GroupAFixesTest` (strip, star mode), `PhoneSearchTest`, `BarRestTest` | new `RajuFirstRunTvTest`, `TvTopBarQuickEntriesTest`, `TvSearch*Test` | `HomeEmptyCardTest`, `BrowseRenderTest`, `SearchFieldTest` |
| 5 Detail page | `DetailDownloadLineTest` (ui jvm), `FormatBadgeRenderTest` | `TvFocusRenderTest` | `DetailSheetRenderTest`, `DetailNoShiftTest` |
| 6 Playing | `ControlLabelsTest`, `AudioButtonAnswerTest`, `BackStopsThePlayerTest`, `SeekChromeRenderTest` | `TvRowFocusTest`, `PauseControlsRowTest`, `SkipMarkFocusTest` | `PlayerKeysTest`, `ShortcutSheetTest`, `PlayerChromeShotsTest` |
| 7 Downloads | new empty page test (no Free up space, one way on), `DownloadsTabBarTest`, `DownloadCardsRenderTest`, `CancelWindowTest` | new empty page focus test, `TvTopBarQuickEntriesTest` | `DownloadsRenderTest`, `DownloadsNavTest` |
| 8 Space and data | `MobileDataRowTest`, `StreamStorageRowTest`, `StorageChartRenderTest` | `SettingsGroupsTvTest` | `SettingsLayoutShotsTest`, `FreeUpReviewRenderTest`, `LowStorageRenderTest` |
| 9 Trouble | `OfflineBannerBarTest`, `OfflineHomePaneTest`, `ConnectHelpFocusTest`, failed pane (new) | `TvBannerFocusTest`, `ConnectHelpFocusTest`, failed pane (new) | `OfflineHomeTest`, `ConnectHelpRenderTest`, failed pane (new) |
| 10 Coming back | `KeepPlaceTest`, `ResumeSyncRowsTest`, `ResumeHeroNoArtTest` | `KeepPlaceTest` (TV focus) | `KeepPlaceCardTest`, `SignOut` frame in `ThemeAuditRenderTest` |
| 11 Settings | `SettingsMapScreenTest`, `SettingsLargeFontTest`, core `SettingsMapTest` (bigger, font, log out) | `SettingsGroupsTvTest`, `TvFocusSettingsRenderTest` | `SettingsMapDesktopTest`, `SettingsLayoutShotsTest` |

## Status of the findings of the first pass

Fixed and pinned: 2.1, 2.2, 2.3, 3.1 to 3.4 (R3), 4.1 (R1: default floor is 0), 4.2, 4.3, 4.4, 4.5, 4.6,
4.8, 4.9 (R2 tab), 5.1, 5.2, 6.2, 6.3, 6.4, 7.1 to 7.4, 8.1 (R2b), 8.2, 9.1 to 9.4, 9.6, 10.1, 10.2, 11.2.
No change needed: 2.7, 5.3, 9.5, 10.4. Not exercised on a device: 8.3.

## Left for the user

Open, small, each needs a call from you rather than a minimal fix:

- **1.1** First open still shows only "Getting ready": the one line promise (`state.first_open_promise`) was never added. Polish.
- **2.4** The phone sign in still hides Proxy settings and Send logs behind a three dot icon in the footer (MainActivity, not testable as a unit). The help link appears with the connect banner (tested), not before it.
- **4.7** An empty search over the added chats still ends in two secondary buttons ("Search my other chats", "Add chats"), not one primary and one link. Polish.
- **6.1** The "Getting the next part" toast on a single file video: not verified without a device.
- **7.5** The phone's empty Downloads page does not offer "Open folder" (the desktop does). Needs a storage access intent.
- **11.1** Settings still has Gestures, Library and Home as their own groups; the review wanted them merged into Playback and "Home and library".
- **11.3** Desktop Playback page still leads with "Force software decoding"; it was meant to move under an Advanced block.
- **Webapp rows (R9's files):** 2.6 wording of the Premium sign in error against the button, 4.10 two bars at 390 wide, 10.3 quiet Sign out with a confirm. Not touched here.
- **TV text fields elsewhere** have the same trap the sign in had unless they handle arrows themselves: Proxy page (two fields), the new list name (`ListsUi`), Add a TV address (`AddTvDialog`), the country picker search, MediaGrid search. `FieldDpad.kt` is in `app`; those files are in the shared `ui` module, so the modifier would first move there. Check each on the Mi TV remote before deciding.
- On a device: steps 1 to 3 on a clean install (the real country guess, SMS consent), the remote on the real TV code page after Back closes the keyboard (the fix above), offline on the real TV, and the 200% font on the POCO. Render tests cannot show the on-screen keyboard.
