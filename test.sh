#!/usr/bin/env bash
# Build and run all tests.
#   unit + golden-layout report tests use fixtures/anonymisiert/ - placeholder identity,
#     so they run on a fresh clone and need no personal data
#   if fixtures/legacy/ exists (real files from the original program, git-ignored) the
#     report tests additionally compare against the untouched KB.EXE printouts
#   API integration and backend (JSON/CSV) tests run against a scratch copy of the
#     current data/ in /tmp; without data/ (fresh clone) they fall back to the
#     anonymised fixtures, so ./test.sh works straight after a clone
JAVA_HOME_DIR="${JAVA_HOME_DIR:-$HOME/.jdks/openjdk-26.0.2.1}"
JAVAC="$JAVA_HOME_DIR/bin/javac"
[ -x "$JAVAC" ] || JAVAC=javac
mkdir -p out
"$JAVAC" --release 17 -d out $(find src/main/java -name '*.java') || exit 1

echo "== unit tests =="
KB_DATA=fixtures/anonymisiert "$JAVA_HOME_DIR/bin/java" -cp out kb.Tests
echo
echo "== report layout tests (golden files) =="
KB_FIXTURES=fixtures/anonymisiert "$JAVA_HOME_DIR/bin/java" -cp out kb.ReportTests
echo
echo "== API integration tests (current KABU.DBF, scratch copy in /tmp) =="
KB_DATA_SRC=data KB_TEST_DATA=/tmp/kbtest-data "$JAVA_HOME_DIR/bin/java" -cp out kb.ApiTests
echo
echo "== backend tests (JSON / CSV against the current KABU.DBF) =="
KB_DATA_SRC=data KB_BACKEND_DATA=/tmp/kbbackend-data "$JAVA_HOME_DIR/bin/java" -cp out kb.BackendTests

