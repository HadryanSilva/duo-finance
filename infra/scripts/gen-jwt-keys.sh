#!/usr/bin/env bash
# Prints a fresh RSA 2048 JWT key pair as two single-line env assignments:
#   JWT_PRIVATE_KEY=<PKCS#8 PEM, newlines removed>
#   JWT_PUBLIC_KEY=<X.509 SubjectPublicKeyInfo PEM, newlines removed>
# Safe with any quoting and with `docker run --env-file`. Nothing is written to disk.
set -euo pipefail

command -v openssl >/dev/null || { echo "openssl is required" >&2; exit 1; }

key="$(openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 2>/dev/null)"
private="$(openssl pkcs8 -topk8 -nocrypt <<<"$key" | tr -d '\r\n')"
public="$(openssl pkey -pubout <<<"$key" | tr -d '\r\n')"

printf 'JWT_PRIVATE_KEY=%s\n' "$private"
printf 'JWT_PUBLIC_KEY=%s\n' "$public"
