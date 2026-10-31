#!/usr/bin/env bash
# Build and start the Kassenbuch web app.
#   ./serve.sh            -> http://localhost:8090
#   KB_PORT=1234 ./serve.sh
JAVA_HOME_DIR="${JAVA_HOME_DIR:-$HOME/.jdks/openjdk-26.0.2.1}"
JAVAC="$JAVA_HOME_DIR/bin/javac"
[ -x "$JAVAC" ] || JAVAC=javac
mkdir -p out
"$JAVAC" --release 17 -d out $(find src/main/java -name '*.java') || exit 1
KB_DATA="${KB_DATA:-data}" KB_PORT="${KB_PORT:-8090}" KB_WEB="${KB_WEB:-web}" "$JAVA_HOME_DIR/bin/java" -cp out kb.WebServer
