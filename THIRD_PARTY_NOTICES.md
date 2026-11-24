# Third-party notices

TMPlayer is distributed under the GNU General Public License, version 3 or (at your option) any
later version (GPL-3.0-or-later), with the additional permission for OpenSSL in
[LICENSE-OPENSSL-EXCEPTION.md](LICENSE-OPENSSL-EXCEPTION.md). It also contains or depends on the
components below. Their copyrights remain with their respective authors, and their licence terms
continue to apply.

The Android app (phone and TV), the desktop app (Windows, Linux and macOS) and the iPhone and iPad
app ship different components, so each has its own section. Libraries they share are listed once,
under "Shared".

## Android

### NextLib Media3 Extensions 1.11.1-0.16.0

- Author: Anil Kumar Beesetti and contributors
- Licence: GNU General Public License version 3
- Source: <https://github.com/anilbeesetti/nextlib/tree/v1.11.1-0.16.0>
- Exact commit: `2dc77cd2d9352bc308aa26782f9c77a320df510b`

The app's AAR is built from that source by `scripts/build-media3ext.sh`, with TMPlayer's patch
`scripts/media3ext/tmplayer-decoders.patch` applied: it adds more of FFmpeg's LGPL decoders to NextLib's `ffmpeg/setup.sh`: video (MPEG-4 Part 2,
Microsoft MPEG-4, VC-1, Motion JPEG, MPEG-1, WMV1 to WMV3, H.263, Flash video, Theora, DV, Sorenson
SVQ1 and SVQ3, VP6, RealVideo 1 to 4, Cinepak, ProRes, FFV1, HuffYUV, Ut Video), audio (WMA
family, MPEG audio layers 1 and 2, Cook, RealAudio, QCELP, GSM, SIPR, G.723.1, Siren, ATRAC3, Speex,
WavPack, TTA, Monkey's Audio, Nellymoser, and the PCM and ADPCM families) and maps Media3's formats to them.
FFmpeg stays LGPL v3 with no GPL and nothing non-free: 8 ADPCM decoders and AHX were skipped
because FFmpeg builds them only with `--enable-gpl`. The patch also adds a light video mode
to NextLib's JNI and Java glue (dav1d's frame threads, film grain left out and YV12 frames to the
window, for the formats the app asks it for). The native codec libraries
are built by that `ffmpeg/setup.sh` from these upstream sources:

- FFmpeg 9.0.1 (libavcodec, libavutil, libswresample, libswscale): LGPL-3.0-or-later for the
  configuration used by NextLib and kept by the patch (`--enable-version3`, without
  `--enable-gpl` or non-free components): <https://ffmpeg.org/releases/ffmpeg-9.0.1.tar.gz>
- dav1d 1.5.4 (the AV1 decoder, linked statically into libavcodec): BSD-2-Clause:
  <https://github.com/videolan/dav1d/tree/1.5.4>
- Mbed TLS 3.6.7: Apache-2.0 selected from its dual licence:
  <https://github.com/Mbed-TLS/mbedtls/tree/mbedtls-3.6.7>. NextLib builds it for FFmpeg's TLS
  protocol in libavformat, which the AAR does not ship, so none of its code is in the app; its
  source is in the archive because the build uses it.

The VP8 and VP9 decoders are FFmpeg's own; libvpx is no longer part of the build.

The corresponding source is one archive, `nextlib-corresponding-source-1.11.1-0.16.0-tm4.tar.gz`,
linked as "nextlib source" from the notes of every TMPlayer release that carries this revision of
the patched build (1.28.x carried the first, `-tm1`, without the light mode, and 1.28.2 the second, `-tm2`, without the wider decoder set). It holds the exact NextLib 1.11.1-0.16.0 source and build scripts, TMPlayer's patch and
build script under `tmplayer/`, and the three upstream source archives above;
`scripts/build-nextlib-source.sh` in this repository rebuilds it from permanent addresses and
SHA-256 sums. Releases up to 1.27.x shipped NextLib's own AAR from Maven Central; their source is
`nextlib-corresponding-source-1.11.1-0.16.0.tar.gz`, attached to 1.27.0.

