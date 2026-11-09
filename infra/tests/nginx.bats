#!/usr/bin/env bats
# nginx image tests. Run from the repo root (Git Bash on Windows, host Docker):
#   npx --yes bats@latest infra/tests/nginx.bats
# Builds the frontend image, starts it with an "api" stub (fixtures/nginx-stack.yml) on a random
# loopback port, and checks SPA fallback, caching, security headers, the /api proxy and rate limits.
# The production template is checked with a self-signed certificate generated at run time into a
# throwaway Docker volume; no certificate is ever written to the repository.

bats_require_minimum_version 1.5.0
REPO_ROOT="$(cd "$BATS_TEST_DIRNAME/../.." && pwd)"
NGINX_IMAGE="duo-nginx:test"
STACK_FILE="infra/tests/fixtures/nginx-stack.yml"
PROD_NAME="duofinance.test"
CSP="default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'"

# Git Bash would rewrite container paths such as /etc/letsencrypt into Windows paths. Every host
# path handed to docker is relative to REPO_ROOT, so path conversion is off for docker only
# (curl still needs it for its temp files). No effect on Linux.
docker() {
  MSYS_NO_PATHCONV=1 command docker "$@"
}

stack() {
  (cd "$REPO_ROOT" && NGINX_IMAGE="$NGINX_IMAGE" docker compose -p "$RUN_ID" -f "$STACK_FILE" "$@")
}

setup_file() {
  export RUN_ID="duo-nginx-$$-$RANDOM"
  (cd "$REPO_ROOT" && docker build -q -t "$NGINX_IMAGE" -f frontend/Dockerfile . >/dev/null)
  stack up -d --quiet-pull >/dev/null 2>&1
  local port i
  port="$(stack port nginx 80 | head -n1 | sed 's/.*://' | tr -d '\r')"
  export BASE="http://127.0.0.1:$port"
  for i in $(seq 1 30); do
    curl -fsS -o /dev/null "$BASE/" 2>/dev/null && break
    sleep 1
  done
  ASSET="$(docker run --rm --entrypoint ls "$NGINX_IMAGE" /usr/share/nginx/html/assets | grep -m1 '\.js$' | tr -d '\r')"
  export ASSET
}

teardown_file() {
  docker rm -f "$RUN_ID-prod" >/dev/null 2>&1 || true
  docker volume rm "$RUN_ID-certs" >/dev/null 2>&1 || true
  stack down -v --remove-orphans >/dev/null 2>&1 || true
}

# fetch <url-or-path> [curl args...] -> prints the status code; headers and body land in
# $BATS_TEST_TMPDIR/headers and $BATS_TEST_TMPDIR/body.
fetch() {
  local url="$1"
  shift
  [[ "$url" == http* ]] || url="$BASE$url"
  curl -sS -o "$BATS_TEST_TMPDIR/body" -D "$BATS_TEST_TMPDIR/headers" -w '%{http_code}' "$@" "$url"
}

# header <name> -> the values of that response header from the last fetch, one per line.
header() {
  grep -i "^$1:" "$BATS_TEST_TMPDIR/headers" | sed 's/^[^:]*:[[:space:]]*//' | tr -d '\r' || true
}

header_count() {
  grep -ci "^$1:" "$BATS_TEST_TMPDIR/headers" || true
}

body() {
  cat "$BATS_TEST_TMPDIR/body"
}

# Security headers every response must carry exactly once (HSTS: never over plain HTTP).
assert_security_headers() {
  local what="$1"
  [ "$(header_count Content-Security-Policy)" -eq 1 ] || { echo "$what: CSP count $(header_count Content-Security-Policy)" >&2; return 1; }
  [ "$(header Content-Security-Policy)" = "$CSP" ] || { echo "$what: CSP '$(header Content-Security-Policy)'" >&2; return 1; }
  [ "$(header_count X-Content-Type-Options)" -eq 1 ] || { echo "$what: nosniff count" >&2; return 1; }
  [ "$(header X-Content-Type-Options)" = "nosniff" ] || { echo "$what: nosniff" >&2; return 1; }
  [ "$(header Referrer-Policy)" = "strict-origin-when-cross-origin" ] || { echo "$what: Referrer-Policy" >&2; return 1; }
}

@test "nginx config is valid" {
  run docker run --rm -e NGINX_TEMPLATE=app-local "$NGINX_IMAGE" nginx -t
  [ "$status" -eq 0 ]
  [[ "$output" == *"test is successful"* ]]
}

@test "unknown NGINX_TEMPLATE is refused" {
  run docker run --rm -e NGINX_TEMPLATE=bogus "$NGINX_IMAGE" nginx -t
  [ "$status" -ne 0 ]
  [[ "$output" == *"NGINX_TEMPLATE"* ]]
}

