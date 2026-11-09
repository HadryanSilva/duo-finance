#!/usr/bin/env bats
# deploy.sh tests, stub-based (no Docker, no network). Run from the repo root:
#   npx --yes bats@latest infra/tests/deploy.bats
# Each test builds a deploy dir in a temp dir (deploy.sh, docker-compose.yml, smoke.sh, .env with
# IMAGE_TAG=sha-old, .last-good-tag=sha-old) and runs deploy.sh with helpers/bin first on PATH:
# the docker stub records calls and what it "started", the smoke stub (SMOKE_CMD) passes or fails
# per tag or compose content. flock is stubbed only when the host has none.

# Each @test runs in its own subshell, so per-test exports are intended.
# shellcheck disable=SC2030,SC2031
bats_require_minimum_version 1.5.0
REPO_ROOT="$(cd "$BATS_TEST_DIRNAME/../.." && pwd)"
# From pwd: on Git Bash BATS_TEST_DIRNAME may be C:/..., whose colon would split PATH.
HELPERS="$REPO_ROOT/infra/tests/helpers"
API=ghcr.io/hadryansilva/duo-finance-api
NGINX=ghcr.io/hadryansilva/duo-finance-nginx

setup() {
  WORK="$BATS_TEST_TMPDIR/deploy"
  mkdir -p "$WORK"
  cp "$REPO_ROOT/infra/scripts/deploy.sh" "$WORK/deploy.sh"
  cp "$WORK/deploy.sh" "$BATS_TEST_TMPDIR/deploy.sh.orig"
  echo '# compose: old' >"$WORK/docker-compose.yml"
  echo '# smoke: old' >"$WORK/smoke.sh"
  echo sha-old >"$WORK/.last-good-tag"
  # Values that are not shell-safe on purpose: deploy.sh must never source .env.
  # shellcheck disable=SC2016
  printf '%s\n' \
    '# production env' \
    'POSTGRES_PASSWORD=p$w`d"x'"'"'y' \
    'IMAGE_TAG=sha-old' \
    'PUBLIC_BASE_URL=https://duofinance.test' \
    'JWT_PRIVATE_KEY=-----BEGIN PRIVATE KEY-----\nMIIabc+/=\n-----END PRIVATE KEY-----' \
    'EMAIL_FROM=Duo Finance <no-reply@duofinance.test>' \
    '' \
    'TRAILING=last line' >"$WORK/.env"
  cp "$WORK/.env" "$BATS_TEST_TMPDIR/env.orig"

  export STUB_LOG="$BATS_TEST_TMPDIR/calls.log"
  export STUB_STATE="$BATS_TEST_TMPDIR/state"
  mkdir -p "$STUB_STATE"
  : >"$STUB_LOG"
  echo sha-old >"$STUB_STATE/running-tag"
  cp "$WORK/docker-compose.yml" "$STUB_STATE/running-compose"

  export PATH="$HELPERS/bin:$PATH"
  if ! command -v flock >/dev/null 2>&1; then
    export PATH="$HELPERS/flock:$PATH"
  fi
  export SMOKE_CMD="$HELPERS/bin/smoke"
  export SMOKE_TIMEOUT=0 SMOKE_INTERVAL=0
  unset SMOKE_BASE_URL SMOKE_RESOLVE SMOKE_FAIL_FOR_TAG SMOKE_FAIL_FOR_COMPOSE SMOKE_FAIL_TIMES \
    DOCKER_PULL_FAIL DOCKER_UP_DELETE_ENV DOCKER_UP_HANG_FOR_TAG DOCKER_IMAGES FLOCK_BUSY \
    COMPOSE_UP_TIMEOUT
}

teardown() {
  exec 8>&- 2>/dev/null || true
}

image_tag() {
  sed -n 's/^IMAGE_TAG=//p' "$WORK/.env"
}

# What the pipeline does: new compose file and scripts in .incoming/, new deploy.sh among them.
stage_new_files() {
  mkdir -p "$WORK/.incoming"
  { cat "$WORK/deploy.sh"; echo '# staged'; } >"$WORK/.incoming/deploy.sh"
  chmod +x "$WORK/.incoming/deploy.sh"
  echo '# compose: new' >"$WORK/.incoming/docker-compose.yml"
  echo '# smoke: new' >"$WORK/.incoming/smoke.sh"
  echo '# backup: new' >"$WORK/.incoming/backup.sh"
}

# Every .env line except IMAGE_TAG is byte-for-byte what setup wrote.
assert_other_lines_untouched() {
  cmp <(grep -v '^IMAGE_TAG=' "$BATS_TEST_TMPDIR/env.orig") <(grep -v '^IMAGE_TAG=' "$WORK/.env")
  [ "$(grep -c '^IMAGE_TAG=' "$WORK/.env")" -eq 1 ]
  # IMAGE_TAG stays on its original line.
  [ "$(grep -n '^IMAGE_TAG=' "$WORK/.env" | cut -d: -f1)" -eq 3 ]
}

