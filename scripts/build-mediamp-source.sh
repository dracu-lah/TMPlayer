#!/usr/bin/env bash
# Builds mediamp-corresponding-source-0.5.2.tar.gz: the complete corresponding source of the mpv
# runtime that the desktop builds bundle through mediamp 0.5.2 (org.openani.mediamp, Open Ani).
#
# The runtime jars (mediamp-mpv-runtime-linux-x64 and -windows-x64) carry libmpv, FFmpeg and every
# shared library they need. mediamp builds libmpv, FFmpeg and dav1d from source; everything else is
# copied out of the build host's packages: Ubuntu 24.04 for Linux, MSYS2 UCRT64 for Windows. Each
# version below was proven against the shipped bytes, not guessed: the Linux libraries by GNU build
# ID against the Ubuntu .deb (patchelf rewrites RUNPATH, so the files differ but the build ID
# survives), the Windows DLLs by SHA-256 against the MSYS2 package, which they are byte for byte.
# glslang and SPIRV-Tools have no DLL of their own: MSYS2 links them statically into
# libshaderc_shared.dll, and the .BUILDINFO of the shaderc 2026.4-1 package (built on 2026-10-04)
# records glslang 16.3.0-1, SPIRV-Tools 1.4.363.0-1 and SPIRV-Headers 1.4.363.0-1 as installed
# for that build; the DLL reports "glslang Khronos. 16.3.0".
#
# mediamp 0.5.2 was built by its Release workflow on 2026-10-16 (tag v0.5.2, commit d0d0a449),
# with the same mpv, FFmpeg and dav1d submodule commits as 0.5.0 and the same FFmpeg configure
# lines and mpv options (both are embedded in the shipped libraries). Many of the other libraries
# are byte for byte the ones 0.5.0 shipped and keep the pins proven for it, at their 0.5.0
# addresses. The ones that changed were proven again: on Linux the OpenSSL, FreeType, GLib and
# libpng updates, against the .debs of the Ubuntu snapshot of 2026-10-16 (the versions the Ubuntu
# job's apt log also shows); on Windows fifteen DLLs, against the MSYS2 packages the Windows job's
# pacman log installed.
#
# Every file is fetched from a permanent address (the Ubuntu snapshots of 2026-09-19 and
# 2026-10-16, the days mediamp 0.5.0 was released and 0.5.2 was built; MSYS2's source archive;
# GitHub archives of exact commits) and checked against the SHA-256 pinned here. A mismatch stops
# the script. Nothing is uploaded: attaching the archive to a release is a separate, manual step.
#
# With --macos it builds a separate archive, mediamp-corresponding-source-0.5.2-macos.tar.gz, for
# mediamp-mpv-runtime-macos-arm64 (the macOS DMG). mediamp builds that runtime on a self-hosted
# Apple Silicon runner: libmpv, FFmpeg and dav1d from the same pinned sources as above, and libass
# and libplacebo with their dependencies copied out of the runner's Homebrew (`brew install meson
# ninja libass libplacebo` in release.yml, then MpvTaskTypes.kt bundles every non-system dylib,
# rewrites the install names to @loader_path and signs them ad hoc). The rewrite and the new
# signature change the file bytes, so each dylib was proven instead by its LC_UUID and the SHA-256
# of its __TEXT,__text section, both equal to the dylib in the Homebrew bottle of the version
# listed (bottle SHA-256 in the comment beside it; the bottles of neighbouring versions and
# rebuilds were fetched too and differ). Each formula file is taken from the homebrew-core commit
# that published that bottle, so it holds the bottle's SHA-256 as well as the source URL and
# patches. Seven dylibs changed from 0.5.0 and match arm64_tahoe bottles (which Homebrew pours on
# macOS 26, so the runner had moved to it); the others are byte for byte the dylibs 0.5.0 shipped
# and keep their arm64_sequoia proofs.
# mpv's own build options and FFmpeg's configure line are embedded in libmpv.2.dylib and
# libavutil.60.dylib; they show -Dgpl=false, no --enable-gpl, mpv v0.41.0 and FFmpeg 8.0.1.
#
# glibc 2.35 rebuilds (issue #30): scripts/build-mpv-linux-2-35.sh rebuilds eight files of the
# Linux runtime on Ubuntu 22.04 (libmpv, libass, libcrypto, libglib-2.0, libharfbuzz, libplacebo,
# libva and libmediampv) from the sources pinned here, at the same versions. A release that ships
# those copies also needs that script's own archive (--source-archive): libplacebo statically
# contains glslang 14.0.0, SPIRV-Tools 2023.6~rc1 and SPIRV-Headers, as Ubuntu's libplacebo338 does
# (so the shipped libplacebo.so.338 carries them too and this archive's Linux part lacks them; that
# script's archive has them), and the build uses the Vulkan headers, xxhash, glad, nv-codec-headers and meson pinned there,
# plus the recipe in natives/mpv-linux. Both archives together are the corresponding source of
# that runtime (natives/mpv-linux/README.md).
#
# Usage: scripts/build-mediamp-source.sh [--macos] [output dir]   (default: build/mediamp-source)
# Downloads are kept in <output dir>/downloads, so a second run fetches nothing.
set -euo pipefail

macos=false
if [ "${1:-}" = "--macos" ]; then
  macos=true
  shift
fi

VERSION="0.5.2"
NAME="mediamp-corresponding-source-$VERSION"
if $macos; then
  NAME="$NAME-macos"
fi

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
out="${1:-$root/build/mediamp-source}"
mkdir -p "$out"
out=$(cd "$out" && pwd)
cache="$out/downloads"
stage="$out/$NAME"

# A mediamp bump means a different runtime, so this archive would be the wrong one.
used="$(sed -n 's/^mediamp = "\(.*\)"$/\1/p' "$root/gradle/libs.versions.toml")"
if [ "$used" != "$VERSION" ]; then
  echo "mediamp is $used in libs.versions.toml, but this script covers $VERSION" >&2
  exit 1
fi

github="https://github.com"
msys2="https://repo.msys2.org/mingw/sources"
ubuntu="https://snapshot.ubuntu.com/ubuntu/20260919T000000Z"
ubuntu2="https://snapshot.ubuntu.com/ubuntu/20261016T000000Z"
brewcore="https://raw.githubusercontent.com/Homebrew/homebrew-core"

# directory in the archive, SHA-256, file name, address
upstream_manifest() {
  cat <<EOF
upstream 74886d8b52beb46f4010ade38d0615b7be84e8d07007d471ab39c44cd26215fd mediamp-0.5.2.tar.gz $github/open-ani/mediamp/archive/refs/tags/v0.5.2.tar.gz
upstream 068960e89211f2adc80af03f637fa393fb5e407d3dd23592c8fe8c5490bb7ae4 mpv-0.41.0.tar.gz $github/mpv-player/mpv/archive/41f6a645068483470267271e1d09966ca3b9f413.tar.gz
upstream 269e43f5560fb647ee8df47b5b09fabaf3de2ad24dd0ee14c9f690b85b873ccc ffmpeg-8.0.1-449453a9.tar.gz $github/FFmpeg/FFmpeg/archive/449453a9713aa1af31a12e148fb0caba80ed02d7.tar.gz
upstream 9edb11a2108b375cc58370354e705feebc93430bb780130363815c1e1ac0c250 dav1d-1.5.4.tar.gz $github/videolan/dav1d/archive/54706fc6bc0cdecab7e9593974a4039cc038fca7.tar.gz
EOF
}

