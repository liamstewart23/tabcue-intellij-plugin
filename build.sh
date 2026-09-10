#!/usr/bin/env bash
# Convenience wrapper: PhpStorm's bundled JBR is the only JDK on this machine, and the gradlew
# launcher needs JAVA_HOME set before it can read org.gradle.java.home.
set -euo pipefail
export JAVA_HOME="${JAVA_HOME:-/Applications/PhpStorm.app/Contents/jbr/Contents/Home}"
exec ./gradlew "$@"
