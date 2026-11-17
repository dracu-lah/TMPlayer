#!/usr/bin/env bash
# Runs inside the image built from natives/mpv-linux/Containerfile (Ubuntu 22.04, glibc 2.35) and
# rebuilds the mpv runtime libraries that need glibc 2.38 in mediamp-mpv-runtime-linux-x64-0.5.2,
# at the same versions and with the same options as the shipped ones:
#
#   libcrypto.so.3      OpenSSL 3.0.13-0ubuntu3.16      Ubuntu 24.04 source package, Debian's configure
#   libglib-2.0.so.0    GLib 2.80.0-6ubuntu3.9          Ubuntu 24.04 source package, Debian's options
#   libharfbuzz.so.0    HarfBuzz 8.3.0-2build2          Ubuntu 24.04 source package (glib, freetype, graphite2)
#   libass.so.9         libass 0.17.1-2build1           Ubuntu 24.04 source package, Debian's configure
#   libva.so.2          libva 2.20.0-2ubuntu0.2         Ubuntu 24.04 source package, Debian's options
#   libplacebo.so.338   libplacebo 6.338.2-2build1      Ubuntu 24.04 source package, with glslang 14.0.0-2,
#                                                       SPIRV-Tools 2023.6~rc1-2 and SPIRV-Headers linked in
#                                                       statically, as Ubuntu's build did
#   libmpv.so.2.6.0     mpv v0.41.0 (41f6a645)          mediamp's submodule commit and meson options
#   libmediampv.so      mediamp 0.5.2 JNI wrapper       mediamp's compiler line (it needs GLIBCXX_3.4.32)
#
# libplacebo and libmediampv are C++: libstdc++ and libgcc go in statically, with their symbols
# kept local (--exclude-libs), so they need nothing above GLIBCXX_3.4.30 and cannot clash with
# another copy of libstdc++ in the JVM process.
#
# Inputs: /src (the pinned downloads, read only, checked by scripts/build-mpv-linux-2-35.sh).
# Output: /out (the libraries, SHA256SUMS and BUILDINFO).
set -euo pipefail

JOBS="${JOBS:-6}"
SRC=/src
OUT=/out
B=/build
STAGE=$B/stage
MULTIARCH=x86_64-linux-gnu
# mediamp's own install prefix, so libmpv's embedded configuration string stays byte for byte the
# shipped one. libmpv reads nothing from it (no config file is loaded through the client API).
MPV_PREFIX=/home/runner/work/mediamp/mediamp/mediamp-mpv/build/mpv/LinuxX64/install
FFMPEG_PREFIX=/opt/ffmpeg
export SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-1792108800}" # 2026-10-16, the day mediamp 0.5.2 was built
export TZ=UTC