# macOS arm64 (--macos). How each bundled dylib was proven: its LC_UUID (second column) and the
# SHA-256 of its __TEXT,__text section are the same as in the dylib of the Homebrew bottle (tag in
# the third column) whose SHA-256 is in the last column (ghcr.io/v2/homebrew/core/<formula>/blobs/
# sha256:...). The formula file pinned below carries that same bottle SHA-256. Versions next to it
# were fetched and differ, as noted.
#   libglib-2.0.0      31e21c050fe33331a56f9e819e39cc0c  tahoe   glib 2.88.2 (2.88.3, 2.90.0, 2.90.1 differ) f8f2828c8899559379018e7d1dbade2e0b41b35239772c1b8469e5083623b139
#   libintl.8          685f7630ffc2340bb015a7ee74c4d830  tahoe   gettext 1.0, bottle rebuild 1 (rebuilds 0, 2 differ) 2b713227e438f51d025d76df24cfa45a2b813b61718df7bb91a6cedb1091037b
#   libpcre2-8.0       ac59ae2708843df1baf138015aa7768b  tahoe   pcre2 10.47_1 (10.47, 10.48, 10.49 differ) 948b00bdaf75a842341f08a949ca3d414ae5333df2c305a7e371be31a2c30963
#   libharfbuzz.0      70ff899305ad3fe78366f3be306ad4b2  tahoe   harfbuzz 14.2.1 (14.3.x to 14.6.0 differ) 45b392b3a078f7def1b927dc46806df3d2768ddd584160d9c0769b20abba7525
#   libgraphite2.3.3.1 6a793c2f575930128ccc7dc558236eaf  tahoe   graphite2 1.3.15 (1.3.14 and its rebuilds differ) c05e664c8872f3a9e08ada587a33d71b841673126840fe0cd301300c1f59acea
#   libfreetype.6      0a7e6c59803b3a5a8f9b240d7b3025ca  tahoe   freetype 2.14.3 (2.14.2, 2.15.0 differ) 449e22351da81ba5c8c398f87c295decac6b396bd6fab54becbb3d18800b62d1
#   libpng16.16        68cbdb4ad1213925a2abfa1a7598b757  tahoe   libpng 1.6.58 (1.6.57, 1.6.59 and the April 1.6.58 bottle differ) a28e1e29bf508ddf89800c215a013694c2751ca63e8ae40aca859aff7bf7b02c
#   libfribidi.0       dd2ba6d9be7f3a9aa790384d5d8e799b  sequoia fribidi 1.0.16              4904bb3375c1b1f2539cb657fbf17b0d93c050904febbba51e9dd690f3540e3b
#   libunibreak.6      bec38d9767d43659bfe87c7b16b4949b  sequoia libunibreak 6.1             0bed33fbc272073291fa047e1f72422aff56e83276de789d02a0f3f82f117b45
#   libass.9           4ee550b266ab35e6a09aa2015c957c56  sequoia libass 0.17.4 (0.17.4_1 differs) d097cb72eb4e70bce4ca584649fa8874e4407da3067d6faa0dcc89013ffe1743
#   liblcms2.2         cd4b47155ce339d9a1b0f9e59af30f3a  sequoia little-cms2 2.17            3827b5a8583f0e92987bd0e7c721bda834157ad366d2cb66596559dd8654c9ac
#   libvulkan.1.4.326  e74f08459df83a3886237a812c87e886  sequoia vulkan-loader 1.4.326       2b3d377d43d59f2576e136e706395c6c64dff5e077fa43c4b0a921b615f635a5
#   libshaderc_shared.1 f102f2c153c73049a160af6eb41a0cc4 sequoia shaderc 2025.3 (2025.2, 2025.4 differ) 45d6afe8db7c6f5fd474d0197aad4cf53c1995db94fecf06be9f001b643a2bea
#   libplacebo.351     9e059c07c700313885bba36d0b7ee444  sequoia libplacebo 7.351.0, bottle rebuild 1 (rebuild 0 differs) 4e5f0af5ad2cb7d838cff63d56a50d7db5e9f7ca27c2045a546834397853240f
# The sequoia rows are byte for byte the dylibs mediamp 0.5.0 shipped, proven then.
# shaderc links glslang, SPIRV-Tools and SPIRV-Headers statically, at the commits its formula pins
# (the dylib reports "glslang Khronos. 15.3.0", the version in glslang efd24d75's CHANGES.md).
# libplacebo's formula stages glad2, Jinja2 and MarkupSafe, which generate part of its source; glib's
# stages gobject-introspection 1.86.0 to build its introspection data (none of it is linked into
# libglib). The source tarballs and the glib patch are the ones each formula names, with its
# SHA-256 (glib 2.88.2's formula carries the patch in homebrew-core as Patches/glib/
# hardcoded-paths.diff, the same bytes as the formula-patches copy pinned here); the three
# glslang/SPIRV archives are GitHub archives of the pinned commits.
macos_manifest() {
  cat <<EOF
macos/homebrew 53443911efbbc4de9e6cd4b9862241df1bbe79d84bb3d0ac99762f4a1b7a229b glib-2.88.2.rb $brewcore/ff1e14952dfa8b4f7f46250dfa15189e79f9d68e/Formula/g/glib.rb
macos/homebrew 5fcb6b3cb99302cd39932a000fe42a0debfca6981cae7d96c6c6ee7561030ec1 gettext-1.0.rb $brewcore/21f6c4fc2bf94860d0359e4a7ddfbd4c0ff5f540/Formula/g/gettext.rb
macos/homebrew 520f6f15b47cd5e0fa03c921ed5d7bd29f8b11ab460d04202451c52a8435c071 pcre2-10.47_1.rb $brewcore/ee08cc645fb0b30f321b12db839a4854259790bb/Formula/p/pcre2.rb
macos/homebrew fd2b2b8d58aaa6f9c3b68c296b0018952cfb1ead44ece165d258814194f5d1e4 harfbuzz-14.2.1.rb $brewcore/4358c38b74e07289b5fc0fb5e3db5135149cdb6f/Formula/h/harfbuzz.rb
macos/homebrew 1c6f14344b08144d7f9d645f59325f163657bf94a9b499ea37dc62cd387ed4e4 graphite2-1.3.15.rb $brewcore/21ea270a44c701fca93cd909b07bfcf22b91e4fc/Formula/g/graphite2.rb
macos/homebrew 9d3ea68e58d204cb55a0750e7705422968d6f69867ebc51952fcb37a68c4b713 freetype-2.14.3.rb $brewcore/5d4a615ff8830b0d49ded1dc667e2d769580545d/Formula/f/freetype.rb
macos/homebrew d188d9842b4e45fff411fbbd18448fc515cc100a7df7260cdc3bbff12b5be027 libpng-1.6.58.rb $brewcore/d0adbeab06c0a2a48cceac52702381b928cd6da4/Formula/lib/libpng.rb
macos/homebrew 54693c5739662b7396217eeb9506e6538f1b5fd8de4fa19c93aa8732f73c252a fribidi-1.0.16.rb $brewcore/8633dd0356aedb86304b5bbd83f1b51e19622981/Formula/f/fribidi.rb
macos/homebrew ff7231cf9ea7bf491cc5530f34b8dacf9884d5c4c83db4d0d5a712b2c6b29f42 libunibreak-6.1.rb $brewcore/4b0c373d30d8e09c18fd7562776fa531c90d95ca/Formula/lib/libunibreak.rb
macos/homebrew 512bb269fc11890c340a702bb8f4ae1823c4673e7b99f0070cc9cd017362b989 libass-0.17.4.rb $brewcore/45ab754dcd6694699f0076f81ebabc1c927af095/Formula/lib/libass.rb
macos/homebrew b9bbd29b12c2d15ff7f2944158e669617c7c2bf89f6203298028ef6c8b10df3d little-cms2-2.17.rb $brewcore/3755f216851a0e0d0975db6d552427b37b09f1cd/Formula/l/little-cms2.rb
macos/homebrew a585e890286bc6de0a0933753b1e062dfc2ead3a137a570d88f152df3020662e vulkan-loader-1.4.326.rb $brewcore/5bf54585a0bb98381a4eac6aaeb2fb90d4b1c857/Formula/v/vulkan-loader.rb
macos/homebrew 6bd81ded76f9d9022a4f300ec84b925e84edf054bf8fa73f0687cd66afe4f012 shaderc-2025.3.rb $brewcore/9b06f6d97edff471bb87e4ec1c7305cda0856eea/Formula/s/shaderc.rb
macos/homebrew 3cad60a678b3eeb7642783b4697c7725a2b5b9ba8ba6925d4a9ff76d61c6cb6a libplacebo-7.351.0.rb $brewcore/599542601b6aa9ad2889ac280afe4dc0dcfb1cb8/Formula/lib/libplacebo.rb
macos/homebrew d846efd0bf62918350da94f850db33b0f8727fece9bfaf8164566e3094e80c97 glib-hardcoded-paths.diff https://raw.githubusercontent.com/Homebrew/formula-patches/b46d8deae6983110b4e39bb2971bcbd10bb59716/glib/hardcoded-paths.diff
macos cf3f215a640c8a4257f14317586b8f1fdd25a10a93cb4bdda147c0f9ad88e74f glib-2.88.2.tar.xz https://download.gnome.org/sources/glib/2.88/glib-2.88.2.tar.xz
macos 920d1a3fcedeadc32acff95c2e203b319039dd4b4a08dd1a2dfd283d19c0b9ae gobject-introspection-1.86.0.tar.xz https://download.gnome.org/sources/gobject-introspection/1.86/gobject-introspection-1.86.0.tar.xz
macos 85d99b79c981a404874c02e0342176cf75c7698e2b51fe41031cf6526d974f1a gettext-1.0.tar.gz https://ftp.gnu.org/gnu/gettext/gettext-1.0.tar.gz
macos 47fe8c99461250d42f89e6e8fdaeba9da057855d06eb7fc08d9ca03fd08d7bc7 pcre2-10.47.tar.bz2 $github/PCRE2Project/pcre2/releases/download/pcre2-10.47/pcre2-10.47.tar.bz2
macos a54a5d8e9380a41fbb762ce367bcbf7704792dfca0d93f1bbca86c5a57902e0e harfbuzz-14.2.1.tar.xz $github/harfbuzz/harfbuzz/releases/download/14.2.1/harfbuzz-14.2.1.tar.xz
macos c6bc8b4252724665297f7cad0c55897285c673f9b8e6db3522ace833593fe0b1 graphite2-1.3.15.tgz $github/silnrsi/graphite/releases/download/1.3.15/graphite2-1.3.15.tgz
macos 36bc4f1cc413335368ee656c42afca65c5a3987e8768cc28cf11ba775e785a5f freetype-2.14.3.tar.xz https://download.savannah.gnu.org/releases/freetype/freetype-2.14.3.tar.xz
macos 28eb403f51f0f7405249132cecfe82ea5c0ef97f1b32c5a65828814ae0d34775 libpng-1.6.58.tar.xz https://downloads.sourceforge.net/project/libpng/libpng16/1.6.58/libpng-1.6.58.tar.xz
macos 1b1cde5b235d40479e91be2f0e88a309e3214c8ab470ec8a2744d82a5a9ea05c fribidi-1.0.16.tar.xz $github/fribidi/fribidi/releases/download/v1.0.16/fribidi-1.0.16.tar.xz
macos cc4de0099cf7ff05005ceabff4afed4c582a736abc38033e70fdac86335ce93f libunibreak-6.1.tar.gz $github/adah1972/libunibreak/releases/download/libunibreak_6_1/libunibreak-6.1.tar.gz
macos 78f1179b838d025e9c26e8fef33f8092f65611444ffa1bfc0cfac6a33511a05a libass-0.17.4.tar.xz $github/libass/libass/releases/download/0.17.4/libass-0.17.4.tar.xz
macos d11af569e42a1baa1650d20ad61d12e41af4fead4aa7964a01f93b08b53ab074 lcms2-2.17.tar.gz https://downloads.sourceforge.net/project/lcms/lcms/2.17/lcms2-2.17.tar.gz
macos 86772b60eeef6f510586636b7cf7a0e0eabd9e9920bcabf6e8f3b1c2a634a4cc vulkan-loader-1.4.326.tar.gz $github/KhronosGroup/Vulkan-Loader/archive/refs/tags/v1.4.326.tar.gz
macos a8e4a25e5c2686fd36981e527ed05e451fcfc226bddf350f4e76181371190937 shaderc-2025.3.tar.gz $github/google/shaderc/archive/refs/tags/v2025.3.tar.gz
macos 9427deccbdf4bde6a269938df38c6bd75247493786a310d8d733a2c82065ef47 glslang-15.3.0-efd24d75.tar.gz $github/KhronosGroup/glslang/archive/efd24d75bcbc55620e759f6bf42c45a32abac5f8.tar.gz
macos 44d1005880c583fc00a0fb41c839214c68214b000ea8dcb54d352732fee600ff SPIRV-Tools-33e02568.tar.gz $github/KhronosGroup/SPIRV-Tools/archive/33e02568181e3312f49a3cf33df470bf96ef293a.tar.gz
macos c2225a49c3d7efa5c4f4ce4a6b42081e6ea3daca376f3353d9d7c2722d77a28a SPIRV-Headers-2a611a97.tar.gz $github/KhronosGroup/SPIRV-Headers/archive/2a611a970fdbc41ac2e3e328802aed9985352dca.tar.gz
macos d68159280842a7f0482dcea44a440f4c9a8e9403b82eccf185e46394dfc77e6a libplacebo-v7.351.0.tar.bz2 https://code.videolan.org/videolan/libplacebo/-/archive/v7.351.0/libplacebo-v7.351.0.tar.bz2
macos b84079b9fa404f37171b961bdd1d8da21370e6c818defb8481c5b3fe3d6436da glad2-2.0.8.tar.gz https://files.pythonhosted.org/packages/6e/5a/d62b24fe1c7c2f34e15c2aa4418a5327a8550fdc272999a59e0dddebc3ee/glad2-2.0.8.tar.gz
macos 0137fb05990d35f1275a587e9aee6d56da821fc83491a0fb838183be43f66d6d jinja2-3.1.6.tar.gz https://files.pythonhosted.org/packages/df/bf/f7da0350254c0ed7c72f3e33cef02e048281fec7ecec5f032d4aac52226b/jinja2-3.1.6.tar.gz
macos ee55d3edf80167e48ea11a923c7386f4669df67d7994554387f84e7d8b0a2bf0 markupsafe-3.0.2.tar.gz https://files.pythonhosted.org/packages/b2/97/5d42485e71dfc078108a86d6de8fa46db44a1a9295e89c5d6d4a06e23a62/markupsafe-3.0.2.tar.gz
EOF
}

