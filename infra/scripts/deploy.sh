#!/usr/bin/env bash
# Usage: deploy.sh <image-tag>      (e.g. deploy.sh sha-0123456789ab)
#
# Lives in DEPLOY_PATH (/opt/duo-finance) next to docker-compose.yml, .env and the other scripts.
# The pipeline copies the new compose file and scripts to DEPLOY_PATH/.incoming/ and runs
# `.incoming/deploy.sh <tag>`, so the deploy logic that runs is always the new one. When run from
# .incoming/, the deploy directory is its parent; when run directly (manual redeploy) it is the
# script's own directory and a leftover .incoming/ is used if present.
#
# Under the lock ($DEPLOY_PATH/.deploy.lock):
#   1. Backs up the live compose file and scripts that have a staged replacement to
#      .previous-files/ and moves the staged ones in (rename, atomic per file).
#   2. Records IMAGE_TAG of .env in .previous-tag and writes the new tag to .env (in place, atomic).
#   3. docker compose pull api nginx && docker compose up -d --remove-orphans.
#   4. Polls smoke.sh against PUBLIC_BASE_URL (from .env) every 5 s for up to 120 s.
#   5. Success: writes .last-good-tag and removes local images of the two app repositories except
#      the new tag and the previous last good tag.
#   6. Failure: restores the backed-up files and the last good tag (.last-good-tag), runs up -d
#      and re-polls the smoke.
# Exit codes: 0 deployed; 1 failed and rolled back; 2 failed and rollback failed or impossible;
#   64 usage; 75 lock busy or unopenable.
# Every log line is timestamped and appended to deploy.log; stdout copies are best effort, and
# HUP/PIPE are ignored, so a dropped SSH session never interrupts a deploy or its rollback.
# Env overrides (tests): SMOKE_BASE_URL, SMOKE_CMD, SMOKE_TIMEOUT, SMOKE_INTERVAL.
set -euo pipefail
trap '' HUP PIPE

readonly REPOS=(ghcr.io/hadryansilva/duo-finance-api ghcr.io/hadryansilva/duo-finance-nginx)
readonly MANAGED_FILES=(docker-compose.yml deploy.sh smoke.sh backup.sh restore.sh install-cron.sh)
readonly BACKUP_DIR=.previous-files
log_file=/dev/null

log() {
  local line
  line="$(date -u +%Y-%m-%dT%H:%M:%SZ) deploy: $*"
  printf '%s\n' "$line" >>"$log_file" 2>/dev/null || true
  printf '%s\n' "$line" 2>/dev/null || true
}

# Runs a command with its output (stdout and stderr) logged line by line; returns its status.
run_logged() {
  "$@" 2>&1 | while IFS= read -r line || [ -n "$line" ]; do log "  $line"; done
  return "${PIPESTATUS[0]}"
}

# shellcheck disable=SC2329 # invoked through run_logged
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

# Writes stdin to a file atomically (temp file in the same directory, then rename).
write_atomic() {
  local tmp
  tmp="$(mktemp "$1.XXXXXX")"
  if ! cat >"$tmp" || ! mv -f "$tmp" "$1"; then
    rm -f "$tmp"
    return 1
  fi
}

# Rewrites the IMAGE_TAG line in place; every other byte and the mode of .env are kept.
set_image_tag() {
  local tmp
  tmp="$(mktemp .env.XXXXXX)" || return 1
  if ! sed "s/^IMAGE_TAG=.*/IMAGE_TAG=$1/" .env >"$tmp" || ! chmod --reference=.env "$tmp" ||
    ! mv -f "$tmp" .env; then
    rm -f "$tmp"
    return 1
  fi
}

# Moves staged files in after backing up the live ones. Sets files_swapped=true once a backup
# exists, so a failure part-way is still rolled back.
swap_in_staged_files() {
  local f staged=()
  [ -d "$staging_dir" ] || { log "no staged files; keeping the live compose file and scripts"; return 0; }
  for f in "${MANAGED_FILES[@]}"; do
    [ ! -f "$staging_dir/$f" ] || staged+=("$f")
  done
  if [ "${#staged[@]}" -eq 0 ]; then
    log "staging dir is empty; keeping the live compose file and scripts"
    rm -rf "$staging_dir"
    return 0
  fi
  rm -rf "$BACKUP_DIR"
  mkdir "$BACKUP_DIR"
  for f in "${staged[@]}"; do
    if [ -f "$f" ]; then
      cp -p "$f" "$BACKUP_DIR/$f" || return 1
    else
      : >"$BACKUP_DIR/$f.absent" || return 1
    fi
  done
  files_swapped=true
  for f in "${staged[@]}"; do
    mv -f "$staging_dir/$f" "$f" || return 1
    log "installed new $f"
  done
  rm -rf "$staging_dir"
}

restore_previous_files() {
  local f tmp
  for f in "${MANAGED_FILES[@]}"; do
    if [ -f "$BACKUP_DIR/$f.absent" ]; then
      rm -f "$f" || return 1
    elif [ -f "$BACKUP_DIR/$f" ]; then
      tmp="$(mktemp "$f.XXXXXX")" || return 1
      if ! cp -p "$BACKUP_DIR/$f" "$tmp" || ! mv -f "$tmp" "$f"; then
        rm -f "$tmp"
        return 1
      fi
    else
      continue
    fi
    log "restored previous $f"
  done
}

