# Dependency and security review

Review of 2026-10-04 (branch `integration/production-ops`). Repeated by CI on every push (job "Dependency
review" and the image scans); re-do this document's manual parts before each release.

## Dependency and image scans

| Scope | Tool | Result before | Action | Result after |
|---|---|---|---|---|
| Web runtime and dev dependencies (`apps/web/package-lock.json`) | `npm audit` | 0 vulnerabilities | — | 0 |
| Maven and npm manifests | OSV-Scanner 2.2.3 | no issues in the 22 direct Maven dependencies; transitive resolution was refused by Maven Central (HTTP 429, rate limit), so OSV did **not** cover transitive Maven dependencies | covered by Trivy on the built image (every jar in it) | see API image |
| API image (Ubuntu 24.04 JRE + every jar) | Trivy 0.67.2, fixable HIGH/CRITICAL | **8**: Tomcat 11.0.24 (3 CRITICAL: security constraint bypass, authentication bypass, unauthorized resource access via FORM) and Jackson 3.1.5 (5 HIGH) | overrides in `apps/api/pom.xml`: `tomcat.version` 11.0.25, `jackson-bom.version` 3.1.7 (drop them once Spring Boot manages these versions) | **0** (rescanned after the rebuild; the full backend suite passed on the new versions) |
| Web image (nginx, Alpine) | Trivy | **42** HIGH in Alpine packages of nginx-unprivileged 1.29 (c-ares, curl, OpenSSL, libexpat, libxml2, pcre2, util-linux) | base moved to nginx-unprivileged 1.30 (pinned digest): 1 left (pcre2 10.48, CVE-2026-103111), upgraded to Alpine's fixed 10.49 in the Dockerfile (that layer is resolved at build time, not pinned) | **0** |
| Cube image (upstream `cubejs/cube:v1.7.46` + our model) | Trivy | **84** fixable HIGH/CRITICAL, all upstream: 46 Debian packages (mostly `linux-libc-dev` kernel headers, OpenSSL, pcre2), 28 in Cube's Node.js packages (1 CRITICAL), 8 in bundled jars (1 CRITICAL), 2 other | not patched: they are inside Cube's own release. CI scans it **report-only**. Cube is optional and off by default (`REPORTS_ENGINE=sql`); upgrading Cube is a precondition for enabling it ([release-checks.md](release-checks.md)) | 84 (accepted while Cube is off) |

The CI image job fails on any fixable HIGH/CRITICAL in the API or web image. Unfixed findings (no patched
version yet) are not counted (`--ignore-unfixed`); review them when they get a fix.

## Application security controls (reviewed, unchanged here)

- **Authentication and sessions**: bcrypt passwords, server-side sessions in PostgreSQL (`HttpOnly`,
  `SameSite=Lax`, `Secure` and `__Host-` prefix with the `prod` profile), session fixation protection, session
  version bump on password change and account deletion, CSRF tokens on every state-changing request
  (webhooks excepted: signature-verified).
- **Authorization and isolation**: every business-scoped query is keyed by the business from the member's
  session, never from request input; composite foreign keys keep rows inside one business; the permission
  matrix and cross-business access are tested for every endpoint family (403/404).
- **Abuse limits**: rate limits in PostgreSQL (sign-in, sign-up, resets, invitations, exports), per-business
  plan limits enforced under locks, the shared chart run limit, statement timeouts on chart queries.
- **Secrets**: only in environment files on the host (`infra/.env`, `infra/.env.prod`, git-ignored); never in
  images, git or logs; Stripe keys refused unless test-mode; tokens stored as SHA-256 only; logs and audit
  details exclude passwords, tokens, links, bodies and payment payloads (tested).
- **Payment provider**: webhook signatures (HMAC-SHA256, 5-minute tolerance, constant-time compare), events
  recorded once by id, provider state re-fetched rather than trusted from payloads, calls outside transactions
  with idempotency keys.

## Deployment hardening (this branch)

- Containers run unprivileged (API uid 10001, nginx uid 101), `no-new-privileges`, all capabilities dropped,
  the API's root filesystem read-only; memory/CPU limits; log rotation.
- Only the web container is published; the API, its management port (8081: health, metrics), PostgreSQL and
  Cube are reachable only inside the compose network. The web container answers 404 for `/actuator`.
- Security headers from nginx: Content-Security-Policy (`default-src 'self'`, no inline scripts, frames denied,
  forms only to the app and Stripe's checkout/portal), `X-Frame-Options: DENY`, `X-Content-Type-Options`,
  `Referrer-Policy`, `Permissions-Policy`. HSTS belongs on the TLS-terminating proxy (see production.md).
- Base images pinned by digest; dependency updates are deliberate (and scanned).

## Open items

1. **Transitive Maven coverage by OSV** depends on Maven Central's rate limit; Trivy on the API image covers
   the jars actually shipped, which is the stronger check.
2. **Cube image findings** (above): upgrade Cube and rescan before `REPORTS_ENGINE=cube` is considered.
3. **pcre2 layer of the web image** is resolved at build time; remove the upgrade once nginx-unprivileged ships
   pcre2 ≥ 10.49.
4. **Security overrides in `pom.xml`** (`tomcat.version`, `jackson-bom.version`): remove when Spring Boot's own
   managed versions reach them, so they do not hold back later fixes.