@test "deploy_updatesTagAndPassesSmoke" {
  run "$WORK/deploy.sh" sha-new
  echo "$output"
  [ "$status" -eq 0 ]
  [ "$(image_tag)" = sha-new ]
  [ "$(cat "$WORK/.previous-tag")" = sha-old ]
  [ "$(cat "$WORK/.last-good-tag")" = sha-new ]
  assert_other_lines_untouched
  # Every deploy.sh log line is timestamped, on stdout and in deploy.log.
  [[ "${lines[0]}" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z\ deploy: ]]
  [ "$(grep -c 'deploy: ' <<<"$output")" -eq "$(grep -cE '^[0-9-]{10}T[0-9:]{8}Z deploy: ' <<<"$output")" ]
  grep -qE '^[0-9-]{10}T[0-9:]{8}Z deploy: deployed sha-new$' "$WORK/deploy.log"
  # pull then up, both on the production compose file, then the smoke against PUBLIC_BASE_URL.
  grep -n . "$STUB_LOG"
  pull_line="$(grep -n '^docker compose -f docker-compose.yml pull api nginx$' "$STUB_LOG" | cut -d: -f1)"
  up_line="$(grep -n '^docker compose -f docker-compose.yml up -d --remove-orphans$' "$STUB_LOG" | cut -d: -f1)"
  [ -n "$pull_line" ]
  [ -n "$up_line" ]
  [ "$pull_line" -lt "$up_line" ]
  grep -qx 'smoke https://duofinance.test sha-new' "$STUB_LOG"
  run ! grep -q '^curl' "$STUB_LOG"
}

@test "deploy_smokeBaseUrlOverridesPublicBaseUrl" {
  SMOKE_BASE_URL=http://localhost:8088 run "$WORK/deploy.sh" sha-new
  [ "$status" -eq 0 ]
  grep -qx 'smoke http://localhost:8088 sha-new' "$STUB_LOG"
}

@test "deploy_smokeResolvesHostToLoopback" {
  run "$WORK/deploy.sh" sha-new
  [ "$status" -eq 0 ]
  # Default: smoke.sh pins the public host name to 127.0.0.1 (no public DNS, no hairpin NAT).
  grep -qx 'smoke-env SMOKE_RESOLVE=127.0.0.1' "$STUB_LOG"
  [ "$(grep -c '^smoke-env' "$STUB_LOG")" -eq "$(grep -cx 'smoke-env SMOKE_RESOLVE=127.0.0.1' "$STUB_LOG")" ]
  # An explicit value wins; empty disables it.
  : >"$STUB_LOG"
  SMOKE_RESOLVE= run "$WORK/deploy.sh" sha-new2
  [ "$status" -eq 0 ]
  grep -qx 'smoke-env SMOKE_RESOLVE=' "$STUB_LOG"
}

@test "deploy_rollsBackWhenComposeUpHangs" {
  export DOCKER_UP_HANG_FOR_TAG=sha-new COMPOSE_UP_TIMEOUT=2
  start=$SECONDS
  run "$WORK/deploy.sh" sha-new
  echo "$output"
  [ "$status" -eq 1 ]
  [ $((SECONDS - start)) -lt 30 ]
  [[ "$output" == *"docker compose up timed out after 2s for sha-new"* ]]
  [[ "$output" == *"rolled back to sha-old"* ]]
  [ "$(image_tag)" = sha-old ]
  [ "$(cat "$STUB_STATE/running-tag")" = sha-old ]
  grep -qx 'smoke https://duofinance.test sha-old' "$STUB_LOG"
  run ! grep -q 'smoke https://duofinance.test sha-new' "$STUB_LOG"
}

@test "deploy_exits2WhenRollbackComposeUpHangs" {
  export DOCKER_UP_HANG_FOR_TAG="sha-new sha-old" COMPOSE_UP_TIMEOUT=1
  run "$WORK/deploy.sh" sha-new
  echo "$output"
  [ "$status" -eq 2 ]
  [[ "$output" == *"docker compose up timed out after 1s for sha-old"* ]]
  [[ "$output" == *"rollback to sha-old FAILED"* ]]
}

@test "deploy_retriesSmokeUntilHealthy" {
  export SMOKE_TIMEOUT=30 SMOKE_FAIL_TIMES=2
  run "$WORK/deploy.sh" sha-new
  echo "$output"
  [ "$status" -eq 0 ]
  [ "$(grep -c '^smoke ' "$STUB_LOG")" -eq 3 ]
  [ "$(image_tag)" = sha-new ]
}

