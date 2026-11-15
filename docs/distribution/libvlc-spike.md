# libVLC as a second Android engine: spike (PR2, 2026-11-15)

What was checked before any libVLC code went in, how, and what it means for each store. "Measured"
is something read off the real artefact on this machine; "from docs" is a claim not yet checked on
a device and is marked unverified.

## Answer

**Ship in a build switch, not in every APK yet.** libVLC resolves, runs on the Android 6 floor, has
16 KB aligned 64-bit libraries and fits the app's GPL-3.0 licence. It is left out of the default
build and of every F-Droid or store build (`-PtmFdroid=true`), and goes in with `-PtmVlc=true`,
because:

- it adds about 20 to 24 MB per ABI to the download (45 MB to the universal APK, which is 47.8 MB
  today), and
- none of it has run on the 1 GB TV stick or the phone yet (idle CPU, memory, native crashes).

Not blocked. The user decides when a published APK carries it (see "Decisions for the user").

## Coordinates and version (measured)

- `org.videolan.android:libvlc-all:3.7.7` on Maven Central, AAR, published 2026-10-01. Resolved
  with Gradle 9 in a scratch build (`configurations.creating { }` plus
  `vlc("org.videolan.android:libvlc-all:3.7.7@aar")`), 92,576,688 bytes.
  Source: <https://repo1.maven.org/maven2/org/videolan/android/libvlc-all/maven-metadata.xml>.
- 3.7.7 is the newest stable line; `4.0.0-eap29` is newer but an early access build of libVLC 4,
  whose Java API differs, so it is not used.
- The native library inside reports itself as VLC 3.0.25 "Vetinari" (`strings libvlc.so`).
- POM dependencies: `androidx.annotation 1.7.1`, `androidx.legacy:legacy-support-v4 1.0.0` and
  `kotlin-stdlib 2.2.10`, all already in or below what the app has.

## Android floor (measured)

- The AAR's manifest says `minSdkVersion 17`, under the app's 23 (Android 6). No guard needed for
  the library itself.
- `libvlc.so` needs only platform libraries (`libEGL`, `libGLESv2`, `libandroid`, `libmediandk`,
  `liblog`, `libm`, `libdl`, `libc`) plus its own `libc++_shared.so`. `libmediandk` exists from
  API 21. No other dependency of the app ships `libc++_shared.so` (checked against the merged
  native libraries of a debug build), so there is no packaging clash.

## ABIs and size (measured)

The AAR carries `arm64-v8a`, `armeabi-v7a`, `x86` and `x86_64`. The release keeps its two ABIs
(`releaseAbis`), so x86 never ships. Sizes per ABI, raw and deflated (the APK stores native
libraries compressed, `useLegacyPackaging = true`):

| ABI | libvlc.so | libc++_shared.so | libvlcjni.so | Added to the APK (deflated) | Added on the device (extracted) |
| --- | --- | --- | --- | --- | --- |
| arm64-v8a | 45.9 MB | 9.3 MB | 0.1 MB | about 24.2 MB | about 55 MB |
| armeabi-v7a | 39.8 MB | 0.6 MB | 0.1 MB | about 20.5 MB | about 40 MB |
| x86_64 (debug only) | 54.4 MB | 9.0 MB | 0.1 MB | about 25.9 MB | about 63 MB |

Plus 0.3 MB of Java classes and 0.3 MB of assets (Lua scripts, an HRTF file). The universal APK
would grow by about 45 MB; the per-ABI APKs by 20 to 24 MB. Measured on the debug APK, which keeps
all four ABIs: 90.9 MB without libVLC, 190.0 MB with `-PtmVlc=true` (2026-11-15).

## 16 KB page size (measured)

`readelf -lW` on every `.so` in the AAR: all LOAD segments of the 64-bit libraries (`arm64-v8a`,
`x86_64`) are aligned to `0x4000` (16 KB). The 32-bit ones are `0x4000` for `libvlc.so` and
`libvlcjni.so`, `0x1000` for `libc++_shared.so`; Play's requirement is for 64-bit libraries only.
With compressed native libraries the ZIP alignment does not apply. Source:
<https://developer.android.com/guide/practices/page-sizes>.

## Licence (measured, not legal advice)

- The POM declares LGPL-2.1. But `libvlc-all` is one prebuilt `libvlc.so` with VLC's modules and
  contribs linked in, and its strings include x264 (GPL-2.0-or-later), libdvdnav and libdvdread
  (GPL-2.0-or-later) and GPL-3.0-or-later licence banners. So the binary as shipped is treated as
  **GPL-2.0-or-later**, not merely LGPL.
- TMPlayer is GPL-3.0-or-later with an OpenSSL exception (`LICENSE`,
  `LICENSE-OPENSSL-EXCEPTION.md`). GPL-2.0-or-later and LGPL-2.1-or-later code combine with it.
- Obligations when an APK carries it: name it in `THIRD_PARTY_NOTICES.md` with its licence, offer
  the corresponding source of the exact version (VideoLAN's `libvlcjni` tag `3.7.7` and the VLC
  3.0.25 tree it builds), and keep relinking possible: the library stays a separate `.so` loaded by
  name, so a user can drop in a rebuilt one. TMPlayer's own source is public already.
  Sources: <https://code.videolan.org/videolan/libvlcjni>, <https://www.gnu.org/licenses/gpl-faq.html#AllCompatibility>.

## Custom input (measured)

