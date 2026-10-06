# Third-party notices

TMPlayer is distributed under the GNU General Public License, version 3 or (at your option) any
later version (GPL-3.0-or-later), with the additional permission for OpenSSL in
[LICENSE-OPENSSL-EXCEPTION.md](LICENSE-OPENSSL-EXCEPTION.md). It also contains or depends on the
components below. Their copyrights remain with their respective authors, and their licence terms
continue to apply.

The Android app (phone and TV) and the desktop app (Windows and Linux) ship different components,
so each has its own section. Libraries both use are listed once, under "Shared".

## Android

### NextLib Media3 Extensions 1.10.1-0.13.0

- Author: Anil Kumar Beesetti and contributors
- Licence: GNU General Public License version 3
- Source: <https://github.com/anilbeesetti/nextlib/tree/v1.10.1-0.13.0>
- Exact commit: `773c841f86d9775abd0fe18c1fd0f84e00e355c8`

The released AAR contains native codec libraries built by NextLib's `ffmpeg/setup.sh` from these
upstream sources:

- FFmpeg 6.0: LGPL-3.0-or-later for the configuration used by NextLib (`--enable-version3`,
  without `--enable-gpl` or non-free components): <https://ffmpeg.org/releases/ffmpeg-6.0.tar.gz>
- libvpx 1.13.0: BSD-3-Clause: <https://github.com/webmproject/libvpx/tree/v1.13.0>
- Mbed TLS 3.4.1: Apache-2.0 selected from its dual licence:
  <https://github.com/Mbed-TLS/mbedtls/tree/v3.4.1>

The corresponding source is one archive, linked from every TMPlayer release's notes:
<https://github.com/dracu-lah/TMPlayer/releases/download/v1.21.0/nextlib-corresponding-source-1.21.0.tar.gz>.
It holds the exact NextLib 1.10.1-0.13.0 source and build scripts plus the three upstream source
archives above, and stays the right archive for as long as TMPlayer bundles that version.

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

### Android libraries

- AndroidX Core, Activity, Compose, TV Material, Lifecycle, Media3 1.10.1, Leanback, Fragment and
  DataStore: Apache License 2.0: <https://github.com/androidx/androidx>
- Sentry Android SDK 8.16.0 (crash reports, see the privacy page): MIT:
  <https://github.com/getsentry/sentry-java>

## Desktop (Windows and Linux)

### mediamp 0.5.0 and its mpv runtime

- Author: Open Ani and contributors (`org.openani.mediamp`)
- Licence: Apache License 2.0
- Source: <https://github.com/open-ani/mediamp/tree/v0.5.0>

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
| FreeType | 2.14.3 | 2.13.2 | FTL or GPL-2.0-or-later |
| FriBidi | 1.0.16 | 1.0.13 | LGPL-2.1-or-later |
| HarfBuzz | 14.4.0 | 8.3.0 | MIT |
| Graphite2 | 1.3.15 | 1.3.14 | LGPL-2.1-or-later |
| libunibreak | 7.0 | 5.1 | Zlib |
| libpng | 1.6.58 | 1.6.43 | libpng-2.0 |
| zlib | 1.3.2 | 1.3 | Zlib |
| bzip2 | 1.0.8 | 1.0.8 | bzip2-1.0.6 |
| Brotli | 1.2.0 | 1.1.0 | MIT |
| GLib | 2.90.0 | 2.80.0 | LGPL-2.1-or-later |
| PCRE2 | 10.48 | 10.42 | BSD-3-Clause |
| OpenSSL | 3.6.4 | 3.0.13 | Apache-2.0 |
| shaderc, with glslang 16.3.0 and SPIRV-Tools 1.4.357.0 inside | 2026.3 | not shipped | Apache-2.0; glslang BSD-3-Clause and others |
| SPIRV-Cross | 1.4.357.0 | not shipped | Apache-2.0 |
| Vulkan loader | 1.4.357.0 | not shipped | Apache-2.0 |
| libdovi | 3.4.0 | not shipped | MIT |
| Fontconfig | 2.18.3 | from the system | HPND-style Fontconfig licence |
| Expat | 2.8.4 | not shipped | MIT |
| GNU libintl (gettext) | 1.0 | not shipped | LGPL-2.1-or-later |
| GNU libiconv | 1.19 | not shipped | LGPL-2.1-or-later |
| GCC runtime (libgcc_s, libstdc++) | 16.2.0 | from the system | GPL-3.0-or-later WITH GCC-exception-3.1 |
| winpthreads | 14.0.0 | not shipped | MIT and BSD-3-Clause-Clear |
| libva | not shipped | 2.20.0 | MIT |
| libxcb (dri3), libXext, libXfixes, libXpresent, libXrandr, libXrender, libXss | not shipped | Ubuntu 24.04 versions | MIT |

