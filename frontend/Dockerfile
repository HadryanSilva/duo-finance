# syntax=docker/dockerfile:1
# Build from the repository root: docker build -f frontend/Dockerfile .
# (the context must reach infra/nginx; frontend/Dockerfile.dockerignore trims it).
FROM node:24-alpine AS build
WORKDIR /src

# Dependencies first so this layer is cached until the lockfile changes.
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY frontend/ ./
RUN npm run build

FROM nginx:stable-alpine
# NGINX_TEMPLATE: app (production, TLS) or app-local (plain HTTP smoke stack).
# NGINX_ENVSUBST_FILTER keeps envsubst away from nginx's own $variables.
ENV NGINX_SERVER_NAME=duofinance.dev.br \
    NGINX_TEMPLATE=app \
    NGINX_ENVSUBST_FILTER=^NGINX_

RUN rm -f /etc/nginx/conf.d/default.conf
COPY infra/nginx/nginx.conf /etc/nginx/nginx.conf
COPY infra/nginx/snippets/ /etc/nginx/snippets/
COPY infra/nginx/templates/ /etc/nginx/templates/
COPY --chmod=0755 infra/nginx/entrypoint.sh /duo-entrypoint.sh
COPY --from=build /src/dist/ /usr/share/nginx/html/

ENTRYPOINT ["/duo-entrypoint.sh"]
CMD ["nginx", "-g", "daemon off;"]