desktop_manifest() {
  cat <<EOF
windows 4a68edae2c1454ffcf7cf0a6fccf6930c876caa5fe2422cdb0b34d1fc0f5fb03 mingw-w64-libass-0.17.5-1.src.tar.zst $msys2/mingw-w64-libass-0.17.5-1.src.tar.zst
windows 2f0f93875aac21990d20f1eaf0ad8b970eb6f186c663c77ae47aaaf9e5711fcf mingw-w64-brotli-1.2.0-1.src.tar.zst $msys2/mingw-w64-brotli-1.2.0-1.src.tar.zst
windows fd9360917314d1cbccb3cb15ed8450d1e56b8a9f733b606504aba52c52159e54 mingw-w64-bzip2-1.0.8-4.src.tar.zst $msys2/mingw-w64-bzip2-1.0.8-4.src.tar.zst
windows 997157ec74a6a722d7c8848605b1389a31a103d4b9f2aa6a40e179373a139cd1 mingw-w64-openssl-3.6.5-1.src.tar.zst $msys2/mingw-w64-openssl-3.6.5-1.src.tar.zst
windows 50e7303249bd57b9bb543e87e5de4b6331d744b22d2d26861bc8968ee60a70a3 mingw-w64-libdovi-3.4.0-1.src.tar.zst $msys2/mingw-w64-libdovi-3.4.0-1.src.tar.zst
windows 014a4b1949f65f053a9774712994db89fea290516f1c1849bbbbf7316ee1606b mingw-w64-expat-2.9.0-1.src.tar.zst $msys2/mingw-w64-expat-2.9.0-1.src.tar.zst
windows 7df65dbf84397f4f71c6aa0bd39fac93f839d6b87f04a935773b69828740230d mingw-w64-fontconfig-2.18.3-1.src.tar.zst $msys2/mingw-w64-fontconfig-2.18.3-1.src.tar.zst
windows 2f09c60b72bd249642d055aa5a34edf2577392e592415f3f4730dd37990b47ad mingw-w64-freetype-2.15.0-1.src.tar.zst $msys2/mingw-w64-freetype-2.15.0-1.src.tar.zst
windows 0f192cf5009fe18e2ac752273de509094cf161106fbedd69f112186e75253850 mingw-w64-fribidi-1.0.17-1.src.tar.zst $msys2/mingw-w64-fribidi-1.0.17-1.src.tar.zst
windows a901d8afb78438abcff9ed020cd9aa9431db76de1992171c2454c3c07ad289e8 mingw-w64-gcc-16.2.0-6.src.tar.zst $msys2/mingw-w64-gcc-16.2.0-6.src.tar.zst
windows ac3bc00e4079e6b51dd0a1fb9ccc420fff033b4e9095e4487dbf6cf01312cee6 mingw-w64-glib2-2.90.1-1.src.tar.zst $msys2/mingw-w64-glib2-2.90.1-1.src.tar.zst
windows eb76ce274c68ea6bad0883144c21e44a803ec94fed9dc0f6584feb0a8415a1d6 mingw-w64-graphite2-1.3.15-1.src.tar.zst $msys2/mingw-w64-graphite2-1.3.15-1.src.tar.zst
windows 6c02c446b27412717a760eca77ac6ed997b294fdc390a5e5f46760ebda8a7c64 mingw-w64-harfbuzz-14.6.0-1.src.tar.zst $msys2/mingw-w64-harfbuzz-14.6.0-1.src.tar.zst
windows 74428280c17094da5b702c29b2e1a0abae59556ea5dfdd65705cc8ccc1e000fb mingw-w64-libiconv-1.19-1.src.tar.zst $msys2/mingw-w64-libiconv-1.19-1.src.tar.zst
windows cca0d0c8f60353faccc5ad40405240014e4a7ccc8b1c6ca9f4626aef3097d26e mingw-w64-gettext-1.0-1.src.tar.zst $msys2/mingw-w64-gettext-1.0-1.src.tar.zst
windows 83414d0d95c5cb2548d3050670694e9efc64304a52190f42bcad951efa038b41 mingw-w64-lcms2-2.19.1-1.src.tar.zst $msys2/mingw-w64-lcms2-2.19.1-1.src.tar.zst
windows b02f0d5070b7d7f6db5c8ba9b009f28c1a07b9b52aaf7964dc0c8b098e4088d1 mingw-w64-pcre2-10.49-1.src.tar.zst $msys2/mingw-w64-pcre2-10.49-1.src.tar.zst
windows 2abe10f1d16981407e8108c5ccb2e13cbeb28ce62f702a4caf1165b732e85bf0 mingw-w64-libplacebo-7.360.1-2.src.tar.zst $msys2/mingw-w64-libplacebo-7.360.1-2.src.tar.zst
windows 2b836f8565a3889ed9bb86f9724074ae274f109e2bcd882dd1f080ea4da8a41a mingw-w64-libpng-1.6.59-1.src.tar.zst $msys2/mingw-w64-libpng-1.6.59-1.src.tar.zst
windows 7c9df6e89d39cc343aa914b3728d4fbacc35832c5ecda83d54ae386966186e2a mingw-w64-shaderc-2026.4-1.src.tar.zst $msys2/mingw-w64-shaderc-2026.4-1.src.tar.zst
windows 948faf9909ad6ff3772077cd97546323415c4bd1164c7775e208bc8645780187 mingw-w64-glslang-16.3.0-1.src.tar.zst $msys2/mingw-w64-glslang-16.3.0-1.src.tar.zst
windows 6110f9257bd8d63da9b8d981c1093fd28f99a7d51c34f067c3a1c5db5e46454a mingw-w64-spirv-tools-3~1.4.363.0-1.src.tar.zst $msys2/mingw-w64-spirv-tools-3~1.4.363.0-1.src.tar.zst
windows 261200e7faf30b0cdfd8a6987543c3ab62710e8eb29d727313518d3e7c363f07 mingw-w64-spirv-headers-2~1.4.363.0-1.src.tar.zst $msys2/mingw-w64-spirv-headers-2~1.4.363.0-1.src.tar.zst
windows 2043f9e7e1359edbf1618a59c38a687d47e3a5779844db6adc4f8de78c5e1eed mingw-w64-spirv-cross-1~1.4.363.0-1.src.tar.zst $msys2/mingw-w64-spirv-cross-1~1.4.363.0-1.src.tar.zst
windows 3d51ed18b20deb475c6947ea8fdabb7d6107962e2e6e690de90e5227537728ad mingw-w64-libunibreak-7.0-1.src.tar.zst $msys2/mingw-w64-libunibreak-7.0-1.src.tar.zst
windows d2d540bb20b227f5d920d8f509e4e0c413e8310d08a96347c1bdeb9af8844d72 mingw-w64-winpthreads-14.0.0.r426.g4564ee4b5-2.src.tar.zst $msys2/mingw-w64-winpthreads-14.0.0.r426.g4564ee4b5-2.src.tar.zst
windows 61ed2ebe6007c464af1687e4ecad93199e9faeb3d8dd4d3c24e5b2576f730c9b mingw-w64-vulkan-loader-1~1.4.363.0-1.src.tar.zst $msys2/mingw-w64-vulkan-loader-1~1.4.363.0-1.src.tar.zst
windows eef69dea52357e01b272d6fd6dc4d7c0773f71260cb7bcde6047c8c383518db3 mingw-w64-zlib-1.3.2-2.src.tar.zst $msys2/mingw-w64-zlib-1.3.2-2.src.tar.zst
linux 8ac2a33db1e08aa1d73f9022b71539f10f344bdea47b6f3dcf75186a1c1de27f libass_0.17.1-2build1.dsc $ubuntu/pool/universe/liba/libass/libass_0.17.1-2build1.dsc
linux d653be97198a0543c69111122173c41a99e0b91426f9e17f06a858982c2fb03d libass_0.17.1.orig.tar.gz $ubuntu/pool/universe/liba/libass/libass_0.17.1.orig.tar.gz
linux 699d1ba876d7bac95dbac56bac0f00126473e8588759a4ec13cdf52ae03d8483 libass_0.17.1.orig.tar.gz.asc $ubuntu/pool/universe/liba/libass/libass_0.17.1.orig.tar.gz.asc
linux 605dadc3d805d2d7bd197a7b21e6a7b8533eb9372f47e8690788ba96905556f4 libass_0.17.1-2build1.debian.tar.xz $ubuntu/pool/universe/liba/libass/libass_0.17.1-2build1.debian.tar.xz
linux c58421959677a80d942924685007e8d5b5d1160fd7f61a1f0a9062bf08835856 brotli_1.1.0-2build2.dsc $ubuntu/pool/main/b/brotli/brotli_1.1.0-2build2.dsc
linux 10973f4b4199eafa1d5735ef661ddb2ec2f97319ee9fd1824d4aabe08cff5265 brotli_1.1.0.orig.tar.gz $ubuntu/pool/main/b/brotli/brotli_1.1.0.orig.tar.gz
linux 96603222d7b6edb3253a846852972b6bde5b86814907d5540249e6950980a943 brotli_1.1.0-2build2.debian.tar.xz $ubuntu/pool/main/b/brotli/brotli_1.1.0-2build2.debian.tar.xz
linux ab5a03176ee106d3f0fa90e381da478ddae405918153cca248e682cd0c4a2269 bzip2_1.0.8.orig.tar.gz $ubuntu/pool/main/b/bzip2/bzip2_1.0.8.orig.tar.gz
linux 77c61e0490ff7cf49aa8ffe62e117e85b3af08e14341c4b851f8d7ec757fe099 bzip2_1.0.8-5.1ubuntu0.1.debian.tar.bz2 $ubuntu/pool/main/b/bzip2/bzip2_1.0.8-5.1ubuntu0.1.debian.tar.bz2
linux 87d782671790af480ab3c3b258885ad2c239e41772e0116ceda6446c493f9dd4 bzip2_1.0.8-5.1ubuntu0.1.dsc $ubuntu/pool/main/b/bzip2/bzip2_1.0.8-5.1ubuntu0.1.dsc
linux 88525753f79d3bec27d2fa7c66aa0b92b3aa9498dafd93d7cfa4b3780cdae313 openssl_3.0.13.orig.tar.gz $ubuntu/pool/main/o/openssl/openssl_3.0.13.orig.tar.gz
linux 77893129922c917792dd187c2853fb36ce1c98597c442b42d8190252959b7f6c openssl_3.0.13-0ubuntu3.16.debian.tar.xz $ubuntu2/pool/main/o/openssl/openssl_3.0.13-0ubuntu3.16.debian.tar.xz
linux 7d1e552a6f519836c352407e521bafdda7552ec6d1ba242968c35fc0e17f47b6 openssl_3.0.13-0ubuntu3.16.dsc $ubuntu2/pool/main/o/openssl/openssl_3.0.13-0ubuntu3.16.dsc
linux 99ee2ed8b98bcfad17bc57c2d9699d764f20fe29ad304c69b8eb28834ca3b48e freetype_2.13.2+dfsg.orig-ft2demos.tar.xz $ubuntu/pool/main/f/freetype/freetype_2.13.2+dfsg.orig-ft2demos.tar.xz
linux e58ba462f7bdcdc5899f777d33453c1ce6f6e46b080047580f45c9fd9f2dc08c freetype_2.13.2+dfsg.orig-ft2demos.tar.xz.asc $ubuntu/pool/main/f/freetype/freetype_2.13.2+dfsg.orig-ft2demos.tar.xz.asc
linux 685c25e1035a5076e5097186b3143b9c06878f3f9087d0a81e4d8538d5d15424 freetype_2.13.2+dfsg.orig-ft2docs.tar.xz $ubuntu/pool/main/f/freetype/freetype_2.13.2+dfsg.orig-ft2docs.tar.xz
linux d7e17c8a3bce50181530ebe06346f506cbfc92ecc5ca7cc395c7dbb24a71a5c0 freetype_2.13.2+dfsg.orig-ft2docs.tar.xz.asc $ubuntu/pool/main/f/freetype/freetype_2.13.2+dfsg.orig-ft2docs.tar.xz.asc
linux 48c78a4194adfcd15a4d089f3206dab8454c311f5577f3ef7eaef95f777f86e6 freetype_2.13.2+dfsg.orig.tar.xz $ubuntu/pool/main/f/freetype/freetype_2.13.2+dfsg.orig.tar.xz
linux 9d0624fe997a7574250dfac286275dc5469b49bdc7a4fe5814c1ca35110dcb5e freetype_2.13.2+dfsg-1ubuntu0.2.debian.tar.xz $ubuntu2/pool/main/f/freetype/freetype_2.13.2+dfsg-1ubuntu0.2.debian.tar.xz
linux c0dfb7768d0c48dfc294bbc3f6ce4826f7062b69a0adc0dc7bb8e1a7ad78bb71 freetype_2.13.2+dfsg-1ubuntu0.2.dsc $ubuntu2/pool/main/f/freetype/freetype_2.13.2+dfsg-1ubuntu0.2.dsc
linux 2df939e37ac9c71ed8de3c1dd55641f069fd2ed1bfa162e2ce03c819084ddbd1 fribidi_1.0.13-3build1.dsc $ubuntu/pool/main/f/fribidi/fribidi_1.0.13-3build1.dsc
linux 7fa16c80c81bd622f7b198d31356da139cc318a63fc7761217af4130903f54a2 fribidi_1.0.13.orig.tar.xz $ubuntu/pool/main/f/fribidi/fribidi_1.0.13.orig.tar.xz
linux d6dd83149e92d0c335f56d5abebcad96591d291ad3d242efeeebcdab38dd20fc fribidi_1.0.13-3build1.debian.tar.xz $ubuntu/pool/main/f/fribidi/fribidi_1.0.13-3build1.debian.tar.xz
linux 38680f78a0ae6258826418cb5096c19ae3566ba8fee0a2112a0ec40056e58729 glib2.0_2.80.0.orig-unicode-data.tar.xz $ubuntu/pool/main/g/glib2.0/glib2.0_2.80.0.orig-unicode-data.tar.xz
linux 8228a92f92a412160b139ae68b6345bd28f24434a7b5af150ebe21ff587a561d glib2.0_2.80.0.orig.tar.xz $ubuntu/pool/main/g/glib2.0/glib2.0_2.80.0.orig.tar.xz
linux b86a66201ce7957256126e4cd69248848ce61a9330891f24df3bc0859d1ca542 glib2.0_2.80.0-6ubuntu3.9.debian.tar.xz $ubuntu2/pool/main/g/glib2.0/glib2.0_2.80.0-6ubuntu3.9.debian.tar.xz
linux b70034bf6d40d9e1386c31e1cae6ce224a95660e473521aa065d163bdd491722 glib2.0_2.80.0-6ubuntu3.9.dsc $ubuntu2/pool/main/g/glib2.0/glib2.0_2.80.0-6ubuntu3.9.dsc
linux 7a3b342c5681921ce2e0c2496509d30b5b078399d5a7bd2358f95166d57d91df graphite2_1.3.14.orig.tar.gz $ubuntu/pool/main/g/graphite2/graphite2_1.3.14.orig.tar.gz
linux 1a860887ab20f97acd841511d2318649cee3a31ad990a150f2999c36afa72693 graphite2_1.3.14-2ubuntu0.24.04.1.debian.tar.xz $ubuntu/pool/main/g/graphite2/graphite2_1.3.14-2ubuntu0.24.04.1.debian.tar.xz
linux c34a4358cdadcf881fe9794e13c27650f1c5de7bcd3aaf9a40a908047c4d44b4 graphite2_1.3.14-2ubuntu0.24.04.1.dsc $ubuntu/pool/main/g/graphite2/graphite2_1.3.14-2ubuntu0.24.04.1.dsc
linux ed14238fb748b9c822ebc80618df9e6018c3f5b919e4c11d6c4b965b90c9d6cb harfbuzz_8.3.0-2build2.dsc $ubuntu/pool/main/h/harfbuzz/harfbuzz_8.3.0-2build2.dsc
linux 109501eaeb8bde3eadb25fab4164e993fbace29c3d775bcaa1c1e58e2f15f847 harfbuzz_8.3.0.orig.tar.xz $ubuntu/pool/main/h/harfbuzz/harfbuzz_8.3.0.orig.tar.xz
linux cc8df8c8bf0301e1067c7a63a7f7384933935f13cdace4451aa3c8239fca40aa harfbuzz_8.3.0-2build2.debian.tar.xz $ubuntu/pool/main/h/harfbuzz/harfbuzz_8.3.0-2build2.debian.tar.xz
linux 28474ea6f6591c4d4cee972123587001a4e6e353412a41b3e9e82219818d5740 lcms2_2.14.orig.tar.gz $ubuntu/pool/main/l/lcms2/lcms2_2.14.orig.tar.gz
linux 3687da63f57e4133918208fe841e72e3a526de91bdd50e55b2f2d564b085abad lcms2_2.14-2ubuntu0.1.debian.tar.xz $ubuntu/pool/main/l/lcms2/lcms2_2.14-2ubuntu0.1.debian.tar.xz
linux 26e71fc172df784f47b04ce18e4783982d83e859ade9c60c08bed9e7c539347a lcms2_2.14-2ubuntu0.1.dsc $ubuntu/pool/main/l/lcms2/lcms2_2.14-2ubuntu0.1.dsc
linux c33b418e3b936ee3153de2c61cc638e7e4fe3156022a5c77d0711bcbb9d64f1f pcre2_10.42.orig.tar.gz $ubuntu/pool/main/p/pcre2/pcre2_10.42.orig.tar.gz
linux 29c5cb6ff392544bf48bd3ec2a98aa0da5297457fa4f4199a1c392ec3d41f19c pcre2_10.42-4ubuntu2.1.diff.gz $ubuntu/pool/main/p/pcre2/pcre2_10.42-4ubuntu2.1.diff.gz
linux 6272177be186d6f8ad16b668bb508b2e07645e05b5b8402d446492cb6d18104e pcre2_10.42-4ubuntu2.1.dsc $ubuntu/pool/main/p/pcre2/pcre2_10.42-4ubuntu2.1.dsc
linux 0dcf296eccbde692802d16cb51fcbd3bc386e76a069609fd36f319b399492796 libplacebo_6.338.2-2build1.dsc $ubuntu/pool/universe/libp/libplacebo/libplacebo_6.338.2-2build1.dsc
linux 15e30f2fdce41ac1cd6475763dbf6a6011126bc7a4f901b1dcfaa4582b01ec30 libplacebo_6.338.2.orig.tar.xz $ubuntu/pool/universe/libp/libplacebo/libplacebo_6.338.2.orig.tar.xz
linux 02b192d87a8f3090d04e4b2683761d8cd4a80b433188018e1033df901ddfe9a9 libplacebo_6.338.2-2build1.debian.tar.xz $ubuntu/pool/universe/libp/libplacebo/libplacebo_6.338.2-2build1.debian.tar.xz
linux fecc95b46cf05e8e3fc8a414750e0ba5aad00d89e9fdf175e94ff041caf1a03a libpng1.6_1.6.43.orig.tar.gz $ubuntu/pool/main/libp/libpng1.6/libpng1.6_1.6.43.orig.tar.gz
linux ea25368d26281cff3282c18ffbec6c75335e6ba0458d79841936c959395c15e6 libpng1.6_1.6.43-5ubuntu0.7.debian.tar.xz $ubuntu2/pool/main/libp/libpng1.6/libpng1.6_1.6.43-5ubuntu0.7.debian.tar.xz
linux 956b23034b8dd709deb9cbaa13dd3e41d8dbef2a3042c7ff30166a72d8031b37 libpng1.6_1.6.43-5ubuntu0.7.dsc $ubuntu2/pool/main/libp/libpng1.6/libpng1.6_1.6.43-5ubuntu0.7.dsc
linux bb228cdb497d198cef494256d0069a43734ca70ec1137ccebeafba568a501465 libunibreak_5.1-2build1.dsc $ubuntu/pool/universe/libu/libunibreak/libunibreak_5.1-2build1.dsc
linux d59878d82c84a60b592940a8e1a264f0a9714f0a9a8868b099846f780d9dc167 libunibreak_5.1.orig.tar.gz $ubuntu/pool/universe/libu/libunibreak/libunibreak_5.1.orig.tar.gz
linux 20e1bc390e2464ff3df2dbcdc73c7359346239599ec3ce734b7d49c6da4cdfd2 libunibreak_5.1-2build1.debian.tar.xz $ubuntu/pool/universe/libu/libunibreak/libunibreak_5.1-2build1.debian.tar.xz
linux a19cbc3b537cd04f43a4511448bcd9fd0b065ea9a3f83efd590bac0a567b0752 libva_2.20.0.orig.tar.xz $ubuntu/pool/universe/libv/libva/libva_2.20.0.orig.tar.xz
linux b2173dfe95da760ec5633bf8c673bd305d5a69b2f0b1b6d6c21966cae58b06d3 libva_2.20.0-2ubuntu0.2.debian.tar.xz $ubuntu/pool/universe/libv/libva/libva_2.20.0-2ubuntu0.2.debian.tar.xz
linux d00d24e6d37e9eceb2bd6965164cdb8e1c9104723ea9548e2232fafb3e5dd617 libva_2.20.0-2ubuntu0.2.dsc $ubuntu/pool/universe/libv/libva/libva_2.20.0-2ubuntu0.2.dsc
linux aa994469e76b17847c1153d43cd37884adebbce5008ad221ef0e1e8ad840f8de libxcb_1.15-1ubuntu2.dsc $ubuntu/pool/main/libx/libxcb/libxcb_1.15-1ubuntu2.dsc
linux 1cb65df8543a69ec0555ac696123ee386321dfac1964a3da39976c9a05ad724d libxcb_1.15.orig.tar.gz $ubuntu/pool/main/libx/libxcb/libxcb_1.15.orig.tar.gz
linux d45e55f604af83b47f621c97b49885f35b518909bb3b9f9c877cfd3daddfb5ee libxcb_1.15-1ubuntu2.diff.gz $ubuntu/pool/main/libx/libxcb/libxcb_1.15-1ubuntu2.diff.gz
linux 0d2faa231cb2d5750994e8d93c4973cf4c24f74d8f1912234a1a244952d5e627 libxext_1.3.4-1build2.dsc $ubuntu/pool/main/libx/libxext/libxext_1.3.4-1build2.dsc
linux 8ef0789f282826661ff40a8eef22430378516ac580167da35cc948be9041aac1 libxext_1.3.4.orig.tar.gz $ubuntu/pool/main/libx/libxext/libxext_1.3.4.orig.tar.gz
linux ed434b3facecdf2c1f9ff58578033da61e087d73d6ce2d84433871ee4fb22ba6 libxext_1.3.4-1build2.diff.gz $ubuntu/pool/main/libx/libxext/libxext_1.3.4-1build2.diff.gz
linux 85c831269a0513da54f1cd6a816a9d2fa75d4bb04159079618fb2ecc5ea2b7ab libxfixes_6.0.0-2build1.dsc $ubuntu/pool/main/libx/libxfixes/libxfixes_6.0.0-2build1.dsc
linux 82045da5625350838390c9440598b90d69c882c324ca92f73af9f0e992cb57c7 libxfixes_6.0.0.orig.tar.gz $ubuntu/pool/main/libx/libxfixes/libxfixes_6.0.0.orig.tar.gz
linux e5598f42aa32140936c7772ab4d99ba35ecb859c29adc1703bb51440e7a54928 libxfixes_6.0.0.orig.tar.gz.asc $ubuntu/pool/main/libx/libxfixes/libxfixes_6.0.0.orig.tar.gz.asc
linux 4afbdb618c6f60e95c4251a8c08e928b19c3a19316dc9801c1e51c5c0b7f7a49 libxfixes_6.0.0-2build1.diff.gz $ubuntu/pool/main/libx/libxfixes/libxfixes_6.0.0-2build1.diff.gz
linux 411d02e86fec75d9d9cc7446a0547fb5abd7044afbcf183a37f72128fdb8dd06 libxpresent_1.0.0-2build2.dsc $ubuntu/pool/universe/libx/libxpresent/libxpresent_1.0.0-2build2.dsc
linux 92f1bdfb67ae2ffcdb25ad72c02cac5e4912dc9bc792858240df1d7f105946fa libxpresent_1.0.0.orig.tar.gz $ubuntu/pool/universe/libx/libxpresent/libxpresent_1.0.0.orig.tar.gz
linux f7608090926d141bb6399567ad9c75378a12064ae9b5e094dbed9d58d6524e50 libxpresent_1.0.0-2build2.diff.gz $ubuntu/pool/universe/libx/libxpresent/libxpresent_1.0.0-2build2.diff.gz
linux 733b5be4c178c017181334e36d6649455c78222b2a146bb732a03598f8da6ffc libxrandr_1.5.2-2build1.dsc $ubuntu/pool/main/libx/libxrandr/libxrandr_1.5.2-2build1.dsc
linux 3f10813ab355e7a09f17e147d61b0ce090d898a5ea5b5519acd0ef68675dcf8e libxrandr_1.5.2.orig.tar.gz $ubuntu/pool/main/libx/libxrandr/libxrandr_1.5.2.orig.tar.gz
linux 65e365ff8a3205d5532e9867045238dc6abbcdc146e063bafc7f614e730c1b7b libxrandr_1.5.2-2build1.diff.gz $ubuntu/pool/main/libx/libxrandr/libxrandr_1.5.2-2build1.diff.gz
linux fbc2eb120a290e733e01420e6bed5f7158a822e94baa8952b10bdcf5ae55a15e libxrender_0.9.10-1.1build1.dsc $ubuntu/pool/main/libx/libxrender/libxrender_0.9.10-1.1build1.dsc
linux 770527cce42500790433df84ec3521e8bf095dfe5079454a92236494ab296adf libxrender_0.9.10.orig.tar.gz $ubuntu/pool/main/libx/libxrender/libxrender_0.9.10.orig.tar.gz
linux 7a09639402d326f5c665c848286d26932ba3a1d104e41fb921500ca93895598b libxrender_0.9.10-1.1build1.diff.gz $ubuntu/pool/main/libx/libxrender/libxrender_0.9.10-1.1build1.diff.gz
linux 55513f76c0237edbd32ff2abd9584d7f5c665701ce923ec9c57bd0bb30cd8502 libxss_1.2.3-1build3.dsc $ubuntu/pool/main/libx/libxss/libxss_1.2.3-1build3.dsc
linux 4f74e7e412144591d8e0616db27f433cfc9f45aae6669c6c4bb03e6bf9be809a libxss_1.2.3.orig.tar.gz $ubuntu/pool/main/libx/libxss/libxss_1.2.3.orig.tar.gz
linux 4e900524d56c8e7263365267efa91bb3671110c9eb28ccab58f70e2188f0b91b libxss_1.2.3.orig.tar.gz.asc $ubuntu/pool/main/libx/libxss/libxss_1.2.3.orig.tar.gz.asc
linux 2bf37e033f8f4e743cce4c32db62b5e5974da7e48c77a891df74a5f302c2342d libxss_1.2.3-1build3.diff.gz $ubuntu/pool/main/libx/libxss/libxss_1.2.3-1build3.diff.gz
linux 5eea0322c1c21c75cad3b607ac1c43ff5c71e014b8ac4a34300b5e2b80d02e70 zlib_1.3.dfsg.orig.tar.xz $ubuntu/pool/main/z/zlib/zlib_1.3.dfsg.orig.tar.xz
linux de1c2d532ecb7caf99ccb905fb03c37fe0c8117fbdc79d05be4474b71e6398c2 zlib_1.3.dfsg-3.1ubuntu2.2.debian.tar.xz $ubuntu/pool/main/z/zlib/zlib_1.3.dfsg-3.1ubuntu2.2.debian.tar.xz
linux 56a7cd029863cbddafce831e4e63cccfa84017f93617929cb7c02f80bb5a122a zlib_1.3.dfsg-3.1ubuntu2.2.dsc $ubuntu/pool/main/z/zlib/zlib_1.3.dfsg-3.1ubuntu2.2.dsc
EOF
}

