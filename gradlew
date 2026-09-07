#!/bin/sh
#
# Copyright © 2015-2021 the original authors.
# Gradle Wrapper Script (Gradle 9.5.0)
#

# 오류 메시지를 출력하고 종료한다. (축약 래퍼에 누락돼 있어 "die: not found" 만 찍히던 결함 수정, 2026-09-07)
die () {
    echo
    echo "$*"
    echo
    exit 1
} >&2

# Determine the Java command to use to start the JVM.
if [ -n "$JAVA_HOME" ] ; then
    if [ -x "$JAVA_HOME/jre/sh/java" ] ; then
        JAVACMD="$JAVA_HOME/jre/sh/java"
    else
        JAVACMD="$JAVA_HOME/bin/java"
    fi
    if [ ! -x "$JAVACMD" ] ; then
        die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME"
    fi
else
    JAVACMD="java"
    command -v java >/dev/null 2>&1 || die "ERROR: JAVA_HOME is not set and no 'java' command could be found."
fi

# Resolve the directory where the wrapper script is located.
APP_HOME=$( cd "${0%[/\\]*}" > /dev/null && pwd -P )

# Classpath for GradleWrapperMain
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

# Execute Gradle
exec "$JAVACMD" \
  -classpath "$CLASSPATH" \
  org.gradle.wrapper.GradleWrapperMain \
  "$@"
