#!/bin/sh
# Small source-only launcher. The official, checksum-verified wrapper JAR is
# bootstrapped on first use because this workspace could not download binaries.
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
if [ ! -f "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" ]; then
    sh "$APP_HOME/scripts/bootstrap-gradle.sh"
fi
if [ -n "${JAVA_HOME:-}" ]; then
    JAVA_EXEC="$JAVA_HOME/bin/java"
else
    JAVA_EXEC=java
fi
# JAVA_OPTS and GRADLE_OPTS contain JVM arguments, as in the standard launcher.
# shellcheck disable=SC2086
exec "$JAVA_EXEC" ${JAVA_OPTS:-} ${GRADLE_OPTS:-} -Dorg.gradle.appname=gradlew \
    -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
