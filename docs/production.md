# Running in production: HTTPS, proxies, email

What a deployment must configure so accounts are safe. Nothing here is deployed by this repository;
it is the configuration an environment has to provide. Account behaviour itself is described in
[auth.md](auth.md).

## The `prod` profile

Start the API with `SPRING_PROFILES_ACTIVE=prod` (add `demo` only if the public read-only demo
should be served). The profile (`application-prod.properties`):

- marks the session and `XSRF-TOKEN` cookies `Secure`, and names the session cookie
  `__Host-SESSION` (Secure, `Path=/`, no `Domain`: no other subdomain can set or overwrite it);
- requires `WEB_BASE_URL`, `MAIL_HOST` and `MAIL_FROM` (startup fails when one is missing);
- defaults SMTP to submission with STARTTLS and authentication (port 587).

`ProductionSafetyCheck` then refuses to start unless the cookies are Secure and `WEB_BASE_URL` is
an `https://` address, so an environment variable such as `COOKIE_SECURE=false` cannot quietly undo
the profile. It logs a warning when no trusted proxy is configured.

## HTTPS

Serve the web app and the API from the **same origin** over HTTPS (for example
`https://insight.example.com` for the static web build, with `/api/**` routed to the API). The
session cookie is `SameSite=Lax` and the SPA reads the `XSRF-TOKEN` cookie, which both assume one
site.

Terminate TLS at a reverse proxy or load balancer:

1. Redirect `http://` to `https://` at the proxy; the API never listens publicly on plain HTTP.
2. Forward `X-Forwarded-For` (appending the client address) and `X-Forwarded-Proto: https`.
3. List the proxy's addresses in `TRUSTED_PROXIES` (below), so the API sees the real client address
   for rate limits and knows the request was HTTPS. Spring Security then sends
   `Strict-Transport-Security` on HTTPS responses.
4. Keep the API's own port reachable only from the proxy (private network or firewall).

Example (nginx, TLS configured elsewhere):

```nginx
location /api/ {
    proxy_pass http://insight-api:8080;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header Host $host;
}
```

## Reverse proxy

| Variable | Example | Meaning |
|---|---|---|
| `TRUSTED_PROXIES` | `10.0.0.0/8,192.168.1.10` | IP addresses or CIDR ranges of the proxies in front of the API. Host names are refused at startup |

Forwarded headers are ignored unless the TCP peer is in this list: without it every client would
be able to choose the address its rate limits count against. From a trusted peer, the client is the
right-most `X-Forwarded-For` entry that is not itself a trusted proxy (entries a client wrote
further left are ignored). Spring Boot's own forwarded-header handling is explicitly off
(`server.forward-headers-strategy=none`), including on platforms such as Kubernetes where Boot
would otherwise switch it on.

If the proxy's address is not listed, the API still works, but every visitor shares the proxy's
address for per-IP limits (a busy site would hit them) and HSTS is not sent.

## Email

Password-reset and invitation links are emailed over SMTP; the API never logs them.

| Variable | prod default | Meaning |
|---|---|---|
| `MAIL_HOST` | required | SMTP server |
| `MAIL_PORT` | `587` | SMTP port |
| `MAIL_USERNAME`, `MAIL_PASSWORD` | – | SMTP credentials (keep them in the platform's secret store) |
| `MAIL_SMTP_AUTH` | `true` | Authenticate to the server |
| `MAIL_STARTTLS` | `true` | Require STARTTLS |
| `MAIL_FROM` | required | Sender, e.g. `Insight Studio <no-reply@example.com>`; the domain needs SPF/DKIM for the provider |
| `WEB_BASE_URL` | required, `https://` | Public address of the web app; links point to `/reset-password` and `/invite` there |

Emails leave asynchronously after the request, from a queue of 500 on two threads; a failure is
logged (without content) and not retried. The health endpoint does not depend on the mail server.

## Sessions and rate limits

Both live in the application database (Flyway V5, V6): any number of API instances behind the
proxy share them, and restarts or rolling deployments keep users signed in. No sticky sessions or
extra store are needed. Expired sessions are purged every minute by each instance; rate-limit rows
older than a day are purged as the API runs.

## Checklist

- [ ] `SPRING_PROFILES_ACTIVE=prod`
- [ ] `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` (from a secret store)
- [ ] `WEB_BASE_URL=https://...`
- [ ] `TRUSTED_PROXIES` = the proxy or load balancer addresses
- [ ] `MAIL_HOST`, `MAIL_FROM`, `MAIL_USERNAME`, `MAIL_PASSWORD`; SPF/DKIM for the sender domain
- [ ] TLS at the proxy, HTTP redirected to HTTPS, API port not public
- [ ] Web app and `/api` on the same origin
- [ ] If Cube is used: `INSIGHT_CUBE_URL` and `CUBEJS_API_SECRET` ([analytics.md](analytics.md)), Cube not public
