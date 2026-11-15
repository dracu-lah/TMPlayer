# Mini player on the phone (U8, phone half)

The desktop has had it since `a31689b`: Back from a playing video docks it into a bar above the
floating navigation, a tap expands it at the same place, an X or a swipe down closes it. This note
is how the phone gets the same without making playback any less reliable.

## The problem

The phone's player is `PlayerActivity`, 10,000 lines that own the ExoPlayer (or `VlcEngine`), the
`MediaSession`, the TDLib stream, the overlay and every recovery path. The browse shell is
`MainActivity`, `singleTask`, in the same task. Only the top activity of a task is on screen, and
starting a `singleTask` activity clears whatever is above it, so today the only way back to
browsing is to finish the player, which releases it.

## Candidates

1. **Move the player into a service** that `PlayerActivity` and the browse shell both bind to.
   The right shape in the long run, but it means lifting the player, its listeners, its session,
   the stream and both engines out of the activity and threading every one of its hundreds of
   reads of `player` through a binder. That is a rewrite of the most tested code in the app, and
   any mistake in it shows up as a video that does not start, seek or recover. Not in one pass.
2. **Keep the player where it is, in a task of its own.** The activity already knows how to play
   on with nobody looking at it: background audio (`PlaybackService`) hands the existing session to
   a media foreground service, turns the video track off and plays on, and the activity is never
   gone. Leaving the player for the browse shell is the same thing, except the shell is in front.
   The only thing in the way is the task: in its own task the player can be left without being
   finished.
3. Picture in picture or a floating window: not this feature.

**Chosen: 2.** Nothing in the playback path changes: the same activity, the same engine, the same
session handover, the same video-off and video-back calls that background audio has shipped with.
What changes is which task the player opens in and what Back does.

## How it works

