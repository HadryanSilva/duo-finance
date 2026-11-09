#!/usr/bin/env bash
# Usage: backup.sh
#
# Daily Postgres backup. Lives in DEPLOY_PATH (/opt/duo-finance) next to docker-compose.yml and
# .env, and is run from there by cron (see install-cron.sh).
#   1. pg_dump -Fc (custom format, already compressed) from the postgres service into a host temp
#      file duofinance-<UTC YYYYMMDD-HHMMSS>.dump, then validated with `pg_restore --list`.
#   2. Uploads to <target>/daily/ through <target>/daily/.partial/<name>, then renames into place,
#      so the final key never holds a partial object.
#   3. Deletes daily/duofinance-*.dump objects older than 30 days.
# Target: BACKUP_TARGET=file:///dir (tests, manual use), otherwise s3://$BACKUP_S3_BUCKET using the
# aws CLI (with --endpoint-url "$BACKUP_S3_ENDPOINT" when set; AWS_* come from .env).
# Settings are read from .env without sourcing it (its values are not shell-safe); an environment
# variable of the same name wins for the BACKUP_* settings.
# Exit codes: 0 ok; non-zero on any failure, with nothing partial left at the target.
# Env overrides (tests): COMPOSE_FILES (compose arguments, default "-f docker-compose.yml"),
#   BACKUP_NOW (epoch seconds used as "now" for retention).
set -euo pipefail

readonly RETENTION_DAYS=30
env_file=.env

log() { printf '%s backup: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }

# Value of a single key of the env file, without sourcing it (values are not shell-safe).
env_value() {
  local value
  value="$(grep -m 1 "^$1=" "$env_file" | cut -d= -f2- | tr -d '\r')" || true
  value="${value#\"}"
  value="${value%\"}"
  printf '%s' "$value"
}

# Setting: environment first, then the env file.
setting() {
  local current="${!1:-}"
  if [ -n "$current" ]; then printf '%s' "$current"; else env_value "$1"; fi
}

read -ra compose_args <<<"${COMPOSE_FILES:--f docker-compose.yml}"
pg() { docker compose "${compose_args[@]}" exec -T postgres "$@"; }

[ -f "$env_file" ] || { log "$env_file not found (run from the deploy directory)"; exit 2; }
pg_user="$(env_value POSTGRES_USER)"
pg_db="$(env_value POSTGRES_DB)"
[ -n "$pg_user" ] && [ -n "$pg_db" ] || { log "POSTGRES_USER/POSTGRES_DB missing in $env_file"; exit 2; }

target="$(setting BACKUP_TARGET)"
endpoint="$(setting BACKUP_S3_ENDPOINT)"
if [ -z "$target" ]; then
  bucket="$(setting BACKUP_S3_BUCKET)"
  [ -n "$bucket" ] || { log "set BACKUP_S3_BUCKET (or BACKUP_TARGET=file://dir)"; exit 2; }
  target="s3://$bucket"
  for key in AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_DEFAULT_REGION; do
    value="$(setting "$key")"
    [ -z "$value" ] || export "$key=$value"
  done
fi

# aws wrapper: adds the custom endpoint when set.
aws_s3() {
  local sub="$1"
  shift
  if [ -n "$endpoint" ]; then aws "$sub" --endpoint-url "$endpoint" "$@"; else aws "$sub" "$@"; fi
}

name="duofinance-$(date -u +%Y%m%d-%H%M%S).dump"
work="$(mktemp -d)"
partial=""
cleanup() {
  rm -rf "$work"
  if [ -n "$partial" ]; then # failed or interrupted before the rename: drop the partial object
    case "$target" in
      file://*)
        rm -f "$partial"
        rmdir "${partial%/*}" 2>/dev/null || true
        ;;
      *) aws_s3 s3 rm "$partial" >/dev/null 2>&1 || true ;;
    esac
  fi
}
trap cleanup EXIT

log "dumping $pg_db to $name"
pg pg_dump -U "$pg_user" -Fc "$pg_db" >"$work/$name"
[ -s "$work/$name" ] || { log "empty dump"; exit 1; }
pg pg_restore --list <"$work/$name" >/dev/null || { log "dump failed validation"; exit 1; }
log "dump valid ($(wc -c <"$work/$name") bytes)"

now="${BACKUP_NOW:-$(date +%s)}"
cutoff=$((now - RETENTION_DAYS * 86400))

case "$target" in
  file://*)
    dir="${target#file://}/daily"
    mkdir -p "$dir/.partial"
    partial="$dir/.partial/$name"
    cp "$work/$name" "$partial"
    mv "$partial" "$dir/$name"
    rmdir "$dir/.partial" 2>/dev/null || true
    partial=""
    log "stored $dir/$name"
    for f in "$dir"/duofinance-*.dump; do
      [ -e "$f" ] || continue
      if [ "$(stat -c %Y "$f")" -lt "$cutoff" ]; then
        rm -f "$f"
        log "pruned ${f##*/}"
      fi
    done
    ;;
  s3://*)
    bucket="${target#s3://}"
    partial="$target/daily/.partial/$name"
    aws_s3 s3 cp "$work/$name" "$partial" --only-show-errors
    aws_s3 s3 mv "$partial" "$target/daily/$name" --only-show-errors
    partial=""
    log "stored $target/daily/$name"
    listing="$(aws_s3 s3api list-objects-v2 --bucket "$bucket" --prefix daily/ \
      --query 'Contents[].[Key,LastModified]' --output text)"
    while IFS=$'\t' read -r key modified; do
      case "$key" in daily/duofinance-*.dump) ;; *) continue ;; esac
      if [ "$(date -d "$modified" +%s)" -lt "$cutoff" ]; then
        aws_s3 s3api delete-object --bucket "$bucket" --key "$key"
        log "pruned $key"
      fi
    done <<<"$listing"
    ;;
  *)
    log "unsupported BACKUP_TARGET: $target"
    exit 2
    ;;
esac
log "done"
