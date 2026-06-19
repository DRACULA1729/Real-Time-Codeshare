# Ship-Readiness Blockers — Real-Time Codeshare

**Scope:** `realtime-codeshare/` Spring Boot app on branch `core-deliverables-working`.
**Date:** 2026-06-19

## Verdict

The app **builds, boots in ~2.5s, and demos on a single machine**. It is **not ship-ready**. The blockers fall into three buckets:

1. As configured it cannot do its one job — multi-machine real-time collaboration.
2. It has no authentication or authorization.
3. The concurrency core (the OT engine the whole product rests on) has correctness bugs.

Blockers are ordered by severity. Each entry lists **where**, **what's wrong**, **why it blocks shipping**, and **the fix**.

---

## 🔴 Critical — breaks the core promise or is unsafe to expose

### 1. Every client connects to its own `localhost` — cross-machine sharing is impossible

- **Where:** `realtime-codeshare/src/main/resources/static/js/codeshare.js:4` (`ws://localhost:8080/ws/ot?sessionId=`); `realtime-codeshare/src/main/resources/templates/index.html:104` (`http://localhost:8080/ws/ot?sessionId=`).
- **What's wrong:** The WebSocket and SockJS endpoints are hardcoded to `localhost:8080`. Each browser, on whatever machine, talks to *its own* loopback.
- **Why it blocks shipping:** Two users on different machines never share a session — the entire "real-time collaborative" premise fails the moment it leaves one box. It also cannot run behind HTTPS, which requires `wss://`, not `ws://`.
- **Fix:** Derive the URL from `window.location` at runtime, e.g. ``const proto = location.protocol === "https:" ? "wss:" : "ws:"; const WS_BASE = `${proto}//${location.host}/ws/ot?sessionId=`;`` Never hardcode host/port/scheme.

### 2. Two CodeMirror editors are created; one is wired to a dead STOMP stack

- **Where:** `realtime-codeshare/src/main/resources/templates/index.html:76` builds a CodeMirror on `#editor`; `realtime-codeshare/src/main/resources/static/js/codeshare.js:19` builds a second one on the same textarea. The inline editor connects via SockJS/STOMP at `index.html:67-68` and `index.html:104-121`.
- **What's wrong:** Two competing editor instances bind to the same `#editor` textarea. The inline one speaks STOMP, but the backend is a raw `TextWebSocketHandler` with **no STOMP broker** (`@EnableWebSocketMessageBroker` is absent), so that connection silently fails and its `subscribe`/`send` go nowhere.
- **Why it blocks shipping:** Confusing, broken UX — two editors, one of which appears connected but isn't. The dead STOMP path is a leftover from the abandoned `Dev` branch design.
- **Fix:** Delete the entire inline `<script>` block in `index.html` (lines ~70–131) and the SockJS/Stomp `<script>` tags (lines 67-68). Keep only `codeshare.js` as the single editor + transport.

### 3. No authentication or authorization

- **Where:** `realtime-codeshare/src/main/java/com/collabcode/realtime_codeshare/controller/OTWebSocketHandler.java:203-210` (`getSessionId`); `realtime-codeshare/src/main/java/com/collabcode/realtime_codeshare/config/WebSocketConfig.java:20` (`setAllowedOrigins("*")`).
- **What's wrong:** `sessionId` is read straight from a query param with no validation or auth. Allowed origins are `"*"`. Anyone who knows or guesses a session id can read and overwrite the shared document. A missing/malformed id silently drops the client into a shared global `"default"` room (`getSessionId` returns `"default"`).
- **Why it blocks shipping:** A "code sharing" tool with a publicly writable, unauthenticated document store is an open door — anyone can join, scrape, or corrupt any session.
- **Dead design:** A full access-control model was designed but **never wired up** — `model/User.java`, `model/Session.java` (`ipWhitelist`, `pendingJoinRequests`, `isAlive`), and `security/Role.java` (CREATOR/JOINER). The WebSocket handler ignores all of it.
- **Fix:** Add an auth/handshake step (token or session-creator approval), validate `sessionId` format, reject unknown rooms instead of falling back to `"default"`, and restrict `setAllowedOrigins` to the deployed origin(s). Either wire up the existing `Session`/`User`/`Role` model or delete it.

