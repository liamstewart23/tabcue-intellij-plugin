#!/usr/bin/env bash
# Convenience wrapper. Gradle needs a JDK to start; if none is on JAVA_HOME, fall back to the
# JetBrains Runtime bundled with a local IDE, which is a full JDK. The JDK used to *compile* is
# provisioned by the toolchain in build.gradle.kts either way, so this only has to launch Gradle.
set -euo pipefail

if [[ -z "${JAVA_HOME:-}" ]]; then
  for candidate in /Applications/PhpStorm.app/Contents/jbr/Contents/Home \
                   /Applications/IntelliJ*.app/Contents/jbr/Contents/Home; do
    if [[ -x "$candidate/bin/java" ]]; then
      export JAVA_HOME="$candidate"
      break
    fi
  done
fi

exec ./gradlew "$@"
