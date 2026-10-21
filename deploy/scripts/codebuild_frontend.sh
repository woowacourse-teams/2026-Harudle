#!/usr/bin/env bash
set -euo pipefail

source_root="${CODEBUILD_SRC_DIR:-$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)}"
cache_root="/root/.cache/harudle-frontend"
fingerprint_file="/tmp/harudle-frontend-fingerprint"
image_archive="$source_root/build-artifacts/frontend-image.tar.gz"
staged_cache_dir=""

valid_image() {
  local tar_file="$1" image_config
  image_config="$(tar -xOf "$tar_file" manifest.json | jq -er '
    select(length == 1) | .[0] | select(.RepoTags | index("harudle-frontend:local")) | .Config
  ')" || return 1
  tar -xOf "$tar_file" -- "$image_config" | jq -e '
    .architecture == "arm64" and .os == "linux"
  ' >/dev/null
}

fingerprint() {
  local base_images image
  # The ignored directories below must match the build context exclusions.
  [[ "$(sha256sum "$source_root/frontend/.dockerignore" | cut -d ' ' -f 1)" == \
    fbb0b12243e2d18d1e6cec8e90e4ab67435300159e52c3b6509cbe04346ccacf ]] || return 1
  base_images="$(awk 'toupper($1) == "FROM" { print $2 }' "$source_root/frontend/Dockerfile")"
  [[ -n "$base_images" ]] || return 1

  (
    cd "$source_root"
    printf 'harudle-frontend-image-v1\n'
    printf '%s\0%s\0' "${REACT_APP_POSTHOG_KEY-}" "${REACT_APP_POSTHOG_HOST-}" | sha256sum
    sha256sum buildspec.yml deploy/scripts/codebuild_frontend.sh
    find frontend \( -path frontend/node_modules -o -path frontend/web/node_modules \
      -o -path frontend/web/dist \) -prune -o -type f -print0 |
      sort -z | xargs -0 -r sha256sum || return 1
    find frontend \( -path frontend/node_modules -o -path frontend/web/node_modules \
      -o -path frontend/web/dist \) -prune -o -type l -print0 |
      sort -z | while IFS= read -r -d '' link; do
        printf '%s -> %s\n' "$link" "$(readlink "$link")"
      done || return 1
    while IFS= read -r image; do
      printf '%s\n' "$image"
      docker buildx imagetools inspect --raw "$image" | sha256sum || return 1
    done <<< "$base_images"
  ) | sha256sum | cut -d ' ' -f 1
}

restore() {
  local current cached_tar
  rm -f -- "$fingerprint_file" "$image_archive"
  if ! current="$(fingerprint)"; then
    echo '[build-metrics] frontend_image_reused=false (fingerprint unavailable)'
    return 1
  fi
  printf '%s\n' "$current" > "$fingerprint_file"

  if [[ -f "$cache_root/fingerprint" && "$(cat "$cache_root/fingerprint")" == "$current" \
      && -s "$cache_root/frontend-image.tar.gz" && -f "$cache_root/frontend-image.sha256" ]] \
      && (cd "$cache_root" && sha256sum --check --status frontend-image.sha256) \
      && gzip -t "$cache_root/frontend-image.tar.gz"; then
    cached_tar="$(mktemp)"
    if gzip -dc "$cache_root/frontend-image.tar.gz" > "$cached_tar" && valid_image "$cached_tar"; then
      cp -- "$cache_root/frontend-image.tar.gz" "$image_archive"
      rm -f -- "$cached_tar"
      echo '[build-metrics] frontend_image_reused=true'
      return 0
    fi
    rm -f -- "$cached_tar"
  fi
  echo '[build-metrics] frontend_image_reused=false'
  return 1
}

save() {
  local current
  [[ -s "$fingerprint_file" && -s "$image_archive" ]] || return 0
  mkdir -p "$cache_root"
  if [[ -f "$cache_root/fingerprint" && -f "$cache_root/frontend-image.sha256" \
      && "$(cat "$cache_root/fingerprint")" == "$(cat "$fingerprint_file")" \
      && -s "$cache_root/frontend-image.tar.gz" ]] \
      && (cd "$cache_root" && sha256sum --check --status frontend-image.sha256); then
    return 0
  fi

  if ! current="$(fingerprint)" || [[ "$current" != "$(cat "$fingerprint_file")" ]]; then
    echo '[build-metrics] frontend_image_cache_prepared=false (inputs changed during build)'
    return 0
  fi

  staged_cache_dir="$(mktemp -d "$cache_root/.new.XXXXXX")"
  trap 'rm -rf -- "$staged_cache_dir"' EXIT
  cp -- "$image_archive" "$staged_cache_dir/frontend-image.tar.gz"
  (cd "$staged_cache_dir" && sha256sum frontend-image.tar.gz > frontend-image.sha256)
  cp -- "$fingerprint_file" "$staged_cache_dir/fingerprint"
  mv -f -- "$staged_cache_dir/frontend-image.tar.gz" "$cache_root/frontend-image.tar.gz"
  mv -f -- "$staged_cache_dir/frontend-image.sha256" "$cache_root/frontend-image.sha256"
  mv -f -- "$staged_cache_dir/fingerprint" "$cache_root/fingerprint"
  echo '[build-metrics] frontend_image_cache_prepared=true'
}

case "${1:-}" in
  restore) restore ;;
  save) save ;;
  *) echo 'Usage: codebuild_frontend.sh {restore|save}' >&2; exit 2 ;;
esac
