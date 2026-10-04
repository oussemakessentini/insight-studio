# syntax=docker/dockerfile:1.7
# Insight Studio web app (docs/deployment.md): the Vite build served by an unprivileged nginx, which also
# proxies /api to the API. Reproducible: images pinned by digest, dependencies from package-lock.json (npm ci).
# Build from apps/web:  docker build -t insight-web:<git sha> apps/web

ARG NODE_IMAGE=node:24-alpine@sha256:ebfe2f90462722a7a4de65e91990e97fe0d401c70e0e762c5b53302f905ec1c1
ARG NGINX_IMAGE=nginxinc/nginx-unprivileged:1.30-alpine@sha256:ed04ec1ff34502c339ee5c3ae3f855442398edc1d05591e2b98981dcbbd20b1e

FROM ${NODE_IMAGE} AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM ${NGINX_IMAGE}
# Rendered to /etc/nginx/conf.d/default.conf at start by the image's entrypoint (envsubst of the variables
# that are set, i.e. API_UPSTREAM; nginx's own $variables are left alone).
# pcre2 10.48 in the pinned base has CVE-2026-103111 (HIGH), fixed in Alpine's 10.49: upgrade only that package
# (resolved at build time, so this layer is not pinned; drop it once the base image ships the fix).
USER root
RUN apk upgrade --no-cache pcre2
USER 101
COPY nginx.conf /etc/nginx/templates/default.conf.template
COPY --from=build /src/dist/ /usr/share/nginx/html/
# The API's address inside the network (the compose service name); overridable at run time.
ENV API_UPSTREAM=http://api:8080
# nginx-unprivileged: listens on 8080 as uid 101.
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --retries=3 CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
