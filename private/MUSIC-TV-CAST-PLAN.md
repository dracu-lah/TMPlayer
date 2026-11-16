# Music: TV app, remote and casting plan (2026-10-30)

Companion to `private/MUSIC-PLAYER-RESEARCH.md` (section 17 holds the decision). Planned per AGENTS.md
"Building a feature": what it does on each platform, every edge case, build order, handoff. Nothing is
built. Starts after the Android phone app reaches the research file's phase M4.

## 0. What we are building

| # | Feature | Plain words |
| --- | --- | --- |
| T | TV app | TMPlayer Music on Android TV and Fire TV: a lean-back player, signed in by QR, streaming from Telegram itself |
| R | Connect (remote and hand-off) | "Play on TV": the phone sends the queue to the TV app and can then lock or leave; "Control from phone": the phone is the remote. Our own apps only, same Wi-Fi |
| C | Cast | Send audio to Google Cast and DLNA speakers and TVs that do not run our app. The phone serves the bytes |

Order: T, then R, then C. R is the one people will use daily; C is a courtesy for rooms without our app.

## 1. Reuse, and one overlap to settle

- The video app's **CP73** (private/PLAN.md, not started) already plans Cast with a local HTTP server that
  serves Telegram files with ranges, Google's Cast framework in the regular APK only (never F-Droid), and
  the phone keeping the controls. **One Cast stack for both apps**: build the local server, the session
  token and the receiver code once in a shared module, music adds an audio profile on top. Whichever app
  goes first builds it; the other adopts it. Record that in CP73.
- QR sign-in, D-pad focus rules, the TV bar and the idle-CPU measurement recipe come from the video TV app
  (`docs/BUILDING.md`, memory "Mi TV speed testing"). `CastRecovery` and `JobFailures` exist for failures.
- Not found in the repo: any discovery code (no NSD or mDNS), so R's discovery is new.

## 2. T: the TV app

**Screens (D-pad first):** Home (Continue, Recently added, Favourites, Chats as rows), Library (Songs,
Albums, Artists, Playlists, Chats as tabs along the top), Search (on-screen keyboard and voice where
the remote has it), Now Playing, Lyrics, Queue, Settings.

**Now Playing on a TV:** big cover with a tinted background, title, artist, a progress bar, the lyrics as a
large side panel (synced, current line centred), the queue peeking from the bottom. Left and right seek
(hold to scrub, same instant-arrow model the video TV player uses), up and down to move between cover,
lyrics and queue, OK to pause, Back to the library with the music still playing, and a mini bar there.

**Rules for a TV:**
- Music keeps playing when the user goes Home: a foreground media session with a notification-less launcher
  presence (the video app already hides notifications on TV).
- Screen: dim after 30 s idle and drift the layout a few pixels every minute against burn-in on OLED;
  never keep the screen on forever, honour the system screensaver and its settings.
- Focus: after every action (add to queue, Keep offline, Remove) focus returns to a sensible element,
  never to the top of a long list.
- The 1 GB stick budget: covers decoded at screen size only, lists paged, no per-frame work while paused,
  idle CPU measured via `/proc/<pid>/stat` after Home, lists and a finished queue.
- Cache default on a TV is 500 MB or off, because 8 GB sticks are small; the Free up space screen is
  reachable with the remote, not only a phone.
- The remote's media keys (play, pause, next, previous, rewind, fast forward) all work; a Bluetooth
  keyboard works.
- Fire TV: the same APK; Vega OS sticks are out of scope (AGENTS.md).
- A TV has no good way to type a Telegram code: sign in only by QR scanned from a phone, and by phone
  number as the last resort.

**Edge cases:** no audio chats chosen yet (a first-run chooser with the remote), a huge chat, the TV
losing Wi-Fi mid-track (buffer, then "waiting for network", resume on its own), the account logged out on
another device, storage full, a codec the stick cannot decode (software fallback from NextLib, with a
note), HDMI audio devices switching, ARC and passthrough off (we play PCM only), volume owned by the TV, a
voice command that arrives while a song plays.

