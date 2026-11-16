# EchoGram comparison (started 2026-11-15)

Goal (user): read the EchoGram group in the user's Telegram account (it has several topics), compare its
update changelogs and bug fixes with TMPlayer, add every finding to the plan and implement what is worth it.

## Public sources (no account needed), what they say
No official changelog is published. Listings disagree on the version (apkcombo 1.0.7.0 on 2026-09-23, 127 MB;
apkpure 1.0.5.6 on 2026-09-02 and 1.0.5.2 on 2026-08-08). Developer: Illuzion Apps, about 50,000 installs.
- Android phone and TV player for the user's Telegram chats; streams in chunks (small TV storage).
- MKV with several audio tracks and subtitles; speed; audio track choice; subtitle appearance.
- Google Cast and DLNA; "continue watching" from phone to TV.
- Premium (developer, Reddit 2025-03-15): in-channel search, video seeking, auto play next, watch history,
  resume a partly watched video. The developer was building its own video controls instead of the stock ones
  and had a loop feature on the to-do list.
- The 1.0.5.2 notes point TV users to a community reached by scanning a QR code in the app.
Sources: apkcombo.com/echogram-tv-for-telegram/com.liori.echogram, appgoblin.info/apps/com.liori.echogram,
apkpure.com/echogram-player-for-telegram/com.liori.echogram, the r/AndroidTV thread of 2025-03-15,
androidphoria.com (Spanish write-up), COMPETITORS.md in this folder.

## TMPlayer against that list
Has: MKV, audio and subtitle tracks, speed, subtitle styling, Cast and DLNA (now with transcode), resume
and history, auto play next, in-chat search, own overlay controls, watch across devices (Saved Messages).
To check: a loop (repeat one) mode for a single video (the audio queue has Repeat one; the video player
needs a look); QR community link; what the group's topics report that is not public.

## From the group (fill in after reading)
(empty: the group's topics are not readable from here without the user's help)