### TDLib with OpenSSL 1.1.1w

tdl-coroutines' Android artifact (see "Shared") bundles TDLib 1.8.67 native libraries
(`libtdjsonjava.so`), which link OpenSSL 1.1.1w statically.

- OpenSSL 1.1.1w: OpenSSL License and the original SSLeay License (dual licence):
  <https://www.openssl.org/source/old/1.1.1/openssl-1.1.1w.tar.gz>

As the OpenSSL License requires: this product includes software developed by the OpenSSL Project
for use in the OpenSSL Toolkit (<https://www.openssl.org/>). This product includes cryptographic
software written by Eric Young (eay@cryptsoft.com).

Because the OpenSSL License is not compatible with the GPL, TMPlayer's own code carries an
additional permission to be combined with it; see
[LICENSE-OPENSSL-EXCEPTION.md](LICENSE-OPENSSL-EXCEPTION.md).

### libVLC for Android 3.7.7 (only in a build made with `-PtmVlc=true`)

The second playback engine (PR2). It is not in the default build, the F-Droid build or any store
build; an APK that carries it says so in its release notes.

- Author: VideoLAN and the VLC authors
- Artifact: `org.videolan.android:libvlc-all:3.7.7` from Maven Central, unmodified
  (libVLC 3.0.25, `libvlc.so`, `libvlcjni.so`, `libc++_shared.so` and the Java binding)
- Licence: the binding and libVLC are LGPL-2.1-or-later. The prebuilt `libvlc.so` links in
  modules and libraries under GPL-2.0-or-later (among them x264, libdvdnav and libdvdread) and
  others under LGPL, BSD and MIT terms, so the library as shipped is distributed under
  GPL-2.0-or-later, which TMPlayer's GPL-3.0-or-later is compatible with.
- Source: <https://code.videolan.org/videolan/libvlcjni/-/tree/3.7.7> and the VLC 3.0.25 tree it
  builds, <https://code.videolan.org/videolan/vlc/-/tree/3.0.25>; the contribs' versions are in that
  tree's `contrib/src/*/rules.mak`.

Relinking: TMPlayer loads libVLC as separate shared libraries by name and calls it only through
its public Java binding, so a modified libVLC built from the source above can replace the three
`.so` files in the APK (re-signed with your own key) without any change to TMPlayer. A release
that carries libVLC links its corresponding source archive from its notes, as for NextLib.

### Android libraries

- AndroidX Core, Activity, Compose, TV Material, Lifecycle, Media3 1.11.1, Leanback, Fragment, Biometric and
  DataStore: Apache License 2.0: <https://github.com/androidx/androidx>
- Sentry Android SDK 8.61.0 (crash reports, see the privacy page): MIT:
  <https://github.com/getsentry/sentry-java>
- AndroidX MediaRouter 1.8.1 (casting to a TV): Apache License 2.0:
  <https://github.com/androidx/androidx>
- Google Play services Cast framework 22.3.1 (casting to a Chromecast or Google TV), in the APKs
  published on GitHub only: the F-Droid build is made without it and has no Cast button. Google's
  own terms: <https://developers.google.com/terms>, with the Android Software Development Kit
  License for the Play services libraries: <https://developer.android.com/studio/terms>
- Google Play services Auth API Phone 18.1.0 (SMS User Consent, which offers the sign in code from
  an SMS after the viewer allows it), in the APKs published on GitHub only, like the Cast
  framework above, and under the same Google terms
- Android desugared library (`desugar_jdk_libs_nio`) 2.1.5, which brings newer Java APIs to
  Android 6 and 7: GNU General Public License version 2 with the Classpath Exception:
  <https://github.com/google/desugar_jdk_libs>

## Desktop (Windows, Linux and macOS)

### mediamp 0.5.2 and its mpv runtime

- Author: Open Ani and contributors (`org.openani.mediamp`)
- Licence: Apache License 2.0
- Source: <https://github.com/open-ani/mediamp/tree/v0.5.2>

mediamp's runtime jars for Windows x64 and Linux x64 bundle libmpv, FFmpeg and the shared libraries
they need:

| Component | Windows x64 | Linux x64 | Licence |
|---|---|---|---|
| mpv (libmpv) | 0.41.0 | 0.41.0 | Windows: LGPL-2.1-or-later. Linux: GPL-2.0-or-later (built with mpv's `gpl` feature) |
| FFmpeg | 8.0.1 (release/8.0, commit `449453a9`) | same | LGPL-2.1-or-later |
| dav1d (static, inside FFmpeg) | 1.5.4 | 1.5.4 | BSD-2-Clause |
| libass | 0.17.5 | 0.17.1 | ISC |
| libplacebo | 7.360.1 | 6.338.2 | LGPL-2.1-or-later |
| Little CMS | 2.19.1 | 2.14 | MIT |
| FreeType | 2.15.0 | 2.13.2 | FTL or GPL-2.0-or-later |
| FriBidi | 1.0.17 | 1.0.13 | LGPL-2.1-or-later |
| HarfBuzz | 14.6.0 | 8.3.0 | MIT |
| Graphite2 | 1.3.15 | 1.3.14 | LGPL-2.1-or-later |
| libunibreak | 7.0 | 5.1 | Zlib |
| libpng | 1.6.59 | 1.6.43 | libpng-2.0 |
| zlib | 1.3.2 | 1.3 | Zlib |
| bzip2 | 1.0.8 | 1.0.8 | bzip2-1.0.6 |
| Brotli | 1.2.0 | 1.1.0 | MIT |
| GLib | 2.90.1 | 2.80.0 | LGPL-2.1-or-later |
| PCRE2 | 10.49 | 10.42 | BSD-3-Clause |
| OpenSSL | 3.6.5 | 3.0.13 | Apache-2.0 |
| shaderc, with glslang 16.3.0 and SPIRV-Tools 1.4.363.0 inside | 2026.4 | not shipped | Apache-2.0; glslang BSD-3-Clause and others |
| SPIRV-Cross | 1.4.363.0 | not shipped | Apache-2.0 |
| Vulkan loader | 1.4.363.0 | not shipped | Apache-2.0 |
| libdovi | 3.4.0 | not shipped | MIT |
| Fontconfig | 2.18.3 | from the system | HPND-style Fontconfig licence |
| Expat | 2.9.0 | not shipped | MIT |
| GNU libintl (gettext) | 1.0 | not shipped | LGPL-2.1-or-later |
| GNU libiconv | 1.19 | not shipped | LGPL-2.1-or-later |
| GCC runtime (libgcc_s, libstdc++) | 16.2.0 | from the system | GPL-3.0-or-later WITH GCC-exception-3.1 |
| winpthreads | 14.0.0 | not shipped | MIT and BSD-3-Clause-Clear |
| libva | not shipped | 2.20.0 | MIT |
| libxcb (dri3), libXext, libXfixes, libXpresent, libXrandr, libXrender, libXss | not shipped | Ubuntu 24.04 versions | MIT |

The Windows libraries are MSYS2 UCRT64 packages and the Linux ones Ubuntu 24.04 packages, except
that the Linux build runs on glibc 2.35 (Ubuntu 22.04 and newer) and so carries rebuilt TDLib and
mpv libraries: TDLib, libmpv, libass, libcrypto, libglib-2.0, libharfbuzz, libplacebo, libva and
mediamp's libmediampv, built from the same sources on Ubuntu 22.04. Their recipes are under
`natives/` and in `scripts/build-tdlib-linux.sh` and `scripts/build-mpv-linux-2-35.sh`
(`natives/mpv-linux/README.md` has the corresponding source statement). mpv,
FFmpeg and dav1d are built from source by mediamp. The corresponding source of every row, with the
exact package versions, MSYS2's and Ubuntu's patches and mediamp's build scripts, is one archive,
`mediamp-corresponding-source-0.5.2.tar.gz`, linked as "mediamp source" from every TMPlayer release's
notes. `scripts/build-mediamp-source.sh` in this repository rebuilds it from permanent addresses
and SHA-256 sums, and its README lists each file. It stays the right archive for as long as
TMPlayer bundles mediamp 0.5.2. Earlier releases bundled mediamp 0.5.0 and link its archives on
1.24.0 (Windows and Linux) and 1.26.0 (macOS).

The macOS Apple Silicon build (a preview, from 1.26.0) bundles mediamp's macos-arm64 runtime
instead. mpv, FFmpeg and dav1d are the same as above, built without mpv's `gpl` feature (so mpv is
LGPL-2.1-or-later there); the other libraries are Homebrew bottles, for macOS 15 or, where marked,
macOS 26, each matched to the shipped file by its Mach-O UUID and code:

| Component | macOS arm64 | Licence |
|---|---|---|
| mpv (libmpv), FFmpeg, dav1d | 0.41.0, 8.0.1, 1.5.4 | LGPL-2.1-or-later, LGPL-2.1-or-later, BSD-2-Clause |
| libass | 0.17.4 | ISC |
| libplacebo | 7.351.0 | LGPL-2.1-or-later |
| Little CMS | 2.17 | MIT |
| FreeType (macOS 26 bottle) | 2.14.3 | FTL or GPL-2.0-or-later |
| FriBidi | 1.0.16 | LGPL-2.1-or-later |
| HarfBuzz (macOS 26 bottle) | 14.2.1 | MIT |
| Graphite2 (macOS 26 bottle) | 1.3.15 | LGPL-2.1-or-later |
| libunibreak | 6.1 | Zlib |
| libpng (macOS 26 bottle) | 1.6.58 | libpng-2.0 |
| GLib (macOS 26 bottle) | 2.88.2 | LGPL-2.1-or-later |
| PCRE2 (macOS 26 bottle) | 10.47 | BSD-3-Clause |
| GNU libintl, from gettext (macOS 26 bottle) | 1.0 | LGPL-2.1-or-later |
| shaderc, with glslang 15.3.0 and SPIRV-Tools inside | 2025.3 | Apache-2.0; glslang BSD-3-Clause and others |
| Vulkan loader | 1.4.326 | Apache-2.0 |

Their corresponding source, with Homebrew's formulas and patches, is a second archive,
`mediamp-corresponding-source-0.5.2-macos.tar.gz` (`scripts/build-mediamp-source.sh --macos`),
linked as "mediamp macOS source" from the release notes.

### TDLib on the desktop

tdl-coroutines' JVM artifact (see "Shared") bundles TDLib 1.8.67 for Windows, Linux and macOS. On Windows
it ships OpenSSL 3.6.4 (`libcrypto-3-x64.dll`, `libssl-3-x64.dll`, Apache-2.0) and zlib (`z.dll`,
Zlib) beside it; on Linux `libtdjsonjava.so` links the system's OpenSSL 3 (`libssl.so.3`, `libcrypto.so.3`, Apache-2.0) and zlib dynamically, and the bundled mpv runtime carries its own `libcrypto.so.3` (OpenSSL 3.0.13, Apache-2.0). On macOS
it links OpenSSL 3 dynamically, so the macOS build carries `libcrypto.3.dylib` and `libssl.3.dylib`
(Apache-2.0) in the app's resources: Homebrew's `openssl@3` bottle as installed on the Mac that
built that release, source at <https://github.com/openssl/openssl> and in Homebrew's formula.

### Java runtime

The Windows installer, the Linux tarball and the AppImage carry a trimmed Eclipse Temurin 21
runtime (OpenJDK): GPL-2.0 with the Classpath Exception: <https://adoptium.net/>,
source at <https://github.com/adoptium/jdk21u>.

### Desktop libraries

- Compose Multiplatform 1.12.2 and Material 3 for Compose Multiplatform: Apache License 2.0:
  <https://github.com/JetBrains/compose-multiplatform>
- Skiko (Skia for Kotlin), which Compose for desktop draws with, including the Skia library it
  bundles: Apache License 2.0 (Skiko) and BSD-3-Clause (Skia): <https://github.com/JetBrains/skiko>
- JNA 5.19.1 and JNA Platform: Apache License 2.0 (selected from its LGPL-2.1 or Apache-2.0 dual
  licence): <https://github.com/java-native-access/jna>
- dbus-java 5.2.2 (core and the native unix socket transport): MIT:
  <https://github.com/hypfvieh/dbus-java>
- FileKit 0.16.0: MIT: <https://github.com/vinceglb/FileKit>
- AppDirs 1.4.0: Apache License 2.0: <https://github.com/harawata/appdirs>
- SLF4J 2.0.20 (no-operation binding): MIT: <https://github.com/qos-ch/slf4j>
- Sentry Java SDK 8.61.0 (crash reports, off until turned on, see the privacy page): MIT:
  <https://github.com/getsentry/sentry-java>

### In-app trailers on the desktop: JCEF and Chromium

Bundled (the Java side only):

- jcefmaven 146.0.10 (JCEF's loader and installer): Apache License 2.0:
  <https://github.com/jcefmaven/jcefmaven>
- JCEF, the Java Chromium Embedded Framework (jcef-api, commit d3de827): BSD-3-Clause:
  <https://bitbucket.org/chromiumembedded/java-cef>
- Apache Commons Compress 1.28.0, Commons IO 2.20.0, Commons Codec 1.19.0 and Commons Lang 3.18.0:
  Apache License 2.0: <https://commons.apache.org/>
- Gson 2.11.0 and Error Prone annotations 2.27.0: Apache License 2.0: <https://github.com/google/gson>

Downloaded only when the viewer agrees, on their first trailer, from Maven Central
(`me.friwi:jcef-natives-<system>`, built by <https://github.com/jcefmaven/jcefbuild>) and never
shipped in an installer: the Chromium Embedded Framework 146.0.10 (BSD-3-Clause,
<https://bitbucket.org/chromiumembedded/cef>) and Chromium 146.0.7680.179 (BSD-3-Clause, with the
third-party licences listed in the `LICENSE.txt` and `README.txt` inside the download and at
<https://source.chromium.org/chromium/chromium/src/+/main:LICENSE>). The trailer itself is
YouTube's embedded player, loaded from youtube-nocookie.com only after the viewer presses a trailer.

### Fonts on the desktop

The desktop app bundles subsets of Noto Sans SC, Noto Sans TC, Noto Sans JP, Noto Sans KR, Noto Sans
Devanagari, Noto Sans Bengali, Noto Sans Malayalam and Noto Sans Arabic UI, so Chinese, Japanese,
Korean, Hindi, Bengali, Malayalam, Arabic and Persian text draws where the system has no font for
it. SIL Open Font License 1.1. Copyright 2014-2021 Adobe (http://www.adobe.com/), with Reserved
Font Name 'Source' (the CJK fonts), and The Noto Project Authors (the others):
<https://github.com/notofonts>. The subsets keep only the characters TMPlayer uses and the common
characters of each script; nothing else in them is changed. The full licence text with each
copyright line travels beside the fonts as `fonts/OFL.txt` inside the app and as `OFL-Noto.txt`
next to these notices in every desktop package.

### Fonts for subtitles

So a subtitle in any script draws where the system has no font for it, the players bundle Noto Sans
cut to one script each by `ui/fonts/subtitle-fonts.py` (the Regular instance, only that script's
Unicode blocks; nothing else changed). SIL Open Font License 1.1, Copyright 2022-2025 The Noto
Project Authors: <https://github.com/notofonts>. The licence text with each font's copyright line is
`OFL.txt` beside the fonts.

- Desktop (`subfonts/` inside the app): Tamil, Telugu, Kannada, Sinhala, Gujarati, Gurmukhi, Oriya,
  Thai, Lao, Myanmar, Khmer, Ethiopic, Georgian, Armenian and Hebrew, given to mpv on Linux next to
  the subsets above.
- Android (`assets/fonts/`): Malayalam, used only on a device whose system has no Malayalam font.
- Web player: all of the above plus Malayalam, Devanagari, Bengali and Arabic, as WOFF2 files a
  browser fetches only for a line that needs them (listed in its `/licenses.txt` too).

## Web player (webapp/)

The SolidJS web player (web.tmplayer.org) writes its own complete list at build
time, served as `/licenses.txt` and linked from its Settings (`webapp/notices.config.ts`): every
npm package in its built files with the package's licence text, all MIT, Apache-2.0, BSD-3-Clause
or MPL-2.0 (Mediabunny and its coders, whose source ships in each npm package), plus:

- FFmpeg (libavcodec, libavutil): LGPL-2.1-or-later, inside Mediabunny's AC-3 and E-AC-3, DTS and
  AAC coders (configured with only those coders, no GPL parts). They are separate files loaded
  only when a video needs them. Source: <https://ffmpeg.org/download.html>; how they are built:
  <https://github.com/Vanilagy/mediabunny/tree/main/packages>.
- FFmpeg 9.0 (libavformat, libavcodec, libavutil, libswresample): LGPL-2.1-or-later, in TMPlayer's
  own two libav.js 6.10.9.0 builds (0BSD-style wrapper, with emfiberthreads 1.3, 0BSD): the engine
  build reads AVI, FLV, ASF/WMV, RealMedia and MPEG-PS and decodes the pictures and sound the
  browser cannot, the sound build decodes AAC, MPEG audio, Opus, Vorbis and FLAC where the browser
  cannot. `webapp/public/libav/`, loaded only when a video needs them, no GPL parts; every module
  and its licence is in `webapp/licenses/libav-modules.txt`. Built by `webapp/libav/build.sh`;
  every deploy serves the exact sources at `/libav/sources/` (`webapp/libav/sources.sh`).
- Kotlin standard library: Apache-2.0, inside the app's rules compiled from `:core`.

## iOS and iPadOS

The iOS and iPadOS port started from TMPlayer for Apple by Pietro Giuseppe D'Amico
(github.com/Killua99x/TMPlayer-for-apple, GPL-3.0). Its code in `iosApp/`, `ios/`, the `ios*` source
sets of `core/` and `ui/` and `gradle/ios-*.gradle.kts`, and its changes to the shared code, are
used under the GPL-3.0 and keep their notices (docs/IOS.md).

The iPhone and iPad app, built from `iosApp/` and `:ios`, is distributed as an unsigned IPA from
this repository's releases tagged `apple-v*`. Each release page links the source of that version and
of MPVKit. The components below were read from the resolved packages and the built app
(2026-11-13, by TMPlayer for Apple).

- MPVKit 1.0.0 (<https://github.com/mpvkit/MPVKit/tree/1.0.0>), its `MPVKit` product only, never
  `MPVKit-GPL`. It bundles libmpv and FFmpeg built without their GPL parts, and the libraries they
  need:

| Component | Version | Licence |
|---|---|---|
| mpv (libmpv) | 0.41.0 | LGPL-2.1-or-later (mpv's LGPL build) |
| FFmpeg (libavcodec, libavformat, libavfilter, libavutil, libavdevice, libswresample, libswscale) | libavcodec 62.28.102 | LGPL-3.0-or-later (as built) |
| libplacebo | 7.360.1 | LGPL-2.1-or-later |
| GnuTLS | 3.8.11 | LGPL-2.1-or-later |
| Nettle and Hogweed | as built by MPVKit 1.0.0 | LGPL-3.0-or-later or GPL-2.0-or-later |
| GMP | as built by MPVKit 1.0.0 | LGPL-3.0-or-later or GPL-2.0-or-later |
| OpenSSL (libssl, libcrypto) | 3.3.5 | Apache-2.0 |
| libbluray | as built by MPVKit 1.0.0 | LGPL-2.1-or-later |
| uchardet | as built by MPVKit 1.0.0 | MPL-1.1, GPL-2.0-or-later or LGPL-2.1-or-later |
| FriBidi | as built by MPVKit 1.0.0 | LGPL-2.1-or-later |
| libass | as built by MPVKit 1.0.0 | ISC |
| FreeType | as built by MPVKit 1.0.0 | FTL or GPL-2.0-or-later, used under the FTL |
| HarfBuzz | as built by MPVKit 1.0.0 | MIT |
| libunibreak | as built by MPVKit 1.0.0 | Zlib |
| Little CMS (lcms2) | as built by MPVKit 1.0.0 | MIT |
| dav1d | as built by MPVKit 1.0.0 | BSD-2-Clause |
| uavs3d | as built by MPVKit 1.0.0 | BSD-3-Clause |
| libdovi (dovi_tool) | as built by MPVKit 1.0.0 | MIT |
| LuaJIT | 2.1 | MIT |
| shaderc | as built by MPVKit 1.0.0 | Apache-2.0 |
| MoltenVK | as built by MPVKit 1.0.0 | Apache-2.0 |

uavs3d: Copyright (c) 2018-2022 Peking University Shenzhen Graduate School, Peng Cheng
Laboratory, and Guangdong Bohua UHD Innovation Corporation. All rights reserved. Its licence
terms are at <https://github.com/uavs3/uavs3d/blob/master/LICENSE>.

The LGPL libraries are dynamic frameworks inside the app bundle (its Frameworks folder), so each
can be replaced by a build of one's own. The source of each is linked from MPVKit's page above, and
the app's own source, with what it takes to build and sign it, is this repository (docs/IOS.md).

- TDLib on iOS, built into the app through tdl-coroutines (see "Shared"), links OpenSSL 3.1.5
  (Apache-2.0) and zlib 1.3.0.1 (Zlib).
- Compose Multiplatform 1.12.2 for iOS, with Skiko and the Skia library it bundles: Apache License
  2.0 (Compose and Skiko) and BSD-3-Clause (Skia): <https://github.com/JetBrains/compose-multiplatform>
  and <https://github.com/JetBrains/skiko>.
- The app icon is TMPlayer's own, made from the Android launcher icon by `iosApp/make-app-icon.py`.

## Shared

### tdl-coroutines 15.0.0 and TDLib 1.8.67

- Author: Georgii Ippolitov and contributors
- Licence: Apache License 2.0
- Source: <https://github.com/g000sha256/tdl-coroutines/tree/15.0.0>
- Exact commit: `1e21103b4204a9dae060b8f203a4fcc1388cdb3d`
- TDLib 1.8.67: Boost Software License 1.0:
  <https://github.com/tdlib/td/tree/bc9c263e2bfee06aaab41e82db51a103376030bc>

The exact tdl-coroutines and TDLib source revisions are linked here for reproducibility. Both are
under permissive licences. The OpenSSL each platform links is listed in its own section above.

### Kotlin and other libraries

- Kotlin standard library, kotlinx.coroutines and kotlinx.serialization: Apache License 2.0:
  <https://github.com/JetBrains/kotlin> and <https://github.com/Kotlin/kotlinx.coroutines>
- ZXing Core 3.5.4: Apache License 2.0: <https://github.com/zxing/zxing/tree/zxing-3.5.4>
- JetBrains annotations: Apache License 2.0: <https://github.com/JetBrains/java-annotations>

The Gradle wrapper scripts are provided under Apache License 2.0. Test-only dependencies are not
packaged in any build.

## Data from online services

Nothing below ships in a build: it is looked up while online metadata is on, cached on the device
and credited in About.

- TMDB (<https://www.themoviedb.org>): posters, overviews, cast and the TMDB score. This product
  uses the TMDB API but is not endorsed or certified by TMDB.
- TVmaze (<https://www.tvmaze.com>): shows, episodes and the TVmaze score, CC BY-SA 4.0.
- AniList (<https://anilist.co>): anime and the AniList score.
- OMDb, the Open Movie Database (<https://www.omdbapi.com>): the IMDb, Rotten Tomatoes and
  Metacritic scores on a detail page, asked by IMDb id with the build's key or the viewer's own.
  OMDb's data is CC BY-NC 4.0 (<https://creativecommons.org/licenses/by-nc/4.0/>); TMPlayer is
  free and non-commercial.
- Wikidata (<https://www.wikidata.org>): the Rotten Tomatoes and Metacritic scores on a detail
  page when OMDb cannot answer, asked by IMDb id on the Wikidata Query Service. Wikidata's data
  is CC0 1.0 (<https://creativecommons.org/publicdomain/zero/1.0/>).

## Visual assets

- Poppins fonts: SIL Open Font License 1.1. The full text is in `site/public/fonts/OFL-Poppins.txt`.
- The player mark was adapted and recoloured from a player icon on SVG Repo
  (<https://www.svgrepo.com>), used under the licence that icon is published with there. SVG Repo
  is credited in the website footer and the README.
- Hugeicons free icons (`@hugeicons/core-free-icons`, Stroke Rounded), MIT License, Copyright (c)
  2025 Hugeicons (<https://hugeicons.com>, <https://www.npmjs.com/package/@hugeicons/core-free-icons>):
  every icon in the app (`TmIcons.kt` and the player and notification `ic_*.xml` drawables),
  converted to Compose and Android vectors. Permission is hereby granted, free of charge, to any
  person obtaining a copy of this software and associated documentation files (the "Software"),
  to deal in the Software without restriction, including without limitation the rights to use,
  copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
  permit persons to whom the Software is furnished to do so, subject to the following conditions:
  The above copyright notice and this permission notice shall be included in all copies or
  substantial portions of the Software. THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY
  KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
  FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS
  BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
  OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
  IN THE SOFTWARE. The phone's morphing play and pause glyph (`PlayPauseIcon.kt`) is drawn in code
  by TMPlayer.
- The TMDB logo in About is TMDB's own (<https://www.themoviedb.org/about/logos-attribution>),
  shown with the notice their terms ask for: this product uses the TMDB API but is not endorsed or
  certified by TMDB.
- The screenshot fixture's (promo build only, never released) Big Buck Bunny poster and landscape
  (`promo_bbb_*.webp`) are (c) Blender Foundation, peach.blender.org, CC BY 3.0
  (<https://creativecommons.org/licenses/by/3.0/>), via Wikimedia Commons, scaled down.
- The screenshot fixture's demo artwork (promo build only, never released; `promo_*_poster`,
  `_backdrop` and `_e1` to `_e4` WebPs) is cut from photos on Unsplash, used under the Unsplash
  License (<https://unsplash.com/license>): Atul Vinayak, NASA, Fabio Sasso, Carolina Garcia
  Tavizon, Athanasios Papazacharias and Jason Mavrommatis. Each photo's page and photographer are
  listed in `docs/promo-art/CREDITS.md`.

## Licence texts

- GPL-3.0: <https://www.gnu.org/licenses/gpl-3.0.html> (also the `LICENSE` file)
- GPL-2.0: <https://www.gnu.org/licenses/old-licenses/gpl-2.0.html>
- LGPL-3.0: <https://www.gnu.org/licenses/lgpl-3.0.html>
- LGPL-2.1: <https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html>
- GCC Runtime Library Exception 3.1: <https://www.gnu.org/licenses/gcc-exception-3.1.html>
- Classpath Exception: <https://openjdk.org/legal/gplv2+ce.html>
- Apache-2.0: <https://www.apache.org/licenses/LICENSE-2.0>
- MIT: <https://opensource.org/license/mit>
- BSD-2-Clause and BSD-3-Clause: <https://opensource.org/license/bsd-2-clause> and
  <https://opensource.org/license/bsd-3-clause>
- ISC: <https://opensource.org/license/isc-license-txt>
- Zlib: <https://opensource.org/license/zlib>
- MPL-1.1: <https://www.mozilla.org/en-US/MPL/1.1/>
- FreeType Project License: <https://gitlab.freedesktop.org/freetype/freetype/-/blob/master/docs/FTL.TXT>
- libpng: <http://www.libpng.org/pub/png/src/libpng-LICENSE.txt>
- OpenSSL 1.1.1 (OpenSSL and SSLeay): <https://www.openssl.org/source/license-openssl-ssleay.txt>
- Boost-1.0: <https://www.boost.org/LICENSE_1_0.txt>
- CC BY 3.0: <https://creativecommons.org/licenses/by/3.0/legalcode>
- CC BY 4.0: <https://creativecommons.org/licenses/by/4.0/legalcode>
- SIL OFL-1.1: <https://openfontlicense.org>
