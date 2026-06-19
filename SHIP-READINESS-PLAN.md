# Ship-Readiness: Make Real-Time Codeshare Production-Ready

> Companion to `SHIP-READINESS-BLOCKERS.md`. This is the remediation plan that fixes all 14 blockers.

## Context

`SHIP-READINESS-BLOCKERS.md` documents 14 blockers that stop the `realtime-codeshare/`
Spring Boot app (branch `core-deliverables-working`) from shipping. The app builds and
demos on one machine, but as configured it **cannot do its one job** (cross-machine
collaboration), has **no auth**, and its **OT concurrency core has correctness bugs**.
Exploration confirmed every blocker against the source.

This plan fixes all 14 to a production-ready state. The intended outcome: a self-hosted,
offline-capable, Google-Meet-style collaborative editor where a host creates an
unguessable session link, admits joiners from a waiting room, edits converge correctly
under concurrency, memory is bounded, and an optional per-user GitHub OAuth push works
without ever putting a token in the browser.

### Decisions locked in (from the user)

| Topic | Decision |
|-------|----------|
| Auth model | Google-Meet style: unguessable session IDs **+ host approval** to admit joiners; reuse the existing `User`/`Session`/`Role` model |
| Identity basis | **Per-connection token** (server-minted), *not* IP. Approved users get a token to skip re-approval on reconnect |
| Host disconnect | **Keep room alive**; transfer admin to the longest-present remaining user |
| GitHub | **Optional push only** (no app login); per-user OAuth, token held server-side per browser session |
| Persistence | **Ephemeral / in-memory**; remove Mongo/JPA/H2 |
| Frontend assets | **Vendor locally** under `static/vendor/` |

### Defaults chosen (documented, override if desired)

- No app-wide login ⇒ **no Spring Security**; auth is custom over the WebSocket and the
  GitHub OAuth code-exchange is hand-rolled with `RestClient`. Drop `thymeleaf-extras-springsecurity6`
  and `spring-security-test`; do **not** enable `spring-boot-starter-security`.
- Waiting-room UI shows an **opaque join key + name/color**, never the raw IP.
- Rejected-join cap = **3** per identity per session, then a temporary block.
- **Any admitted user** may push to GitHub (they push the doc they can already see, using
  *their own* OAuth token). Not admin-only.
- OT history uses a **length cap (~2000)** now, with a `compactBelow(version)` seam for
  precise snapshotting later.
- Idle/empty rooms are reaped by a **scheduled sweeper** (TTL).
- GitHub OAuth scope defaults to **`public_repo`** (least privilege), overridable to `repo`.
- Delete `package.json` + `package-lock.json` (assets are vendored, no Node build).
- Keep highlight.js auto-detect, working **offline** via a vendored fixed mode set.

---

## Architecture (target shape)

- **One room = one `Session` (access control) + one `DocumentStore` (OT state).**
  A new `SessionService` (`...service`) is the authoritative in-memory registry of
  `Session` objects keyed by unguessable id; `OTService` still owns `DocumentStore`s but
  they are only created for ids that exist in `SessionService` — **no `"default"` fallback**.
- **Transport stays raw WebSocket** (`@EnableWebSocket`, `TextWebSocketHandler`). The dead
  SockJS/STOMP path is deleted, not revived.
- **Identity** = per-connection server-minted token. `User` is adapted to carry a token/id
  and key `equals`/`hashCode` on it (instead of IP). `Session.ipWhitelist` is repurposed as
  the set of approved member tokens (rename to `approvedTokens` or add alongside).
- **Everything ephemeral**; reaper evicts idle rooms; no datastore.

---

## Workstream 1 — Repo & build hygiene (do first; unblocks clean diffs)
*Blockers 9, 10, 13 (RCE), part of 14*

- **`.gitignore`** (new, repo root): `target/`, `build/`, `out/`, `node_modules/`, `*.log`,
  `.env*`, IDE files (`.idea/`, `*.iml`, `.vscode/`, `.classpath`, `.project`, `.settings/`),
  `.DS_Store`. Then `git rm -r --cached realtime-codeshare/target` (untrack the 26 committed
  artifacts; keep them on disk) and commit with the `.gitignore`.
