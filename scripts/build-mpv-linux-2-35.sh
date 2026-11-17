#!/usr/bin/env bash
# Rebuilds the mpv runtime libraries that keep mediamp-mpv-runtime-linux-x64-0.5.2 off glibc 2.35
# (Ubuntu 22.04, Pop!_OS 22.04, Mint 21; issue #30), in an Ubuntu 22.04 container with podman.
#
# mediamp builds that runtime on Ubuntu 24.04, so seven of its libraries ask for glibc 2.38 (only
# through the __isoc23_* aliases and, in GLib, strlcpy/strlcat that the 24.04 headers pick) and two
# for GLIBCXX_3.4.32 (GCC 13's std::ios_base_library_init). Rebuilt from the same sources on 22.04
# they need glibc 2.35 and, with libstdc++ linked in statically, no GLIBCXX at all:
#
#   libmpv.so.2.6.0 (also shipped as libmpv.so and libmpv.so.2), libass.so.9, libcrypto.so.3,
#   libglib-2.0.so.0, libharfbuzz.so.0, libplacebo.so.338, libva.so.2, and libmediampv.so.
#
# Same versions as the shipped files: libmpv from mediamp's mpv submodule commit with the meson
# options embedded in the shipped libmpv; the six Ubuntu libraries from the Ubuntu 24.04 source
# packages scripts/build-mediamp-source.sh proved by GNU build ID, with Debian's options; libplacebo
# with the glslang 14.0.0 and SPIRV-Tools 2023.6~rc1 that Ubuntu linked into it (the shipped file
# reports "glslang Khronos. 14.0.0"); libmediampv from mediamp 0.5.2 with its compiler line. The
# FFmpeg libraries and everything else in the jar are not rebuilt: they load on 2.35 as they are,
# and libmpv is linked against the jar's own FFmpeg. natives/mpv-linux/README.md has the details
# and the corresponding source statement.
#
# Every input comes from a permanent address and is checked against the SHA-256 pinned below; a
# mismatch stops the script. The build image is pinned by digest and installs its packages from
# one Ubuntu snapshot day (natives/mpv-linux/Containerfile).
#
# Usage: scripts/build-mpv-linux-2-35.sh [output dir]
#        scripts/build-mpv-linux-2-35.sh --source-archive [dir]   (default build/mpv-linux-2-35-source)
#   output   default ~/.cache/tmplayer-natives/mpv (the libraries, SHA256SUMS, BUILDINFO)
#   --source-archive fetches and checks the same inputs and writes the corresponding source of
#            these builds (every pinned source plus this script and natives/mpv-linux) as
#            tmplayer-mpv-linux-2-35-source.tar.gz, without building; it goes beside the archive
#            from scripts/build-mediamp-source.sh (natives/mpv-linux/README.md).
#   downloads are kept in ~/.cache/tmplayer-natives/mpv-downloads (TMPLAYER_MPV_DOWNLOADS), so a
#   second run fetches nothing. JOBS (default 6) caps the parallel compile jobs.
# Then natives/mpv-linux/check-floor.sh runs on the output, and natives/mpv-linux/smoke-test.sh
# plays a video with the swapped runtime inside a plain ubuntu:22.04.
set -euo pipefail

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
recipe="$root/natives/mpv-linux"
source_archive=false
if [ "${1:-}" = "--source-archive" ]; then
  source_archive=true
  shift
  out="${1:-$root/build/mpv-linux-2-35-source}"
else
  out="${1:-$HOME/.cache/tmplayer-natives/mpv}"
fi
cache="${TMPLAYER_MPV_DOWNLOADS:-$HOME/.cache/tmplayer-natives/mpv-downloads}"
jobs="${JOBS:-6}"
image="tmplayer-mpv-linux-2-35"

# The recipe is for this mediamp; a bump means a different runtime to rebuild.
used="$(sed -n 's/^mediamp = "\(.*\)"$/\1/p' "$root/gradle/libs.versions.toml")"
if [ "$used" != "0.5.2" ]; then
  echo "mediamp is $used in libs.versions.toml, but this recipe rebuilds the 0.5.2 runtime" >&2
  exit 1