manifest() {
  upstream_manifest
  if $macos; then macos_manifest; else desktop_manifest; fi
}

rm -rf "$stage"
mkdir -p "$cache" "$stage"
count=0
while read -r dir sum file url; do
  [ -n "$dir" ] || continue
  if [ ! -f "$cache/$file" ] || ! echo "$sum  $cache/$file" | sha256sum --check --status; then
    echo "fetching $file"
    curl --fail --silent --show-error --location --retry 3 --max-time 1800 -o "$cache/$file.part" "$url"
    mv "$cache/$file.part" "$cache/$file"
  fi
  if ! echo "$sum  $cache/$file" | sha256sum --check --status; then
    echo "SHA-256 mismatch for $file from $url" >&2
    exit 1
  fi
  mkdir -p "$stage/$dir"
  ln -f "$cache/$file" "$stage/$dir/$file" 2>/dev/null || cp "$cache/$file" "$stage/$dir/$file"
  count=$((count + 1))
done < <(manifest)

manifest | awk '{ print $2 "  " $1 "/" $3 }' > "$stage/SHA256SUMS"

if $macos; then
  # A copy of each component's licence texts, taken from its own source archive.
  while read -r dir sum file url; do
    case "$file" in *.tar.gz | *.tgz | *.tar.xz | *.tar.bz2) ;; *) continue ;; esac
    members=$(tar -tf "$cache/$file" | grep -iE \
      '^[^/]+/(copying[^/]*|licen[cs]e[^/]*|copyright[^/]*|notice[^/]*|licenses/[^/]+|docs/(ftl|gplv2)\.txt|gettext-runtime/intl/copying[^/]*)$' || true)
    [ -n "$members" ] || continue
    dest="$stage/licenses/${file%.t*}"
    mkdir -p "$dest"
    echo "$members" | tar -xf "$cache/$file" -C "$dest" --strip-components=1 --files-from=-
  done < <(manifest)

  cat > "$stage/README.md" <<'EOF'