- **Own task.** `PlayerActivity` gets `android:taskAffinity="${applicationId}.player"` and
  `autoRemoveFromRecents`. Affinity only matters for a launch with `FLAG_ACTIVITY_NEW_TASK`, so
  every launch that does not add it (the TV, the player's own next episode, anything from inside
  the player task) lands where it does today. The browse shell's three launch sites go through
  `PlayerLaunch.fromBrowse`, which on a phone with the setting on adds `NEW_TASK | CLEAR_TASK`
  (a fresh player task every time, never two players) and `EXTRA_OUTSIDE_APP_TASK` (so a player
  that closes brings the browse shell back, the rule the corner window's players already follow).
  With the setting off nothing is added and the phone behaves exactly as before.
- **Back docks.** Back with nothing left to peel asks `MiniPlayerState.verdict` (core, shared
  with the desktop). Dock needs: a phone, the setting on, the player root of its own task, a video
  open, not ended, not failed, no sheet up (the player's status sheet also carries "Starting" and
  "Trying again", so a video still opening closes on Back as today, unlike the desktop), not
  casting, the screen lock off, not in picture in picture. Anything else closes the player as
  today. Docking starts `MainActivity` with
  `FLAG_ACTIVITY_NO_USER_ACTION` (so leaving is not taken as the viewer going Home: no picture in
  picture by itself) and the player's `onStop` hands its session to `PlaybackService` exactly as
  background audio does, whatever the background audio setting, because the viewer is still in
  the app.
- **Home is not Back.** Leaving the app from the player follows the background audio setting as
  today (plays on in the notification, or pauses). A player left that way is docked too, paused or
  playing, so opening TMPlayer from the launcher shows the bar to carry on from; with a sheet up
  (a failure, a question, the end) its play button opens the player to answer it. Leaving the
  shell (Home, or Back on its first page, which goes to the launcher while a video is docked so
  Android never surfaces the player's task by itself) follows the same setting for a docked video.
- **The bar.** `MiniDock` (app) is the one process wide link between the docked activity and the
  shell: a `MiniPlayerState` and a handle that answers title, playing, place, length, casting and
  takes play, pause, expand and dismiss. `TouchNavHost` draws the shared `MiniPlayerBar` (ui)
  above the floating bar on every phone page, and adds its height to `LocalBottomChrome` so lists
  end above it. The place is read once a second, only while the bar is drawn and the shell is
  resumed, so it stops by itself.
- **Tap expands.** `ActivityManager.AppTask.moveToFront()` (API 23) on the player's task. The
  activity's `onStart` already takes the session back, turns the video track on (ExoPlayer seeks
  the video stream to the keyframe before the playhead by itself) and clears the stopped notice:
  the same place, never a new player. The media notification's tap, with the player in its own
  task, goes through the shell with an expand extra so it still opens the player.
- **Dismiss frees everything.** The X or a swipe down pauses and finishes the docked activity, and
  its `onDestroy` releases the player, the decoders, the session and the stream as on any other way
  out. Opening another video from the shell dismisses the docked one first.

## Video in the bar: no

The bar shows the title, play or pause and a thin progress line, and a tap brings the picture back.
Drawing video in the bar would mean moving the player's output from the player's window to the
shell's (a second `Surface` in another activity, reattached on every dock and expand) or a second
decoder. The first is a new path through the most fragile part of playback (surface loss and
reattach on hardware decoders, tunnelling, HDR, the VLC surface), and the second is ruled out on a
1 GB phone. Audio only needs neither: the video track is off while docked, which releases the video
decoder, and comes back on through the path background audio already uses.

## Edge cases

| Case | What happens |
| --- | --- |
| Process death | Everything docked lives in memory, so after a death there is no bar. `PlaybackService` restarted sticky finds no session and stops itself. The player task, if Android restores it, reopens its video from the saved place, as a task with the player on top did before. Nothing waits on a player that is gone. |
| Audio focus | ExoPlayer keeps handling it. A call or another app's sound pauses the docked video; the bar shows the pause on its next read. |
| Rotation | The shell is recreated, `MiniDock` is process wide, the bar comes back. The docked player handles its own configuration changes as it does today. |
| Android 6 floor | `AppTask.moveToFront` and `RecentTaskInfo.id` are API 23; `taskId` is read from API 29. Nothing newer is needed. |
| 1 GB phone | Docked means the video track is off (its decoder released) and one player at most: a new video dismisses the docked one first, and `CLEAR_TASK` finishes any player left in the task. The bar's read is once a second and only while drawn. |
| Cast | Back while casting closes the player as today. Home while casting docks it (the cast carries on, the video track stays as the cast left it), and the bar's tap and play both open the player's cast controls: on the phone the bar is the only way back to them from the launcher, so it shows rather than hides. |
| Kids lock | A docked video whose chat the kids lock no longer allows is dismissed. The player's screen lock refuses Back, so a locked video never docks. |
| App lock | The pad replaces the shell, so the bar is not drawn while locked. Expanding runs the player's `onStart`, which still steps aside when the lock asks on open. The notification keeps showing only the app's name with the lock on. |
| Engine switch | The handle reads whichever engine is playing (`ExoEngine` or `VlcEngine`) each time; a switch puts the new player into the same session. With VLC the video track cannot be turned off, so it plays with no surface. |
| Queue and next-up | A docked video that ends goes on to the next episode in the same activity, the path background audio uses (`continueUnseen`), and the bar shows the new title. With nothing next the bar closes and the player is released. "Still watching?", a failure or a question pauses it; play on the bar then opens the player to show it. |
| Protected video | The bar has no picture, so nothing protected is drawn outside the player's own secure window. |
| Setting off | No own task, no dock: Back closes, Home pauses or plays on in the notification, as before. |
| TV | Never: `MiniPlayerState.allowedOn` and the launch flags both check the form factor. |

## Costs and limits

- Two TMPlayer cards in Recents while a video is open (the player's task goes when the player
  closes, `autoRemoveFromRecents`). Excluding the player's task from Recents was tried on paper and
  left out: Android can drop a hidden task that is not on screen once another task starts (a share
  sheet, a file picker), which would close a docked video without a word.
- From the launcher, TMPlayer with a video open now shows browsing with the bar, not the player.
- The notification's tap reaches the player through the shell, a frame of browsing first.
