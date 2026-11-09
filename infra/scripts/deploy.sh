#!/usr/bin/env bash
# Usage: deploy.sh <image-tag>      (e.g. deploy.sh sha-0123456789ab)
# Runs on the instance from DEPLOY_PATH (/opt/duo-finance), next to docker-compose.yml and .env.
#   1. Records the current IMAGE_TAG of .env in .previous-tag and writes the new tag to .env.
#   2. docker compose pull api nginx && docker compose up -d --remove-orphans.
#   3. Polls smoke.sh against PUBLIC_BASE_URL (from .env) every 5 s for up to 120 s.
#   4. On failure, restores the previous tag, runs up -d again and re-polls the smoke.
#   5. On success, removes local images of the two app repositories except the new and previous tags.
# Exit codes: 0 deployed; 1 failed and rolled back; 2 failed and rollback failed or impossible;
#   64 usage; 75 another deploy holds the lock.
# Env overrides (tests): SMOKE_BASE_URL, SMOKE_CMD, SMOKE_TIMEOUT, SMOKE_INTERVAL, DEPLOY_LOCK_FILE.
set -euo pipefail

log() {
  printf '%s deploy: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"
}

new_tag="${1:-}"
if [ -z "$new_tag" ]; then
  echo "usage: deploy.sh <image-tag>" >&2
  exit 64
fi
# Docker tag grammar; also keeps the value inert in .env and in sed.
if ! [[ "$new_tag" =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$ ]]; then
  echo "deploy.sh: invalid image tag: $new_tag" >&2
  exit 64
fi

cd "$(dirname "$0")"

readonly REPOS=(ghcr.io/hadryansilva/duo-finance-api ghcr.io/hadryansilva/duo-finance-nginx)
lock_file="${DEPLOY_LOCK_FILE:-/tmp/duo-deploy.lock}"
smoke_timeout="${SMOKE_TIMEOUT:-120}"
smoke_interval="${SMOKE_INTERVAL:-5}"

exec 9>"$lock_file"
if ! flock -n 9; then
  log "another deploy is running (lock $lock_file); aborting"
  exit 75
fi

compose() {
  docker compose -f docker-compose.yml "$@"
}

# Value of a single key of .env, without sourcing it (values are not shell-safe).
env_value() {
  local value
  value="$(grep -m 1 "^$1=" .env | cut -d= -f2- | tr -d '\r')" || true
  value="${value#\"}"
  value="${value%\"}"
  printf '%s' "$value"
}

# Rewrites the IMAGE_TAG line in place; every other byte of .env is kept. Atomic (same-dir mv).
set_image_tag() {
  local tmp
  tmp="$(mktemp .env.XXXXXX)"
  if ! sed "s/^IMAGE_TAG=.*/IMAGE_TAG=$1/" .env >"$tmp" || ! chmod --reference=.env "$tmp"; then
    rm -f "$tmp"
    return 1
  fi
  mv -f "$tmp" .env
}

# smoke.sh is single-shot: retry it until it passes or the deadline is reached.
smoke_until_healthy() {
  local deadline=$((SECONDS + smoke_timeout)) attempt=1
  while :; do
    log "smoke attempt $attempt against $smoke_url"
    if run_smoke "$smoke_url"; then
      return 0
    fi
    if ((SECONDS + smoke_interval >= deadline)); then
      return 1
    fi
    sleep "$smoke_interval"
    attempt=$((attempt + 1))
  done
}

run_smoke() {
  if [ -n "${SMOKE_CMD:-}" ]; then
    "$SMOKE_CMD" "$1"
  else
    bash ./smoke.sh "$1"
  fi
}

[ -f .env ] || { log ".env not found in $PWD"; exit 2; }
if ! grep -q '^IMAGE_TAG=' .env; then
  log ".env has no IMAGE_TAG line; add 'IMAGE_TAG=' (see .env.example)"
  exit 2
fi
smoke_url="${SMOKE_BASE_URL:-$(env_value PUBLIC_BASE_URL)}"
[ -n "$smoke_url" ] || { log "PUBLIC_BASE_URL is not set in .env"; exit 2; }

current_tag="$(env_value IMAGE_TAG)"
previous_tag="$current_tag"
if [ "$current_tag" = "$new_tag" ]; then
  # Redeploy of the running tag: the rollback target stays the one recorded earlier.
  previous_tag="$(cat .previous-tag 2>/dev/null || true)"
fi
log "deploying $new_tag (current: ${current_tag:-none}, rollback target: ${previous_tag:-none})"
if [ -n "$previous_tag" ] && [ "$previous_tag" != "$new_tag" ]; then
  printf '%s\n' "$previous_tag" >.previous-tag
fi
set_image_tag "$new_tag"

deployed=false
log "pulling api and nginx images"
if compose pull api nginx; then
  log "starting stack"
  if compose up -d --remove-orphans; then
    if smoke_until_healthy; then
      deployed=true
    else
      log "smoke failed for $new_tag after ${smoke_timeout}s"
    fi
  else
    log "docker compose up failed for $new_tag"
  fi
else
  log "docker compose pull failed for $new_tag"
fi

if [ "$deployed" = true ]; then
  log "deployed $new_tag"
  keep=("$new_tag" "$previous_tag")
  for repo in "${REPOS[@]}"; do
    while IFS= read -r ref; do
      [[ "$ref" == "$repo:"* ]] || continue
      tag="${ref##*:}"
      [ "$tag" != "<none>" ] || continue
      for k in "${keep[@]}"; do
        [ "$tag" != "$k" ] || continue 2
      done
      log "removing old image $ref"
      docker image rm "$ref" >/dev/null || log "could not remove $ref (in use?); skipped"
    done < <(docker image ls --format '{{.Repository}}:{{.Tag}}' "$repo")
  done
  exit 0
fi

if [ -z "$previous_tag" ] || [ "$previous_tag" = "$new_tag" ]; then
  log "no previous tag to roll back to; $new_tag left in place"
  exit 2
fi

log "rolling back to $previous_tag"
set_image_tag "$previous_tag"
if compose up -d --remove-orphans && smoke_until_healthy; then
  log "rolled back to $previous_tag"
  exit 1
fi
log "rollback to $previous_tag FAILED; manual intervention needed"
exit 2