- **`pom.xml`** — remove `spring-boot-starter-data-mongodb` (37-40, the source of the
  `MongoSocketOpenException` spam), `spring-boot-starter-data-jpa` (34-36), `h2` (72-76),
  `thymeleaf-extras-springsecurity6` (61-64), `spring-security-test` (87-91). Leave
  `spring-boot-starter-security` commented out. Keep web, websocket, thymeleaf, validation,
  devtools, lombok, starter-test and both build plugins.
- **Delete `realtime-codeshare/package.json` + `package-lock.json`** (unused Yjs stack).
- **Delete dead code:** `model/Type.java` (its only reference is the unused import at
  `OTService.java:3` — remove that import too); the entire commented-out `saveAndRunCode`
  RCE block in `OTWebSocketHandler.java:109-194` (plus the stale routing comment ~96-107 and
  field comment ~23).
- **Keep** `model/User.java`, `model/Session.java`, `security/Role.java` — WS-3 wires them up.

Critical files: `pom.xml`, new `.gitignore`, `ot/OTService.java`, `controller/OTWebSocketHandler.java`, `model/Type.java`.

---

## Workstream 2 — OT concurrency core, lifecycle, validation, logging, tests
*Blockers 5, 6, 7, 8, 12, part of 14 — the product's correctness rests here*

All changes in `ot/OTService.java` and `controller/OTWebSocketHandler.java` unless noted.

1. **Atomic transform-and-apply (5).** Add `synchronized ApplyResult commit(Op incoming)` to
   `DocumentStore` that does read-history → transform-loop → mutate-doc → bump-version →
   append-history **inside one monitor**. `applyOp` becomes: validate DTO, then
   `return getOrCreateStore(sessionId).commit(incoming)`. Inline and delete the now-redundant
   `getOpsSince`/`applyTransformed`; delete the 4 dead snapshot helpers
   (`replaceDocument`/`restore`/`replaceDocumentWithInitialCommit`/`restoreDocument`). Remove
   the `[OT DEBUG]` `System.out` lines (24,27,28). Rationale: the version/history/doc invariant
   belongs to `DocumentStore`, so the critical section lives there — no caller can reopen the gap.

2. **Thread-safe broadcast (6).** Wrap each socket in `ConcurrentWebSocketSessionDecorator`
   (sendTimeLimit ~10s, bufferSizeLimit ~512KB) in `afterConnectionEstablished`, and store the
   *decorated* session. Re-key the room maps from `Set<WebSocketSession>` to
   `Map<wsSessionId, WebSocketSession>` (outer + inner `ConcurrentHashMap`) so add/remove by
   `session.getId()` is identity-safe across raw vs decorated and O(1). Broadcast loops iterate
   `.values()`; extract a `broadcast(sessionId, json, excludeWsId)` helper.

3. **Memory bounds & lifecycle (7).** Evict `DocumentStore` + room map + `cursorPositions`
   when a room empties via a handler `onRoomEmpty(sessionId)` that calls a new
   `OTService.evict(sessionId)`. Fix the cursor cleanup: store `clientId` as a WS-session
   attribute and remove that entry on close, then broadcast `cursorRemove`. Add
   `@EnableScheduling` (on `WebSocketConfig`) + a `RoomReaper` `@Component` sweeping rooms that
   are empty AND idle past `codeshare.room.idleTtlMs`. History length cap in `commit` (~2000)
   plus a `DocumentStore.compactBelow(int version)` seam.

4. **Validation & rate limiting (8).** Validate in `applyOp`: non-blank `clientId`, `type ∈
   {insert,delete}`, `pos≥0`, `baseVersion≥0`, `length≥0`, payload ≤ `MAX_OP_LEN` (~64KB); throw
   a new `InvalidOpException` (caught in the handler → warn + ignore, never crash). Harden
   `clientWinsInsertTie` to be null-safe. **Strict routing:** dispatch `cursor` to the cursor
   branch, `insert`/`delete` to the op branch, reject everything else — note the client sends
   `insert`/`delete`, **never** `"op"` (that is the server→client shape), so a literal
   `type=="op"` check would break all edits. Container message cap via a
   `ServletServerContainerFactoryBean` bean (`setMaxTextMessageBufferSize(65536)`) + an
   app-level `payloadLength` guard. Per-connection token-bucket rate limiter (new `TokenBucket`
   in `...ratelimit`, `codeshare.rate.opsPerSec` default 50), one bucket per socket.

