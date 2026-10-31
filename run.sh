#!/usr/bin/env bash
JAVA_HOME_DIR="${JAVA_HOME_DIR:-$HOME/.jdks/openjdk-26.0.2.1}"
KB_DATA="${KB_DATA:-data}" "$JAVA_HOME_DIR/bin/java" -cp out kb.Cli "$@"