@test "deploy_rollsBackWhenHealthFails" {
  export SMOKE_FAIL_FOR_TAG=sha-new
  run "$WORK/deploy.sh" sha-new
  echo "$output"
  [ "$status" -eq 1 ]
  [ "$(image_tag)" = sha-old ]
  [ "$(cat "$WORK/.last-good-tag")" = sha-old ]
  assert_other_lines_untouched
  [[ "$output" == *"rolled back to sha-old"* ]]
  grep -q 'rolled back to sha-old' "$WORK/deploy.log"
  # Old version started again and verified.
  [ "$(grep -c '^docker compose -f docker-compose.yml up -d' "$STUB_LOG")" -eq 2 ]
  [ "$(cat "$STUB_STATE/running-tag")" = sha-old ]
  grep -qx 'smoke https://duofinance.test sha-old' "$STUB_LOG"
  # No pruning after a failed deploy.
  run ! grep -q '^docker image rm' "$STUB_LOG"
}

@test "deploy_rollsBackWhenPullFails" {
  export DOCKER_PULL_FAIL=1
  run "$WORK/deploy.sh" sha-new
  echo "$output"
  [ "$status" -eq 1 ]
  [ "$(image_tag)" = sha-old ]
  [[ "$output" == *"rolled back to sha-old"* ]]
}

@test "deploy_exits2WhenRollbackAlsoFails" {
  export SMOKE_FAIL_FOR_TAG="sha-new sha-old"
  run "$WORK/deploy.sh" sha-new
  echo "$output"
  [ "$status" -eq 2 ]
  [ "$(image_tag)" = sha-old ]
  assert_other_lines_untouched
  [[ "$output" == *"rollback to sha-old FAILED"* ]]
}

@test "deploy_rollbackTargetIsLastGoodTag" {
  # A previous deploy of sha-broken failed without recovering: .env still names it.
  sed -i 's/^IMAGE_TAG=.*/IMAGE_TAG=sha-broken/' "$WORK/.env"
  export SMOKE_FAIL_FOR_TAG="sha-new sha-broken"
  run "$WORK/deploy.sh" sha-new
  echo "$output"
  [ "$status" -eq 1 ]
  [ "$(image_tag)" = sha-old ]
  [ "$(cat "$WORK/.previous-tag")" = sha-broken ]
  [[ "$output" == *"rolled back to sha-old"* ]]
}

@test "deploy_exits2OnFirstDeployWithoutLastGoodTag" {
  rm "$WORK/.last-good-tag"
  export SMOKE_FAIL_FOR_TAG=sha-new
  run "$WORK/deploy.sh" sha-new
  echo "$output"
  [ "$status" -eq 2 ]
  [[ "$output" == *"no last good tag"* ]]
  [ ! -e "$WORK/.last-good-tag" ]
}

@test "deploy_firstDeployRecordsLastGoodTag" {
  rm "$WORK/.last-good-tag"
  sed -i 's/^IMAGE_TAG=.*/IMAGE_TAG=/' "$WORK/.env"
  run "$WORK/deploy.sh" sha-new
  [ "$status" -eq 0 ]
  [ "$(cat "$WORK/.last-good-tag")" = sha-new ]
  [ ! -e "$WORK/.previous-tag" ]
}

@test "deploy_exits2WhenRollbackEnvRewriteFails" {
  export SMOKE_FAIL_FOR_TAG=sha-new DOCKER_UP_DELETE_ENV=1
  run "$WORK/deploy.sh" sha-new
  echo "$output"
  [ "$status" -eq 2 ]
  [[ "$output" == *"could not write IMAGE_TAG to .env"* ]]
}

@test "deploy_installsStagedFiles" {
  stage_new_files
  run "$WORK/.incoming/deploy.sh" sha-new
  echo "$output"
  [ "$status" -eq 0 ]
  [ "$(cat "$WORK/docker-compose.yml")" = '# compose: new' ]
  [ "$(cat "$WORK/smoke.sh")" = '# smoke: new' ]
  [ "$(cat "$WORK/backup.sh")" = '# backup: new' ]
  [ "$(tail -n 1 "$WORK/deploy.sh")" = '# staged' ]
  [ ! -e "$WORK/.incoming" ]
  [ "$(cat "$STUB_STATE/running-compose")" = '# compose: new' ]
  [ "$(cat "$WORK/.previous-files/docker-compose.yml")" = '# compose: old' ]
  # Logs, lock and state live in the deploy dir, not in .incoming.
  [ -f "$WORK/deploy.log" ]
  [ -f "$WORK/.deploy.lock" ]
  [ "$(image_tag)" = sha-new ]
}