5. **Logging (14).** Add SLF4J loggers; replace all `System.out.println`
   (OTService 24-28; handler 37,55,200) — per-op at DEBUG (never above, it carries document
   content), join/leave at INFO, rejections at WARN.

6. **Tests (12)** — plain JUnit 5 (no `@SpringBootTest`, no Mongo): `OTEngineTransformTest`
   (every transform branch incl. insert/insert ties both directions, overlaps, boundaries,
   null-clientId no-throw), `DocumentStoreCommitTest` (incl. the stale-base-version regression
   test for the atomicity fix), `OTServiceValidationTest` (each invalid op rejected with store
   state unchanged), `OTConvergenceTest` (N-client batches in many orderings converge
   byte-identical), `OTConcurrencyStressTest` (K threads hammer `commit`, assert no
   exceptions/divergence). Leave `contextLoads()` as-is.

Critical files: `ot/OTService.java`, `controller/OTWebSocketHandler.java`, `config/WebSocketConfig.java`, new `RoomReaper.java`, `TokenBucket.java`, `InvalidOpException.java`, 5 new test classes, `application.yml`.

---

## Workstream 3 — Meet-style access control
*Blocker 3 + origin lockdown; reuses `User`/`Session`/`Role`*

**New files:**
- `service/SessionService.java` — authoritative `ConcurrentHashMap<String,Session>` registry.
  `SecureRandom` URL-safe id generation (`putIfAbsent` retry); `isValidIdFormat` regex
  (`^[A-Za-z0-9_-]{16,64}$`, single source reused by handler + REST + GitHub); `createSession`,
  `get` (no `"default"` fallback), `requestJoin`/`approve`/`reject` (per-`Session`
  `synchronized` around the non-thread-safe `pendingJoinRequests`), `isAdmitted` (O(1) token
  set check on the hot path), `isAdmin`, `leave` (on creator leave → **promote longest-present
  member to CREATOR**, broadcast `adminChanged`), `evict`. Mints per-connection member tokens;
  an opaque `joinKey` maps to each pending user so the admit UI never sees IPs.
- `controller/SessionController.java` — `POST /api/sessions` (creator name/color → `201
  {sessionId, adminToken, wsUrl}`, the only id-minting path), `GET /api/sessions/{id}/exists`
  → `{exists,alive}`; ids validated (400 malformed, 404 unknown).
- `config/WsHandshakeInterceptor.java` — at upgrade: parse + validate `sessionId` (reject the
  upgrade if missing/malformed/unknown — no room ever auto-created), read `token`/`name`/`color`
  from the query into WS attributes, mark `roleIntent` (admin token match ⇒ CREATOR).

**Model changes (additive):** `User` carries a `token`/`id`, `equals`/`hashCode` keyed on it.
`Session` repurposes `ipWhitelist` → approved-token set; add `rejectedByToken` count map and
`pending` helpers; track join order for admin transfer (`LinkedHashSet`/`joinedAt`).

**`OTWebSocketHandler` gating:** maintain a separate `pendingSockets` map so `roomClients`
means *admitted only*. `afterConnectionEstablished` → `requestJoin`: ADMITTED (creator or
approved-token reconnect) gets `init` + `presence`; PENDING gets `waitingForApproval` and every
admin gets `joinRequest`; REJECTED/BANNED gets `joinDenied` then close (1008). `handleTextMessage`
guards every op/cursor with `isAdmitted` before processing, and handles `approve`/`reject`/`kick`/
`endSession` only when `isAdmin`. Replace `getSessionId` (203-210) with the validated attribute.

**`config/WebSocketConfig.java`:** add the interceptor; replace `setAllowedOrigins("*")` with a
configurable list (`codeshare.allowed-origins`, env-overridable, supports wss/https origins).

**`controller/GitHubController.java`:** authorize the caller is admitted to `req.sessionId` and
validate id format (404 unknown) — closes the REST-side waiting-room bypass.

