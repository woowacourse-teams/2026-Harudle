#!/usr/bin/env bash
set -euo pipefail

source_root="$(cd -- "${CODEBUILD_SRC_DIR:-$(dirname -- "${BASH_SOURCE[0]}")/../..}" && pwd -P)"
cache_root="$(realpath -m -- "${HARUDLE_GRADLE_CACHE_ROOT:-/root/.cache/harudle-gradle}")"
# This path stays the same when CodeBuild checks out a new source directory.
workspace="$cache_root/workspace"
snapshot="$cache_root/incremental"
export GRADLE_USER_HOME="$cache_root/home"

case "$cache_root" in
  /|"$source_root"|"$source_root"/*)
    echo 'The Gradle cache must be outside the source directory.' >&2
    exit 1
    ;;
esac
if [[ "$source_root" == "$cache_root"/* ]]; then
  echo 'The source directory must be outside the Gradle cache.' >&2
  exit 1
fi

build_backend() {
  local fingerprint started restored=false
  mkdir -p "$cache_root" "$source_root/backend/build/codebuild"
  rm -f -- "$source_root/backend/build/codebuild/app.jar"
  rm -rf -- "$workspace"
  mkdir -p "$workspace"
  # Copy current inputs only. Never restore sources from the previous build.
  tar -C "$source_root/backend" --exclude='./build' --exclude='./.gradle' -cf - . \
    | tar -C "$workspace" -xf -

  fingerprint="$(
    {
      printf 'harudle-incremental-v1\n'
      uname -sm
      java -version 2>&1
      cd "$workspace"
      find . -maxdepth 1 -type f \
        \( -name '*.gradle' -o -name '*.gradle.kts' -o -name 'gradle.properties' -o -name '*.lockfile' \) \
        -print0 | sort -z | xargs -0 -r sha256sum
      find gradle -type f -print0 | sort -z | xargs -0 -r sha256sum
    } | sha256sum | cut -d ' ' -f 1
  )"
  started="$(date +%s)"
  if [[ -f "$snapshot/fingerprint" && -f "$snapshot/directories" && -d "$snapshot/project/.gradle" ]] \
      && [[ "$(cat "$snapshot/fingerprint")" == "$fingerprint" ]]; then
    cp -a "$snapshot/project/." "$workspace/"
    # File-based cache archives can omit empty Java output directories. Missing
    # headers/processor directories also invalidate compileJava's output snapshot.
    (cd "$workspace" && xargs -0 -r mkdir -p -- < "$snapshot/directories")
    restored=true
  fi
  echo "[build-metrics] backend_incremental_state_restored=$restored"
  echo "[build-metrics] backend_incremental_restore_seconds=$(($(date +%s) - started))"

  started="$(date +%s)"
  (
    cd "$workspace"
    chmod +x gradlew
    # Previous outputs and task history provide incrementality. Do not duplicate
    # those outputs in a second, input-keyed Gradle build cache.
    ./gradlew --no-daemon --console=plain --no-build-cache --no-configuration-cache \
      --profile bootJar -x test
  )
  echo "[build-metrics] backend_gradle_bootjar_seconds=$(($(date +%s) - started))"

  local jars=()
  mapfile -t jars < <(find "$workspace/build/libs" -maxdepth 1 -type f \
    -name '*.jar' ! -name '*-plain.jar' | sort)
  if [[ ${#jars[@]} -ne 1 || ! -s "${jars[0]}" ]]; then
    echo 'Expected exactly one non-empty backend executable JAR.' >&2
    return 1
  fi
  cp -- "${jars[0]}" "$source_root/backend/build/codebuild/app.jar"
  if [[ -d "$workspace/build/reports/profile" ]]; then
    mkdir -p "$source_root/build-artifacts/backend-gradle-profile"
    cp -a "$workspace/build/reports/profile/." "$source_root/build-artifacts/backend-gradle-profile/"
  fi
  # Save is a separate operation, after both images and artifacts pass checks.
  printf '%s\n' "$fingerprint" > "$workspace/.codebuild-cache-fingerprint"
}

save_incremental_state() {
  if [[ "${CODEBUILD_BUILD_SUCCEEDING:-1}" != 1 ]]; then
    echo 'Keeping the previous incremental state because the build failed.'
    return 0
  fi
  test -s "$workspace/.codebuild-cache-fingerprint"
  test -s "$source_root/backend/build/codebuild/app.jar"

  local previous="$cache_root/incremental.previous" path started
  started="$(date +%s)"
  staged_snapshot="$(mktemp -d "$cache_root/incremental.XXXXXX")"
  trap 'rm -rf -- "$staged_snapshot"' EXIT
  mkdir -p "$staged_snapshot/project"
  for path in \
    .gradle \
    build/classes/java/main \
    build/resources/main \
    build/generated/sources/annotationProcessor/java/main \
    build/generated/sources/headers/java/main \
    build/tmp/compileJava/previous-compilation-data.bin; do
    if [[ -e "$workspace/$path" ]]; then
      mkdir -p "$staged_snapshot/project/$(dirname -- "$path")"
      cp -a "$workspace/$path" "$staged_snapshot/project/$path"
    fi
  done
  # No JARs, reports, test outputs or compiler transaction leftovers are saved.
  # Project cache lock files also carry persistent cache state. Keep them after
  # Gradle exits; the operating-system lock itself is not copied. Only the
  # dependency-cache lock is excluded, as required for portable modules-2 caches.
  rm -f -- "$GRADLE_USER_HOME/caches/modules-2/modules-2.lock"
  find "$staged_snapshot/project" -mindepth 1 -type d -printf './%P\0' > "$staged_snapshot/directories"
  cp -- "$workspace/.codebuild-cache-fingerprint" "$staged_snapshot/fingerprint"
  rm -rf -- "$previous"
  if [[ -d "$snapshot" ]]; then mv -- "$snapshot" "$previous"; fi
  if ! mv -- "$staged_snapshot" "$snapshot"; then
    if [[ -d "$previous" ]]; then mv -- "$previous" "$snapshot"; fi
    return 1
  fi
  trap - EXIT
  rm -rf -- "$previous"
  echo "[build-metrics] backend_incremental_save_seconds=$(($(date +%s) - started))"
  echo 'Gradle distribution, Gradle user caches and incremental state sizes (uncompressed):'
  for path in "$GRADLE_USER_HOME/wrapper/dists" "$GRADLE_USER_HOME/native" "$GRADLE_USER_HOME/caches" "$snapshot"; do
    if [[ -d "$path" ]]; then du -sh -- "$path"; fi
  done
}

case "${1:-build}" in
  build) build_backend ;;
  save) save_incremental_state ;;
  *) echo "Usage: $0 [build|save]" >&2; exit 2 ;;
esac