# Corresponding source for the mpv runtime in TMPlayer's macOS build

TMPlayer's macOS (Apple Silicon) build plays video through mediamp 0.5.2 (`org.openani.mediamp`,
by Open Ani, Apache-2.0), whose runtime jar `mediamp-mpv-runtime-macos-arm64` bundles libmpv,
FFmpeg and the dylibs listed below. This archive holds the exact source of each of them.
`SHA256SUMS` lists every file; `licenses/` holds a copy of each component's licence texts, taken
from its source archive.

TMPlayer's own source is at <https://github.com/dracu-lah/TMPlayer>, under GPL-3.0-or-later.

## Built from source by mediamp (`upstream/`)

| Component | Version | Licence | File |
|---|---|---|---|
| mediamp (the build scripts in `buildSrc/`, the JNI glue `libmediampv.dylib` with its Metal/IOSurface renderer, and `mediamp-mpv/render_d3d11.patch`, which is applied to the mpv tree on every platform) | 0.5.2 | Apache-2.0 | `mediamp-0.5.2.tar.gz` |
| mpv (`libmpv.2.dylib`) | 0.41.0, commit `41f6a645` | LGPL-2.1-or-later (`-Dgpl=false`) | `mpv-0.41.0.tar.gz` |
| FFmpeg (`libavutil.60`, `libavcodec.62`, `libavformat.62`, `libavfilter.11`, `libavdevice.62`, `libswresample.6`, `libswscale.9`) | release/8.0 branch, commit `449453a9` (reports 8.0.1) | LGPL-2.1-or-later (no `--enable-gpl`, no non-free parts) | `ffmpeg-8.0.1-449453a9.tar.gz` |
| dav1d (linked statically into libavcodec) | 1.5.4, commit `54706fc6` | BSD-2-Clause | `dav1d-1.5.4.tar.gz` |

