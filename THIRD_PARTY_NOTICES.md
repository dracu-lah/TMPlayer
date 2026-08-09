# Third-party notices

TMPlayer is distributed under GPL-3.0. It also contains or depends on the components below. Their
copyrights remain with their respective authors, and their licence terms continue to apply.

## Native media extension

### NextLib Media3 Extensions 1.8.0-0.9.0

- Author: Anil Kumar Beesetti and contributors
- Licence: GNU General Public License version 3
- Source: <https://github.com/anilbeesetti/nextlib/tree/1.8.0-0.9.0>
- Exact commit: `1b69e9490c172c42f73f7a6f1b9ffd1c8be4a060`

The released AAR contains native codec libraries built by NextLib's `ffmpeg/setup.sh` from these
upstream sources:

- FFmpeg 6.0: LGPL-3.0-or-later for the configuration used by NextLib (`--enable-version3`,
  without `--enable-gpl` or non-free components): <https://ffmpeg.org/releases/ffmpeg-6.0.tar.gz>
- libvpx 1.13.0: BSD-3-Clause: <https://github.com/webmproject/libvpx/tree/v1.13.0>
- Mbed TLS 3.4.1: Apache-2.0 selected from its dual licence:
  <https://github.com/Mbed-TLS/mbedtls/tree/v3.4.1>

Each TMPlayer release includes a `nextlib-corresponding-source-<version>.tar.gz` archive containing
the exact NextLib source and build scripts plus the three upstream source archives above.

## Telegram client stack

### tdl-coroutines 9.0.0

- Author: Georgii Ippolitov and contributors
- Licence: Apache License 2.0
- Source: <https://github.com/g000sha256/tdl-coroutines/tree/9.0.0>
- Exact commit: `cdbaa628a8453fffbc0805afed47c9df85521429`

The Android artifact bundles TDLib 1.8.61 native libraries:

- TDLib 1.8.61: Boost Software License 1.0
- Source: <https://github.com/tdlib/td/tree/6d509061574d684117f74133056aa43df89022fc>
- Exact commit: `6d509061574d684117f74133056aa43df89022fc`
- The TDLib Android build uses OpenSSL `OpenSSL_1_1_1w`; its OpenSSL and original SSLeay licence
  texts are included in the OpenSSL source distribution.

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

## Licence texts

- GPL-3.0: <https://www.gnu.org/licenses/gpl-3.0.html>
- LGPL-3.0: <https://www.gnu.org/licenses/lgpl-3.0.html>
- Apache-2.0: <https://www.apache.org/licenses/LICENSE-2.0>
- BSD-3-Clause: <https://opensource.org/license/bsd-3-clause>
- Boost-1.0: <https://www.boost.org/LICENSE_1_0.txt>
- SIL OFL-1.1: <https://openfontlicense.org>