The Java binding has no input callbacks: `org.videolan.libvlc.Media` takes a path or URL
(`String`), an `android.net.Uri`, a `FileDescriptor` or an `AssetFileDescriptor`, nothing else
(`javap` on the AAR's `classes.jar`). `libvlc_media_new_callbacks` is not exposed. So:

- a video not fully downloaded plays from `LoopbackStream` (an `http://127.0.0.1:<port>/<token>/`
  URL, Range requests through the read arbiter), the same server the external player hand-off uses;
- a finished download in the app's own folder plays by file path;
- a finished download in a folder the viewer picked (a `content://` URI) plays by file descriptor.

## HDR, Dolby Vision, passthrough, tunneling (from docs, unverified)

| Feature | libVLC 3 on Android | Source |
| --- | --- | --- |
| HDR10 | Hardware decode through MediaCodec into the Surface, so a TV that shows HDR for ExoPlayer should for VLC too; tone mapping in the OpenGL output when the surface is not direct. Unverified. | VLC 3.0 release notes, <https://www.videolan.org/vlc/releases/3.0.0.html> |
| Dolby Vision | Not supported as Dolby Vision: profile 5 shows wrong colours, profiles 7 and 8 fall back to their HDR10 base layer. The `ff_dovi_*` symbols are FFmpeg's metadata parser, not a renderer. Unverified. | VideoLAN issue tracker, `vlc-android` issues on Dolby Vision |
| Audio passthrough | Yes: `MediaPlayer.setAudioDigitalOutputEnabled` and `canDoPassthrough` (AC-3, E-AC-3, DTS through AudioTrack). Present in the API (measured); behaviour unverified. | `javap` on `MediaPlayer` |
| Tunneled playback | No. The "tunnel" strings in `libvlc.so` are RTSP over HTTP, not MediaCodec tunneling. | `strings libvlc.so` (measured) |
| Frame rate matching | Not in libVLC; TMPlayer's own `FrameRateMatch` works on the Surface and could be applied, not done in this pass. | |

Because of these, the 3D view and anything that needs tunneling or Dolby Vision stays on ExoPlayer
(`EngineSelector` rule 1 already keeps 3D there).

## F-Droid (from docs)

F-Droid builds every app from source and does not accept prebuilt native binaries, whatever their
licence; a library from Maven Central with `.so` files in it is flagged by its scanner. VLC for
Android itself is on F-Droid only because F-Droid builds the whole native tree from source.
TMPlayer's F-Droid build (`-PtmFdroid=true`) therefore leaves libVLC out entirely: the dependency is
not added and the VLC classes are not compiled (`src/novlc`). Building libVLC from source in that
recipe is possible (as `scripts/build-media3ext.sh` does for FFmpeg) but takes an hour and a full
NDK toolchain per build; not done. Sources: <https://f-droid.org/docs/Inclusion_Policy/>,
<https://f-droid.org/docs/Build_Metadata_Reference/#build_scanignore>.

## Google Play (from docs)

- 16 KB: met (above).
- Downloaded code: the library and its Lua scripts are inside the APK and nothing is fetched or
  updated at run time, which the Device and Network Abuse policy allows. VLC's Lua extensions and
  service discovery are not used by TMPlayer. Source:
  <https://support.google.com/googleplay/android-developer/answer/9888379>.
- Data safety: libVLC sends nothing by itself for local files and a 127.0.0.1 stream; no change.
- Size: a store build would be an App Bundle, so each device downloads one ABI (about 20 to 24 MB
  more).

## Risks found, not settled here

- `libvlc.so` exports about 6,100 FFmpeg symbols (`av_*`, `ff_*`) while the app also loads NextLib's
  `libavcodec.so`. Android loads app libraries without `RTLD_GLOBAL`, so each library resolves
  against its own dependencies and the two should not cross. Unverified on a device; the engine
  contract test cannot see it.
- Memory on a 1 GB stick: 46 MB of code mapped (file backed) plus libVLC's own buffers. Not
  measured.
- A native crash in libVLC kills the process with no Java exception: PR6 (the crash marker) covers
  what can be learned from that.

## Decisions for the user

- Whether the GitHub APK carries libVLC (`-PtmVlc=true` in the release workflow), and whether
  only as per-ABI files or in the universal one too. Default until answered: not carried.
- F-Droid: left out (default), or built from source in the recipe.

## Each channel (PR7, 2026-11-15)

| Channel | libVLC in it | Action |
| --- | --- | --- |
| GitHub APK (universal and per-ABI) | No, until the user decides | Build with `-PtmVlc=true` once the device checks pass; link the libVLC source in the release notes; per-ABI files only would keep the universal download as it is |
| F-Droid | No | Leave out (the default). Building libVLC from source in the recipe is the only way in |
| Google Play (set aside, Phase G) | Possible | Met: 16 KB, no downloaded code, Data safety unchanged ([play.md](play.md)); the AAB gives one ABI per device |
| Amazon Appstore | Possible | Same build as Play's store file; most Fire TV sticks run 32-bit `armeabi-v7a`, which libVLC ships (unverified on a stick) |
| Obtainium, Uptodown, XDA | Follows the GitHub APK | Nothing of their own: they take the GitHub release |
| winget, AUR, desktop | Not applicable | The desktop plays with mpv (mediamp), no libVLC |
| Telegram's terms | Checked | The loopback hand-off stays on 127.0.0.1 with a token, nothing leaves the device, a chat that restricts saving is never served ([telegram-terms.md](../agents/telegram-terms.md)) |