# shellcheck disable=SC2329 # invoked through run_logged
run_smoke() {
  if [ -n "${SMOKE_CMD:-}" ]; then
    "$SMOKE_CMD" "$1"
  else
    bash ./smoke.sh "$1"
  fi
}

# smoke.sh is single-shot: retry it until it passes or the deadline is reached.
smoke_until_healthy() {
  local deadline=$((SECONDS + smoke_timeout)) attempt=1
  while :; do
    log "smoke attempt $attempt against $smoke_url"
    if run_logged run_smoke "$smoke_url"; then
      return 0
    fi
    if ((SECONDS + smoke_interval >= deadline)); then
      return 1
    fi
    sleep "$smoke_interval"
    attempt=$((attempt + 1))
  done
}

prune_old_images() {
  local repo ref tag k
  for repo in "${REPOS[@]}"; do
    while IFS= read -r ref; do
      [[ "$ref" == "$repo:"* ]] || continue
      tag="${ref##*:}"
      [ "$tag" != "<none>" ] || continue
      for k in "$@"; do
        [ "$tag" != "$k" ] || continue 2
      done
      log "removing old image $ref"
      run_logged docker image rm "$ref" || log "could not remove $ref (in use?); skipped"
    done < <(docker image ls --format '{{.Repository}}:{{.Tag}}' "$repo")
  done
}

main() {
  local new_tag="${1:-}"
  if [ -z "$new_tag" ]; then
    echo "usage: deploy.sh <image-tag>" >&2
    exit 64
  fi
  # Docker tag grammar; also keeps the value inert in .env and in sed.
  if ! [[ "$new_tag" =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$ ]]; then
    echo "deploy.sh: invalid image tag: $new_tag" >&2
    exit 64
  fi

  local script_dir deploy_dir
  script_dir="$(cd "$(dirname "$0")" && pwd)"
  if [ "$(basename "$script_dir")" = .incoming ]; then
    deploy_dir="$(dirname "$script_dir")"
  else
    deploy_dir="$script_dir"
  fi
  cd "$deploy_dir"
  staging_dir="$deploy_dir/.incoming"
  log_file="$deploy_dir/deploy.log"
  smoke_timeout="${SMOKE_TIMEOUT:-120}"
  smoke_interval="${SMOKE_INTERVAL:-5}"

  local lock_file="$deploy_dir/.deploy.lock"
  if ! { exec 9>"$lock_file"; } 2>/dev/null; then
    log "cannot open lock file $lock_file; aborting"
    exit 75
  fi
  if ! flock -n 9; then
    log "another deploy is running (lock $lock_file); aborting"
    exit 75
  fi

  [ -f .env ] || { log ".env not found in $PWD"; exit 2; }
  if ! grep -q '^IMAGE_TAG=' .env; then
    log ".env has no IMAGE_TAG line; add 'IMAGE_TAG=' (see .env.example)"
    exit 2
  fi
  smoke_url="${SMOKE_BASE_URL:-$(env_value PUBLIC_BASE_URL)}"
  [ -n "$smoke_url" ] || { log "PUBLIC_BASE_URL is not set in .env"; exit 2; }

  local current_tag last_good
  current_tag="$(env_value IMAGE_TAG)"
  last_good="$(cat .last-good-tag 2>/dev/null || true)"
  log "deploying $new_tag (current: ${current_tag:-none}, last good: ${last_good:-none})"
  if [ -n "$current_tag" ]; then
    printf '%s\n' "$current_tag" | write_atomic .previous-tag
  fi

  files_swapped=false
  local deployed=false
  if ! swap_in_staged_files; then
    log "could not install the staged files"
  elif ! set_image_tag "$new_tag"; then
    log "could not write IMAGE_TAG=$new_tag to .env"
  else
    log "pulling api and nginx images"
    if ! run_logged compose pull api nginx; then
      log "docker compose pull failed for $new_tag"
    else
      log "starting stack"
      if ! run_logged compose up -d --remove-orphans; then
        log "docker compose up failed for $new_tag"
      elif ! smoke_until_healthy; then
        log "smoke failed for $new_tag after ${smoke_timeout}s"
      else
        deployed=true
      fi
    fi
  fi

  if [ "$deployed" = true ]; then
    printf '%s\n' "$new_tag" | write_atomic .last-good-tag || log "could not write .last-good-tag"
    log "deployed $new_tag"
    prune_old_images "$new_tag" "$last_good"
    exit 0
  fi

  if [ -z "$last_good" ]; then
    log "no last good tag (.last-good-tag missing: first deploy?); nothing to roll back to, $new_tag left in place"
    exit 2
  fi

  log "rolling back to $last_good"
  if [ "$files_swapped" = true ] && ! restore_previous_files; then
    log "rollback to $last_good FAILED: could not restore the previous compose file and scripts"
    exit 2
  fi
  if ! set_image_tag "$last_good"; then
    log "rollback to $last_good FAILED: could not write IMAGE_TAG to .env"
    exit 2
  fi
  if run_logged compose up -d --remove-orphans && smoke_until_healthy; then
    log "rolled back to $last_good"
    exit 1
  fi
  log "rollback to $last_good FAILED; manual intervention needed"
  exit 2
}

# Everything runs inside main, so bash has parsed the whole file before deploy.sh replaces itself.
main "$@"
