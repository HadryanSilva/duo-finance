#!/usr/bin/env bats
# bats file_tags=docker
# backup.sh / restore.sh / install-cron.sh tests. Run from the repo root (Git Bash on Windows,
# host Docker):
#   npx --yes bats@latest infra/tests/backup.bats
# setup_file starts only the postgres service of docker-compose.yml + docker-compose.local.yml
# (own compose project, no published ports, temp env file with a random password outside the repo)
# and teardown_file always runs `down -v`. Each test runs the scripts from a temp "deploy dir" with
# its own .env and BACKUP_TARGET=file://<tmp>. The S3 path runs against a stubbed aws
# (helpers/bin/aws); real S3 is never contacted.

# Each @test runs in its own subshell, so per-test exports are intended.
# shellcheck disable=SC2030,SC2031
bats_require_minimum_version 1.5.0
REPO_ROOT="$(cd "$BATS_TEST_DIRNAME/../.." && pwd)"
HELPERS="$REPO_ROOT/infra/tests/helpers"

docker() {
  MSYS_NO_PATHCONV=1 command docker "$@"
}

stack() {
  (cd "$REPO_ROOT/infra" && DUO_ENV_FILE="$ENV_FILE" docker compose -p "$RUN_ID" --env-file "$ENV_FILE" \
    -f docker-compose.yml -f docker-compose.local.yml "$@")
}

psql_db() {
  stack exec -T postgres psql -U duo -d duo -v ON_ERROR_STOP=1 -tA "$@"
}

setup_file() {
  export RUN_ID="duo-backup-$$-$RANDOM"
  export ENV_FILE
  ENV_FILE="$(mktemp)"
  if command -v cygpath >/dev/null 2>&1; then ENV_FILE="$(cygpath -m "$ENV_FILE")"; fi
  local pw
  pw="$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')"
  {
    echo "IMAGE_TAG=local"
    echo "POSTGRES_DB=duo"
    echo "POSTGRES_USER=duo"
    echo "POSTGRES_PASSWORD=$pw"
    echo "DB_URL=jdbc:postgresql://postgres:5432/duo"
    echo "DB_USER=duo"
    echo "DB_PASSWORD=$pw"
    echo "PUBLIC_BASE_URL=http://localhost:8088"
    echo "RESEND_API_KEY=re_test"
    echo "EMAIL_FROM=test@example.com"
    echo "JWT_PRIVATE_KEY=unused"
  } >"$ENV_FILE"
  if ! stack up -d --wait --wait-timeout 120 postgres >&3 2>&3; then
    stack logs --tail 40 >&3 2>&3 || true
    return 1
  fi
  psql_db -c "CREATE TABLE users (id int primary key, name text);
              CREATE TABLE transactions (id int primary key, user_id int references users(id), cents int);
              INSERT INTO users SELECT g, 'user' || g FROM generate_series(1, 5) g;
              INSERT INTO transactions SELECT g, 1 + g % 5, g * 100 FROM generate_series(1, 40) g;" >&3
}

teardown_file() {
  stack down -v --remove-orphans >/dev/null 2>&1 || true
  rm -f "$ENV_FILE"
}

setup() {
  WORK="$BATS_TEST_TMPDIR/deploy"
  BK="$BATS_TEST_TMPDIR/bk"
  mkdir -p "$WORK" "$BK"
  cp "$REPO_ROOT"/infra/scripts/{backup,restore,install-cron}.sh "$WORK/"
  # Not shell-safe on purpose: the scripts must never source .env.
  # shellcheck disable=SC2016
  {
    cat "$ENV_FILE"
    echo 'EMAIL_NOTE=p$w`d"x'"'"'y'
    echo "BACKUP_TARGET=file://$BK"
  } >"$WORK/.env"
  export COMPOSE_FILES="-p $RUN_ID --env-file $ENV_FILE -f $REPO_ROOT/infra/docker-compose.yml -f $REPO_ROOT/infra/docker-compose.local.yml"
  export DUO_ENV_FILE="$ENV_FILE"
}

dumps() { find "$BK/daily" -maxdepth 1 -name 'duofinance-*.dump' 2>/dev/null; }