mkdir -p "$B" "$STAGE" "$OUT/logs"
rm -f "$OUT"/logs/*.log
start=$(date +%s)
step=0
CUR="$OUT/logs/00-setup.log"

# Each step's output goes to its own log in $OUT/logs; a failure prints the end of it.
log() {
  step=$((step + 1))
  CUR="$OUT/logs/$(printf '%02d' "$step")-$(echo "$1" | cut -d' ' -f1 | tr 'A-Z' 'a-z').log"
  : > "$CUR"
  printf '=== %s (%ss)\n' "$*" "$(($(date +%s) - start))"
}
trap 'echo "FAILED, end of $CUR:" >&2; tail -40 "$CUR" >&2' ERR

# Debian's build flags for this package, as dpkg-buildflags computes them in the source tree.
debflags() {
  unset CFLAGS CXXFLAGS CPPFLAGS LDFLAGS OBJCFLAGS OBJCXXFLAGS FFLAGS FCFLAGS GCJFLAGS
  eval "$(DEB_BUILD_MAINT_OPTIONS="${1:-}" DEB_LDFLAGS_MAINT_APPEND="${2:-}" dpkg-buildflags --export=sh)"
}

noflags() {
  unset CFLAGS CXXFLAGS CPPFLAGS LDFLAGS OBJCFLAGS OBJCXXFLAGS FFLAGS FCFLAGS GCJFLAGS
}

# Unpacks a Debian source package (dpkg-source checks the .dsc checksums and applies a 3.0 (quilt)
# series) and applies the series by hand for the 1.0 format, where it leaves debian/patches alone.
unpack() {
  local dsc="$1" dir="$2"
  rm -rf "$dir"
  dpkg-source --no-copy -x "$SRC/$dsc" "$dir" >>"$CUR" 2>&1
  if [ "$(cat "$dir/debian/source/format" 2>/dev/null || echo 1.0)" = "1.0" ] \
     && [ -f "$dir/debian/patches/series" ]; then
    (cd "$dir" && grep -v '^\s*#' debian/patches/series | while read -r p opt; do
       [ -n "$p" ] || continue
       patch ${opt:--p1} --quiet --forward < "debian/patches/$p"
     done)
  fi
}

MESON_DEB=(--wrap-mode=nodownload --buildtype=plain --prefix=/usr --sysconfdir=/etc
  --localstatedir=/var --libdir="lib/$MULTIARCH" -Dpython.bytecompile=-1)
CMAKE_DEB=(-DCMAKE_INSTALL_PREFIX=/usr -DCMAKE_INSTALL_SYSCONFDIR=/etc
  -DCMAKE_INSTALL_LOCALSTATEDIR=/var -DCMAKE_INSTALL_LIBDIR="lib/$MULTIARCH"
  -DCMAKE_EXPORT_NO_PACKAGE_REGISTRY=ON -DCMAKE_FIND_USE_PACKAGE_REGISTRY=OFF
  -DFETCHCONTENT_FULLY_DISCONNECTED=ON -DCMAKE_POSITION_INDEPENDENT_CODE=ON -GNinja)

# The C++ runtime goes in statically and stays private to the library.
STATIC_CXX="-static-libstdc++ -static-libgcc -Wl,--exclude-libs,ALL"

# --- build tools -------------------------------------------------------------------------------
log "meson 1.3.2 (Ubuntu 24.04's; mpv needs 1.3, GLib 1.2, 22.04 has 0.61)"
unpack meson_1.3.2-1ubuntu1.dsc /opt/meson
printf '#!/bin/sh\nexec python3 /opt/meson/meson.py "$@"\n' > /usr/local/bin/meson
chmod +x /usr/local/bin/meson
meson --version

# --- OpenSSL: libcrypto.so.3 ---------------------------------------------------------------------
log "OpenSSL 3.0.13-0ubuntu3.16"
unpack openssl_3.0.13-0ubuntu3.16.dsc $B/openssl
cd $B/openssl
debflags "hardening=+all future=+lfs optimize=-lto"
mkdir build_shared && cd build_shared
HASHBANGPERL=/usr/bin/perl ../Configure shared --prefix=/usr --openssldir=/usr/lib/ssl \
  --libdir="lib/$MULTIARCH" no-idea no-mdc2 no-rc5 no-zlib no-ssl3 enable-unit-test \
  no-ssl3-method enable-rfc3779 enable-cms no-capieng no-rdrand enable-ec_nistp_64_gcc_128 \
  debian-amd64 >>"$CUR" 2>&1
make -s -j"$JOBS" build_libs >>"$CUR" 2>&1
cp libcrypto.so.3 $STAGE/

# --- GLib: libglib-2.0.so.0 ----------------------------------------------------------------------
log "GLib 2.80.0-6ubuntu3.9"
unpack glib2.0_2.80.0-6ubuntu3.9.dsc $B/glib
cd $B/glib
debflags "hardening=+bindnow" "-Wl,-z,defs -Wl,--no-as-needed -Wl,-O1"
meson setup build "${MESON_DEB[@]}" -Dlibelf=enabled -Dlibmount=enabled -Dmultiarch=true \
  -Dselinux=enabled -Dsystemtap=true -Dxattr=true -Dman-pages=disabled -Dintrospection=disabled \
  -Dinstalled_tests=false -Ddocumentation=false -Dtests=false >>"$CUR" 2>&1
ninja -C build -j"$JOBS" >>"$CUR" 2>&1
ninja -C build install >>"$CUR" 2>&1
cp "$(readlink -f build/glib/libglib-2.0.so.0)" $STAGE/libglib-2.0.so.0

# --- HarfBuzz: libharfbuzz.so.0 ------------------------------------------------------------------
# Debian turns every auto feature on; the ones that reach libharfbuzz.so.0 itself are glib,
# freetype and graphite2 (cairo, gobject, icu, chafa build separate libraries or tools).
log "HarfBuzz 8.3.0-2build2"
unpack harfbuzz_8.3.0-2build2.dsc $B/harfbuzz
cd $B/harfbuzz
debflags "hardening=+all" "-Wl,-O1 -Wl,-z,defs"
meson setup build-main "${MESON_DEB[@]}" -Dglib=enabled -Dfreetype=enabled -Dgraphite2=enabled \
  -Dgobject=disabled -Dcairo=disabled -Dchafa=disabled -Dicu=disabled -Dintrospection=disabled \
  -Ddocs=disabled -Dtests=disabled -Dutilities=disabled -Dbenchmark=disabled >>"$CUR" 2>&1
ninja -C build-main -j"$JOBS" >>"$CUR" 2>&1
ninja -C build-main install >>"$CUR" 2>&1
cp "$(readlink -f build-main/src/libharfbuzz.so.0)" $STAGE/libharfbuzz.so.0

# --- libass: libass.so.9 -------------------------------------------------------------------------
# libass links the runtime's libunibreak.so.5 (24.04's 5.1); 22.04 only has libunibreak.so.1
# (4.x), so 5.1 is built here to link against. It is not copied out: the jar's copy stays.
log "libunibreak 5.1-2build1 (to link libass against, not shipped)"
unpack libunibreak_5.1-2build1.dsc $B/libunibreak
cd $B/libunibreak
debflags ""
autoreconf -fi >>"$CUR" 2>&1 # the tarball is a git export without configure, as for Debian
./configure --prefix=/usr --libdir=/usr/lib/$MULTIARCH --disable-static >>"$CUR" 2>&1
make -s -j"$JOBS" >>"$CUR" 2>&1
make -s install >>"$CUR" 2>&1

log "libass 0.17.1-2build1"
unpack libass_0.17.1-2build1.dsc $B/libass
cd $B/libass
debflags "hardening=+all"
./configure --build=$MULTIARCH --prefix=/usr --includedir=/usr/include --mandir=/usr/share/man \
  --infodir=/usr/share/info --sysconfdir=/etc --localstatedir=/var --disable-option-checking \
  --disable-silent-rules --libdir=/usr/lib/$MULTIARCH --runstatedir=/run \
  --disable-maintainer-mode --disable-dependency-tracking \
  --with-pic --disable-directwrite --disable-coretext >>"$CUR" 2>&1
make -s -j"$JOBS" >>"$CUR" 2>&1
make -s install >>"$CUR" 2>&1
cp "$(readlink -f libass/.libs/libass.so.9)" $STAGE/libass.so.9

# --- libva: libva.so.2 ---------------------------------------------------------------------------
# Wayland only changes libva-wayland, which the runtime does not ship.
log "libva 2.20.0-2ubuntu0.2"
unpack libva_2.20.0-2ubuntu0.2.dsc $B/libva
cd $B/libva
debflags ""
meson setup build "${MESON_DEB[@]}" -Dwith_wayland=no -Dwith_x11=yes \
  -Ddriverdir=/usr/lib/$MULTIARCH/dri >>"$CUR" 2>&1
ninja -C build -j"$JOBS" >>"$CUR" 2>&1
ninja -C build install >>"$CUR" 2>&1
cp "$(readlink -f build/va/libva.so.2)" $STAGE/libva.so.2

# --- libplacebo's build inputs: SPIRV-Headers, SPIRV-Tools, glslang (static), xxhash, glad -------
# SPIRV-Headers at the commit SPIRV-Tools 2023.6~rc1 pins in its DEPS: the Ubuntu 24.04 package
# (1.3.275+git20240228) is newer and no longer builds this SPIRV-Tools.
log "SPIRV-Headers 1c6bb274 (SPIRV-Tools' DEPS pin)"
rm -rf $B/spirv-headers && mkdir -p $B/spirv-headers
tar -xzf "$SRC/SPIRV-Headers-1c6bb274.tar.gz" -C $B/spirv-headers --strip-components=1
cd $B/spirv-headers
debflags ""
cmake -S . -B build "${CMAKE_DEB[@]}" >>"$CUR" 2>&1
cmake --build build -j"$JOBS" >>"$CUR" 2>&1
cmake --install build >>"$CUR" 2>&1

log "SPIRV-Tools 2023.6~rc1-2 (static)"
unpack spirv-tools_2023.6~rc1-2.dsc $B/spirv-tools
cd $B/spirv-tools
debflags "optimize=-lto"
cmake -S . -B build "${CMAKE_DEB[@]}" -DCMAKE_BUILD_TYPE=Release -DSPIRV-Headers_SOURCE_DIR=/usr \
  -DBUILD_SHARED_LIBS=OFF -DSPIRV_TOOLS_BUILD_STATIC=ON -DSPIRV_TOOLS_LIBRARY_TYPE=STATIC \
  -DSPIRV_SKIP_TESTS=ON -DSPIRV_SKIP_EXECUTABLES=ON >>"$CUR" 2>&1
cmake --build build -j"$JOBS" >>"$CUR" 2>&1
cmake --install build >>"$CUR" 2>&1

log "glslang 14.0.0-2 (static)"
unpack glslang_14.0.0-2.dsc $B/glslang
cd $B/glslang
debflags ""
cmake -S . -B build "${CMAKE_DEB[@]}" -DBUILD_SHARED_LIBS=OFF -DALLOW_EXTERNAL_SPIRV_TOOLS=ON \
  -DCMAKE_BUILD_TYPE=Release -DGLSLANG_TESTS=OFF -DENABLE_GLSLANG_BINARIES=OFF >>"$CUR" 2>&1
cmake --build build -j"$JOBS" >>"$CUR" 2>&1
cmake --install build >>"$CUR" 2>&1

log "xxhash 0.8.2-2build1 (headers; libplacebo inlines it)"
unpack xxhash_0.8.2-2build1.dsc $B/xxhash
make -s -C $B/xxhash PREFIX=/usr LIBDIR=/usr/lib/$MULTIARCH \
  install_libxxhash.includes install_libxxhash.pc >>"$CUR" 2>&1

log "glad 2.0.2-0.1"
unpack python-glad_2.0.2-0.1.dsc $B/glad

# --- libplacebo: libplacebo.so.338 ---------------------------------------------------------------
# Ubuntu built it against the system Vulkan headers and vk.xml (1.3.275, bundled in the
# vulkan-loader source). They replace 22.04's 1.3.204 headers in /usr/include, since mpv later
# compiles libplacebo's libav.h, which pulls in FFmpeg 8's hwcontext_vulkan.h (Vulkan video
# types), and libplacebo also gets them in 3rdparty/, where it takes vk.xml from.
log "libplacebo 6.338.2-2build1"
mkdir -p $B/vulkan-headers
tar -xJf "$SRC/vulkan-loader_1.3.275.0.orig.tar.xz" -C $B/vulkan-headers --strip-components=3 \
  vulkan-loader-1.3.275.0/./vulkan-headers/include vulkan-loader-1.3.275.0/./vulkan-headers/registry
rm -rf /usr/include/vulkan /usr/include/vk_video
cp -a $B/vulkan-headers/include/. /usr/include/
mkdir -p /usr/share/vulkan/registry
cp -a $B/vulkan-headers/registry/. /usr/share/vulkan/registry/
unpack libplacebo_6.338.2-2build1.dsc $B/libplacebo
cd $B/libplacebo
mkdir -p 3rdparty/Vulkan-Headers 3rdparty/glad
cp -a $B/vulkan-headers/. 3rdparty/Vulkan-Headers/
cp -a $B/glad/. 3rdparty/glad/
debflags "hardening=+all" "$STATIC_CXX"
meson setup build "${MESON_DEB[@]}" -Dtests=false -Ddemos=false >>"$CUR" 2>&1
ninja -C build -j"$JOBS" >>"$CUR" 2>&1
ninja -C build install >>"$CUR" 2>&1
cp "$(readlink -f build/src/libplacebo.so.338)" $STAGE/libplacebo.so.338

# --- FFmpeg headers, linked against the runtime's own FFmpeg -------------------------------------
# libmpv links the FFmpeg the runtime already ships (it loads on glibc 2.35 as it is), so only the
# public headers and pkg-config files of the same FFmpeg commit are needed, plus the ffnvcodec
# headers for mpv's CUDA hwaccel (Ubuntu 24.04's nv-codec-headers, as mediamp's runner used).
log "nv-codec-headers 12.1.14.0-1"
unpack nv-codec-headers_12.1.14.0-1.dsc $B/nv-codec-headers
make -s -C $B/nv-codec-headers PREFIX=/usr LIBDIR=lib/$MULTIARCH install >>"$CUR" 2>&1

log "FFmpeg 8.0.1 (449453a9) headers"
rm -rf $B/ffmpeg && mkdir -p $B/ffmpeg
tar -xzf "$SRC/ffmpeg-8.0.1-449453a9.tar.gz" -C $B/ffmpeg --strip-components=1
cd $B/ffmpeg
noflags
./configure --prefix=$FFMPEG_PREFIX --disable-everything --disable-autodetect --disable-programs \
  --disable-doc --disable-x86asm --disable-static --enable-shared >>"$CUR" 2>&1
make -s install-headers >>"$CUR" 2>&1
mkdir -p $B/runtime
unzip -q -o "$SRC/mediamp-mpv-runtime-linux-x64-0.5.2.jar" -d $B/runtime
cp -a $B/runtime/libav*.so* $B/runtime/libsw*.so* $FFMPEG_PREFIX/lib/
for lib in libavcodec libavdevice libavfilter libavformat libavutil libswresample libswscale; do
  want=$(sed -n 's/^Version: //p' $FFMPEG_PREFIX/lib/pkgconfig/$lib.pc)
  [ -e "$FFMPEG_PREFIX/lib/$lib.so.$want" ] || { echo "$lib $want is not the runtime's" >&2; exit 1; }
done

# --- mpv: libmpv.so.2.6.0 ------------------------------------------------------------------------
# The options are the configuration string embedded in the shipped libmpv, in the same order.
# mediamp applies its render_d3d11.patch on every platform (it adds the D3D11 render API and moves
# the client API to 2.6, hence libmpv.so.2.6.0); its sources are in the mediamp tarball.
log "mpv v0.41.0 (41f6a645) with mediamp's render_d3d11.patch"
rm -rf $B/mediamp $B/mpv && mkdir -p $B/mediamp $B/mpv
tar -xzf "$SRC/mediamp-0.5.2.tar.gz" -C $B/mediamp --strip-components=1
tar -xzf "$SRC/mpv-0.41.0.tar.gz" -C $B/mpv --strip-components=1
cd $B/mpv
patch -p1 --quiet < $B/mediamp/mediamp-mpv/render_d3d11.patch
noflags
PKG_CONFIG_PATH=$FFMPEG_PREFIX/lib/pkgconfig meson setup build \
  -Dlibdir=lib -Dlibmpv=true -Dcplayer=false -Dtests=false -Dfuzzers=false -Dbuild-date=false \
  -Ddefault_library=shared -Dlua=disabled -Djavascript=disabled -Dcplugins=disabled \
  -Dcdda=disabled -Ddvbin=disabled -Ddvdnav=disabled -Djpeg=disabled -Dlcms2=disabled \
  -Dlibarchive=disabled -Dlibavdevice=enabled -Dlibbluray=disabled -Drubberband=disabled \
  -Dsdl2-audio=disabled -Dsdl2-video=disabled -Dsdl2-gamepad=disabled -Duchardet=disabled \
  -Dvapoursynth=disabled -Dzimg=disabled -Dhtml-build=disabled -Dmanpage-build=disabled \
  -Dpdf-build=disabled -Dvulkan=disabled -Dshaderc=disabled -Dspirv-cross=disabled \
  -Dcuda-hwaccel=enabled -Dcuda-interop=enabled -Dd3d9-hwaccel=disabled -Dvaapi=enabled \
  -Dvaapi-drm=disabled -Dvaapi-wayland=disabled -Dvaapi-win32=disabled -Dvaapi-x11=enabled \
  -Dvdpau=disabled -Dvdpau-gl-x11=disabled -Dxv=disabled -Dwayland=disabled -Ddrm=disabled \
  -Dgbm=disabled -Dsixel=disabled -Dcaca=disabled -Dplain-gl=enabled -Dzlib=enabled -Dgl=enabled \
  -Dgl-x11=enabled -Degl=enabled -Degl-x11=enabled -Dx11=enabled -Dd3d11=disabled \
  -Ddirect3d=disabled -Dwasapi=disabled -Daudiotrack=disabled -Dopensles=disabled \
  -Daaudio=disabled -Dprefix=$MPV_PREFIX -Dbuildtype=release >>"$CUR" 2>&1
ninja -C build -j"$JOBS" >>"$CUR" 2>&1
ninja -C build install >>"$CUR" 2>&1
cp build/libmpv.so.2.6.0 $STAGE/

# --- libmediampv.so ------------------------------------------------------------------------------
# mediamp's MpvJniBuildTask command for linuxX64 (MpvTargets.kt), plus the static C++ runtime.
log "mediamp 0.5.2 libmediampv.so"
cd $B/mediamp/mediamp-mpv/src/cpp
noflags
JAVA_HOME=$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")
mapfile -t sources < <(find "$PWD" -type f -name '*.cpp' | LC_ALL=C sort)
g++ -pthread -std=c++17 -fPIC -shared -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
  -I"$PWD/include" -I"$MPV_PREFIX/include" -I"$FFMPEG_PREFIX/include" \
  -o $STAGE/libmediampv.so "${sources[@]}" "$MPV_PREFIX/lib/libmpv.so" \
  "$FFMPEG_PREFIX/lib/libavcodec.so" -pthread '-Wl,-rpath,$ORIGIN' -lGL -lX11 -ldl -lz \
  $STATIC_CXX >>"$CUR" 2>&1

# --- finish: strip like the shipped files, RUNPATH=$ORIGIN, copy out ------------------------------
log "finishing"
cd $STAGE
# The Ubuntu libraries were stripped by dh_strip; libmpv and libmediampv keep their symbol table.
for f in libcrypto.so.3 libglib-2.0.so.0 libharfbuzz.so.0 libass.so.9 libva.so.2 libplacebo.so.338; do
  strip --remove-section=.comment --remove-section=.note --strip-unneeded "$f"
done
for f in *; do
  patchelf --set-rpath '$ORIGIN' "$f"
done
cp libmpv.so.2.6.0 libmpv.so.2
cp libmpv.so.2.6.0 libmpv.so
mkdir -p "$OUT"
rm -f "$OUT"/*.so* "$OUT/SHA256SUMS" "$OUT/BUILDINFO"
cp -a ./* "$OUT/"
(cd "$OUT" && sha256sum -- *.so* > SHA256SUMS)
{
  echo "built $(date -u +%Y-%m-%dT%H:%M:%SZ) in $(($(date +%s) - start)) s, SOURCE_DATE_EPOCH=$SOURCE_DATE_EPOCH"
  echo "compiler: $(gcc --version | head -1)"
  echo "glibc: $(getconf GNU_LIBC_VERSION)"
  echo "packages of the build image:"
  cat /usr/share/build-packages.txt
} > "$OUT/BUILDINFO"
log "done"
cat "$OUT/SHA256SUMS"
