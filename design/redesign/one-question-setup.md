# One question setup: "Which chats have your videos?", then Home

User approved R3 on 2026-11-16 ("do all"), with one change: no video counts anywhere.

## Problem Raju hits
- After sign in he met four pages (Theme and size, Posters, Performance, Storage, mobile data, crash
  reports), then a chat gate, a tour offer, sometimes a language card and a crash card. Up to 10 taps before
  the first video, and five of the six questions are about things he has never heard of.
- Back on the first page was "Skip all", which silently ended the whole setup.
- Every default was already the right one. The only question that matters is which chats hold videos.

## New flow
1. Sign in arms the setup (`SettingsStore.armSetup`, unchanged: a fresh install turns streaming mode on).
2. One screen: **"Which chats have your videos?"** It is the chat picker as T5 built it (search box, Recommended
   band with the reason line "Has many videos", no counts). Nothing is ticked, Done is disabled until one chat is
   ticked, Back asks **"Leave without choosing?"** (Leave closes the app on phone and TV).
3. Done writes the choice and the setup ends: Home.
4. Everything else is decided by the app or lives in Settings:
   - Lighter or Full pictures: `DeviceFit` decides and the recommendations are applied silently, once, when the
     setup starts on an account that has not answered yet. No page, no card.
   - Crash reports: off. The one question is the "Send crash reports" row in Settings (Account), which says
     what is sent. It is not asked in the setup and not as a card after Skip.
   - Posters, streaming mode, mobile data: defaults (posters on where the build has them, streaming mode on for
     a fresh install, mobile data as before). The rows stay in Settings > Redo setup choices.
   - The quick tour: a dismissible **Tips** card at the foot of Home, never a dialog. "Show me" opens the tour,
     "Not now" turns the card off for good (one tap, one answer).

## Platforms

| | Phone | TV (remote) | Desktop (keyboard) | Web |
| --- | --- | --- | --- | --- |
| The question | picker in place of the app, full screen | same picker, 10 foot sizes | picker over the window, no Back | `/setup/chats` then `/added-chats?setup=1`, already one step |
| First focus | as the picker is today | first chat row (tested) | as the picker is today | search field |
| Tick | tap row | OK on a row (tested) | Space, Enter or click | click |
| Done | foot button, disabled with "Choose at least one chat" | Right from the list moves to Done (tested) | Tab to Done, Enter (tested by click) | Done button, disabled until one |
| Back | asks "Leave without choosing?" (Leave closes the app) | Back climbs to the top of the list, then asks the same | Esc: nothing to go back to | browser Back: nothing behind it, gate is entered again |
| Waiting for chats | spinner with `picker.loading` under it, "Continue without choosing" after 4 s | same, remote lands on the button | same | text `picker.loading` |
| Offline | offline card ("You are offline"), Try again, Continue without choosing | same, remote on Try again | same | same words in the loader |
| Tips card | card in its own row under Home, two buttons | one slim line, remote on "Show me", Back = Not now | card in the corner of the window | none (no tour on the web) |
| Redo | Settings > Redo setup choices: rows and "Run the setup again" which opens the same picker, with Back (not required) | same | same | n/a |

## Edge cases
- **No video chats** (the account has Saved Messages and nothing else, or every count is empty): the picker is
  shown with whatever chats exist, even one (Saved Messages), and says "Forward a video to Saved Messages". The old
  gate waited for four chats and then let the app through with an empty Home.
- **Slow chat list**: the spinner with `picker.loading` under it; after 4 s "Continue without choosing" (nothing is
  written, asked again next start); after 8 s the setup still waits, it never ends by itself without a choice.
- **Offline during setup**: the offline card after 3 s with no list and no network. It goes by itself when the
  list comes. Continue without choosing is the way out.
- **Sign in on a device that already had a setup** (a session that ended elsewhere, not a Sign out, which clears
  the settings): the account's answer is stored. A stored answer (picked or all) ends the setup at once, no screen
  and no recommendations written again. A new account or a cleared install has no answer, so it is asked.
- **Kids lock**: not asked (as before).
- **Run again from Settings**: shows the picker with the chosen chats ticked, a normal Back and Done, and ends the
  setup when closed. Nothing else is repeated.
- **Huge account (3,000 chats)**: unchanged: first band within 3 s, counting in the background (T5).
- **Rotation, process death**: picker model is kept under the gate run; the setup flag lives in the settings store.
- **Stored values**: `setup` flag values, crash flags, `tour offer after` stay readable (the last is ignored).

## What goes
- `SetupScreen` pages (Appearance, Posters, Performance, Storage/mobile data/crash), `SetupAndTour`, `SetupState`,
  the steps' paging (`Setup.pages`, `firstRun`, `withCrashReports`), the crash step.
- The modal tour offer (`tour.offer_*`), "Not now, ask in a week" bookkeeping, the crash ask card after Skip
  (`setup.crash_ask_*`).
- Strings no page uses: listed in `private/DEAD-STRINGS.md` (kept in en.json until the next sync).

## Tests (machine first)
- `SetupFlowTest` (core, pure): every input combination of the state machine.
- `SetupFlagTest`: fresh install ends with streaming mode on and crash reports off; replay; finished setup.
- `OneQuestionSetupTvTest` (app, Robolectric): focus starts on the first chat, OK ticks, Right moves to Done, Back asks.
- Render per form factor (desktop harness): phone, tablet, TV, desktop widths, light and dark, offline and empty.