@test "backup_writesValidDump" {
  cd "$WORK"
  run bash ./backup.sh
  echo "$output"
  [ "$status" -eq 0 ]
  [ "$(dumps | wc -l)" -eq 1 ]
  [[ "$(basename "$(dumps)")" =~ ^duofinance-[0-9]{8}-[0-9]{6}\.dump$ ]]
  [ ! -e "$BK/daily/.partial" ]
  list="$(stack exec -T postgres pg_restore --list <"$(dumps)")"
  [[ "$list" == *"TABLE public users"* ]]
  [[ "$list" == *"TABLE public transactions"* ]]
}

@test "backup_restoresIntoEmptyDatabase" {
  cd "$WORK"
  run bash ./backup.sh
  [ "$status" -eq 0 ]
  before="$(psql_db -c "SELECT (SELECT count(*) FROM users) || '/' || (SELECT count(*) FROM transactions) || '/' || (SELECT sum(cents) FROM transactions)")"
  [ "$before" = "5/40/82000" ]
  psql_db -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"
  [ "$(psql_db -c "SELECT count(*) FROM information_schema.tables WHERE table_schema='public'")" = "0" ]

  run bash ./restore.sh "$(dumps)"
  [ "$status" -ne 0 ]
  [[ "$output" == *"--yes"* ]]
  [ "$(psql_db -c "SELECT count(*) FROM information_schema.tables WHERE table_schema='public'")" = "0" ]

  run bash ./restore.sh "$(dumps)" --yes
  echo "$output"
  [ "$status" -eq 0 ]
  after="$(psql_db -c "SELECT (SELECT count(*) FROM users) || '/' || (SELECT count(*) FROM transactions) || '/' || (SELECT sum(cents) FROM transactions)")"
  [ "$after" = "$before" ]
}

@test "backup_failsLoudly" {
  cd "$WORK"
  sed -i 's/^POSTGRES_USER=.*/POSTGRES_USER=nobody/' .env
  run bash ./backup.sh
  echo "$output"
  [ "$status" -ne 0 ]
  [ -z "$(dumps)" ]
  [ ! -e "$BK/daily/.partial" ]
  [ -z "$(find "$BK" -type f)" ]
}

@test "backup_prunesOlderThan30Days" {
  cd "$WORK"
  mkdir -p "$BK/daily"
  touch -d "31 days ago" "$BK/daily/duofinance-old.dump"
  touch -d "29 days ago" "$BK/daily/duofinance-recent.dump"
  touch -d "40 days ago" "$BK/daily/unrelated.txt"
  run bash ./backup.sh
  echo "$output"
  [ "$status" -eq 0 ]
  [ ! -e "$BK/daily/duofinance-old.dump" ]
  [ -e "$BK/daily/duofinance-recent.dump" ]
  [ -e "$BK/daily/unrelated.txt" ]
  [ "$(dumps | wc -l)" -eq 2 ]
}

@test "backup_s3Path_usesPartialThenRenameAndPrunes" {
  cd "$WORK"
  export STUB_S3_ROOT="$BATS_TEST_TMPDIR/s3"
  export STUB_LOG="$BATS_TEST_TMPDIR/aws.log"
  mkdir -p "$STUB_S3_ROOT/bkt/daily"
  touch -d "31 days ago" "$STUB_S3_ROOT/bkt/daily/duofinance-old.dump"
  touch -d "29 days ago" "$STUB_S3_ROOT/bkt/daily/duofinance-recent.dump"
  sed -i '/^BACKUP_TARGET=/d' .env
  printf '%s\n' 'BACKUP_S3_BUCKET=bkt' 'BACKUP_S3_ENDPOINT=https://s3.stub.test' >>.env
  mkdir -p "$BATS_TEST_TMPDIR/awsbin" && cp "$HELPERS/bin/aws" "$BATS_TEST_TMPDIR/awsbin/aws" # only aws: helpers/bin also stubs docker
  PATH="$BATS_TEST_TMPDIR/awsbin:$PATH" run bash ./backup.sh
  echo "$output"
  [ "$status" -eq 0 ]
  [ "$(find "$STUB_S3_ROOT/bkt/daily" -name 'duofinance-2*.dump' | wc -l)" -eq 1 ]
  [ -e "$STUB_S3_ROOT/bkt/daily/duofinance-recent.dump" ]
  [ ! -e "$STUB_S3_ROOT/bkt/daily/duofinance-old.dump" ]
  [ ! -e "$STUB_S3_ROOT/bkt/daily/.partial" ]
  grep -q -- "--endpoint-url https://s3.stub.test" "$STUB_LOG"
  grep -q " cp .* s3://bkt/daily/.partial/duofinance-" "$STUB_LOG"
  grep -q " mv s3://bkt/daily/.partial/duofinance-" "$STUB_LOG"
}

