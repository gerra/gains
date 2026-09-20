#!/bin/sh
# Xcode Cloud runs this right after cloning the repository, before it builds.
# The "Compile Kotlin Framework" build phase runs Gradle, which needs a JDK that
# Xcode Cloud machines do not ship with. Install one and register it with
# /usr/libexec/java_home so Gradle finds it without any PATH or JAVA_HOME changes
# (environment set here does not reach the Xcode build).
#
# Setup and troubleshooting: docs/testflight.md#upload-from-xcode-cloud
set -euo pipefail

brew install openjdk@17
JDK="$(brew --prefix openjdk@17)/libexec/openjdk.jdk"
sudo mkdir -p /Library/Java/JavaVirtualMachines
sudo ln -sfn "$JDK" /Library/Java/JavaVirtualMachines/openjdk-17.jdk

/usr/libexec/java_home -V
java -version