mediamp builds these on its Apple Silicon runner for macOS 12.0 and later. The FFmpeg configure
line (`buildSrc/src/main/kotlin/ffmpeg/FfmpegTargets.kt`, macOS target: Secure Transport for TLS,
VideoToolbox, dav1d) is embedded in the shipped `libavutil`, and the mpv meson options
(`buildSrc/src/main/kotlin/mpv/MpvTargets.kt`, macOS target: Cocoa, Core Audio, OpenGL,
VideoToolbox, Swift) in the shipped `libmpv`.

## Homebrew libraries (`macos/`)

mediamp's release job installs `libass` and `libplacebo` from Homebrew and copies every non-system
dylib they need into the runtime, rewriting the install names to `@loader_path` and signing them
ad hoc. Each dylib below has the same LC_UUID and the same code (`__TEXT,__text`) as the dylib in
the Homebrew bottle of the version listed (`arm64_tahoe` for FreeType, HarfBuzz, Graphite2, libpng,
GLib, PCRE2 and libintl, `arm64_sequoia` for the rest). `macos/homebrew/` holds each formula
file as of the homebrew-core commit that published that bottle (the bottle's SHA-256 is in it):
the build recipe, configure options and patches. The source archives are the ones the formulae
name, with the SHA-256 they pin.

| Library (dylib) | Homebrew formula and version | Licence | Source |
|---|---|---|---|
| libass (`libass.9.dylib`) | libass 0.17.4 | ISC | `libass-0.17.4.tar.xz` |
| libplacebo (`libplacebo.351.dylib`) | libplacebo 7.351.0 (bottle rebuild 1) | LGPL-2.1-or-later | `libplacebo-v7.351.0.tar.bz2`, plus the build-time generators glad2 2.0.8 (MIT), Jinja2 3.1.6 and MarkupSafe 3.0.2 (BSD-3-Clause) |
| shaderc (`libshaderc_shared.1.dylib`) | shaderc 2025.3 | Apache-2.0 | `shaderc-2025.3.tar.gz` |
| glslang (static, inside shaderc) | 15.3.0, commit `efd24d75` | BSD-3-Clause and others, see its LICENSE.txt | `glslang-15.3.0-efd24d75.tar.gz` |
| SPIRV-Tools (static, inside shaderc) | commit `33e02568` | Apache-2.0 | `SPIRV-Tools-33e02568.tar.gz` |
| SPIRV-Headers (headers, inside shaderc) | commit `2a611a97` | MIT | `SPIRV-Headers-2a611a97.tar.gz` |
| Vulkan loader (`libvulkan.1.4.326.dylib`) | vulkan-loader 1.4.326 | Apache-2.0 | `vulkan-loader-1.4.326.tar.gz` |
| Little CMS (`liblcms2.2.dylib`) | little-cms2 2.17 | MIT | `lcms2-2.17.tar.gz` |
| FreeType (`libfreetype.6.dylib`) | freetype 2.14.3 | FTL or GPL-2.0-or-later | `freetype-2.14.3.tar.xz` |
| FriBidi (`libfribidi.0.dylib`) | fribidi 1.0.16 | LGPL-2.1-or-later | `fribidi-1.0.16.tar.xz` |
| HarfBuzz (`libharfbuzz.0.dylib`) | harfbuzz 14.2.1 | MIT | `harfbuzz-14.2.1.tar.xz` |
| Graphite2 (`libgraphite2.3.3.1.dylib`) | graphite2 1.3.15 | LGPL-2.1-or-later, GPL-2.0-or-later or MPL-1.1, at your choice | `graphite2-1.3.15.tgz` |
| libunibreak (`libunibreak.6.dylib`) | libunibreak 6.1 | Zlib | `libunibreak-6.1.tar.gz` |
| libpng (`libpng16.16.dylib`) | libpng 1.6.58 | libpng-2.0 | `libpng-1.6.58.tar.xz` |
| GLib (`libglib-2.0.0.dylib`) | glib 2.88.2, with Homebrew's `glib-hardcoded-paths.diff` | LGPL-2.1-or-later | `glib-2.88.2.tar.xz`, plus gobject-introspection 1.86.0 (LGPL-2.1-or-later and GPL-2.0-or-later), which the formula uses at build time for the introspection data |
| PCRE2 (`libpcre2-8.0.dylib`) | pcre2 10.47 (formula revision 1) | BSD-3-Clause | `pcre2-10.47.tar.bz2` |
| GNU libintl, from gettext (`libintl.8.dylib`) | gettext 1.0 (bottle rebuild 1) | LGPL-2.1-or-later | `gettext-1.0.tar.gz` |

zlib, bzip2, libiconv, libc++ and the system frameworks are part of macOS and are not shipped.

libplacebo also compiles in two header-only build dependencies that Homebrew does not pin to a
version: fast_float (MIT, Apache-2.0 or BSL-1.0) and the Vulkan headers (Apache-2.0 or MIT). Their
versions cannot be read from the shipped dylib, so they are not included here; both are
permissively licensed and available from their upstream projects.
EOF
else
cat > "$stage/README.md" <<'EOF'
# Corresponding source for the mpv runtime in TMPlayer's desktop builds

TMPlayer's Windows and Linux builds play video through mediamp 0.5.2 (`org.openani.mediamp`, by
Open Ani, Apache-2.0), whose runtime jars `mediamp-mpv-runtime-windows-x64` and
`mediamp-mpv-runtime-linux-x64` bundle libmpv, FFmpeg and the shared libraries listed below. This
archive holds the exact source of each of them. `SHA256SUMS` lists every file.

