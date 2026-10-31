#!/usr/bin/env bash
# Build and run all tests.
#   unit tests + golden-layout report tests use the frozen fixtures (fixtures/)
#   API integration tests run against a scratch copy of the current data/ in /tmp
#   backend (JSON/CSV) tests use the current data/KABU.DBF as the source of truth
JAVA_HOME_DIR="${JAVA_HOME_DIR:-$HOME/.jdks/openjdk-26.0.2.1}"
JAVAC="$JAVA_HOME_DIR/bin/javac"
[ -x "$JAVAC" ] || JAVAC=javac
mkdir -p out
"$JAVAC" --release 17 -d out $(find src/main/java -name '*.java') || exit 1

echo "== unit tests =="
KB_DATA=fixtures "$JAVA_HOME_DIR/bin/java" -cp out kb.Tests
echo
echo "== report layout tests (golden files) =="
KB_FIXTURES=fixtures "$JAVA_HOME_DIR/bin/java" -cp out kb.ReportTests
echo
echo "== API integration tests (current KABU.DBF, scratch copy in /tmp) =="
KB_DATA_SRC=data KB_TEST_DATA=/tmp/kbtest-data "$JAVA_HOME_DIR/bin/java" -cp out kb.ApiTests
echo
echo "== backend tests (JSON / CSV against the current KABU.DBF) =="
KB_DATA_SRC=data KB_BACKEND_DATA=/tmp/kbbackend-data "$JAVA_HOME_DIR/bin/java" -cp out kb.BackendTests