## 3. R: Connect, the remote and hand-off between our apps

**How it works:** the TV app (or a desktop app) runs a small control server on the local network and
advertises itself with NSD/mDNS as "TMPlayer Music on Living room TV". The phone lists the devices it sees,
and a Connect button on Now Playing opens them.

- **Pairing, once:** the TV shows a 6 digit code and a QR; the phone enters or scans it; both store a
  secret. Every command afterwards carries a token made from that secret, so another device on the Wi-Fi
  cannot drive the TV. A device can be forgotten from either side. No Telegram data is sent over the LAN
  except what a command needs (a file id, a position).
- **Same account:** the TV signs in by the user's own QR, so the account is the same; the phone checks that
  the account id matches before sending a queue and says plainly if it does not.
- **Play on TV (hand-off):** the phone sends the queue as message references (chat id, message id, file id),
  the current index and position. The TV streams from Telegram by itself, so the phone can lock, leave
  Wi-Fi or die, and the music continues. The phone's own player pauses at the same moment, so there is
  never sound from two places. A Bring back to phone button reverses it with the TV's current position.
- **Control from phone:** play, pause, seek, next, previous, shuffle, repeat, queue edits (add, remove,
  reorder, play next), volume of the TV app's stream, and the favourite heart. The lyrics follow on both
  screens from the TV's clock. The phone's lock screen and notification control the TV while connected.
- **Both directions:** a TV remote action shows on the phone within 1 s. Two phones may be connected; the
  last command wins and both screens stay in step. Roles never cross: the phone is a remote, not a second
  player, while connected.
- **Desktop too:** the desktop app is a receiver and a remote with the same protocol (useful for a PC
  attached to speakers).
- **Transport:** a WebSocket on the LAN with JSON messages (state, command, ack) and a version number;
  state is pushed on change and once per second while playing as a heartbeat; messages are small and
  parsed defensively (`GarbageInputTest` gets the line).

**Edge cases:** Wi-Fi drops (the TV keeps playing, the phone shows "reconnecting" and re-syncs, never
restarts the track), the phone and TV on different subnets or a guest Wi-Fi that isolates clients (say so
and point at Settings), a router that blocks multicast (a manual "Add by IP and code"), the TV asleep
(Wake on LAN is optional, otherwise "Open the app on the TV"), the TV app killed (the phone says so, offers
to take the music back), hand-off while a track is mid-download on the phone only (the TV fetches it
itself), two TVs on the network, a queue of 20,000 (sent as a window plus a source reference, not 20,000
items), a track the TV cannot play (it skips with a reason sent to the phone), version mismatch between
apps (a message to update), battery saver on the phone throttling the socket, the user pressing Connect
twice, volume jumping (the first volume sync is ignored), and a stranger's device asking to pair (a code
nobody sees is refused after three tries).

## 4. C: Cast to speakers and TVs without our app

- **Google Cast (Chromecast, Google TV, Nest, many soundbars):** the shared CP73 stack, with an audio
  profile. The phone serves `http://<phone-ip>:<port>/<token>/<fileId>` with Range support from Telegram;
  the receiver pulls it. Cast's own receiver app is the Default Media Receiver (no registration needed to
  start, a custom receiver only if we want lyrics and a skin). Android only, regular APK only.
- **DLNA / UPnP renderers (many TVs, speakers, Kodi, Volumio):** the same local server, SOAP control. Lower
  priority than Cast; decide after the Cast pass.
- **AirPlay:** out of scope (needs Apple protocol work and no reliable library).
- **Controls stay on the phone:** play, pause, seek, skip, volume, stop; the notification and lock screen
  control the receiver; the place is kept like any other playback.