TMPlayer's own source is at <https://github.com/dracu-lah/TMPlayer>, under GPL-3.0-or-later.

## Built from source by mediamp (`upstream/`)

| Component | Version | Licence | File |
|---|---|---|---|
| mediamp (the build scripts in `buildSrc/`, the JNI glue `libmediampv`/`mediampv.dll`, and `mediamp-mpv/render_d3d11.patch`, applied to mpv on Windows) | 0.5.2 | Apache-2.0 | `mediamp-0.5.2.tar.gz` |
| mpv (libmpv) | 0.41.0, commit `41f6a645` | Linux build: GPL-2.0-or-later (the `gpl` feature is on). Windows build: LGPL-2.1-or-later (`-Dgpl=false`) | `mpv-0.41.0.tar.gz` |
| FFmpeg | release/8.0 branch, commit `449453a9` (reports 8.0.1) | LGPL-2.1-or-later (no `--enable-gpl`, no non-free parts) | `ffmpeg-8.0.1-449453a9.tar.gz` |
| dav1d (linked statically into libavcodec) | 1.5.4, commit `54706fc6` | BSD-2-Clause | `dav1d-1.5.4.tar.gz` |

The FFmpeg configure line and the mpv meson options are generated by mediamp's `buildSrc`
(`mpv/MpvSupport.kt`, `ffmpeg/`); the configure line is also embedded in each shipped
`libavutil`. The mediamp and mpv tarballs are GitHub archives of the tag and commit above; mediamp's
git submodules (mpv, FFmpeg, dav1d) are the three other archives.

