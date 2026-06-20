# Plan: Rewrite the frontend as a React + TypeScript SPA

## Context

The backend on the `ship-readiness` branch is production-hardened (atomic OT, Meet-style
access control, GitHub OAuth, offline single-JAR shipping). The **frontend has not kept
pace**: it is a single 482-line vanilla file
([codeshare.js](realtime-codeshare/src/main/resources/static/js/codeshare.js)) driving a
static Thymeleaf page
([index.html](realtime-codeshare/src/main/resources/templates/index.html)) with 314 lines
of hand-written CSS. State lives in globals, the UI is manipulated through `$()` DOM
queries, and a hand-rolled OT engine is tightly coupled to CodeMirror 5's imperative API.
It works but is hard to extend, hard to test, and the protocol is untyped.

**Goal:** replace the frontend with a React + TypeScript SPA that is component-structured,
type-safe over the WebSocket/OT/REST protocol, and unit-testable — while **changing nothing
about the backend**, **preserving the offline single-JAR shipping model**, and **matching
the current look and behavior 1:1** (faithful parity).

The backend is already cleanly decoupled — REST under `/api/**` and a JSON WebSocket at
`/ws/ot` — so the SPA consumes it unchanged. Two backend facts constrain the design:

- **GitHub OAuth is cookie/session-bound.** The token store is keyed by `JSESSIONID`
  ([GitHubTokenStore.java](realtime-codeshare/src/main/java/com/collabcode/realtime_codeshare/github/GitHubTokenStore.java))
  and the flow does full-page redirects to GitHub and back with a `returnTo`. → The SPA
  must be served **same-origin** from Spring Boot.
- **The app is fully offline** (CodeMirror, highlight.js, JSZip all vendored, zero CDN). →
  The React build must bundle all deps and ship inside the JAR.

## Approach

A Vite + React + TypeScript app under `realtime-codeshare/frontend/`, built by
`frontend-maven-plugin` during `./mvnw package` into the Spring Boot static dir, served
same-origin at `:8080`. Routing stays **query-string based** (`?sessionId=...`,
`?github=connected`) exactly as today — so **no backend routing change** is required and
the existing welcome-page resolution keeps working.

### 1. Build & serving integration

- New `realtime-codeshare/frontend/`: `package.json`, `vite.config.ts`, `tsconfig.json`,
  `index.html`, `src/**`.
- **Vite** `build.outDir` → `../src/main/resources/static` with `emptyOutDir: true`.
  The built `static/index.html` becomes Spring Boot's welcome page at `/` automatically.
  The Thymeleaf `templates/index.html` is removed; `spring-boot-starter-thymeleaf` can be
  dropped from the pom since nothing else uses it.
- **pom.xml** ([realtime-codeshare/pom.xml](realtime-codeshare/pom.xml)): add
  `com.github.eirslett:frontend-maven-plugin` with pinned Node/npm, executions for
  `install-node-and-npm` → `npm ci` → `npm run build`, bound to the `generate-resources`
  phase (so assets exist before packaging). `workingDirectory` = `frontend`.
- Generated assets are **git-ignored** and produced fresh by Maven; the JAR remains a
  single self-contained, offline artifact. (Devs iterate via the Vite dev server, below.)
- **Dev loop:** `vite.config.ts` `server.proxy` forwards `/api` → `http://localhost:8080`
  and `/ws` → `ws://localhost:8080` (`ws: true`), passing cookies through so `JSESSIONID`
  stays consistent for GitHub status checks. Caveat to document: the full OAuth
  redirect round-trip is most faithfully tested against the packaged same-origin build
  (`java -jar`), since GitHub redirects to the backend origin.

### 2. Frontend module layout (`frontend/src/`)

- `api/` — typed REST client mirroring
  [SessionController.java](realtime-codeshare/src/main/java/com/collabcode/realtime_codeshare/controller/SessionController.java)
  and the GitHub controllers: `createSession({name,color})`,
  `sessionExists(id)`, `github.status()`, `github.authorize(returnTo)`,
  `github.disconnect()`, `github.push({owner,repo,path,branch,message,sessionId,sessionToken})`.
- `ws/` — WebSocket client + **typed message unions**. `WsInbound` =
  `init | op | cursor | cursorRemove | presence | waitingForApproval | joinRequest |
  joinResolved | joinDenied | adminChanged | kicked | sessionEnded`. `WsOutbound` =
  `insert | delete | cursor | kick | approve | reject | endsession`. Builds the
  `ws[s]://host/ws/ot?sessionId&clientId&name&color&token` URL exactly as today.
