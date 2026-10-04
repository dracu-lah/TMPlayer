# Third-party notices

TMPlayer is distributed under GPL-3.0. It also contains or depends on the components below. Their
copyrights remain with their respective authors, and their licence terms continue to apply.

## Native media extension

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
<https://github.com/dracu-lah/TMPlayer/releases/download/v1.21.0/nextlib-corresponding-source-1.21.0.tar.gz>. It holds the exact NextLib 1.10.1-0.13.0 source and build scripts plus the three upstream
source archives above, and stays the right archive for as long as TMPlayer bundles that version.

## Telegram client stack

### tdl-coroutines 15.0.0

- Author: Georgii Ippolitov and contributors
- Licence: Apache License 2.0
- Source: <https://github.com/g000sha256/tdl-coroutines/tree/15.0.0>
- Exact commit: `1e21103b4204a9dae060b8f203a4fcc1388cdb3d`

The Android artifact bundles TDLib 1.8.67 native libraries:

- TDLib 1.8.67: Boost Software License 1.0
- Source: <https://github.com/tdlib/td/tree/bc9c263e2bfee06aaab41e82db51a103376030bc>
- Exact commit: `bc9c263e2bfee06aaab41e82db51a103376030bc`
- The TDLib Android build links OpenSSL; its OpenSSL licence text is included in the OpenSSL
  source distribution, and the exact OpenSSL revision is recorded in tdl-coroutines' build
  scripts at the commit above.

The exact tdl-coroutines and TDLib source revisions are linked here for reproducibility. Both are
under permissive licences and do not impose a corresponding-source requirement on TMPlayer.

## Android and Kotlin libraries

- AndroidX Core, Activity, Compose, TV Material, Lifecycle, Media3, Leanback, Fragment, and
  DataStore: Apache License 2.0: <https://github.com/androidx/androidx>
- Kotlin standard library and kotlinx coroutines/serialization dependencies: Apache License 2.0:
  <https://github.com/JetBrains/kotlin> and <https://github.com/Kotlin/kotlinx.coroutines>
- ZXing Core 3.5.3: Apache License 2.0: <https://github.com/zxing/zxing/tree/zxing-3.5.3>
- JetBrains annotations: Apache License 2.0: <https://github.com/JetBrains/java-annotations>

The Gradle wrapper scripts are provided under Apache License 2.0. Test-only dependencies are not
packaged in the APK.

## Website and visual assets

- Poppins fonts: SIL Open Font License 1.1. The full text is in
  `site/fonts/OFL-Poppins.txt`.
- The player mark was adapted and recoloured from an SVG Repo player icon. SVG Repo is credited in
  the website footer and README; the adapted vector is distributed with TMPlayer under GPL-3.0.
- The in-player play and pause glyphs (`ic_player_play.xml`, `ic_player_pause.xml`) are from the
  Solar icon set, obtained via SVG Repo under its CC Attribution licence:
  <https://www.svgrepo.com>. The seek, previous, next, back, overflow, lock, picture in picture,
  brightness and volume glyphs are Material Symbols paths: Apache License 2.0:
  <https://github.com/google/material-design-icons>. The phone's morphing play and pause glyph
  (`PlayPauseIcon.kt`) is drawn in code by TMPlayer.

## Licence texts

- GPL-3.0: <https://www.gnu.org/licenses/gpl-3.0.html>
- LGPL-3.0: <https://www.gnu.org/licenses/lgpl-3.0.html>
- Apache-2.0: <https://www.apache.org/licenses/LICENSE-2.0>
- BSD-3-Clause: <https://opensource.org/license/bsd-3-clause>
- Boost-1.0: <https://www.boost.org/LICENSE_1_0.txt>
- SIL OFL-1.1: <https://openfontlicense.org>
