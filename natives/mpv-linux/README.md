# mpv runtime libraries for glibc 2.35

The desktop's Linux player is mediamp's `mediamp-mpv-runtime-linux-x64-0.5.2.jar`, built on Ubuntu
24.04. Seven of its libraries ask for glibc 2.38 and two for GCC 13's libstdc++, so on Ubuntu
22.04, Pop!_OS 22.04 and Mint 21 (glibc 2.35, GLIBCXX_3.4.30) libmpv does not load (issue #30).
Nothing in them needs a newer glibc API: the 24.04 headers turn `strtol` and friends into the
C23 `__isoc23_*` aliases (GLib also picks up `strlcpy`/`strlcat`). Rebuilt from the same sources
on Ubuntu 22.04 they load there and keep everything else.

| File | Version | Built from |
| --- | --- | --- |
| `libmpv.so.2.6.0`, `libmpv.so.2`, `libmpv.so` (three copies, as in the jar) | mpv v0.41.0, commit 41f6a645 | mediamp's submodule commit, the meson options embedded in the shipped libmpv, the same prefix string |
| `libass.so.9` | 0.17.1-2build1 | Ubuntu 24.04 source package, Debian's configure line |
| `libcrypto.so.3` | OpenSSL 3.0.13-0ubuntu3.16 | Ubuntu 24.04 source package, Debian's `Configure` line (same `OPENSSLDIR`, engines and modules paths) |
| `libglib-2.0.so.0` | 2.80.0-6ubuntu3.9 | Ubuntu 24.04 source package, Debian's meson options |
| `libharfbuzz.so.0` | 8.3.0-2build2 | Ubuntu 24.04 source package (GLib, FreeType, graphite2) |
| `libplacebo.so.338` | 6.338.2-2build1 | Ubuntu 24.04 source package, with glslang 14.0.0-2 and SPIRV-Tools 2023.6~rc1-2 linked in statically as Ubuntu did (SPIRV-Tools built against the SPIRV-Headers commit its DEPS pins, 1c6bb274, since 24.04's newer headers no longer build it), Vulkan headers 1.3.275, xxhash 0.8.2, glad 2.0.2 |
| `libva.so.2` | 2.20.0-2ubuntu0.2 | Ubuntu 24.04 source package (driver path `/usr/lib/x86_64-linux-gnu/dri`) |
| `libmediampv.so` | mediamp 0.5.2 | mediamp's JNI wrapper with its own compiler line; the shipped one needs `GLIBCXX_3.4.32` |

The versions are the ones `scripts/build-mediamp-source.sh` proved against the shipped bytes; the
libplacebo build inputs are the Ubuntu 24.04 release versions (the shipped file reports
`glslang Khronos. 14.0.0`). Everything else in the jar (FFmpeg, FreeType, FriBidi, libssl, the X
libraries, ...) loads on glibc 2.35 as it is and is not rebuilt; libmpv is linked against the jar's
own FFmpeg, with the public headers of the same FFmpeg commit. libunibreak 5.1 (24.04's) is built
only to link libass against, since 22.04's libunibreak is `libunibreak.so.1` and the runtime ships
`libunibreak.so.5`. mpv gets mediamp's `render_d3d11.patch`, applied on every platform (client API
2.6, hence `libmpv.so.2.6.0`).

libplacebo and libmediampv are C++. libstdc++ and libgcc are linked in statically with their
symbols kept local (`-static-libstdc++ -static-libgcc -Wl,--exclude-libs,ALL`), so neither needs
`libstdc++.so.6` any more and nothing has to be bundled beside them. SONAMEs and `RUNPATH=$ORIGIN`
are the shipped ones, so the files drop into the runtime folder unchanged.

## Building

```sh
scripts/build-mpv-linux-2-35.sh            # 7 to 12 minutes with 6 jobs; podman, no root
natives/mpv-linux/check-floor.sh ~/.cache/tmplayer-natives/mpv
natives/mpv-linux/smoke-test.sh            # swapped runtime in a plain ubuntu:22.04, then here
```

The libraries, `SHA256SUMS` and `BUILDINFO` (compiler, glibc and every package of the build image)
go to `~/.cache/tmplayer-natives/mpv/`; downloads stay in `~/.cache/tmplayer-natives/mpv-downloads/`.
No binary is committed. `JOBS` caps the parallel jobs (default 6). Two runs give byte identical
libraries (checked 2026-11-18).

- `Containerfile`: the build image, `ubuntu:22.04` pinned by digest, packages from one Ubuntu
  snapshot day. Build tools and headers only.
- `build-in-container.sh`: the build, in dependency order, with each package's Debian flags
  (`dpkg-buildflags` with the package's own `DEB_BUILD_MAINT_OPTIONS`).
- `check-floor.sh <dir>`: `objdump -T` over every `.so`; fails above glibc 2.35, GLIBCXX_3.4.30
  or CXXABI_1.3.13.
- `smoke-test.sh`: the jar's libraries with the rebuilt ones swapped in. Checks the floor, compares
  SONAME, RUNPATH, NEEDED and exported symbols with the shipped files, decodes an H.264/AAC MP4
  (with a subtitle through libass) and an MKV inside a plain `ubuntu:22.04`, then on the host, and
  diffs libmpv's version, configuration, option, property and decoder lists against the shipped
  runtime.
- `smoke.c`: the small loader the smoke test runs (dlopen by path, like the app).

## Corresponding source (GPL)

The Linux runtime's libmpv is GPLv2 or later (mediamp builds it with X11), and the rebuilt copies
replace files of that runtime, so their complete corresponding source must ship with any release
that contains them:

1. the mediamp runtime archive from `scripts/build-mediamp-source.sh` (mediamp, mpv, FFmpeg, dav1d
   and the Ubuntu 24.04 sources of the bundled libraries, the seven rebuilt ones included), and
2. the extra inputs of these builds: every file pinned in `scripts/build-mpv-linux-2-35.sh`
   (glslang, SPIRV-Tools, SPIRV-Headers, vulkan-loader for its Vulkan headers, xxhash, glad,
   nv-codec-headers, libunibreak, meson) together with this folder and that script, which are the build
   scripts. `scripts/build-mpv-linux-2-35.sh --source-archive [dir]` writes them as one tarball,
   `tmplayer-mpv-linux-2-35-source.tar.gz`, with a `SHA256SUMS`.

The build image's own packages (Ubuntu 22.04's compiler and the headers of libraries the runtime
does not rebuild) are listed in `BUILDINFO`; Ubuntu keeps their sources at the snapshot named in
the `Containerfile`.
