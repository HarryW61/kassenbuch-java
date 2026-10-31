#!/usr/bin/env bash
JAVA_HOME_DIR="${JAVA_HOME_DIR:-$HOME/.jdks/openjdk-26.0.2.1}"
JAVAC="$JAVA_HOME_DIR/bin/javac"
[ -x "$JAVAC" ] || JAVAC=javac
mkdir -p out
"$JAVAC" --release 17 -d out $(find src/main/java -name '*.java')
