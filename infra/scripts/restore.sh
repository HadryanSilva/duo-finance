#!/usr/bin/env bash
# Usage: restore.sh <dump-file | s3-uri> --yes [--stop-api]
#
# Restores a backup.sh dump into the running postgres service, in DEPLOY_PATH next to
# docker-compose.yml and .env. Prints the plan and refuses to run without --yes.
# Runs `pg_restore --clean --if-exists --no-owner -d $POSTGRES_DB` inside the postgres container,
# reading the dump from stdin. Existing objects of the dump are dropped and recreated.
# The api should be stopped first (the restore drops tables it is using): pass --stop-api to stop
# it before and start it again afterwards, or run `docker compose stop api` yourself.
# An s3:// source is downloaded with the aws CLI (same .env settings as backup.sh).
# Env overrides (tests): COMPOSE_FILES (default "-f docker-compose.yml").
set -euo pipefail

env_file=.env

log() { printf '%s restore: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }
usage() {
  echo "usage: $0 <dump-file | s3://bucket/key> --yes [--stop-api]" >&2
  exit 64
}

# Value of a single key of the env file, without sourcing it (values are not shell-safe).
env_value() {
  local value
  value="$(grep -m 1 "^$1=" "$env_file" | cut -d= -f2- | tr -d '\r')" || true
  value="${value#\"}"
  value="${value%\"}"
  printf '%s' "$value"
}

source_arg=""
yes=false
stop_api=false
for arg in "$@"; do
  case "$arg" in
    --yes) yes=true ;;
    --stop-api) stop_api=true ;;
    -*) usage ;;
    *)
      [ -z "$source_arg" ] || usage
      source_arg="$arg"
      ;;
  esac
done
[ -n "$source_arg" ] || usage

read -ra compose_args <<<"${COMPOSE_FILES:--f docker-compose.yml}"
compose() { docker compose "${compose_args[@]}" "$@"; }

[ -f "$env_file" ] || { log "$env_file not found (run from the deploy directory)"; exit 2; }
pg_db="$(env_value POSTGRES_DB)"
pg_user="$(env_value POSTGRES_USER)"
[ -n "$pg_db" ] && [ -n "$pg_user" ] || { log "POSTGRES_USER/POSTGRES_DB missing in $env_file"; exit 2; }

echo "Restore plan:"
echo "  source:   $source_arg"
echo "  database: $pg_db (objects in the dump are dropped and recreated: --clean --if-exists --no-owner)"
if $stop_api; then
  echo "  api:      will be stopped before and started after the restore"
else
  echo "  api:      NOT stopped; stopping it first is recommended (use --stop-api)"
fi
if ! $yes; then
  echo "Refusing to run without --yes." >&2
  exit 64
fi

work="$(mktemp -d)"
api_stopped=false
cleanup() {
  rm -rf "$work"
  if $api_stopped; then
    log "starting api"
    compose start api || log "could not start api: start it manually"
  fi
}
trap cleanup EXIT

dump="$source_arg"
case "$source_arg" in
  s3://*)
    endpoint="$(env_value BACKUP_S3_ENDPOINT)"
    for key in AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_DEFAULT_REGION; do
      value="$(env_value "$key")"
      [ -z "$value" ] || export "$key=$value"
    done
    dump="$work/restore.dump"
    log "downloading $source_arg"
    if [ -n "$endpoint" ]; then
      aws s3 cp --endpoint-url "$endpoint" "$source_arg" "$dump" --only-show-errors
    else
      aws s3 cp "$source_arg" "$dump" --only-show-errors
    fi
    ;;
esac
[ -s "$dump" ] || { log "dump not found or empty: $dump"; exit 1; }

log "validating dump"
compose exec -T postgres pg_restore --list <"$dump" >/dev/null

if $stop_api; then
  log "stopping api"
  api_stopped=true
  compose stop api
fi

log "restoring into $pg_db"
compose exec -T postgres pg_restore -U "$pg_user" --clean --if-exists --no-owner -d "$pg_db" <"$dump"
log "done"