### 4. GitHub Personal Access Token handled insecurely

- **Where:** `realtime-codeshare/src/main/resources/static/js/codeshare.js:417-436` (`pushToGitHub`); `realtime-codeshare/src/main/java/com/collabcode/realtime_codeshare/controller/GitHubController.java:33-72`.
- **What's wrong:** The PAT is collected via a browser `prompt()` and POSTed to the server, which uses it with no validation, hardcodes the push to `branch: "main"` (`GitHubController.java:62`), and declares `throws Exception` — so any 4xx/5xx from GitHub surfaces to the client as an HTTP 500 with a stack trace. The token can land in request logs.
- **Why it blocks shipping:** Long-lived credentials entered into a browser prompt and handled server-side with no scoping, validation, or error handling is not acceptable for release.
- **Fix:** Use OAuth (or at minimum a server-held, per-user token store), make the target branch configurable, validate inputs, and catch GitHub errors to return clean status codes instead of stack traces. Never log the token.

---

## 🟠 Major — concurrency correctness (the OT engine is the product)

### 5. Transform-and-apply is not atomic → documents diverge under load

- **Where:** `realtime-codeshare/src/main/java/com/collabcode/realtime_codeshare/ot/OTService.java:19-33` (`applyOp`).
- **What's wrong:** `applyOp` calls `getOpsSince(...)` and computes the transform loop **outside any lock**, then calls the `synchronized applyTransformed(...)` as a separate step. Another thread can commit an op in between, so the incoming op is transformed against stale history and then applied.
- **Why it blocks shipping:** Under genuine concurrent editing, clients' documents silently diverge — defeating the entire purpose of an OT engine. This is the single most important correctness bug.
- **Fix:** Make read → transform → apply one atomic critical section — e.g. `synchronized (store) { ... }` around the whole sequence, or move the transform loop inside a single synchronized method on `DocumentStore`.

### 6. WebSocket broadcast is not thread-safe

- **Where:** `realtime-codeshare/src/main/java/com/collabcode/realtime_codeshare/controller/OTWebSocketHandler.java:90-94` (op broadcast loop); also the cursor broadcast at `:66-70`.
- **What's wrong:** `session.sendMessage(...)` is called from concurrent threads. Spring's `WebSocketSession.sendMessage` is **not** safe for concurrent invocation on the same session; concurrent sends throw `IllegalStateException` ("TEXT_PARTIAL_WRITING"/truncation).
- **Why it blocks shipping:** Under concurrent editing the message stream to a client gets corrupted or the connection drops.
- **Fix:** Wrap each session in `ConcurrentWebSocketSessionDecorator` at connection time, or synchronize per-session sends.

### 7. Unbounded memory growth — no cleanup or expiry

- **Where:** `realtime-codeshare/src/main/java/com/collabcode/realtime_codeshare/ot/OTService.java:210` (`history` list); the `sessions` map at `:13`; `realtime-codeshare/src/main/java/com/collabcode/realtime_codeshare/controller/OTWebSocketHandler.java:196-201` (`afterConnectionClosed`).
- **What's wrong:** `DocumentStore.history` grows forever — no trimming/compaction. When a room empties, `afterConnectionClosed` removes the socket from the set but leaves the empty `roomClients` set, the `cursorPositions` map, and the full `DocumentStore` (with complete op history) resident forever. The `isAlive` flag on `Session` exists but is never used. The cursor cleanup `removeIf(e -> e.getValue()==null)` never matches (values are never null), so disconnected users' cursors also leak.
- **Why it blocks shipping:** A long-running server leaks memory until OOM. No session TTL means every session ever opened stays in RAM.
- **Fix:** Add session expiry/TTL, evict `DocumentStore`/`roomClients`/`cursorPositions` entries when a room empties, trim/compact op history (or snapshot + discard), and fix the cursor cleanup to remove the disconnected client's entry by id.

### 8. No input validation or rate limiting on ops