@test "deploy_rollsBackComposeChange" {
  stage_new_files
  export SMOKE_FAIL_FOR_COMPOSE='compose: new'
  run "$WORK/.incoming/deploy.sh" sha-new
  echo "$output"
  [ "$status" -eq 1 ]
  [[ "$output" == *"rolled back to sha-old"* ]]
  # Previous compose file and scripts are back; files that did not exist before are gone.
  [ "$(cat "$WORK/docker-compose.yml")" = '# compose: old' ]
  [ "$(cat "$WORK/smoke.sh")" = '# smoke: old' ]
  [ ! -e "$WORK/backup.sh" ]
  cmp "$BATS_TEST_TMPDIR/deploy.sh.orig" "$WORK/deploy.sh"
  [ "$(image_tag)" = sha-old ]
  # up ran with the new compose file, then again with the old one.
  printf '%s\n' '# compose: new' '# compose: old' | cmp - "$STUB_STATE/up-composes"
  [ "$(cat "$STUB_STATE/running-compose")" = '# compose: old' ]
}

@test "deploy_refusesConcurrentRun" {
  if [ "$(command -v flock)" = "$HELPERS/flock/flock" ]; then
    export FLOCK_BUSY=1
  else
    # Real flock: hold the lock from this test process on another open file description.
    exec 8>"$WORK/.deploy.lock"
    flock -n 8
  fi
  run "$WORK/deploy.sh" sha-new
  echo "$output"
  [ "$status" -eq 75 ]
  [[ "$output" == *"another deploy is running"* ]]
  cmp "$BATS_TEST_TMPDIR/env.orig" "$WORK/.env"
  [ ! -e "$WORK/.previous-tag" ]
  run ! grep -q '^docker' "$STUB_LOG"
}

@test "deploy_exits75WhenLockCannotBeOpened" {
  mkdir "$WORK/.deploy.lock"
  run "$WORK/deploy.sh" sha-new
  echo "$output"
  [ "$status" -eq 75 ]
  [[ "$output" == *"cannot open lock file"* ]]
  cmp "$BATS_TEST_TMPDIR/env.orig" "$WORK/.env"
  run ! grep -q '^docker' "$STUB_LOG"
}

@test "deploy_requiresTagArgument" {
  run "$WORK/deploy.sh"
  [ "$status" -eq 64 ]
  [[ "$output" == *"usage: deploy.sh <image-tag>"* ]]
  run "$WORK/deploy.sh" 'sha-new;rm -rf /'
  [ "$status" -eq 64 ]
  [[ "$output" == *"invalid image tag"* ]]
  cmp "$BATS_TEST_TMPDIR/env.orig" "$WORK/.env"
  run ! grep -q '^docker' "$STUB_LOG"
}

@test "deploy_prunesOnlyOldAppImages" {
  export DOCKER_IMAGES="$API:sha-new $API:sha-old $API:sha-older $API:<none> \
$NGINX:sha-new $NGINX:sha-old $NGINX:sha-ancient $NGINX:main postgres:16 certbot/certbot:v3.0.1"
  run "$WORK/deploy.sh" sha-new
  echo "$output"
  [ "$status" -eq 0 ]
  grep '^docker image rm' "$STUB_LOG" | sort >"$BATS_TEST_TMPDIR/rm.log"
  cat "$BATS_TEST_TMPDIR/rm.log"
  printf '%s\n' "docker image rm $API:sha-older" "docker image rm $NGINX:main" \
    "docker image rm $NGINX:sha-ancient" | sort | cmp - "$BATS_TEST_TMPDIR/rm.log"
  run ! grep -q 'prune' "$STUB_LOG"
}

@test "deploy_keepsLoggingWhenStdoutIsClosed" {
  export SMOKE_FAIL_FOR_TAG=sha-new
  run bash -c '"$0" sha-new >&- 2>&-' "$WORK/deploy.sh"
  [ "$status" -eq 1 ]
  grep -q 'pulling api and nginx images' "$WORK/deploy.log"
  grep -q 'rolled back to sha-old' "$WORK/deploy.log"
  [ "$(image_tag)" = sha-old ]
}

@test "deploy_survivesBrokenPipe" {
  # The reader goes away at once, as when the SSH session drops: writes to stdout get EPIPE.
  run bash -c '"$0" sha-new | head -c 0; exit "${PIPESTATUS[0]}"' "$WORK/deploy.sh"
  [ "$status" -eq 0 ]
  grep -q 'deployed sha-new' "$WORK/deploy.log"
  [ "$(image_tag)" = sha-new ]
}

@test "deploy_preservesEnvMode" {
  chmod 600 "$WORK/.env"
  mode="$(stat -c %a "$WORK/.env" 2>/dev/null || true)"
  [ "$mode" = 600 ] || skip "file modes not supported here (stat -c %a gave '$mode')"
  run "$WORK/deploy.sh" sha-new
  [ "$status" -eq 0 ]
  [ "$(stat -c %a "$WORK/.env")" = 600 ]
}
