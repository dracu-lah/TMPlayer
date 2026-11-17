#!/bin/sh
# Writes JvmDefaults.kt into each package compiled for iOS, from JvmDefaults.kt.template, and
# AndroidDefaults.kt into the :ios module's packages, from AndroidDefaults.kt.template.
# Run it after editing a template or when a module gains a package.
#
# Each package gets the JVM defaults once, in the lowest module that has it, and public there: a
# package spread over modules (com.tmplayer.data is in :core, :ui and :ios) sees them from that
# module. Internal copies of the same declarations in several modules compile, but the framework
# link then fails with "IrPropertySymbolImpl is already bound".
set -e
cd "$(dirname "$0")"

generate() {
  visibility="$1"
  root="$2"
  shift 2
  for package in "$@"; do
    dir="$root/$(echo "$package" | tr . /)"
    mkdir -p "$dir"
    sed -e "s/^package PACKAGE$/package $package/" -e "s/^internal /$visibility /" JvmDefaults.kt.template > "$dir/JvmDefaults.kt"
  done
}

# :core's packages.
generate public kotlin com.tmplayer.cast com.tmplayer.data com.tmplayer.online com.tmplayer.platform com.tmplayer.player

# :ui's packages that :core does not have.
generate public ../../../ui/src/iosStandIns/kotlin com.tmplayer.ui.about com.tmplayer.ui.auth \
  com.tmplayer.ui.browse com.tmplayer.ui.components com.tmplayer.ui.connect com.tmplayer.ui.i18n \
  com.tmplayer.ui.icons com.tmplayer.ui.lock com.tmplayer.ui.nav com.tmplayer.ui.onboarding \
  com.tmplayer.ui.online com.tmplayer.ui.player com.tmplayer.ui.settings com.tmplayer.ui.theme

# :ios's packages that neither has; nothing builds on :ios, so internal is enough.
generate internal ../../../ios/src/iosStandIns/kotlin com.tmplayer com.tmplayer.ui.downloads com.tmplayer.ui.update \
  com.tmplayer.desktop com.tmplayer.desktop.player

# The Android key event members :app's screens read, only :ios has them.
for package in com.tmplayer com.tmplayer.data com.tmplayer.player com.tmplayer.ui.auth com.tmplayer.ui.browse com.tmplayer.ui.components \
  com.tmplayer.ui.downloads com.tmplayer.ui.onboarding com.tmplayer.ui.settings com.tmplayer.ui.theme com.tmplayer.ui.update; do
  dir="../../../ios/src/iosStandIns/kotlin/$(echo "$package" | tr . /)"
  mkdir -p "$dir"
  sed "s/^package PACKAGE$/package $package/" AndroidDefaults.kt.template > "$dir/AndroidDefaults.kt"
done