- **Where:** `realtime-codeshare/src/main/java/com/collabcode/realtime_codeshare/ot/OTService.java:292-309` (`OpDTO`/`toOp`); `realtime-codeshare/src/main/java/com/collabcode/realtime_codeshare/controller/OTWebSocketHandler.java:74` (type routing).
- **What's wrong:** `OpDTO` accepts negative `pos`, arbitrarily large `length`, and null `clientId` with no checks. A null `clientId` in an insert tie NPEs inside `OTEngine.clientWinsInsertTie` (`clientA.compareTo(clientB)`). The routing condition `if ("op".equalsIgnoreCase(type) || !type.isEmpty())` treats *any* non-empty, non-cursor message as an op. There is no message-size cap or per-client throttle.
- **Why it blocks shipping:** A single client can crash the server (NPE) or DoS a room by flooding ops or sending one enormous insert.
- **Fix:** Validate `OpDTO` (non-null clientId, bounds-checked pos/length, size cap), route strictly on `type == "op"`, and add per-connection rate limiting and a max message size.

---

## 🟡 Build, dependencies & repository hygiene

### 9. Build output (`target/`) is committed to git; no `.gitignore`

- **Where:** 26 tracked files under `realtime-codeshare/target/` (`.class`, maven-status); no `.gitignore` anywhere in the repo.
- **What's wrong:** Compiled artifacts are versioned. This is the entire noisy diff currently showing in `git status`.
- **Why it blocks shipping:** Build artifacts in version control cause spurious diffs, merge conflicts, and bloat.
- **Fix:** Add a `.gitignore` (`target/`, IDE files, `.env`, `node_modules/`), then `git rm -r --cached realtime-codeshare/target` and commit.

### 10. Heavy unused dependencies

- **Where:** `realtime-codeshare/pom.xml`; `realtime-codeshare/package.json`.
- **What's wrong:**
  - `spring-boot-starter-data-mongodb` (`pom.xml:37-40`) — no `@Document` or repository exists, yet on startup the driver repeatedly tries to reach `localhost:27017` and logs `MongoSocketOpenException` ~every 10s.
  - `spring-boot-starter-data-jpa` + `h2` (`pom.xml:34-36`, `:72-76`) — no entities/repositories; auto-configures a datasource for nothing.
  - `thymeleaf-extras-springsecurity6` (`pom.xml:61-64`) + `spring-security-test` (`pom.xml:87-91`) — Spring Security is commented out (`pom.xml:41-44`), so these dangle.
  - `package.json` lists `yjs`, `y-websocket`, `y-codemirror` — a completely different (Yjs) collaboration stack that is not used; the app uses CDN CodeMirror 5 + a custom OT engine. Misleading and no build step consumes it.
- **Why it blocks shipping:** Error-log spam, slower startup, larger artifact, and a misleading dependency manifest.
- **Fix:** Remove the unused starters and the `package.json` Yjs deps (or actually adopt one stack). If Mongo snapshots are intended later, make the connection lazy/optional and guard it behind config.

### 11. Frontend is 100% CDN-dependent — contradicts the stated product goal

- **Where:** `realtime-codeshare/src/main/resources/templates/index.html:8-14` and `:63-68` (CodeMirror, JSZip, highlight.js, SockJS, Stomp from cdnjs/jsdelivr); `README.md`.
- **What's wrong:** Every frontend library loads from an external CDN. The README explicitly promises a platform "**without dependence on external domains or cloud services**" and "locally hosted."
- **Why it blocks shipping:** With no internet (the exact offline/local use case the README sells), the UI is dead. Direct contradiction of the product's stated value.
- **Fix:** Vendor the JS/CSS assets locally under `static/` and reference them by relative path.

### 12. No meaningful tests