fi
github="https://github.com"
maven="https://repo1.maven.org/maven2"
ubuntu="https://snapshot.ubuntu.com/ubuntu/20260919T000000Z"
ubuntu2="https://snapshot.ubuntu.com/ubuntu/20261016T000000Z"

# SHA-256, file name, address. The first block is the same set build-mediamp-source.sh pins
# (mediamp, mpv, FFmpeg and the Ubuntu 24.04 packages of the rebuilt libraries); the second is what
# libplacebo, libmpv and the build need on top (Ubuntu 24.04 release pocket, the versions the
# shipped files were built with); the jar is the runtime itself, from Maven Central.
manifest() {
  cat <<EOF
74886d8b52beb46f4010ade38d0615b7be84e8d07007d471ab39c44cd26215fd mediamp-0.5.2.tar.gz $github/open-ani/mediamp/archive/refs/tags/v0.5.2.tar.gz
068960e89211f2adc80af03f637fa393fb5e407d3dd23592c8fe8c5490bb7ae4 mpv-0.41.0.tar.gz $github/mpv-player/mpv/archive/41f6a645068483470267271e1d09966ca3b9f413.tar.gz
269e43f5560fb647ee8df47b5b09fabaf3de2ad24dd0ee14c9f690b85b873ccc ffmpeg-8.0.1-449453a9.tar.gz $github/FFmpeg/FFmpeg/archive/449453a9713aa1af31a12e148fb0caba80ed02d7.tar.gz
8ac2a33db1e08aa1d73f9022b71539f10f344bdea47b6f3dcf75186a1c1de27f libass_0.17.1-2build1.dsc $ubuntu/pool/universe/liba/libass/libass_0.17.1-2build1.dsc
d653be97198a0543c69111122173c41a99e0b91426f9e17f06a858982c2fb03d libass_0.17.1.orig.tar.gz $ubuntu/pool/universe/liba/libass/libass_0.17.1.orig.tar.gz
699d1ba876d7bac95dbac56bac0f00126473e8588759a4ec13cdf52ae03d8483 libass_0.17.1.orig.tar.gz.asc $ubuntu/pool/universe/liba/libass/libass_0.17.1.orig.tar.gz.asc
605dadc3d805d2d7bd197a7b21e6a7b8533eb9372f47e8690788ba96905556f4 libass_0.17.1-2build1.debian.tar.xz $ubuntu/pool/universe/liba/libass/libass_0.17.1-2build1.debian.tar.xz
bb228cdb497d198cef494256d0069a43734ca70ec1137ccebeafba568a501465 libunibreak_5.1-2build1.dsc $ubuntu/pool/universe/libu/libunibreak/libunibreak_5.1-2build1.dsc
d59878d82c84a60b592940a8e1a264f0a9714f0a9a8868b099846f780d9dc167 libunibreak_5.1.orig.tar.gz $ubuntu/pool/universe/libu/libunibreak/libunibreak_5.1.orig.tar.gz
20e1bc390e2464ff3df2dbcdc73c7359346239599ec3ce734b7d49c6da4cdfd2 libunibreak_5.1-2build1.debian.tar.xz $ubuntu/pool/universe/libu/libunibreak/libunibreak_5.1-2build1.debian.tar.xz
88525753f79d3bec27d2fa7c66aa0b92b3aa9498dafd93d7cfa4b3780cdae313 openssl_3.0.13.orig.tar.gz $ubuntu/pool/main/o/openssl/openssl_3.0.13.orig.tar.gz
77893129922c917792dd187c2853fb36ce1c98597c442b42d8190252959b7f6c openssl_3.0.13-0ubuntu3.16.debian.tar.xz $ubuntu2/pool/main/o/openssl/openssl_3.0.13-0ubuntu3.16.debian.tar.xz
7d1e552a6f519836c352407e521bafdda7552ec6d1ba242968c35fc0e17f47b6 openssl_3.0.13-0ubuntu3.16.dsc $ubuntu2/pool/main/o/openssl/openssl_3.0.13-0ubuntu3.16.dsc
38680f78a0ae6258826418cb5096c19ae3566ba8fee0a2112a0ec40056e58729 glib2.0_2.80.0.orig-unicode-data.tar.xz $ubuntu/pool/main/g/glib2.0/glib2.0_2.80.0.orig-unicode-data.tar.xz
8228a92f92a412160b139ae68b6345bd28f24434a7b5af150ebe21ff587a561d glib2.0_2.80.0.orig.tar.xz $ubuntu/pool/main/g/glib2.0/glib2.0_2.80.0.orig.tar.xz
b86a66201ce7957256126e4cd69248848ce61a9330891f24df3bc0859d1ca542 glib2.0_2.80.0-6ubuntu3.9.debian.tar.xz $ubuntu2/pool/main/g/glib2.0/glib2.0_2.80.0-6ubuntu3.9.debian.tar.xz
b70034bf6d40d9e1386c31e1cae6ce224a95660e473521aa065d163bdd491722 glib2.0_2.80.0-6ubuntu3.9.dsc $ubuntu2/pool/main/g/glib2.0/glib2.0_2.80.0-6ubuntu3.9.dsc
ed14238fb748b9c822ebc80618df9e6018c3f5b919e4c11d6c4b965b90c9d6cb harfbuzz_8.3.0-2build2.dsc $ubuntu/pool/main/h/harfbuzz/harfbuzz_8.3.0-2build2.dsc
109501eaeb8bde3eadb25fab4164e993fbace29c3d775bcaa1c1e58e2f15f847 harfbuzz_8.3.0.orig.tar.xz $ubuntu/pool/main/h/harfbuzz/harfbuzz_8.3.0.orig.tar.xz
cc8df8c8bf0301e1067c7a63a7f7384933935f13cdace4451aa3c8239fca40aa harfbuzz_8.3.0-2build2.debian.tar.xz $ubuntu/pool/main/h/harfbuzz/harfbuzz_8.3.0-2build2.debian.tar.xz
0dcf296eccbde692802d16cb51fcbd3bc386e76a069609fd36f319b399492796 libplacebo_6.338.2-2build1.dsc $ubuntu/pool/universe/libp/libplacebo/libplacebo_6.338.2-2build1.dsc
15e30f2fdce41ac1cd6475763dbf6a6011126bc7a4f901b1dcfaa4582b01ec30 libplacebo_6.338.2.orig.tar.xz $ubuntu/pool/universe/libp/libplacebo/libplacebo_6.338.2.orig.tar.xz
02b192d87a8f3090d04e4b2683761d8cd4a80b433188018e1033df901ddfe9a9 libplacebo_6.338.2-2build1.debian.tar.xz $ubuntu/pool/universe/libp/libplacebo/libplacebo_6.338.2-2build1.debian.tar.xz
a19cbc3b537cd04f43a4511448bcd9fd0b065ea9a3f83efd590bac0a567b0752 libva_2.20.0.orig.tar.xz $ubuntu/pool/universe/libv/libva/libva_2.20.0.orig.tar.xz
b2173dfe95da760ec5633bf8c673bd305d5a69b2f0b1b6d6c21966cae58b06d3 libva_2.20.0-2ubuntu0.2.debian.tar.xz $ubuntu/pool/universe/libv/libva/libva_2.20.0-2ubuntu0.2.debian.tar.xz
d00d24e6d37e9eceb2bd6965164cdb8e1c9104723ea9548e2232fafb3e5dd617 libva_2.20.0-2ubuntu0.2.dsc $ubuntu/pool/universe/libv/libva/libva_2.20.0-2ubuntu0.2.dsc
c098a094cdc19650565a5a0a04a23b975654303956b46f1d3dd5207314c860d5 glslang_14.0.0-2.dsc $ubuntu/pool/universe/g/glslang/glslang_14.0.0-2.dsc
80bbb916a23e94ea9cbfb1acb5d1a44a7e0c9613bcf5b5947c03f2273bdc92b0 glslang_14.0.0.orig.tar.gz $ubuntu/pool/universe/g/glslang/glslang_14.0.0.orig.tar.gz
c6cee472f08ccb0d71a91e8ae2b41ea8b99a437bed0ad1f5f5c6855d3eafd8ae glslang_14.0.0-2.diff.gz $ubuntu/pool/universe/g/glslang/glslang_14.0.0-2.diff.gz
40c8cc697af05dec5fa0cf783202c1bc13edbc580dcff9dbff4c34ffe156562a spirv-tools_2023.6~rc1-2.dsc $ubuntu/pool/universe/s/spirv-tools/spirv-tools_2023.6~rc1-2.dsc
8ec5f3d3507a55e43f8fbda4ef61a7674d7aea93a800535f5b9abf9145043908 spirv-tools_2023.6~rc1.orig.tar.gz $ubuntu/pool/universe/s/spirv-tools/spirv-tools_2023.6~rc1.orig.tar.gz
e21ec0ced794b8c75cecf727eadb7db578701d04c25155444979814c9d02baf8 spirv-tools_2023.6~rc1-2.debian.tar.xz $ubuntu/pool/universe/s/spirv-tools/spirv-tools_2023.6~rc1-2.debian.tar.xz
1a248f4199f4a30b2e7304ed4d62f731765bab12d9fa4c5abb189a5e2d57f1f5 SPIRV-Headers-1c6bb274.tar.gz $github/KhronosGroup/SPIRV-Headers/archive/1c6bb2743599e6eb6f37b2969acc0aef812e32e3.tar.gz
e158bda184efcdbb5a063dc2e008ec5ab943ea4f1825eb9cb86615a190df05e5 vulkan-loader_1.3.275.0-1build1.dsc $ubuntu/pool/main/v/vulkan-loader/vulkan-loader_1.3.275.0-1build1.dsc
5f95219fc189b4c09ce537d8ec1ea4b5861b087cd00b6d821f99aecf6e9dddce vulkan-loader_1.3.275.0.orig.tar.xz $ubuntu/pool/main/v/vulkan-loader/vulkan-loader_1.3.275.0.orig.tar.xz
16b22d9a95fb35ae1e9d9d89f26b4f27305f86700b20615c23618014ab184ac9 vulkan-loader_1.3.275.0-1build1.debian.tar.xz $ubuntu/pool/main/v/vulkan-loader/vulkan-loader_1.3.275.0-1build1.debian.tar.xz
d2bcd3ca27047a0b8ce6a52dfc9eda1382b3fc02b919d47a980e7354a9193faf nv-codec-headers_12.1.14.0-1.dsc $ubuntu/pool/universe/n/nv-codec-headers/nv-codec-headers_12.1.14.0-1.dsc
0ca1a0c85b149646fa6ce4d600584507fdccf5a23d58e668d8701e89cee0c3fb nv-codec-headers_12.1.14.0.orig.tar.xz $ubuntu/pool/universe/n/nv-codec-headers/nv-codec-headers_12.1.14.0.orig.tar.xz
4f361e870a83a17139b15a8068eedccd8f0e153778e9c5303efc686f43b7cc52 nv-codec-headers_12.1.14.0-1.debian.tar.xz $ubuntu/pool/universe/n/nv-codec-headers/nv-codec-headers_12.1.14.0-1.debian.tar.xz
a500864f82084ce3c431c0f4a233fb1c0c48a3a8486cc49989ea073e05814d31 xxhash_0.8.2-2build1.dsc $ubuntu/pool/main/x/xxhash/xxhash_0.8.2-2build1.dsc
baee0c6afd4f03165de7a4e67988d16f0f2b257b51d0e3cb91909302a26a79c4 xxhash_0.8.2.orig.tar.gz $ubuntu/pool/main/x/xxhash/xxhash_0.8.2.orig.tar.gz
2ec69c1d868b3634a91c6f9e4921b86101fd9384b186f2401fdbac532c78830c xxhash_0.8.2-2build1.debian.tar.xz $ubuntu/pool/main/x/xxhash/xxhash_0.8.2-2build1.debian.tar.xz
2d3c8a49d0bbd5371c079605f030943138f753842f623a5846f03dba73ce3faf python-glad_2.0.2-0.1.dsc $ubuntu/pool/universe/p/python-glad/python-glad_2.0.2-0.1.dsc
0326d59b1ccf8ccae1fface5bfb71bec1bb41fbe2341e722b31f6acac3068aeb python-glad_2.0.2.orig.tar.gz $ubuntu/pool/universe/p/python-glad/python-glad_2.0.2.orig.tar.gz
9948ed21abdd19321cb624442ff2e5e8a22c3c2eb551dd421ff01d17b674a983 python-glad_2.0.2-0.1.debian.tar.xz $ubuntu/pool/universe/p/python-glad/python-glad_2.0.2-0.1.debian.tar.xz
2dd0118cc6cf52c12251aee5848259a9f98e9eb92a095417444c4619a1e7b088 meson_1.3.2-1ubuntu1.dsc $ubuntu/pool/universe/m/meson/meson_1.3.2-1ubuntu1.dsc
492eb450c8b073024276f916f5adbb3c4bb7e90e9e6ec124efda064f3d9b5baa meson_1.3.2.orig.tar.gz $ubuntu/pool/universe/m/meson/meson_1.3.2.orig.tar.gz
eec1fa1e1f1e9a7037db18bec1eea1e3d1fd288e894c84fce9b6bade28fd9229 meson_1.3.2-1ubuntu1.debian.tar.xz $ubuntu/pool/universe/m/meson/meson_1.3.2-1ubuntu1.debian.tar.xz
d1169c752a587f8d96311e773cda057ca8563fc5c8cb5088f299d61c95d0a15a mediamp-mpv-runtime-linux-x64-0.5.2.jar $maven/org/openani/mediamp/mediamp-mpv-runtime-linux-x64/0.5.2/mediamp-mpv-runtime-linux-x64-0.5.2.jar
EOF
}