@test "backup_s3UploadFailure_leavesNoFinalObjectAndRemovesPartial" {
  cd "$WORK"
  export STUB_S3_ROOT="$BATS_TEST_TMPDIR/s3"
  export STUB_LOG="$BATS_TEST_TMPDIR/aws.log"
  export STUB_FAIL=mv
  mkdir -p "$STUB_S3_ROOT/bkt/daily"
  sed -i '/^BACKUP_TARGET=/d' .env
  printf '%s\n' 'BACKUP_S3_BUCKET=bkt' >>.env
  mkdir -p "$BATS_TEST_TMPDIR/awsbin" && cp "$HELPERS/bin/aws" "$BATS_TEST_TMPDIR/awsbin/aws"
  PATH="$BATS_TEST_TMPDIR/awsbin:$PATH" run bash ./backup.sh
  echo "$output"
  [ "$status" -ne 0 ]
  [ -z "$(find "$STUB_S3_ROOT/bkt" -type f -name 'duofinance-*.dump')" ]
  [ -z "$(find "$STUB_S3_ROOT/bkt" -path '*.partial*')" ]
  grep -q " cp .* s3://bkt/daily/.partial/duofinance-" "$STUB_LOG"
  grep -q " rm s3://bkt/daily/.partial/duofinance-" "$STUB_LOG"
}

@test "backup_s3UnparsableLastModified_isLoggedAndSkipped" {
  cd "$WORK"
  export STUB_S3_ROOT="$BATS_TEST_TMPDIR/s3"
  export STUB_LOG="$BATS_TEST_TMPDIR/aws.log"
  export STUB_BAD_DATE=1
  mkdir -p "$STUB_S3_ROOT/bkt/daily"
  touch -d "40 days ago" "$STUB_S3_ROOT/bkt/daily/duofinance-old.dump"
  sed -i '/^BACKUP_TARGET=/d' .env
  printf '%s\n' 'BACKUP_S3_BUCKET=bkt' >>.env
  mkdir -p "$BATS_TEST_TMPDIR/awsbin" && cp "$HELPERS/bin/aws" "$BATS_TEST_TMPDIR/awsbin/aws"
  PATH="$BATS_TEST_TMPDIR/awsbin:$PATH" run bash ./backup.sh
  echo "$output"
  [ "$status" -eq 0 ]
  [[ "$output" == *"cannot parse LastModified"*"daily/duofinance-old.dump"* ]]
  [ -e "$STUB_S3_ROOT/bkt/daily/duofinance-old.dump" ]
}

# docker wrapper (first on PATH) that only logs and fakes `stop api` / `start api` (the test stack
# has no api service); everything else goes to the real docker. FAIL_RESTORE_CALL=1 makes the
# pg_restore --single-transaction call fail.
api_wrapper() {
  local bin="$BATS_TEST_TMPDIR/bin"
  mkdir -p "$bin"
  cat >"$bin/docker" <<'STUB'
#!/usr/bin/env bash
case " $* " in
  *" stop api"*|*" start api"*) echo "docker $*" >>"$STUB_LOG"; exit 0 ;;
esac
exec "$REAL_DOCKER" "$@"
STUB
  chmod +x "$bin/docker"
  export STUB_LOG="$BATS_TEST_TMPDIR/docker.log"
  REAL_DOCKER="$(type -P docker)"
  export REAL_DOCKER
  PATH="$bin:$PATH"
}

teardown() {
  psql_db -c "DROP VIEW IF EXISTS v_users; DROP TABLE IF EXISTS newer_only; DELETE FROM transactions WHERE id > 40; DELETE FROM users WHERE id > 5" >/dev/null 2>&1 || true
}