- **Formats:** FLAC and MP3 and AAC and Opus direct when the receiver decodes them (known tables for
  Chromecast generations); M4A with the index at the end remuxed so the receiver can start; when only the
  codec is the problem, convert the audio alone to AAC (the same approach CP73 takes for the sound).
  Lossless to a lossy receiver says so in the UI ("Sent as AAC").
- **Security:** a random token per session in the URL, the server binds to the LAN interface only, refuses
  other source addresses than the receiver, stops when the session ends, never serves anything but the
  files in the queue. A test for each (PixelPlayer's cast tests cover session security and codec sniffing
  for the same reasons, a useful checklist for our own tests).
- **Edge cases:** phone leaves Wi-Fi or goes to sleep (a foreground service holds the server, with a stop
  button; Doze and battery saver warned about), the phone's cache off (the server streams from TDLib, not
  the disk), the receiver asking for ranges out of order or hammering the same range, a track not yet
  downloaded at a seek, a file removed from the chat during playback, Android 17 changes to local HTTP
  streams (PixelPlayer has an open issue for it: cleartext traffic rules; test on the newest Android),
  multiple receivers (a group), the user's mobile data being used by mistake (Wi-Fi only check).

## 5. Platform summary

| | Phone | TV | Desktop | Web |
| --- | --- | --- | --- | --- |
| T (own app) | n/a | yes | n/a | no |
| R (remote and hand-off) | sender and remote | receiver | receiver and remote | remote later (a browser cannot receive LAN connections; it can send through our relay only if we add one, so not planned) |
| C (Cast) | yes (Android) | no | later | Cast sender SDK in Chrome later |

## 6. Wording, store and Telegram terms

- Say "play on your TV" and "cast", never "stream movies" or anything about content. Cast and Chromecast are
  Google trademarks: the label is "Cast", the logo only as Google's guidelines allow.
- A local server and a Cast SDK belong in the regular APK only, never in F-Droid or a store build without
  the review, as with the updater.
- Local network permission: Android 13 `NEARBY_WIFI_DEVICES` and 14+ network rules; the app asks when the
  user first opens Connect and explains why. Without it the feature says what is missing.
- Nothing is done on the user's behalf unseen: connecting, pairing and casting are always started by a
  tap.

## 7. Build order

| Step | Work | Done when |
| --- | --- | --- |
| TV0 | TV module: launcher banner, QR sign-in reuse, focus map, the shell with Home and Library from the shared index | Opens on the TV emulator, D-pad reaches every row |
| TV1 | Now Playing, Lyrics, Queue on TV; media keys; idle drift and dim; background play | Plays FLAC and MP3 on the emulator; idle CPU measured |
| TV2 | Cache and Free up space on TV; low-memory pass on the 1 GB stick | Done on the Mi TV recipe |
| R0 | Protocol spec file, message types, garbage tests, token and pairing | Unit tests green |
| R1 | Receiver on TV and desktop; sender and remote UI on phone; NSD discovery | Phone drives the emulator TV |
| R2 | Hand-off both ways, reconnect, two controllers, queue windowing | Edge cases in section 3 covered |
| C0 | Shared local server and token (with CP73), audio profile, Cast sender | A Chromecast plays a FLAC from a chat |
| C1 | Format table, remux and AAC fallback, security tests, Doze | Edge cases in section 4 covered |
| C2 | DLNA only if asked for | |

Device passes: real phone, the emulator TV, and the user's Mi TV and a Chromecast when the user sets them
up (memory: no phone testing unless lifted, TV on request).

## Progress / handoff

- 2026-10-30: plan written, nothing built. Open items for the user: Q-C1 whether DLNA is wanted at all;
  Q-C2 which receivers the user owns for the pass (a Chromecast or a Google TV); Q-R1 whether a remote
  from the web player is ever wanted. Defaults: skip DLNA for now, no web remote.
- Next work: the Android phone app (research file, M0 to M4) comes first; start TV0 after M4.