Critical files: new `service/SessionService.java`, `controller/SessionController.java`, `config/WsHandshakeInterceptor.java`; modified `controller/OTWebSocketHandler.java`, `config/WebSocketConfig.java`, `model/User.java`, `model/Session.java`.

---

## Workstream 4 — Frontend correctness + vendor assets offline
*Blockers 1, 2, 11, part of 13 & 14*

- **`templates/index.html`:** delete the CDN `<link>`/`<script>` tags (8,12,13,14,63-68) and the
  **entire dead inline `<script>` block 70-131** (the second CodeMirror + dead SockJS/STOMP).
  Replace head/body refs with local `vendor/...` paths. Relocate the `#toggleActionsBtn` handler
  into `codeshare.js`. Re-home the `#lang-name` "Detected:" label to be written by `codeshare.js`.
- **`static/js/codeshare.js`:** replace the hardcoded WS URL (line 4) with a runtime-derived one:
  `` const proto = location.protocol==='https:'?'wss:':'ws:'; const WS_BASE = `${proto}//${location.host}/ws/ot?sessionId=`; ``
  — the single highest-impact fix (cross-machine + HTTPS). Delete the dead duplicate download
  block (447-490) and the undefined `getLanguageExt` call sites (461,475); keep the working
  `handleDownloadChoice`/`downloadRaw`/`downloadZip`/`detectExtension`. Replace the `prompt()`
  session/name flow (5-6) with a small **create/join screen** (create → `POST /api/sessions`,
  store admin token; join → read sessionId from URL + ask name/color) and a **waiting-room/admit
  panel** (`#joinRequestsList`) + populate the existing `#usersOnlineList` from `presence`. Add an
  offline `detectAndSetMode()` using local `hljs` + `CodeMirror.findModeByName` against the
  bundled mode set (no dynamic CDN script injection).
- **`static/vendor/`** (new): CodeMirror 5.65.13 (core js/css, dracula theme, `addon/mode/meta`,
  and modes `clike`,`python`,`javascript`,`xml`,`css`,`htmlmixed`,`sql`), highlight.js 11.9.0
  (js + default css), JSZip 3.10.0. **Do not** vendor SockJS/Stomp. Pin to these exact versions.

Critical files: `templates/index.html`, `static/js/codeshare.js`, new `static/vendor/` tree.

---

## Workstream 5 — GitHub push via per-user OAuth (optional, no app login)
*Blocker 4*

Lightweight hand-rolled OAuth2 authorization-code flow (no Spring Security):
- **New `github/` package:** `GitHubProperties` (`@ConfigurationProperties("github")`),
  `GitHubTokenStore` (`@Component`, `ConcurrentHashMap<httpSessionId,token>`, never logged),
  `GitHubOAuthController` — `GET /api/github/authorize` (redirect to GitHub authorize URL with a
  CSRF `state` stored in the HTTP session), `GET /api/github/callback` (verify `state`, exchange
  `code`→token via `RestClient`, store in `GitHubTokenStore` keyed by HTTP session, redirect to
  `/?github=connected`), `GET /api/github/status` → `{authorized}`, `POST /api/github/disconnect`.
  `GlobalExceptionHandler` (`@RestControllerAdvice`) mapping GitHub/HTTP/validation errors to
  clean JSON `{error,status}` — never a stack trace, never the token.