## Windows x64 (`windows/`)

MSYS2 UCRT64 source packages: each holds the PKGBUILD, MSYS2's patches and the upstream source.
Every DLL in the runtime jar is byte for byte the one in the binary package of the same version.

| Library (DLL) | Package version | Licence |
|---|---|---|
| libass (`libass-9.dll`) | 0.17.5-1 | ISC |
| libplacebo (`libplacebo-360.dll`) | 7.360.1-2 | LGPL-2.1-or-later |
| shaderc (`libshaderc_shared.dll`) | 2026.4-1 | Apache-2.0 |
| glslang (static, inside shaderc) | 16.3.0-1 | BSD-3-Clause and others, see its LICENSE.txt |
| SPIRV-Tools (static, inside shaderc) | 1.4.363.0-1 | Apache-2.0 |
| SPIRV-Headers (headers, inside shaderc) | 1.4.363.0-1 | MIT |
| SPIRV-Cross (`libspirv-cross-c-shared.dll`) | 1.4.363.0-1 | Apache-2.0 |
| Vulkan loader (`vulkan-1.dll`) | 1.4.363.0-1 | Apache-2.0 |
| libdovi (`libdovi.dll`) | 3.4.0-1 | MIT |
| Little CMS (`liblcms2-2.dll`) | 2.19.1-1 | MIT |
| FreeType (`libfreetype-6.dll`) | 2.15.0-1 | FTL or GPL-2.0-or-later |
| FriBidi (`libfribidi-0.dll`) | 1.0.17-1 | LGPL-2.1-or-later |
| HarfBuzz (`libharfbuzz-0.dll`) | 14.6.0-1 | MIT |
| Graphite2 (`libgraphite2.dll`) | 1.3.15-1 | LGPL-2.1-or-later |
| libunibreak (`libunibreak-7.dll`) | 7.0-1 | Zlib |
| Fontconfig (`libfontconfig-1.dll`) | 2.18.3-1 | HPND-style Fontconfig licence |
| Expat (`libexpat-1.dll`) | 2.9.0-1 | MIT |
| libpng (`libpng16-16.dll`) | 1.6.59-1 | libpng-2.0 |
| zlib (`zlib1.dll`) | 1.3.2-2 | Zlib |
| bzip2 (`libbz2-1.dll`) | 1.0.8-4 | bzip2-1.0.6 |
| Brotli (`libbrotlicommon.dll`, `libbrotlidec.dll`) | 1.2.0-1 | MIT |
| GLib (`libglib-2.0-0.dll`) | 2.90.1-1 | LGPL-2.1-or-later |
| PCRE2 (`libpcre2-8-0.dll`) | 10.49-1 | BSD-3-Clause |
| GNU libintl, from gettext (`libintl-8.dll`) | 1.0-1 | LGPL-2.1-or-later |
| GNU libiconv (`libiconv-2.dll`) | 1.19-1 | LGPL-2.1-or-later |
| OpenSSL (`libcrypto-3-x64.dll`, `libssl-3-x64.dll`) | 3.6.5-1 | Apache-2.0 |
| GCC runtime (`libgcc_s_seh-1.dll`, `libstdc++-6.dll`) | gcc 16.2.0-6 | GPL-3.0-or-later WITH GCC-exception-3.1 |
| winpthreads (`libwinpthread-1.dll`) | 14.0.0.r426.g4564ee4b5-2 | MIT and BSD-3-Clause-Clear |

## Linux x64 (`linux/`)

Ubuntu 24.04 source packages (`.dsc`, upstream `.orig` tarball and Ubuntu's `.debian` changes;
unpack with `dpkg-source -x <name>.dsc`). Each library's GNU build ID matches the Ubuntu binary
package of the same version.

| Library | Source package version | Licence |
|---|---|---|
| libass (`libass.so.9`) | libass 1:0.17.1-2build1 | ISC |
| libplacebo (`libplacebo.so.338`) | libplacebo 6.338.2-2build1 | LGPL-2.1-or-later |
| Little CMS (`liblcms2.so.2`) | lcms2 2.14-2ubuntu0.1 | MIT |
| FreeType (`libfreetype.so.6`) | freetype 2.13.2+dfsg-1ubuntu0.2 | FTL or GPL-2.0-or-later |
| FriBidi (`libfribidi.so.0`) | fribidi 1.0.13-3build1 | LGPL-2.1-or-later |
| HarfBuzz (`libharfbuzz.so.0`) | harfbuzz 8.3.0-2build2 | MIT |
| Graphite2 (`libgraphite2.so.3`) | graphite2 1.3.14-2ubuntu0.24.04.1 | LGPL-2.1-or-later |
| libunibreak (`libunibreak.so.5`) | libunibreak 5.1-2build1 | Zlib |
| libpng (`libpng16.so.16`) | libpng1.6 1.6.43-5ubuntu0.7 | libpng-2.0 |
| zlib (`libz.so.1`) | zlib 1:1.3.dfsg-3.1ubuntu2.2 | Zlib |
| bzip2 (`libbz2.so.1.0`) | bzip2 1.0.8-5.1ubuntu0.1 | bzip2-1.0.6 |
| Brotli (`libbrotlicommon.so.1`, `libbrotlidec.so.1`) | brotli 1.1.0-2build2 | MIT |
| GLib (`libglib-2.0.so.0`) | glib2.0 2.80.0-6ubuntu3.9 | LGPL-2.1-or-later |
| PCRE2 (`libpcre2-8.so.0`) | pcre2 10.42-4ubuntu2.1 | BSD-3-Clause |
| OpenSSL (`libcrypto.so.3`, `libssl.so.3`) | openssl 3.0.13-0ubuntu3.16 | Apache-2.0 |
| libva (`libva.so.2`, `libva-drm.so.2`, `libva-x11.so.2`) | libva 2.20.0-2ubuntu0.2 | MIT |
| libxcb (`libxcb-dri3.so.0`) | libxcb 1.15-1ubuntu2 | MIT |
| libXext (`libXext.so.6`) | libxext 2:1.3.4-1build2 | MIT |
| libXfixes (`libXfixes.so.3`) | libxfixes 1:6.0.0-2build1 | MIT |
| libXpresent (`libXpresent.so.1`) | libxpresent 1.0.0-2build2 | MIT |
| libXrandr (`libXrandr.so.2`) | libxrandr 2:1.5.2-2build1 | MIT |
| libXrender (`libXrender.so.1`) | libxrender 1:0.9.10-1.1build1 | MIT |
| libXss (`libXss.so.1`) | libxss 1:1.2.3-1build3 | MIT |

On Linux the FFmpeg build also compiles in the NVIDIA codec headers (nv-codec-headers, MIT,
headers only) from Ubuntu's `libffmpeg-nvenc-dev`; no library of theirs is shipped.
EOF
fi

tar -C "$out" --owner=0 --group=0 --sort=name --mtime='2026-10-16 00:00Z' -chf - "$NAME" \
  | gzip -n > "$out/$NAME.tar.gz"
echo "wrote $out/$NAME.tar.gz ($count files, $(du -h "$out/$NAME.tar.gz" | cut -f1))"
sha256sum "$out/$NAME.tar.gz"