@test "spa_deepLinkServesIndex" {
  local path
  for path in "/transactions?month=2026-11" "/invite/abc123def456" "/"; do
    [ "$(fetch "$path")" = "200" ] || { echo "$path: not 200" >&2; return 1; }
    body | grep -q '<div id="app">' || { echo "$path: no app mount point" >&2; return 1; }
  done
}

@test "missingAsset_is404" {
  [ "$(fetch /assets/nao-existe.js)" = "404" ]
  run ! grep -q '<div id="app">' "$BATS_TEST_TMPDIR/body"
}

@test "cacheHeaders" {
  local path
  for path in / /index.html "/transactions?month=2026-11"; do
    [ "$(fetch "$path")" = "200" ]
    [ "$(header Cache-Control)" = "no-cache" ] || { echo "$path: '$(header Cache-Control)'" >&2; return 1; }
  done
  [ -n "$ASSET" ]
  [ "$(fetch "/assets/$ASSET")" = "200" ]
  [ "$(header Cache-Control)" = "public, max-age=31536000, immutable" ]
}

@test "securityHeaders" {
  local path
  for path in / /index.html "/transactions?month=2026-11" "/assets/$ASSET" /assets/nao-existe.js \
    /api/actuator/health /api/v1/public/registration-mode /api/v1/__e2e/outbox; do
    fetch "$path" >/dev/null
    assert_security_headers "$path"
    [ "$(header_count Strict-Transport-Security)" -eq 0 ] || { echo "$path: HSTS over http" >&2; return 1; }
  done
}

@test "apiProxy_forwardsHeaders" {
  local path code
  for path in /api/actuator/health /api/v1/auth/login /api/v1/public/registration-mode; do
    code="$(fetch "$path" -H 'X-Forwarded-For: 6.6.6.6' -H 'X-Forwarded-Host: evil.example' \
      -H 'X-Forwarded-Port: 1234' -H 'X-Forwarded-Proto: https' -H 'X-Real-IP: 6.6.6.6')"
    [[ "$code" == 200 || "$code" == 401 ]] || { echo "$path: $code" >&2; return 1; }
    local real_ip xff
    real_ip="$(header X-Echo-Real-IP)"
    xff="$(header X-Echo-Forwarded-For)"
    [ -n "$real_ip" ] && [ "$real_ip" != "6.6.6.6" ] || { echo "$path: X-Real-IP '$real_ip'" >&2; return 1; }
    # nginx appends the real peer as the last hop; the backend trusts only that hop.
    [ "$xff" = "6.6.6.6, $real_ip" ] || { echo "$path: X-Forwarded-For '$xff'" >&2; return 1; }
    [ "$(header X-Echo-Forwarded-Proto)" = "http" ] || { echo "$path: proto" >&2; return 1; }
    [ "$(header X-Echo-Forwarded-Host)" = "127.0.0.1" ] || { echo "$path: X-Forwarded-Host '$(header X-Echo-Forwarded-Host)'" >&2; return 1; }
    [ "$(header X-Echo-Forwarded-Port)" = "80" ] || { echo "$path: X-Forwarded-Port '$(header X-Echo-Forwarded-Port)'" >&2; return 1; }
  done
  fetch /api/actuator/health >/dev/null
  [ "$(body)" = '{"status":"UP"}' ]
}

@test "authBurst_isRateLimited" {
  local i code limited=0 proxied=0
  for i in $(seq 1 20); do
    code="$(fetch /api/v1/auth/login -X POST -H 'Content-Type: application/json' -d '{}')"
    [ "$code" = "429" ] && limited=$((limited + 1))
    [ "$code" = "401" ] && proxied=$((proxied + 1))
  done
  echo "proxied=$proxied limited=$limited" >&2
  [ "$proxied" -ge 1 ]
  [ "$limited" -ge 1 ]
  [ "$(fetch /)" = "200" ]
  [ "$(fetch /api/actuator/health)" = "200" ]
}

@test "sessionRefresh_isNotRateLimited" {
  # The SPA refreshes the session on every page load; a 429 there signs the user out.
  local i code
  for i in $(seq 1 20); do
    code="$(fetch /api/v1/auth/refresh -X POST)"
    [ "$code" = "200" ] || { echo "refresh #$i: $code" >&2; return 1; }
    [ -n "$(header X-Echo-Real-IP)" ] || { echo "refresh #$i: not proxied" >&2; return 1; }
  done
  [ "$(fetch /api/v1/auth/logout -X POST)" = "200" ]
}

