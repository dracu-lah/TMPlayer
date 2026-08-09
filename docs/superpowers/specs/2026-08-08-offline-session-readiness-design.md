# Offline Session Readiness Design

## Problem

TMPlayer starts `ChatListViewModel` before TDLib has resolved authorization. The view model
immediately requests the account and chat list. During QR sign-in, and sometimes during a cold
start, those calls can run before `AuthorizationStateReady`. Their failed state remains hidden
behind the sign-in screen, then becomes visible as soon as sign-in completes. Retry works because
it is the first request made after authorization is ready.

The same ordering has two related risks:

- `awaitConnected()` waits for Telegram connectivity, but does not wait for authorization. It also
  times out after 15 seconds and lets a request run even when Telegram is still unavailable.
- A repository can retain the TDLib client it captured before logout. Reusing its view model after
  a new sign-in can send requests to a closed client.
- `PlayerActivity` guards one restored-process lookup against a missing TDLib client, but its
  download observer and data-source creation still access `Td.client` directly. A restored player
  can therefore fail before the locally cached video is opened.

A full-screen offline dialog is not appropriate. TDLib keeps account and chat data locally, and a
video whose download is complete can be read from its local path without a network request.
TMPlayer should remain useful offline and restrict only actions that truly require connectivity.

## Goals

- Never request account or chat data before TDLib authorization is ready.
- Avoid stale work and stale TDLib clients across cold start, QR sign-in, logout and re-login.
- Clear old account content immediately when authorization leaves the ready state.
- Show cached account, chat, history, artwork and video information while offline.
- Play a completely downloaded video without internet.
- Explain connectivity at the component or action that is affected, without blocking the app.
- Recover automatically when internet and Telegram connectivity return.
- Show a branded launch experience while the saved Telegram session is being resolved.
- Keep readiness and connectivity rules centralized and testable.

## Non-goals

- Building a second database beside TDLib's existing local database.
- Keeping partially downloaded playback running beyond the bytes already on disk.
- Queuing Telegram writes for later. TMPlayer has no message-writing feature.
- Adding background synchronization through WorkManager.
- Changing player controls, storage policy or the one-video cache policy.

## Architecture

### Initial session resolution

`Td` will expose whether its first meaningful authorization state has been resolved. Parameter
setup is still startup work. QR confirmation, password, ready and terminal failure states are
meaningful results that can choose the next screen.

`MainActivity` will use AndroidX SplashScreen with the existing `ic_logo` mark and the app's dark
background. The native splash remains while initial authorization is unresolved, with a short
maximum hold of two seconds so a TDLib problem cannot leave the system splash on screen forever.
If resolution takes longer, the existing Compose tree shows a branded TMPlayer loading screen.
The splash never waits for internet or for chat synchronization.

This means:

- A saved session opens directly into cached content once authorization is ready.
- A signed-out session opens the onboarding or QR screen once TDLib asks for sign-in.
- An offline saved session still opens the app instead of hanging on the logo.

### Network and Telegram state

A process-wide `NetworkMonitor` will observe Android's default network through
`ConnectivityManager.NetworkCallback`. Its state is `Unknown`, `Online` or `Offline`. A network is
online only when it has internet capability and Android reports it as validated.

The Android network signal is advisory UI state, not absolute proof that a particular request will
succeed. TDLib being connected overrides a false Android offline signal for Telegram operations,
and the result of an HTTP request remains authoritative for GitHub. Callback code uses the
capabilities supplied with the callback rather than querying them synchronously from inside it.

TDLib connectivity remains a separate signal. Android can have validated internet while TDLib is
still reconnecting. The UI state is derived from both signals:

- `Normal`: no connectivity status is needed.
- `Offline`: Android has no validated internet.
- `Reconnecting`: internet returned, but TDLib is not ready yet.

Authorization is not treated as connectivity. QR and password screens continue to represent
authorization, while the small offline status remains available if the TV loses internet there.

### Request readiness

Requests are divided by what they need:

1. Local-capable reads wait for authorization only. These include `getMe`, known chats, cached file
   state and cached media metadata.
2. Remote synchronization waits for authorization and TDLib connectivity. These include loading
   newer chats, refreshing a listing and starting or extending a file download.
3. Pure local operations do not wait for TDLib connectivity. These include settings, watch history,
   cached artwork and reading a completed video from disk.

`Td` will provide explicit suspendable readiness gates for the first two categories. The remote
gate does not time out and then issue a request anyway. A caller remains in a loading or cached
state until the condition is true, or its lifecycle cancels the work.

Repository operations capture the current TDLib client only after the required gate passes and do
not retain it across client recreation. Each client creation increments a session generation.
Asynchronous results carry the generation they started under and are discarded if logout or client
recreation changed it before publication. View models cancel an older load before starting another.

### Chat and account loading

`ChatListViewModel` will no longer publish a pre-authorization failure. Its load sequence is:

1. Cancel an older load.
2. Wait for authorization.
3. Read the account and locally known chats without waiting for internet.
4. Publish cached content immediately when it exists.
5. When TDLib is connected, synchronize the chat list and publish the refreshed result.

The root observes transitions into `AuthState.Ready` and asks the view model to load. This covers
initial QR completion and every later re-login. A load also keeps its own authorization gate so an
early or future caller cannot recreate the bug.