- `ot/` — **the highest-risk port.** Move the transform engine verbatim from
  [codeshare.js:228-262](realtime-codeshare/src/main/resources/static/js/codeshare.js#L228-L262)
  (`transformII/ID/DI/DD`) plus the pending-op transform/apply logic, as **pure typed
  functions**, kept byte-for-byte semantically identical to the server
  ([OTService.java:185-263](realtime-codeshare/src/main/java/com/collabcode/realtime_codeshare/ot/OTService.java#L185-L263)),
  including the same `clientId` lexicographic tie-break. Covered by Vitest unit tests.
- `editor/` — CodeMirror 6 via `@uiw/react-codemirror` + `@codemirror/*` language packages
  and a dracula theme. Map CM6 transaction `changes` → OT insert/delete ops by **absolute
  offset**; apply remote ops via `view.dispatch` tagged with a transaction annotation so
  remote-applied edits are not echoed back as local ops. Remote cursors via a
  `StateField` + decorations, color-coded per client. Language auto-detect drives the mode
  + the language label (replacing the highlight.js `highlightAuto` call).
- `state/` — a small **Zustand** store for the cross-cutting mutable state that today lives
  in globals (sessionId, token, role, name, color, version, pendingOps, users, join
  requests). Zustand fits the imperative WS callbacks updating state from outside the React
  tree; token persisted to `sessionStorage` per session as today.
- `components/` — one component per current UI region, same dark/dracula styling:
  `Gate` (Create / Join / Waiting / Denied panels), `TopBar` (title, session id, copy
  invite link), `EditorPane`, `PresencePanel` (users + kick, creator-only), `JoinRequests`
  (approve/reject, creator-only), `ActionsPanel` (download raw / `.zip` via JSZip with
  `metadata.json`, filename + auto extension, GitHub authorize/disconnect/push form).
- CSS: port [style.css](realtime-codeshare/src/main/resources/static/css/style.css) into
  the components (CSS modules), preserving the palette, layout, animations, and the
  `<600px` responsive rule for parity.

### 3. Parity checklist (behavior must match exactly)

Create/join/waiting/denied → WS connect + token persistence → `init` (doc/version/role) →
OT live edit with pending-op transform → color-coded remote cursors → presence list →
kick / admin transfer (`adminChanged`) / kicked / sessionEnded → join-request
approve/reject → language auto-detect + label + mode switch → download raw + `.zip` →
copy invite link → GitHub status/authorize(`returnTo`)/disconnect/push + handle the
`?github=connected` return param. Client keeps sending ops at its own pace (server-side
rate limiting/`tryAcquire` is unchanged and transparent to the client).

### 4. Cleanup (after parity is confirmed, not before)

Remove [templates/index.html](realtime-codeshare/src/main/resources/templates/index.html),
[static/js/codeshare.js](realtime-codeshare/src/main/resources/static/js/codeshare.js),
[static/css/style.css](realtime-codeshare/src/main/resources/static/css/style.css), and
`static/vendor/**`; drop the Thymeleaf dependency. Keep them in place until the React app
reaches parity so there is a working fallback during development.

## Critical files

- **New:** `realtime-codeshare/frontend/{package.json, vite.config.ts, tsconfig.json,
  index.html, src/**}` — the SPA.
- **Modified:** [realtime-codeshare/pom.xml](realtime-codeshare/pom.xml) — add
  frontend-maven-plugin, drop Thymeleaf; `.gitignore` for built static assets.
- **Removed (post-parity):** the four legacy frontend paths listed in Cleanup.
- **Reference / port from:**
  [codeshare.js](realtime-codeshare/src/main/resources/static/js/codeshare.js) (OT + WS
  protocol), [templates/index.html](realtime-codeshare/src/main/resources/templates/index.html)
  (DOM structure), [style.css](realtime-codeshare/src/main/resources/static/css/style.css)
  (styling). Server-side truth for OT:
  [OTService.java](realtime-codeshare/src/main/java/com/collabcode/realtime_codeshare/ot/OTService.java);
  access control:
  [SessionService.java](realtime-codeshare/src/main/java/com/collabcode/realtime_codeshare/service/SessionService.java);
  WS handler:
  [OTWebSocketHandler.java](realtime-codeshare/src/main/java/com/collabcode/realtime_codeshare/controller/OTWebSocketHandler.java).

## Verification

1. **OT unit tests** (Vitest): port the server's transform scenarios; assert
   insert/insert tie-break, insert/delete and delete/delete overlap, and convergence of
   interleaved op sequences against expected document state.
2. **Dev two-client test:** run backend `./mvnw spring-boot:run` and `cd frontend &&
   npm install && npm run dev` (Vite :5173, proxy to :8080). In two browsers: create in
   one, join in the other, approve, type concurrently → verify both converge; check remote
   cursors, presence, kick, admin transfer (close host tab), download raw + `.zip`, copy
   invite link.
3. **Packaged same-origin run:** `./mvnw clean package && java -jar target/*.jar` → open
   `http://localhost:8080`. Repeat the two-client test on the real artifact. With
   `GITHUB_CLIENT_ID`/`GITHUB_CLIENT_SECRET` set, run the full GitHub OAuth round-trip and
   push the document to a repo (verifies the same-origin cookie flow end-to-end).
4. **Offline check:** with DevTools Network open, confirm zero external/CDN requests —
   every asset is served from `:8080`.
5. Run the existing backend test suite (`./mvnw test`) to confirm no backend behavior was
   touched.
