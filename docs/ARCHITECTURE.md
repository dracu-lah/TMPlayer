# How TMPlayer works

[TDLib](https://core.telegram.org/tdlib), Telegram's own client library, handles the account, the
chats and the files, through [tdl-coroutines](https://github.com/g000sha256/tdl-coroutines), which
ships prebuilt natives for every ABI here. TDLib is never compiled in this repository.

The interesting part is a small [Media3](https://developer.android.com/media/media3) `DataSource`
that maps ExoPlayer's reads onto TDLib's offset-based partial downloads. Seek to a new position and
it points TDLib at that byte instead of waiting for the intervening gigabytes. The window
arithmetic lives in one pure class with the awkward cases pinned down by unit tests, because that
is where a streaming player either works or does not.

The player itself is Media3's leanback integration: the standard Android TV transport controls,
not a hand-rolled imitation of them. The same APK draws a Material 3 layout on a phone, so there
is one library, one data layer and two presentations of it.

Audio the device cannot decode in hardware (DTS, TrueHD, E-AC3 on a cheap stick) falls back to
software decoders from NextLib, while video stays on the hardware decoder.

## Constraints the design answers to

The target device is a 1 GB RAM, 8 GB storage Android TV stick. That is why the app caches one
video at a time, why memory during playback is watched, and why the APK is split per ABI while a
universal APK is published for people who should not have to identify their CPU.

## Where to read more

- The design specs under [superpowers/specs/](superpowers/specs/) record the reasoning behind
  individual pieces of work.
- The unit tests around the streaming data source are the executable version of the seek rules.
- Version pins and why they exist: [BUILDING.md](BUILDING.md).
