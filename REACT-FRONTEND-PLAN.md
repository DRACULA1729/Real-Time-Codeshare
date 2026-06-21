# Plan: Rewrite the frontend as a React + TypeScript SPA (Synchronous Mono design)

## Context

The backend on the `ship-readiness` branch is production-hardened (atomic OT, Meet-style
access control, GitHub OAuth, offline single-JAR shipping). The **frontend has not kept
pace**: it is a single 482-line vanilla file
([codeshare.js](realtime-codeshare/src/main/resources/static/js/codeshare.js)) driving a
static Thymeleaf page
([index.html](realtime-codeshare/src/main/resources/templates/index.html)) with 314 lines
of hand-written CSS. State lives in globals, the UI is manipulated through `$()` DOM
queries, and a hand-rolled OT engine is tightly coupled to CodeMirror 5's imperative API.

We are replacing it with a **React + TypeScript SPA** that adopts the **"Synchronous Mono"**
design system delivered as a Stitch export in
`/home/crest/Downloads/Final_Ui_Codeshare/stitch_real_time_codeshare/` (7 screens +
`synchronous_mono/DESIGN.md`). The rewrite must: **change nothing about the backend**,
**preserve the offline single-JAR shipping model**, and **realize the new design** — adopting
its full visual language while only wiring controls the backend actually supports.

Two backend facts still constrain the architecture:

- **GitHub OAuth is cookie/session-bound.** The token store is keyed by `JSESSIONID`
  ([GitHubTokenStore.java](realtime-codeshare/src/main/java/com/collabcode/realtime_codeshare/github/GitHubTokenStore.java))
  and the flow does full-page redirects to GitHub and back with a `returnTo`. → The SPA
  must be served **same-origin** from Spring Boot.
- **The app is fully offline** (no CDN). The mockups load Tailwind, Google Fonts, Material
  Symbols, and avatar images from CDNs — **none of that survives**. Tailwind must run at
  build time and all fonts/icons must be self-hosted and bundled.

### Decision: design vs backend reality ("look yes, dead buttons no")

The mockups depict aspirational IDE chrome the backend does not implement. We adopt the
**visual language and layout** but render **only backed controls** — no inert buttons.

**Keep & wire to the backend:**
- Create / Join / Waiting / Declined entry screens
- Workspace shell: top app bar (logo, Session ID chip + copy, Share Session), 240px left
  sidebar, presence strip, footer status bar
- Sidebar **Collaborators** section = presence list + kick (host only); sidebar **Actions**
  entry opens the Actions popover
- Presence chips (name + participant color, "YOU (HOST)" marker), language label, cursor
  Ln/Col, connection/"Syncing" status
- Join-request **glass panel** (host): Admit / Reject
- **Actions** glass popover: Export (filename + raw/`.zip`), GitHub Integration
  (status/authorize/disconnect + owner/repo/path/branch/message + Push)
- Editor: gutter line numbers, active-line highlight, remote-cursor bars with name-flag
  labels in each user's color
- Admin transfer, kicked, session-ended flows; copy invite link via the Session ID chip /
  Share button

**Drop (no backend) — omit entirely, not disabled:** multi-file tabs & Explorer / New File,
`Files`·`Deploy`·`Terminal` nav, `Git`·`Extensions`·`Account` sidebar items, Run-Code FAB,
Chat FAB, photo avatars (use **monogram avatars** — initial on a participant-color chip),
"Explore Public Projects", deploy/encryption copy. The single shared document is presented
as one fixed file name (editable, drives the download filename + language label).

## Approach

A Vite + React + TypeScript app under `realtime-codeshare/frontend/`, styled with
**Tailwind** (build-time) using the Synchronous Mono tokens, built by `frontend-maven-plugin`
during `./mvnw package` into the Spring Boot static dir, served same-origin at `:8080`.
Routing stays **query-string based** (`?sessionId=...`, `?github=connected`) — **no backend
routing change**, existing welcome-page resolution keeps working.

### 1. Build, serving & offline assets

- New `realtime-codeshare/frontend/`: `package.json`, `vite.config.ts`, `tsconfig.json`,
  `tailwind.config.ts`, `postcss.config.js`, `index.html`, `src/**`.
