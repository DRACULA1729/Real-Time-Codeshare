# Operations & deployment

How to build, configure, and run the real-time codeshare service, plus the
security model and what each knob does.

## Architecture in one paragraph

A Spring Boot app serves a React/TS single-page app (built into the JAR) and a raw
WebSocket endpoint at `/ws/ot`. Editing is operational-transform: the server holds
the authoritative document per room and is the single writer (`DocumentStore.commit`
is one synchronized critical section). Access control is "Meet-style" — a server-minted
token per member, a waiting room, host approval, and admin transfer when the host
leaves. Connections are gated by a single-use ticket so the token never rides the URL.
State is in-memory today; the interfaces (`SessionStore` is still in-memory,
`DocumentRepository`, `TicketStore`, `RateLimiter`) are the seams for the Redis/Postgres
swap in the scale phase.

## Build & run

```bash
cd realtime-codeshare

# Full build: downloads Node, builds the SPA, runs Java + TS tests, makes the JAR.
./mvnw clean verify

# Run (defaults to the base profile on :8080).
java -jar target/realtime-codeshare-0.0.1-SNAPSHOT.jar

# Local dev with the Vite dev server (HMR) proxying to :8080:
cd frontend && npm run dev          # serves :5173, proxies /api and /ws to :8080
# (run the JAR or `./mvnw spring-boot:run` alongside it)
```

### Reproducible / air-gapped build

The build normally downloads Node at `generate-resources`. To build the JAR from
**prebuilt** SPA assets already in `src/main/resources/static` (no Node download):

```bash
./mvnw -Dfrontend.skip=true clean package
```

### Docker

```bash
docker build -t realtime-codeshare:latest .      # multi-stage: build -> slim JRE, non-root
docker run -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod,postgres \
  -e DB_URL='jdbc:postgresql://<host>/<db>?sslmode=require' -e DB_USER=... -e DB_PASSWORD=... \
  -e CODESHARE_ALLOWED_ORIGINS=https://codeshare.example.com \
  realtime-codeshare:latest
```

## Profiles

| Profile | Use | Notable settings |
|---|---|---|
| (default) | local / trusted LAN | origins = localhost; cookies not Secure (works over HTTP) |
| `dev` | local dev | adds Vite `:5173` + ngrok wildcard origins; DEBUG logging |
| `prod` | internet-exposed | Secure cookies; origins from env (no localhost); actuator on a separate port; **fails fast** if required config is missing |

Activate with `--spring.profiles.active=prod,postgres` or `SPRING_PROFILES_ACTIVE=prod,postgres`.

**Durability:** only the `postgres` profile keeps documents across a restart. The default
profile holds them in memory (logs a startup WARN) and `redis` alone is a non-durable
cache, so `prod` refuses to start without `postgres`.

## Required environment (prod)

The `prod` profile refuses to start (clear message) if these are missing/wrong:

| Variable | Required | Purpose |
|---|---|---|
| `postgres` profile + `DB_URL` / `DB_USER` / `DB_PASSWORD` | **yes** | Restart-durable document store |
| `CODESHARE_ALLOWED_ORIGINS` | **yes** | Exact WS-upgrade origin(s), comma-separated. Must not be localhost. |
| `GITHUB_CLIENT_ID` / `GITHUB_CLIENT_SECRET` | only if GitHub push is enabled | OAuth app credentials |
| `GITHUB_REDIRECT_URI` | if GitHub configured | Public **https** callback (not localhost) |
| `MANAGEMENT_PORT` | optional (default 9091) | Internal port for actuator |
| `SERVER_PORT` | optional (default 8080) | Public listener |
| `TRUSTED_PROXIES` | optional | Regex of proxy IPs allowed to set `X-Forwarded-*` (default: private ranges + loopback) |

TLS is terminated at the proxy/LB. In `prod` the app honors `X-Forwarded-*`
**only from `TRUSTED_PROXIES`** (Tomcat `RemoteIpValve`), for secure-cookie and
client-IP (rate-limit) decisions. The proxy **must overwrite, not append**,
`X-Forwarded-For`, or a client could spoof its IP and bypass the per-IP limit. The
default (base) profile trusts no forwarded headers, so a directly-exposed instance
rate-limits on the real socket IP.

## Observability

- `GET /actuator/health` — liveness/readiness probes (`/health/liveness`, `/health/readiness`).
- Metrics (`/actuator/prometheus`, `/actuator/metrics`) include `codeshare_sessions_active`,
  `codeshare_rooms_live`, `codeshare_ws_connections`, `codeshare_ops_committed_total`,
  `codeshare_ratelimit_drops_total`, `codeshare_broadcast_send_failures_total`.
- **Exposure:** the base profile exposes only `health,info` on the public port (so a
  directly-exposed instance never leaks metrics / route enumeration). The `dev` profile
  adds `prometheus,metrics` on the main port; `prod` serves them on the separate internal
  management port (`MANAGEMENT_PORT`, default 9091).

## Security model (what protects what)

- **Token off the URL** — clients POST for a single-use ~30s ticket; the WS URL
  carries only the opaque ticket (CWE-598).
- **Origin check** on the WS upgrade (locked to configured origins) guards against
  cross-site WebSocket hijacking.
- **CSRF** double-submit (`XSRF-TOKEN` cookie + `X-CSRF-Token` header) on unsafe
  `/api` calls; `SameSite=Lax` cookies.
- **CSP + headers** — `default-src 'self'`, `frame-ancestors 'none'`, nosniff,
  `Referrer-Policy: no-referrer`, HSTS over HTTPS.
- **Rate limits** — per-IP session creation + global session cap (memory-DoS guard);
  per-connection op and cursor token buckets; inbound frame-size cap.
- **GitHub token** stays server-side (never sent to the browser), with a sliding TTL;
  disconnect also revokes it at GitHub (best-effort, async).

## Known follow-ups (not launch-blocking)

- GitHub tokens that TTL-expire (no explicit disconnect) are dropped locally but not
  revoked at GitHub.
- Horizontal scale (Redis + Postgres, async broadcast, snapshot compaction) — the
  abstractions are in place; the implementations land in the scale phase.