- **Where:** `realtime-codeshare/src/test/java/com/collabcode/realtime_codeshare/RealtimeCodeshareApplicationTests.java` — only `contextLoads()`.
- **What's wrong:** Zero coverage of OT transforms, convergence, concurrent edits, the WebSocket handler, or the GitHub controller. `contextLoads` also boots the full Mongo/JPA stack, making it slow and fragile.
- **Why it blocks shipping:** The product's correctness rests entirely on OT convergence, which is completely untested. Regressions in the transform functions would ship silently.
- **Fix:** Add unit tests for each transform case (insert/insert ties, insert/delete, delete/delete overlap) and property/convergence tests proving concurrent ops converge to the same document on all clients.

### 13. Dead code — including a commented-out remote-code-execution path

- **Where:** `realtime-codeshare/src/main/java/com/collabcode/realtime_codeshare/controller/OTWebSocketHandler.java:109-194` (`saveAndRunCode`); `realtime-codeshare/src/main/resources/static/js/codeshare.js:447-489` and `:462`, `:475`; `realtime-codeshare/src/main/java/com/collabcode/realtime_codeshare/ot/OTService.java:253-289`, `:327-330`.
- **What's wrong:**
  - `saveAndRunCode` shells out to `javac`/`java` on user-supplied code — a glaring RCE. It's commented out, but should not ship even in comments.
  - `getLanguageExt()` is called at `codeshare.js:462` and `:475` but is **never defined** (only a commented-out version exists at `:448`). It doesn't throw only because the `downloadSource`/`downloadZip` elements don't exist in the DOM.
  - Duplicate download wiring: two separate download implementations (`handleDownloadChoice`/`downloadRaw`/`downloadZip` vs. the `downloadSource`/`downloadZip` block).
  - Four overlapping, unused snapshot helpers: `replaceDocument`, `restore`, `restoreDocument`, `replaceDocumentWithInitialCommit`.
- **Why it blocks shipping:** Dead code hides intent, invites accidental re-enabling of the RCE, and signals an unfinished codebase.
- **Fix:** Delete the commented RCE block, remove the undefined `getLanguageExt` call sites and duplicate download wiring, and keep at most one snapshot/restore helper (or none until snapshots are implemented).

### 14. Debug logging spam and `prompt()`/`alert()` UX

- **Where:** `realtime-codeshare/src/main/java/com/collabcode/realtime_codeshare/ot/OTService.java:24-28` (`[OT DEBUG]` per op); `realtime-codeshare/src/main/java/com/collabcode/realtime_codeshare/controller/OTWebSocketHandler.java:37`, `:55`, `:200`; `realtime-codeshare/src/main/resources/static/js/codeshare.js:5-6`, `:417-436`; `realtime-codeshare/src/main/resources/templates/index.html:35-37`.
- **What's wrong:** Every op and inbound message is printed to stdout via `System.out.println`, leaking document content/ops into logs and hurting throughput — no SLF4J usage. The UI collects session id, username, and the **GitHub PAT** through browser `prompt()`, and reports results via `alert()`. The "Users Online" panel (`index.html:35-37`) is never populated — presence is unwired.
- **Why it blocks shipping:** Log spam + sensitive data in stdout is a security and ops problem; `prompt()`/`alert()` for credentials is not production UX; the half-built presence UI looks broken.
- **Fix:** Replace `System.out.println` with SLF4J at `DEBUG` (or remove), build a proper join/credentials UI, and either wire up presence or remove the panel.

---

## Minimum path to a private beta

| # | Blocker | Effort |
|---|---------|--------|
| 1 | Derive WS/HTTP URLs from `window.location` | Low |
| 2 | Delete the dead inline STOMP editor block | Low |
| 5 | Make `applyOp` atomic | Low–Med |
| 6 | Serialize per-session sends (`ConcurrentWebSocketSessionDecorator`) | Low |
| 3 | Session-id authorization + tighten allowed origins | Med |
| 4 | Move GitHub token out of the browser / handle errors | Med |
| 9–11 | `.gitignore` + drop unused starters + vendor frontend assets | Low–Med |
| 12 | OT convergence tests | Med |

Items **7** (memory/TTL), **8** (validation/rate-limit), and the dead-code cleanup (**13–14**) are required before anything beyond a controlled beta.

**Highest-impact first commit:** the two-line fix for #1 plus removing the dead STOMP block (#2) — that is what makes the product actually function across machines.
