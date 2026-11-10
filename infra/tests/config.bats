#!/usr/bin/env bats
# Static configuration checks (no images built, no containers started). Run from the repo root:
#   npx --yes bats@latest infra/tests/config.bats
# - The image build context/Dockerfile pairs of deploy.yml match docker-compose.local.yml and the
#   image tests, and every COPY source of each Dockerfile exists in its context.
# - The api container never receives the backup credentials (`docker compose config`, CLI only).

bats_require_minimum_version 1.5.0
REPO_ROOT="$(cd "$BATS_TEST_DIRNAME/../.." && pwd)"
DEPLOY_YML="$REPO_ROOT/.github/workflows/deploy.yml"
LOCAL_YML="$REPO_ROOT/infra/docker-compose.local.yml"

# "<context> <file>" of the deploy.yml step with this name, as written (relative to the repo root).
workflow_build() {
  awk -v step="- name: $1" '
    index($0, step) { found = 1; next }
    found && /^      - / { exit }
    found && $1 == "context:" { ctx = $2 }
    found && $1 == "file:" { file = $2 }
    END { print ctx, file }
  ' "$DEPLOY_YML"
}

# "<context> <file>" of a service build in docker-compose.local.yml, relative to the repo root.
compose_build() {
  local ctx dockerfile
  read -r ctx dockerfile < <(awk -v svc="  $1:" '
    $0 == svc { found = 1; next }
    found && /^  [a-z]/ { exit }
    found && $1 == "context:" { ctx = $2 }
    found && $1 == "dockerfile:" { file = $2 }
    END { print ctx, (file == "" ? "Dockerfile" : file) }
  ' "$LOCAL_YML")
  ctx="$(realpath -m --relative-to="$REPO_ROOT" "$REPO_ROOT/infra/$ctx")"
  if [ "$ctx" = . ]; then echo ". $dockerfile"; else echo "$ctx $ctx/$dockerfile"; fi
}

# COPY sources (not --from=) of a Dockerfile, one per line.
copy_sources() {
  awk '
    $1 == "COPY" {
      n = 0
      for (i = 2; i <= NF; i++) {
        if ($i ~ /^--from=/) { n = -1; break }
        if ($i ~ /^--/) continue
        args[++n] = $i
      }
      for (i = 1; i < n; i++) print args[i]
    }
  ' "$1"
}

@test "buildContexts_deployMatchesComposeAndImageTests" {
  api="$(workflow_build 'Build and push api')"
  nginx="$(workflow_build 'Build and push nginx')"
  echo "deploy.yml: api=[$api] nginx=[$nginx]"
  [ "$api" = "backend backend/Dockerfile" ]
  [ "$nginx" = ". frontend/Dockerfile" ]
  [ "$(compose_build api)" = "$api" ]
  [ "$(compose_build nginx)" = "$nginx" ]
  # images.bats builds the api from its context with the default Dockerfile; nginx.bats builds
  # nginx from the repo root with -f.
  grep -qF 'docker build -q -t "$API_IMAGE" "$REPO_ROOT/backend"' "$REPO_ROOT/infra/tests/images.bats"
  grep -qF '(cd "$REPO_ROOT" && docker build -q -t "$NGINX_IMAGE" -f frontend/Dockerfile .' \
    "$REPO_ROOT/infra/tests/nginx.bats"
}

@test "buildContexts_everyCopySourceExistsInContext" {
  local step ctx file src missing=0
  for step in 'Build and push api' 'Build and push nginx'; do
    read -r ctx file < <(workflow_build "$step")
    [ -f "$REPO_ROOT/$file" ]
    while IFS= read -r src; do
      if [ ! -e "$REPO_ROOT/$ctx/$src" ]; then
        echo "$step: COPY source $src not found in context $ctx"
        missing=1
      fi
    done < <(copy_sources "$REPO_ROOT/$file")
  done
  [ "$missing" -eq 0 ]
}

@test "compose_apiGetsNoBackupCredentials" {
  command -v docker >/dev/null 2>&1 || skip "docker CLI not available"
  local dir="$BATS_TEST_TMPDIR/deploy"
  mkdir -p "$dir"
  cp "$REPO_ROOT/infra/docker-compose.yml" "$dir/"
  sed -e 's/^IMAGE_TAG=$/IMAGE_TAG=sha-test/' -e 's/^POSTGRES_PASSWORD=$/POSTGRES_PASSWORD=pw/' \
    "$REPO_ROOT/infra/.env.example" >"$dir/.env"
  # A filled backup.env next to it, as on the instance.
  [ -f "$REPO_ROOT/infra/backup.env.example" ]
  sed -e 's/^AWS_ACCESS_KEY_ID=$/AWS_ACCESS_KEY_ID=AKIDTEST/' \
    -e 's/^AWS_SECRET_ACCESS_KEY=$/AWS_SECRET_ACCESS_KEY=secret-test/' \
    -e 's/^BACKUP_S3_BUCKET=$/BACKUP_S3_BUCKET=bkt/' \
    "$REPO_ROOT/infra/backup.env.example" >"$dir/backup.env"
  run bash -c 'cd "$1" && docker compose config api' _ "$dir"
  [ "$status" -eq 0 ]
  api_env="$(awk '
    /^  api:/ { svc = 1; next }
    svc && /^  [a-z]/ { exit }
    svc && /^    environment:/ { env = 1; next }
    env && !/^      / { exit }
    env { print }
  ' <<<"$output")"
  echo "$api_env"
  # Not vacuous: the block holds the app settings.
  [[ "$api_env" == *"DB_URL: jdbc:postgresql://postgres:5432/duofinance"* ]]
  run ! grep -E '^ +(AWS_|BACKUP_)' <<<"$api_env"
  # backup.env.example: placeholders only.
  run grep -vE '^(#|$|[A-Z0-9_]+=$)' "$REPO_ROOT/infra/backup.env.example"
  [ -z "$output" ]
}