@test "e2eOutbox_isBlocked" {
  local path
  for path in /api/v1/__e2e/outbox /api/v1/__e2e /api/v1/%5F%5Fe2e/outbox; do
    [ "$(fetch "$path")" = "404" ] || { echo "$path: not 404" >&2; return 1; }
    # Answered by nginx itself: the stub would have echoed the forwarded headers.
    [ -z "$(header X-Echo-Real-IP)" ] || { echo "$path: reached the api" >&2; return 1; }
  done
}

make_dummy_cert() {
  docker volume create "$RUN_ID-certs" >/dev/null
  docker run --rm -v "$RUN_ID-certs:/etc/letsencrypt" --entrypoint sh alpine/openssl -c "
    set -e
    mkdir -p /etc/letsencrypt/live/$PROD_NAME
    cd /etc/letsencrypt/live/$PROD_NAME
    openssl req -x509 -nodes -newkey rsa:2048 -days 1 -subj '/CN=$PROD_NAME' \
      -addext 'subjectAltName=DNS:$PROD_NAME,DNS:www.$PROD_NAME' \
      -keyout privkey.pem -out fullchain.pem 2>/dev/null
  "
}

@test "prod template renders and validates with a dummy cert" {
  make_dummy_cert
  run docker run --rm -e NGINX_TEMPLATE=app -e NGINX_SERVER_NAME="$PROD_NAME" \
    -v "$RUN_ID-certs:/etc/letsencrypt:ro" "$NGINX_IMAGE" \
    sh -c '/duo-entrypoint.sh nginx -t && cat /etc/nginx/conf.d/*.conf && ls /etc/nginx/templates'
  [ "$status" -eq 0 ]
  [[ "$output" == *"test is successful"* ]]
  [[ "$output" == *"/etc/letsencrypt/live/$PROD_NAME/fullchain.pem"* ]]
  [[ "$output" == *"return 301 https://$PROD_NAME\$request_uri"* ]]
  [[ "$output" != *"app-local"* ]]
}

@test "prod template redirects to https and sends HSTS on every response" {
  make_dummy_cert
  docker run -d --name "$RUN_ID-prod" --network "${RUN_ID}_default" \
    -e NGINX_TEMPLATE=app -e NGINX_SERVER_NAME="$PROD_NAME" \
    -v "$RUN_ID-certs:/etc/letsencrypt:ro" -p 127.0.0.1::80 -p 127.0.0.1::443 "$NGINX_IMAGE" >/dev/null
  local http https i
  http="$(docker port "$RUN_ID-prod" 80/tcp | head -n1 | sed 's/.*://' | tr -d '\r')"
  https="$(docker port "$RUN_ID-prod" 443/tcp | head -n1 | sed 's/.*://' | tr -d '\r')"
  local tls=(-k --resolve "$PROD_NAME:$https:127.0.0.1" --resolve "www.$PROD_NAME:$https:127.0.0.1")
  for i in $(seq 1 30); do
    curl -fsS -o /dev/null "${tls[@]}" "https://$PROD_NAME:$https/" 2>/dev/null && break
    sleep 1
  done

  [ "$(fetch "http://127.0.0.1:$http/transactions?month=2026-11" -H "Host: $PROD_NAME")" = "301" ]
  [ "$(header Location)" = "https://$PROD_NAME/transactions?month=2026-11" ]
  # The ACME webroot is served over plain HTTP, never redirected.
  [ "$(fetch "http://127.0.0.1:$http/.well-known/acme-challenge/token" -H "Host: $PROD_NAME")" = "404" ]

  [ "$(fetch "https://www.$PROD_NAME:$https/x?y=1" "${tls[@]}")" = "301" ]
  [ "$(header Location)" = "https://$PROD_NAME/x?y=1" ]

  local path
  for path in / /index.html /transactions "/assets/$ASSET" /assets/nao-existe.js /api/actuator/health; do
    fetch "https://$PROD_NAME:$https$path" "${tls[@]}" >/dev/null
    assert_security_headers "https $path"
    [ "$(header_count Strict-Transport-Security)" -eq 1 ] || { echo "$path: HSTS count $(header_count Strict-Transport-Security)" >&2; return 1; }
    [ "$(header Strict-Transport-Security)" = "max-age=31536000; includeSubDomains" ] || { echo "$path: HSTS" >&2; return 1; }
  done
  fetch "https://$PROD_NAME:$https/api/actuator/health" "${tls[@]}" >/dev/null
  [ "$(header X-Echo-Forwarded-Proto)" = "https" ]
  [ "$(header X-Echo-Forwarded-Port)" = "443" ]
  [ "$(header X-Echo-Forwarded-Host)" = "$PROD_NAME" ]
}
