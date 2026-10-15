#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
script="$script_dir/codebuild_backend.sh"
test_parent="$(cd -- "${TMPDIR:-/tmp}" && pwd -P)"
test_root="$(mktemp -d "$test_parent/harudle-codebuild-test.XXXXXX")"
case "$test_root" in "$test_parent"/harudle-codebuild-test.*) ;; *) exit 90 ;; esac
cleanup() {
  local result=$?
  if [[ $result -ne 0 && -f "$test_root/output" ]]; then cat "$test_root/output"; fi
  rm -rf -- "$test_root"
  exit "$result"
}
trap cleanup EXIT
export CODEBUILD_SRC_DIR="$test_root/source-one"
export HARUDLE_GRADLE_CACHE_ROOT="$test_root/harudle-gradle"
export PATH="$test_root/bin:$PATH"
mkdir -p "$test_root/bin" "$CODEBUILD_SRC_DIR/backend/gradle/wrapper" "$CODEBUILD_SRC_DIR/backend/src"
printf 'source-one\n' > "$CODEBUILD_SRC_DIR/backend/src/current.txt"
printf 'plugins {}\n' > "$CODEBUILD_SRC_DIR/backend/build.gradle"
printf 'rootProject.name="fixture"\n' > "$CODEBUILD_SRC_DIR/backend/settings.gradle"
printf 'distributionUrl=gradle-9.5.1-bin.zip\n' > "$CODEBUILD_SRC_DIR/backend/gradle/wrapper/gradle-wrapper.properties"
cat > "$test_root/bin/java" <<'MOCK'
#!/usr/bin/env bash
printf 'openjdk version "%s"\n' "${MOCK_JAVA_VERSION:-21}"
MOCK
chmod +x "$test_root/bin/java"
cat > "$CODEBUILD_SRC_DIR/backend/gradlew" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
[[ "$*" == '--no-daemon --console=plain --no-build-cache --no-configuration-cache --profile bootJar -x test' ]]
if [[ "${EXPECT_RESTORED:-false}" == true ]]; then
  test -s .gradle/9.5.1/executionHistory/history.bin
  test -s build/classes/java/main/Current.class
  test -s build/tmp/compileJava/previous-compilation-data.bin
  test -d build/generated/sources/headers/java/main
  test -d build/generated/sources/annotationProcessor/java/main
  test -d build/classes/java/main/empty-package
else
  test ! -e build/classes/java/main/Current.class
fi
# The current checkout, never the cached checkout, supplies application inputs.
cmp src/current.txt "$CODEBUILD_SRC_DIR/backend/src/current.txt"
mkdir -p .gradle/9.5.1/executionHistory build/classes/java/main build/resources/main \
  build/tmp/compileJava/compileTransaction build/libs build/reports/profile/css build/test-results/test \
  "$GRADLE_USER_HOME/caches/modules-2" build/generated/sources/headers/java/main \
  build/generated/sources/annotationProcessor/java/main build/classes/java/main/empty-package
printf 'history\n' > .gradle/9.5.1/executionHistory/history.bin
printf 'lock\n' > .gradle/9.5.1/history.lock
printf 'lock\n' > "$GRADLE_USER_HOME/caches/modules-2/modules-2.lock"
cp src/current.txt build/classes/java/main/Current.class
printf 'analysis\n' > build/tmp/compileJava/previous-compilation-data.bin
printf 'transaction\n' > build/tmp/compileJava/compileTransaction/leftover
printf 'resource\n' > build/resources/main/application.yml
printf 'test\n' > build/test-results/test/result.xml
printf 'profile\n' > build/reports/profile/profile.html
printf 'css\n' > build/reports/profile/css/style.css
if [[ "${MOCK_MODE:-}" == failure ]]; then exit 42; fi
if [[ "${MOCK_MODE:-}" != missing-jar ]]; then cp src/current.txt build/libs/app.jar; fi
if [[ "${MOCK_MODE:-}" == ambiguous-jar ]]; then cp src/current.txt build/libs/other.jar; fi
printf 'plain\n' > build/libs/app-plain.jar
MOCK

snapshot="$HARUDLE_GRADLE_CACHE_ROOT/incremental"
workspace="$HARUDLE_GRADLE_CACHE_ROOT/workspace"
passed=0
pass() { passed=$((passed + 1)); printf 'PASS: %s\n' "$1"; }
run_build() { bash "$script" build > "$test_root/output" 2>&1; }
run_save() { bash "$script" save >> "$test_root/output" 2>&1; }
snapshot_hash() { find "$snapshot" -type f -print0 | sort -z | xargs -0 sha256sum | sha256sum; }
expect_failure() {
  local expected="$1" actual=0
  run_build || actual=$?
  if [[ "$actual" != "$expected" ]]; then cat "$test_root/output"; exit 1; fi
  test ! -e "$CODEBUILD_SRC_DIR/backend/build/codebuild/app.jar"
  test ! -e "$workspace/.codebuild-cache-fingerprint"
}