mkdir -p "$cache" "$out"
cache=$(cd "$cache" && pwd)
out=$(cd "$out" && pwd)

while read -r sum file url; do
  [ -n "$sum" ] || continue
  if [ ! -f "$cache/$file" ] || ! echo "$sum  $cache/$file" | sha256sum --check --status; then
    echo "fetching $file"
    curl --fail --silent --show-error --location --retry 3 --max-time 1800 \
      -o "$cache/$file.part" "$url"
    mv "$cache/$file.part" "$cache/$file"
  fi
  if ! echo "$sum  $cache/$file" | sha256sum --check --status; then
    echo "SHA-256 mismatch for $file from $url" >&2
    exit 1
  fi
done < <(manifest)

if $source_archive; then
  name="tmplayer-mpv-linux-2-35-source"
  stage="$out/$name"
  rm -rf "$stage"
  mkdir -p "$stage/sources" "$stage/recipe/scripts" "$stage/recipe/natives"
  # The jar is the binary runtime the libraries go into, not source; its own source is the
  # build-mediamp-source.sh archive.
  manifest | grep -v ' mediamp-mpv-runtime-linux-x64-' | while read -r sum file url; do
    ln -f "$cache/$file" "$stage/sources/$file" 2>/dev/null || cp "$cache/$file" "$stage/sources/$file"
  done
  cp "$root/scripts/build-mpv-linux-2-35.sh" "$stage/recipe/scripts/"
  cp -r "$recipe" "$stage/recipe/natives/"
  manifest | grep -v ' mediamp-mpv-runtime-linux-x64-' | awk '{ print $1 "  sources/" $2 }' \
    > "$stage/SHA256SUMS"
  tar -C "$out" --sort=name --owner=0 --group=0 --numeric-owner --mtime=@1792108800 \
    -czf "$out/$name.tar.gz" "$name"
  rm -rf "$stage"
  echo "wrote $out/$name.tar.gz ($(du -h "$out/$name.tar.gz" | cut -f1))"
  exit 0
fi

command -v podman >/dev/null || { echo "podman is needed (rootless is fine)" >&2; exit 1; }
echo "building the image $image (Ubuntu 22.04)"
podman build -q -t "$image" -f "$recipe/Containerfile" "$recipe" >/dev/null

# label=disable: the mounts are read by the container as they are, no SELinux relabel of the
# user's folders. The build tree lives in the container and goes with it.
podman run --rm --security-opt label=disable -e JOBS="$jobs" \
  -v "$cache:/src:ro" -v "$out:/out" "$image" bash /usr/local/bin/build-in-container.sh

"$recipe/check-floor.sh" "$out"
echo "done: $out"