- **Rework `controller/GitHubController.java`:** drop the `token` field from `PushRequest`; add
  `branch` (blank ⇒ resolve the repo's `default_branch`); take the token from `GitHubTokenStore`
  for the current HTTP session; `@Valid` the request (owner/repo/path non-blank, path `@Pattern`
  no `..`/leading `/`); remove `throws Exception`; use `RestClient` with
  `Accept: application/vnd.github+json` + `X-GitHub-Api-Version`; keep the GET-sha→PUT logic but
  translate GitHub statuses to `GitHubApiException`.
- **Frontend:** replace the PAT `prompt()` with an "Authorize GitHub" button (visible when
  `/api/github/status` is false → navigates to `/api/github/authorize`) + an owner/repo/path/
  branch/message form; POST without a token; surface the JSON error body.

Critical files: new `github/*`, modified `controller/GitHubController.java`, `static/js/codeshare.js`, `templates/index.html`, `application.yml`.

---

## `application.yml` additions

```yaml
codeshare:
  allowed-origins: ${CODESHARE_ALLOWED_ORIGINS:http://localhost:8080}  # comma-separated; add https/wss for prod
  session: { max-rejected-attempts: 3, ttl-minutes: 30 }
  room:    { idle-ttl-ms: 1800000 }
  reaper:  { interval-ms: 60000 }
  rate:    { ops-per-sec: 50 }
  ws:      { max-message-bytes: 65536 }
github:
  client-id: ${GITHUB_CLIENT_ID:}
  client-secret: ${GITHUB_CLIENT_SECRET:}
  scope: ${GITHUB_OAUTH_SCOPE:public_repo}
  redirect-uri: ${GITHUB_REDIRECT_URI:http://localhost:8080/api/github/callback}
  api-base: ${GITHUB_API_BASE:https://api.github.com}
logging:
  level: { root: INFO, com.collabcode.realtime_codeshare: INFO }   # DEBUG locally for per-op trace
```

Empty GitHub credentials ⇒ push UI degrades gracefully (the app still boots and runs).

---

## Sequencing

1. **WS-1** hygiene (clean tree, quiet boot).
2. **WS-2** OT core + tests (the correctness foundation; independent).
3. **WS-4** frontend WS-URL + vendoring + dead-code (makes it work cross-machine/offline;
   independent of auth except the create/join screen hook).
4. **WS-3** access control (depends on WS-2 lifecycle hooks; provides the create/join UI contract WS-4 consumes).
5. **WS-5** GitHub OAuth (independent; last).

---

## Verification

- **Build & unit tests:** `cd realtime-codeshare && ./mvnw clean test` — all OT transform,
  convergence, validation, and concurrency-stress tests green; boot is quiet (no
  `MongoSocketOpenException`).
- **Cross-machine collaboration (1, 2, 6):** run the jar, open from two *different* browsers/hosts
  (and over an HTTPS reverse proxy to confirm `wss://`); host creates a session, both edit
  simultaneously — documents converge, no `IllegalStateException`/dropped sockets, single editor.
- **Meet-style auth (3):** joiner lands in the waiting room and sees nothing until admitted; host
  sees the request and approves/rejects; approved user reconnects without re-approval; unknown/
  malformed `sessionId` is rejected (no `"default"` room); cross-origin connect is blocked;
  host disconnect transfers admin and keeps the room alive.
- **Memory/lifecycle (7):** open then close all clients in a room; confirm the room, cursors, and
  `DocumentStore` are evicted and the reaper clears idle rooms.
- **Validation/rate-limit (8):** send a null-`clientId` op, a negative `pos`, an oversize insert,
  and an op flood — server logs warnings and stays up; no NPE/crash.
- **Offline (11):** disable network, load the page — editor, themes, language detect, zip download
  all work from `static/vendor/`.
- **GitHub OAuth (4):** with `GITHUB_CLIENT_ID/SECRET` set, click Authorize → GitHub consent →
  redirected back authorized; push to a chosen owner/repo/path/branch succeeds; an invalid repo
  returns a clean JSON error (not a 500 stack trace); the token never appears in logs or the browser.
- **Git hygiene (9,10):** `git status` is clean of `target/`; `git ls-files | grep target` is empty.

---

## Blocker coverage

| # | Blocker | Workstream |
|---|---------|-----------|
| 1 | Hardcoded localhost WS URL | WS-4 |
| 2 | Dual editors / dead STOMP | WS-4 |
| 3 | No auth/authz | WS-3 |
| 4 | Insecure GitHub PAT | WS-5 |
| 5 | Non-atomic applyOp | WS-2 |
| 6 | Unsafe broadcast | WS-2 |
| 7 | Unbounded memory | WS-2 |
| 8 | No validation/rate-limit | WS-2 |
| 9 | Committed `target/` / no `.gitignore` | WS-1 |
| 10 | Unused heavy deps | WS-1 |
| 11 | CDN-only frontend | WS-4 |
| 12 | No tests | WS-2 |
| 13 | Dead code / RCE block | WS-1 (backend) + WS-4 (frontend) |
| 14 | Log spam / prompt-alert UX | WS-2 (logging) + WS-3/WS-4/WS-5 (UI) |
