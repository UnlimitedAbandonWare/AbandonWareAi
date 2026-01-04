#!/usr/bin/env sh
# Standard Gradle wrapper launcher (fixed by src111_merge15 patch).
set -e
DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
WRAPPER_JAR="$DIR/gradle/wrapper/gradle-wrapper.jar"
WRAPPER_MAIN="org.gradle.wrapper.GradleWrapperMain"

if [ -f "$WRAPPER_JAR" ]; then
  exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -Dorg.gradle.appname=gradlew -classpath "$WRAPPER_JAR" $WRAPPER_MAIN "$@"
fi

if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
fi

echo "Gradle wrapper and system Gradle not found. Install Gradle or add gradle-wrapper.jar." >&2
exit 1