@test "restore_stopApiFlagStopsAndStartsApi" {
  cd "$WORK"
  bash ./backup.sh
  api_wrapper
  run bash ./restore.sh "$(dumps)" --yes --stop-api
  echo "$output"
  [ "$status" -eq 0 ]
  [ "$(sed -n '1p' "$STUB_LOG" | grep -c ' stop api')" -eq 1 ]
  [ "$(sed -n '2p' "$STUB_LOG" | grep -c ' start api')" -eq 1 ]
}

@test "restore_failureRollsBackAndStillRestartsApi" {
  cd "$WORK"
  bash ./backup.sh
  # State that makes the restore's DROP TABLE fail (dependent view), plus a row newer than the dump.
  psql_db -c "CREATE VIEW v_users AS SELECT * FROM users; INSERT INTO users VALUES (6, 'newer')"
  api_wrapper
  run bash ./restore.sh "$(dumps)" --yes --stop-api
  echo "$output"
  [ "$status" -ne 0 ]
  [ "$(grep -c ' start api' "$STUB_LOG")" -eq 1 ]
  # Rolled back: the DB is exactly as before the restore (new row and view still there).
  [ "$(psql_db -c "SELECT count(*) FROM users")" = "6" ]
  [ "$(psql_db -c "SELECT count(*) FROM pg_views WHERE viewname='v_users'")" = "1" ]
  [ "$(psql_db -c "SELECT count(*) FROM transactions")" = "40" ]
}

@test "restore_dropSchema_restoresOlderDumpOverNewerSchema" {
  cd "$WORK"
  bash ./backup.sh
  psql_db -c "CREATE TABLE newer_only (id int); CREATE VIEW v_users AS SELECT * FROM users; INSERT INTO users VALUES (6, 'newer')"
  run bash ./restore.sh "$(dumps)" --yes --drop-schema
  echo "$output"
  [ "$status" -eq 0 ]
  [[ "$output" == *"public is DROPPED"* ]]
  [ "$(psql_db -c "SELECT count(*) FROM information_schema.tables WHERE table_name IN ('newer_only','v_users')")" = "0" ]
  [ "$(psql_db -c "SELECT count(*) FROM users")" = "5" ]
  [ "$(psql_db -c "SELECT count(*) FROM transactions")" = "40" ]
  # Without --yes the plan mentions it and nothing runs.
  run bash ./restore.sh "$(dumps)" --drop-schema
  [ "$status" -ne 0 ]
  [[ "$output" == *"public is DROPPED"* ]]
}

@test "installCron_isIdempotent" {
  me="$(id -un)"
  export CRON_DIR="$BATS_TEST_TMPDIR/cron.d" CRON_USER="$me" DEPLOY_PATH=/opt/duo-finance
  export BACKUP_LOG="$BATS_TEST_TMPDIR/backup.log"
  mkdir -p "$CRON_DIR"
  run bash "$WORK/install-cron.sh"
  [ "$status" -eq 0 ]
  echo "keep" >"$BACKUP_LOG"
  first="$(cat "$CRON_DIR/duo-finance-backup")"
  run bash "$WORK/install-cron.sh"
  [ "$status" -eq 0 ]
  [ "$(cat "$CRON_DIR/duo-finance-backup")" = "$first" ]
  [ "$(grep -c 'backup.sh' "$CRON_DIR/duo-finance-backup")" -eq 1 ]
  grep -qxF "30 3 * * * $me cd /opt/duo-finance && ./backup.sh >> $BACKUP_LOG 2>&1" \
    "$CRON_DIR/duo-finance-backup"
  [ "$(stat -c %a "$CRON_DIR/duo-finance-backup")" = "644" ]
  [ "$(find "$CRON_DIR" -type f | wc -l)" -eq 1 ]
  # Log file: created once, never truncated, 0640, owned by CRON_USER.
  [ "$(cat "$BACKUP_LOG")" = "keep" ]
  # Windows file systems (Git Bash) cannot represent 0640: only assert the mode where chmod sticks.
  touch "$BATS_TEST_TMPDIR/probe" && chmod 0640 "$BATS_TEST_TMPDIR/probe"
  if [ "$(stat -c %a "$BATS_TEST_TMPDIR/probe")" = "640" ]; then [ "$(stat -c %a "$BACKUP_LOG")" = "640" ]; fi
  [ "$(stat -c %U "$BACKUP_LOG")" = "$me" ]
}
