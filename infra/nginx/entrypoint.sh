#!/bin/sh
# Keeps only the server template chosen by NGINX_TEMPLATE (app | app-local), then hands over to
# the official nginx entrypoint, which renders /etc/nginx/templates into /etc/nginx/conf.d.
set -eu

template="${NGINX_TEMPLATE:-app}"
case "$template" in
  app | app-local) ;;
  *)
    echo "duo-entrypoint: NGINX_TEMPLATE must be 'app' or 'app-local', got '$template'" >&2
    exit 64
    ;;
esac

for file in /etc/nginx/templates/*.conf.template; do
  [ "$file" = "/etc/nginx/templates/$template.conf.template" ] || rm -f "$file"
done

exec /docker-entrypoint.sh "$@"