When authorization leaves `Ready`, the view model cancels its work and clears its account and chat
snapshot. No previous account content is allowed to wait behind the sign-in screen and reappear
while a different account is loading.

If cached chats exist and synchronization fails, the content remains visible and connectivity UI
explains the limitation. A network failure must not replace useful content with a full-screen
error. A genuine non-connectivity failure can still use the existing error scaffold when there is
no content to show.

### Offline presentation

There is no app-wide modal. A reusable, non-focusable connectivity status component appears above
the current screen without changing D-pad focus:

- Offline: `Offline. Saved videos still work.`
- Restoring: `Back online. Reconnecting to Telegram...`

The component uses the existing dark surfaces, text colors and icon family. It includes text and
an icon so status is not communicated by color alone. It must stay inside TV overscan margins and
must not move the underlying layout when appearing or disappearing.

When Telegram becomes usable again, the status disappears, the visible data is synchronized, and
a short `Back online` toast confirms recovery.

Online-only actions handle offline state locally:

- Refresh keeps existing content and reports `Connect to the internet to refresh.`
- A non-downloaded video reports `This video needs internet before it can play.`
- Update checks fail quietly or show a scoped message instead of replacing the whole screen.

### Downloaded video behavior

`MediaItem` will carry the local file state reported with its Telegram message. A reusable local
availability check does not trust `isDownloadingCompleted` alone. It requires a non-empty local
path and an existing regular file. If TDLib knows the expected size, the file length must be at
least that size. If the size is unknown, the file must be non-empty. Its result is `Complete`,
`Partial` or `Missing`.

Video cards can show an `On this TV` badge without making a separate request per card. The badge is
updated from relevant TDLib file updates and is rechecked when the grid resumes, so clearing the
cache or completing a download cannot leave stale availability on screen. Continue-watching
records have their visible cached status resolved through the same check because those records
persist independently of the original message model.

Before starting playback while offline:

- A complete local file opens normally.
- A partial or absent file stays on the current screen and shows the scoped internet-required
  message.

`TdDataSource` already skips `downloadFile` for a completed file and reads its local path. That
fast path remains unchanged. During partially downloaded playback, already buffered bytes may
continue. If playback reaches missing bytes while offline, the player uses its own status overlay,
not the browsing connectivity component.

Player startup waits for an authorized current TDLib client, but never waits for network when the
file is complete. The download observer follows the same client readiness rule. If the viewer has
enabled `Download the whole video first`, `PlayerActivity` checks local availability before calling
the synchronous download function. A complete file starts directly while offline.

### Recovery

Android network recovery changes the status from offline to reconnecting. TDLib reconnects using
its existing client behavior. When its connection becomes ready or updating:

- Pending remote loads are released.
- The account and chat list refresh once.
- The currently visible media screen can refresh without clearing already visible items.
- The reconnecting status closes and the success toast appears.

Recovery is edge-triggered. Recomposition and repeated TDLib connection updates must not launch
duplicate refreshes or duplicate success toasts.

## Error handling

- Unauthorized requests are prevented by the authorization gate, not presented as connectivity
  errors.
- Offline and reconnecting are application states, not exceptional full-screen failures.
- Cached content wins over a synchronization error.
- Non-connectivity TDLib failures retain their existing human-readable mapping.
- The initial network state is `Unknown`, preventing a false offline flash while Android reports
  the active network.
- Offline status appears only after the offline signal remains stable for 750 milliseconds, which
  avoids flashing the component during a brief network handoff. An attempted online-only action
  always receives immediate feedback.

## Testing

### Unit tests

- Initial authorization resolution selects splash, login and ready states correctly.
- Local reads cannot pass the authorization gate before `AuthState.Ready`.
- Remote reads require both authorization and TDLib connectivity.
- Connection UI reduces Android and TDLib signals into normal, offline and reconnecting states.
- Recovery produces one refresh event and one success event.
- A newer load cancels or supersedes an older load.
- Completed, partial and absent files produce the correct offline play decision.
- A completed flag with an empty, deleted or zero-length path is not treated as playable offline.
- Results from an old session generation cannot publish into a new session.
- Restored player startup waits for the current client without waiting for internet.
- `Download the whole video first` makes no download request for a verified complete file.

### Device checks on the connected API 28 TV

- Cold-start an existing signed-in session repeatedly.
- Sign out, scan the QR code and confirm profile and chats load without Retry.
- Launch with internet disabled and confirm cached content appears after session resolution.
- Play the completely downloaded video while offline.
- Confirm a non-downloaded video is blocked only at the play action.
- Restore internet and confirm reconnecting status, automatic refresh and one success toast.
- Drop internet during partial playback and confirm the player owns the stall feedback.
- Confirm Back, Home and D-pad focus remain predictable while connectivity status is visible.

## Acceptance criteria

- QR sign-in never reveals a stale pre-login error.
- Cold start never requires Refresh to show the account.
- The system splash uses the TMPlayer logo and never waits indefinitely for internet.
- Offline mode does not cover or disable the whole browsing application.
- A fully downloaded video starts and seeks without internet.
- Network-required actions explain their requirement at the point of use.
- Returning connectivity refreshes visible data automatically without a loading-screen flash.
- Logout and re-login never reuse a closed TDLib client or previous account content.
- Process-restored playback does not fail because TDLib is still creating its client.
- A stale TDLib completion flag never opens a missing local file as though it were downloaded.
