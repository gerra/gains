#!/bin/sh
# Xcode Cloud runs this right after cloning the repository, before it builds.
#
# Two jobs:
#  1. Install a JDK. The "Compile Kotlin Framework" build phase runs Gradle, and Xcode
#     Cloud machines ship without Java. The JDK is registered with /usr/libexec/java_home
#     so Gradle finds it without PATH or JAVA_HOME changes (environment set here does not
#     reach the Xcode build).
#  2. Keep the slow parts between builds. Xcode Cloud preserves the derived data folder
#     ($CI_DERIVED_DATA_PATH) from one build to the next unless the workflow's "Clean"
#     option is on, so the JDK, the Gradle home (wrapper, dependency and build caches) and
#     the Kotlin/Native toolchain (~/.konan) are stored there and symlinked into place.
#     The first build fills the cache; later builds skip the downloads and reuse Gradle's
#     build cache, which is what turns a 30-minute build into a few minutes.
#
# Setup and troubleshooting: docs/testflight.md#upload-from-xcode-cloud
set -eu

JDK_VERSION=17
CACHE="${CI_DERIVED_DATA_PATH:-}"
if [ -n "$CACHE" ]; then
  CACHE="$CACHE/gains-cache"
  mkdir -p "$CACHE"
  echo "Build cache: $CACHE"
  du -sh "$CACHE"/* 2>/dev/null || echo "Build cache is empty; this build fills it."
else
  echo "CI_DERIVED_DATA_PATH is not set; nothing is cached between builds."
fi

# Point a home-directory folder at its cached copy. Anything already there (for example
# a Gradle home a previous step created) is merged in so nothing is lost.
link_cached() {
  target="$HOME/$1"
  [ -z "$CACHE" ] && return 0
  cached="$CACHE/$1"
  mkdir -p "$cached"
  if [ -d "$target" ] && [ ! -L "$target" ]; then
    cp -R "$target"/. "$cached"/ 2>/dev/null || true
    rm -rf "$target"
  fi
  ln -sfn "$cached" "$target"
  echo "$target -> $cached"
}

# --- 1. JDK -------------------------------------------------------------------------
JDK_LINK="/Library/Java/JavaVirtualMachines/openjdk-$JDK_VERSION.jdk"
CACHED_JDK="${CACHE:+$CACHE/openjdk-$JDK_VERSION.jdk}"

if [ -n "$CACHED_JDK" ] && [ -x "$CACHED_JDK/Contents/Home/bin/java" ]; then
  echo "Using cached JDK $JDK_VERSION"
  JDK="$CACHED_JDK"
else
  echo "Installing OpenJDK $JDK_VERSION with Homebrew"
  brew install --quiet "openjdk@$JDK_VERSION"
  JDK="$(brew --prefix "openjdk@$JDK_VERSION")/libexec/openjdk.jdk"
  if [ -n "$CACHED_JDK" ]; then
    rm -rf "$CACHED_JDK"
    cp -R "$JDK" "$CACHED_JDK"
    JDK="$CACHED_JDK"
  fi
fi
sudo mkdir -p /Library/Java/JavaVirtualMachines
sudo ln -sfn "$JDK" "$JDK_LINK"

# --- 2. Gradle home and Kotlin/Native toolchain ---------------------------------------
link_cached .gradle
link_cached .konan

# --- Sanity check ---------------------------------------------------------------------
/usr/libexec/java_home -v "$JDK_VERSION"
java -version