- **Tailwind config** = lift the Synchronous Mono tokens verbatim from
  `Final_Ui_Codeshare/.../synchronous_mono/DESIGN.md` (and the identical `tailwind.config`
  inlined in each `code.html`): the `colors` map (`surface-page #0F1115`,
  `surface-raised #1C1F26`, `surface-container #1c2028`, `border-subtle #2D313A`,
  `text-primary #F9FAFB`, `text-muted #9CA3AF`, `primary #adc6ff`, `on-primary #002e6a`,
  `secondary #d0bcff`, `success #10B981`, `danger #EF4444`, `participant-1..4`
  `#F472B6/#34D399/#FBBF24/#60A5FA`, …), the `fontSize` scale (`headline-*`, `body-*`,
  `code-*`, `label-caps`), `borderRadius`, and `spacing` (`editor-padding 32px`, etc.).
- **Self-hosted fonts/icons (offline):** add `@fontsource/geist-sans` +
  `@fontsource/jetbrains-mono` and vendor **Material Symbols Outlined** (woff2 + the
  `.material-symbols-outlined` variation-settings CSS) locally. Zero `fonts.googleapis.com`
  / `cdn.tailwindcss.com` references. Avatars are CSS monograms — no remote images.
- **Vite** `build.outDir` → `../src/main/resources/static`, `emptyOutDir: true`. The built
  `static/index.html` becomes Spring Boot's welcome page at `/`. Remove the Thymeleaf
  `templates/index.html`; drop `spring-boot-starter-thymeleaf` from the pom.
- **pom.xml** ([realtime-codeshare/pom.xml](realtime-codeshare/pom.xml)): add
  `com.github.eirslett:frontend-maven-plugin` (pinned Node/npm) with `install-node-and-npm`
  → `npm ci` → `npm run build`, bound to `generate-resources`, `workingDirectory=frontend`.
  Built assets are git-ignored and produced fresh by Maven; the JAR stays a single,
  self-contained, offline artifact.
- **Dev loop:** `vite.config.ts` `server.proxy` forwards `/api` → `http://localhost:8080`
  and `/ws` → `ws://localhost:8080` (`ws:true`), cookies passed through so `JSESSIONID`
  stays consistent. (Full OAuth round-trip is verified against the packaged same-origin
  build, since GitHub redirects to the backend origin.)

### 2. Frontend module layout (`frontend/src/`)

Core protocol layers are **unchanged by the redesign** — the design only affects the
presentation layer:

- `api/` — typed REST client for
  [SessionController.java](realtime-codeshare/src/main/java/com/collabcode/realtime_codeshare/controller/SessionController.java)
  + GitHub controllers: `createSession({name,color})`, `sessionExists(id)`,
  `github.status() / authorize(returnTo) / disconnect() / push({owner,repo,path,branch,message,sessionId,sessionToken})`.
- `ws/` — WebSocket client + **typed message unions**. `WsInbound` = `init | op | cursor |
  cursorRemove | presence | waitingForApproval | joinRequest | joinResolved | joinDenied |
  adminChanged | kicked | sessionEnded`; `WsOutbound` = `insert | delete | cursor | kick |
  approve | reject | endsession`. Builds the
  `ws[s]://host/ws/ot?sessionId&clientId&name&color&token` URL as today.
