#!/usr/bin/env bash
# Usage: install-cron.sh      (as root, on the instance)
#
# Installs /etc/cron.d/duo-finance-backup: backup.sh every day at 03:30, logging to
# /var/log/duo-finance-backup.log. Rewrites the whole file each time (temp file + rename), so it
# is idempotent. Mode 0644, which cron.d requires.
# cron.d uses the system timezone; Ubuntu on Lightsail runs on UTC, so 03:30 means 03:30 UTC.
# Env overrides: CRON_DIR (default /etc/cron.d), CRON_USER (default root),
#   DEPLOY_PATH (default /opt/duo-finance).
set -euo pipefail

cron_dir="${CRON_DIR:-/etc/cron.d}"
cron_user="${CRON_USER:-root}"
deploy_path="${DEPLOY_PATH:-/opt/duo-finance}"
file="$cron_dir/duo-finance-backup"

# Dot-prefixed temp name: cron ignores file names with dots, so it never reads a half-written file.
tmp="$(mktemp "$cron_dir/.duo-finance-backup.XXXXXX")"
trap 'rm -f "$tmp"' EXIT
cat >"$tmp" <<CRON
# Duo Finance: daily Postgres backup (managed by install-cron.sh).
# Times are in the system timezone (UTC on Ubuntu/Lightsail): 03:30 UTC.
SHELL=/bin/bash
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
30 3 * * * $cron_user cd $deploy_path && ./backup.sh >> /var/log/duo-finance-backup.log 2>&1
CRON
chmod 0644 "$tmp"
mv -f "$tmp" "$file"
trap - EXIT
echo "installed $file"
