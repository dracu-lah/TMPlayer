#!/usr/bin/env bash
# Runs inside the `build` target of natives/tdlib-linux/Containerfile; started by
# scripts/build-tdlib-linux.sh, not by hand.
#
# The steps are the ones tdl-coroutines' own "Build JVM Linux x64" job runs (its
# .github/workflows/build-td.yml at tag 15.0.0): TDLib with TD_ENABLE_JNI, installed, then
# example/java with TD_JSON_JAVA. Two things differ, both on purpose:
#   - the C++ runtime (libstdc++, libgcc) is linked in statically and its symbols are kept
#     private, so the library needs no GLIBCXX_ from the system at all;
#   - the source is a GitHub archive, not a git checkout, so TDLib's CMake cannot read the commit
#     hash; it is written into GitCommitHash.cpp.in instead, so getOption("commit_hash") answers
#     the same as the jar's library.
#
# Usage: build-in-container.sh <source tarball> <commit> <jobs> <output dir>
set -euo pipefail

tarball="$1"
commit="$2"
jobs="$3"
out="$4"

export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export SOURCE_DATE_EPOCH=1

src=/build/td
rm -rf "$src"
mkdir -p /build/unpack
tar -xzf "$tarball" -C /build/unpack
mv "/build/unpack/TDLib-$commit" "$src"
rmdir /build/unpack

sed -i "s/@TD_GIT_COMMIT_HASH@/$commit/" "$src/td/telegram/GitCommitHash.cpp.in"
grep -q "$commit" "$src/td/telegram/GitCommitHash.cpp.in"

# Keep the static C++ runtime's symbols out of the dynamic table: exported, they could stand in for
# (or be replaced by) another library's libstdc++ in the same JVM, such as the one libmpv loads.
runtime_flags="-static-libstdc++ -static-libgcc -Wl,--exclude-libs,libstdc++.a:libsupc++.a:libgcc.a:libgcc_eh.a"

mkdir -p "$src/build"
cd "$src/build"
cmake -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_INSTALL_PREFIX:PATH=../example/java/td \
  -DTD_ENABLE_JNI=ON \
  -DCMAKE_SHARED_LINKER_FLAGS="$runtime_flags" \
  ..
cmake --build . --target install -j "$jobs"

mkdir -p "$src/example/java/build"
cd "$src/example/java/build"
cmake -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_INSTALL_PREFIX:PATH=../../../tdlib \
  -DTd_DIR:PATH="$(readlink -e ../td/lib/cmake/Td)" \
  -DTD_JSON_JAVA=ON \
  -DCMAKE_SHARED_LINKER_FLAGS="$runtime_flags" \
  ..
cmake --build . --target install -j "$jobs"

cp "$src/tdlib/bin/libtdjsonjava.so" "$out/libtdjsonjava.so.new"
mv "$out/libtdjsonjava.so.new" "$out/libtdjsonjava.so"