- `ot/` — **highest-risk port.** Move the transform engine verbatim from
  [codeshare.js:228-262](realtime-codeshare/src/main/resources/static/js/codeshare.js#L228-L262)
  (`transformII/ID/DI/DD`) + pending-op transform/apply logic as **pure typed functions**,
  semantically identical to the server
  ([OTService.java:185-263](realtime-codeshare/src/main/java/com/collabcode/realtime_codeshare/ot/OTService.java#L185-L263)),
  same `clientId` tie-break. Vitest unit tests.
- `state/` — small **Zustand** store for cross-cutting state (sessionId, token, role, name,
  color, version, pendingOps, users, joinRequests, doc/file name); token persisted to
  `sessionStorage` per session.
- `editor/` — CodeMirror 6 (`@uiw/react-codemirror` + `@codemirror/*` + theme). Map CM6
  transaction `changes` → OT insert/delete by absolute offset; apply remote ops via
  `view.dispatch` tagged with an annotation so they aren't echoed. **Style to the mockups:**
  custom dark theme using the design's syntax colors, gutter in `text-muted`, active line at
  `primary/5`, and remote cursors as a 2px participant-color bar + a small name flag
  (`label-caps`) via a `StateField`/decorations. Language auto-detect drives the mode + the
  presence-strip language chip.
- `components/` (Tailwind, matching the mockups one-to-one):
  - `gate/` — `CreateSession`, `JoinSession` (session-code chip), `WaitingRoom` (loader ring
    + host/session card + Cancel), `RequestDeclined` (terminal-style card + "start your own"
    action). Color picker = participant swatches.
  - `shell/` — `AppShell` (top app bar + left `Sidebar` + footer `StatusBar`), `TopBar`
    (logo, Session ID chip + copy, Share Session), `Sidebar` (project header, **Collaborators**
    = `PresencePanel` with kick, **Actions** trigger), `PresenceStrip` (participant chips +
    language/Ln·Col/sync).
  - `workspace/` — `EditorPane` (gutter + editor + cursors), `JoinRequestPanel` (glass,
    Admit/Reject), `ActionsPopover` (glass; Export raw/`.zip` via JSZip incl. `metadata.json`;
    GitHub form), `Avatar` (monogram).
- Reuse the design's `glass-panel` (12px backdrop-blur, 80% opacity), 8px/16px radii, and
  `150ms ease-out` transitions as Tailwind utilities/components.

### 3. Parity of behavior (must still match the backend contract)

Create/join/waiting/declined → WS connect + token persistence → `init` (doc/version/role) →
OT live edit with pending-op transform → color-coded remote cursors → presence → kick /
admin transfer (`adminChanged`) / kicked / sessionEnded → join-request approve/reject →
language auto-detect + label + mode → download raw + `.zip` → copy invite link → GitHub
status/authorize(`returnTo`)/disconnect/push + handle `?github=connected`. Client sends ops
at its own pace (server rate limiting unchanged).

### 4. Cleanup (after parity is confirmed, not before)

Remove [templates/index.html](realtime-codeshare/src/main/resources/templates/index.html),
[static/js/codeshare.js](realtime-codeshare/src/main/resources/static/js/codeshare.js),
[static/css/style.css](realtime-codeshare/src/main/resources/static/css/style.css), and
`static/vendor/**`; drop Thymeleaf. Keep them until the React app reaches parity (working
fallback during development).

## Critical files

- **New:** `realtime-codeshare/frontend/{package.json, vite.config.ts, tsconfig.json,
  tailwind.config.ts, index.html, src/**}` — the SPA.
- **Design source (reference, lift tokens from):**
  `~/Downloads/Final_Ui_Codeshare/stitch_real_time_codeshare/` — `synchronous_mono/DESIGN.md`
  (tokens + rationale) and each screen's `code.html` (exact Tailwind config + markup) /
  `screen.png`.
- **Modified:** [realtime-codeshare/pom.xml](realtime-codeshare/pom.xml) — add
  frontend-maven-plugin, drop Thymeleaf; `.gitignore` for built static assets.
- **Removed (post-parity):** the four legacy frontend paths in Cleanup.
- **Backend truth (do not modify):**
  [OTService.java](realtime-codeshare/src/main/java/com/collabcode/realtime_codeshare/ot/OTService.java),
  [SessionService.java](realtime-codeshare/src/main/java/com/collabcode/realtime_codeshare/service/SessionService.java),
  [OTWebSocketHandler.java](realtime-codeshare/src/main/java/com/collabcode/realtime_codeshare/controller/OTWebSocketHandler.java).

## Verification

1. **OT unit tests** (Vitest): port the server's transform scenarios — insert/insert
   tie-break, insert/delete & delete/delete overlap, convergence of interleaved sequences.
2. **Dev two-client test:** backend `./mvnw spring-boot:run` + `cd frontend && npm install
   && npm run dev` (Vite :5173, proxy to :8080). Two browsers: create / join / admit / type
   concurrently → converge; verify remote cursors + name flags, presence chips, kick, admin
   transfer, export raw + `.zip`, copy invite link.
3. **Visual fidelity:** spot-check each rendered screen against the matching `screen.png`
   (colors, fonts, spacing, glass panels) — adopting the look is an explicit goal.
4. **Packaged same-origin run:** `./mvnw clean package && java -jar target/*.jar` → open
   `http://localhost:8080`; repeat the two-client test on the artifact. With
   `GITHUB_CLIENT_ID`/`GITHUB_CLIENT_SECRET` set, run the full GitHub OAuth round-trip and
   push to a repo (verifies the same-origin cookie flow).
5. **Offline check:** DevTools Network shows zero external/CDN requests — fonts, icons,
   Tailwind output, and all assets served from `:8080`.
6. Run the backend suite (`./mvnw test`) to confirm no backend behavior changed.