The Windows libraries are MSYS2 UCRT64 packages and the Linux ones Ubuntu 24.04 packages; mpv,
FFmpeg and dav1d are built from source by mediamp. The corresponding source of every row, with the
exact package versions, MSYS2's and Ubuntu's patches and mediamp's build scripts, is one archive,
`mediamp-corresponding-source-0.5.0.tar.gz`, linked as "mediamp source" from every TMPlayer release's
notes. `scripts/build-mediamp-source.sh` in this repository rebuilds it from permanent addresses
and SHA-256 sums, and its README lists each file. It stays the right archive for as long as
TMPlayer bundles mediamp 0.5.0.

### TDLib on the desktop

tdl-coroutines' JVM artifact (see "Shared") bundles TDLib 1.8.67 for Windows and Linux. On Windows
it ships OpenSSL 3.6.4 (`libcrypto-3-x64.dll`, `libssl-3-x64.dll`, Apache-2.0) and zlib (`z.dll`,
Zlib) beside it; on Linux OpenSSL 3.0.13 (Apache-2.0) is linked into `libtdjsonjava.so`.

### Java runtime

The Windows installer, the Linux tarball and the AppImage carry a trimmed Eclipse Temurin 21
runtime (OpenJDK): GPL-2.0 with the Classpath Exception: <https://adoptium.net/>,
source at <https://github.com/adoptium/jdk21u>.

### Desktop libraries

- Compose Multiplatform 1.12.1 and Material 3 for Compose Multiplatform: Apache License 2.0:
  <https://github.com/JetBrains/compose-multiplatform>
- Skiko (Skia for Kotlin), which Compose for desktop draws with, including the Skia library it
  bundles: Apache License 2.0 (Skiko) and BSD-3-Clause (Skia): <https://github.com/JetBrains/skiko>
- JNA 5.19.1 and JNA Platform: Apache License 2.0 (selected from its LGPL-2.1 or Apache-2.0 dual
  licence): <https://github.com/java-native-access/jna>
- dbus-java 5.2.2 (core and the native unix socket transport): MIT:
  <https://github.com/hypfvieh/dbus-java>
- FileKit 0.16.0: MIT: <https://github.com/vinceglb/FileKit>
- AppDirs 1.4.0: Apache License 2.0: <https://github.com/harawata/appdirs>
- SLF4J 2.0.17 (no-operation binding): MIT: <https://github.com/qos-ch/slf4j>

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
- ZXing Core 3.5.3: Apache License 2.0: <https://github.com/zxing/zxing/tree/zxing-3.5.3>
- JetBrains annotations: Apache License 2.0: <https://github.com/JetBrains/java-annotations>

The Gradle wrapper scripts are provided under Apache License 2.0. Test-only dependencies are not
packaged in any build.

## Visual assets

- Poppins fonts: SIL Open Font License 1.1. The full text is in `site/fonts/OFL-Poppins.txt`.
- The player mark was adapted and recoloured from a player icon on SVG Repo
  (<https://www.svgrepo.com>), used under the licence that icon is published with there. SVG Repo
  is credited in the website footer and the README.
- Solar Icons by 480 Design, CC BY 4.0 (<https://creativecommons.org/licenses/by/4.0/>), modified:
  the in-player play and pause glyphs (`ic_player_play.xml`, `ic_player_pause.xml`), obtained via
  SVG Repo. The seek, previous, next, back, overflow, lock, picture in picture, brightness and
  volume glyphs are Material Symbols paths: Apache License 2.0:
  <https://github.com/google/material-design-icons>. The phone's morphing play and pause glyph
  (`PlayPauseIcon.kt`) is drawn in code by TMPlayer.
- The TMDB logo in About is TMDB's own (<https://www.themoviedb.org/about/logos-attribution>),
  shown with the notice their terms ask for: this product uses the TMDB API but is not endorsed or
  certified by TMDB.
- The screenshot fixture's (promo build only, never released) Big Buck Bunny poster and landscape
  (`promo_bbb_*.webp`) are (c) Blender Foundation, peach.blender.org, CC BY 3.0
  (<https://creativecommons.org/licenses/by/3.0/>), via Wikimedia Commons, scaled down.

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
- FreeType Project License: <https://gitlab.freedesktop.org/freetype/freetype/-/blob/master/docs/FTL.TXT>
- libpng: <http://www.libpng.org/pub/png/src/libpng-LICENSE.txt>
- OpenSSL 1.1.1 (OpenSSL and SSLeay): <https://www.openssl.org/source/license-openssl-ssleay.txt>
- Boost-1.0: <https://www.boost.org/LICENSE_1_0.txt>
- CC BY 3.0: <https://creativecommons.org/licenses/by/3.0/legalcode>
- CC BY 4.0: <https://creativecommons.org/licenses/by/4.0/legalcode>
- SIL OFL-1.1: <https://openfontlicense.org>
