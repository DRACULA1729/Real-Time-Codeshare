# Real-Time Codeshare

A self-hosted, real-time collaborative code editor. Several people edit the same
document at once over WebSockets, and a custom Operational-Transform engine keeps
every copy in sync. It runs on one machine with no external services, and the
frontend libraries are bundled locally, so it works with no internet at all.

Access works like Google Meet: the host creates a session, gets an unguessable link,
and lets people in from a waiting room. There is no account to sign up for.

Live instance: https://sushant-codeshare.duckdns.org

## Repository layout

```
src/        Spring Boot backend: OT engine, sessions, WebSocket handler, REST API
frontend/   React + TypeScript SPA (Vite, CodeMirror 6); built into the jar by Maven
deploy/     Production bundles: docker compose + Caddy, and the no-Docker VPS variant
docs/       OPERATIONS.md (running it), SCALE.md (multi-node), DEPLOY.md (own VM)
start.sh    One-command local run (`./start.sh` prod, `./start.sh dev` with Vite HMR)
```

## Requirements

- JDK 21
- The project ships a Maven wrapper (`./mvnw`); a system Maven 3.8+ also works.

## Run

```bash
./start.sh          # builds the jar on first run, serves on http://localhost:8080
./start.sh dev      # backend on :8080 plus Vite HMR on :5173
```

Or `./mvnw spring-boot:run` for the backend alone.

Open http://localhost:8080, create a session, and share the invite link. Joiners land
in the waiting room until the host admits them. Approved users keep a token so a
reconnect skips re-approval, and if the host leaves, admin passes to the
longest-present remaining member.

## Test

```bash
./mvnw verify                 # backend + frontend suites (what CI runs)
cd frontend && npm test       # frontend only
```

The backend suite covers the OT transforms, commit atomicity, input validation,
multi-order convergence, a concurrency stress run, and the session access-control
state machine. The frontend suite covers the client OT loop, reconnect and catch-up,
and language detection.

## Configuration

All keys have working defaults (see `src/main/resources/application.yml`) and can be
overridden via environment variables.

| Setting | Env var | Default |
|---------|---------|---------|
| Allowed WebSocket origins | `CODESHARE_ALLOWED_ORIGINS` | `http://localhost:8080` |
| Rejected joins before block | — | `3` |
| Idle-room TTL (ms) | — | `1800000` |
| Per-connection op rate (ops/s) | — | `50` |
| Max inbound message (bytes) | — | `65536` |

Behind an HTTPS reverse proxy, set `CODESHARE_ALLOWED_ORIGINS` to your public origin;
the client picks `wss://` automatically.

## Optional: push to GitHub

A session's document can be pushed to GitHub using each user's own account via OAuth —
no personal access token is ever typed into the browser, and the token is held
server-side. It's off until you register a GitHub OAuth app and set:

```bash
export GITHUB_CLIENT_ID=...
export GITHUB_CLIENT_SECRET=...
export GITHUB_REDIRECT_URI=http://localhost:8080/api/github/callback
# GITHUB_OAUTH_SCOPE defaults to public_repo; use "repo" for private repositories
```

Without these the app still runs; the push panel just shows that GitHub isn't configured.

## Production

- [docs/OPERATIONS.md](docs/OPERATIONS.md): profiles, environment, Postgres and Redis,
  health and metrics, what to put in front of it.
- [docs/DEPLOY.md](docs/DEPLOY.md) with [deploy/](deploy/): docker compose
  (app + Postgres + Caddy with automatic TLS) for a dedicated VM.
- [deploy/vps/](deploy/vps/): the no-Docker variant for a shared server
  (systemd + host Postgres + an existing web server as reverse proxy).
- [docs/SCALE.md](docs/SCALE.md): what changes for more than one instance.