run_build
grep -Fq 'backend_incremental_state_restored=false' "$test_root/output"
test ! -e "$snapshot"
cmp "$CODEBUILD_SRC_DIR/backend/src/current.txt" "$CODEBUILD_SRC_DIR/backend/build/codebuild/app.jar"
test -s "$CODEBUILD_SRC_DIR/build-artifacts/backend-gradle-profile/profile.html"
test -s "$CODEBUILD_SRC_DIR/build-artifacts/backend-gradle-profile/css/style.css"
run_save
test -s "$snapshot/project/build/classes/java/main/Current.class"
test -s "$snapshot/project/build/resources/main/application.yml"
test -s "$snapshot/project/build/tmp/compileJava/previous-compilation-data.bin"
test ! -e "$snapshot/project/build/libs"
test ! -e "$snapshot/project/build/reports"
test ! -e "$snapshot/project/build/test-results"
test ! -e "$snapshot/project/build/tmp/compileJava/compileTransaction"
test ! -e "$snapshot/project/src"
test -s "$snapshot/project/.gradle/9.5.1/history.lock"
test ! -e "$HARUDLE_GRADLE_CACHE_ROOT/home/caches/modules-2/modules-2.lock"
pass 'cold build saves only production incremental state; JAR and profile stay outside the cache'

# Simulate an S3 archive containing files but dropping empty directories.
find "$snapshot/project" -depth -type d -empty -delete
test ! -d "$snapshot/project/build/generated/sources/headers/java/main"


# A different CodeBuild checkout still uses the same compilation workspace.
cp -a "$CODEBUILD_SRC_DIR" "$test_root/source-two"
export CODEBUILD_SRC_DIR="$test_root/source-two"
printf 'source-two\n' > "$CODEBUILD_SRC_DIR/backend/src/current.txt"
EXPECT_RESTORED=true run_build
grep -Fq 'backend_incremental_state_restored=true' "$test_root/output"
cmp "$CODEBUILD_SRC_DIR/backend/src/current.txt" "$CODEBUILD_SRC_DIR/backend/build/codebuild/app.jar"
run_save
pass 'warm build restores classes, history and analysis across checkout paths, using current sources and restoring empty outputs'

# Saving replaces one snapshot; obsolete files do not accumulate.
printf 'old\n' > "$snapshot/project/build/classes/java/main/Obsolete.class"
rm -- "$workspace/build/classes/java/main/Obsolete.class" 2>/dev/null || true
run_save
test ! -e "$snapshot/project/build/classes/java/main/Obsolete.class"
pass 'successful save replaces the previous snapshot instead of accumulating versions'

printf 'plugins { /* changed */ }\n' > "$CODEBUILD_SRC_DIR/backend/build.gradle"
run_build
grep -Fq 'backend_incremental_state_restored=false' "$test_root/output"
run_save
pass 'build configuration change resets incremental state'

MOCK_JAVA_VERSION=21.0.99 run_build
grep -Fq 'backend_incremental_state_restored=false' "$test_root/output"
pass 'Java runtime change resets incremental state'

before="$(snapshot_hash)"
MOCK_MODE=failure EXPECT_RESTORED=true expect_failure 42
test "$(snapshot_hash)" == "$before"
pass 'failed compilation removes the stale prepared JAR and preserves the successful snapshot'

MOCK_MODE=missing-jar EXPECT_RESTORED=true expect_failure 1
test "$(snapshot_hash)" == "$before"
pass 'missing executable JAR cannot reuse an older JAR or publish a snapshot'

MOCK_MODE=ambiguous-jar EXPECT_RESTORED=true expect_failure 1
test "$(snapshot_hash)" == "$before"
pass 'ambiguous executable JARs fail the build'

EXPECT_RESTORED=true run_build
CODEBUILD_BUILD_SUCCEEDING=0 run_save
test "$(snapshot_hash)" == "$before"
pass 'later image or artifact failure keeps the previous successful incremental snapshot'

# If publication fails after moving the old snapshot, roll it back and clean staging.
mv() {
  if [[ "${MOCK_FAIL_PUBLISH:-0}" == 1 && $# -eq 3 && "$1" == -- \
      && "$2" == "$HARUDLE_GRADLE_CACHE_ROOT"/incremental.?????? && "$3" == "$snapshot" ]]; then
    return 55
  fi
  command mv "$@"
}
export snapshot
export -f mv
status=0
MOCK_FAIL_PUBLISH=1 run_save || status=$?
unset -f mv
[[ "$status" == 1 ]]
test "$(snapshot_hash)" == "$before"
test ! -d "$HARUDLE_GRADLE_CACHE_ROOT/incremental.previous"
test -z "$(find "$HARUDLE_GRADLE_CACHE_ROOT" -maxdepth 1 -name 'incremental.??????' -print -quit)"
pass 'failed snapshot publication rolls back the previous snapshot and removes staging'

if HARUDLE_GRADLE_CACHE_ROOT="$CODEBUILD_SRC_DIR/backend" run_build; then
  echo 'FAIL: cache inside source directory was accepted.' >&2
  exit 1
fi
test -s "$CODEBUILD_SRC_DIR/backend/src/current.txt"
pass 'unsafe cache location is rejected before changing the source directory'

printf '%s CodeBuild backend cache tests passed.\n' "$passed"
