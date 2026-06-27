# Phase 4 — horizontal scale & durability (Upstash + Neon)

Status of the scale work, the Upstash/Neon feasibility verdict (with proof), and
the remaining multi-node pieces.

## Feasibility verdict: both are feasible ✅

I implemented the document persistence layer against both engines and verified it
**against real Redis and PostgreSQL** (local Docker), not just on paper.

| Target | What it maps to | Verified |
|---|---|---|
| **Neon** | managed PostgreSQL (wire-compatible) | `JpaDocumentRepository` + Flyway, `postgres` profile. Load-on-miss read a seeded snapshot; an edit wrote through to `document_snapshot`. |
| **Upstash** | managed Redis (RESP/TLS-compatible) | `RedisDocumentRepository`, `redis` profile. Load-on-miss read a seeded `cs:doc:{id}` hash; an edit wrote through. |

Because both are protocol/library compatible with the standard Spring tooling
(Spring Data JPA + PostgreSQL JDBC; Spring Data Redis + Lettuce), the same code runs
against the managed services by swapping the connection string:

- **Neon:** `DB_URL=jdbc:postgresql://<host>/<db>?sslmode=require` (use the direct or
  session-pooler endpoint so Flyway migrations run; the transaction-pooler doesn't
  support session-level features Flyway needs).
- **Upstash:** `REDIS_URL=rediss://default:<token>@<host>:6379` (TLS). Lettuce holds a
  persistent connection and auto-reconnects; mind the per-plan max-connection limit.

### Caveats to plan for
- **Upstash pub/sub** works over the TCP/TLS endpoint (not the REST API) and messages
  aren't persisted — fine for the op/presence fan-out path, but the design must
  tolerate a missed message (the SHA-256 resync probe already self-heals drift).
- **Neon autosuspend** (free tier) cold-starts a paused branch on first connect;
  HikariCP's first checkout may see a short delay. Set a sane connection timeout.

## What's implemented now (verifiable single-node + the persistence seam)

- **`DocumentRepository` has three implementations** behind profiles: in-memory
  (default), `postgres` (durable), `redis` (shared). The default build needs neither
  engine — the JPA/DataSource/Flyway/Redis auto-configs are excluded in
  `application.yml` and re-enabled per profile. **Activate exactly one of `postgres`
  / `redis`** — they are mutually exclusive in this build (the app fails fast with a
  clear message if both are set; the composite Redis-over-Postgres design is item 5
  below).
- **P2-2 head-of-line blocking:** the per-session `ConcurrentWebSocketSessionDecorator`
  now uses `OverflowStrategy.TERMINATE`, so a peer that can't drain its send buffer is
  closed rather than stalling the room's fan-out.
- **P2-3 snapshot compaction:** already in place from P0-3 — `DocumentStore` tracks a
  compaction floor; ops based below it get a forced resync instead of being
  transformed against partial history, bounding `commit()` cost.

Run a scale profile locally:
```bash
docker run -d -p 5432:5432 -e POSTGRES_USER=codeshare -e POSTGRES_PASSWORD=codeshare -e POSTGRES_DB=codeshare postgres:16
java -jar target/realtime-codeshare-*.jar --spring.profiles.active=postgres   # DB_URL/DB_USER/DB_PASSWORD override the localhost defaults

docker run -d -p 6379:6379 redis:7
java -jar target/realtime-codeshare-*.jar --spring.profiles.active=redis       # REDIS_URL overrides
```

## Remaining for full horizontal scale (the larger follow-up)

The document store is externalized; full multi-node needs the rest of the in-memory
state externalized and a cross-node fan-out path. These need a real multi-node + LB
setup to verify (node-failover, rolling deploy), which is why they're sequenced last:

1. **Externalize the remaining singletons to Redis** behind interfaces (same pattern):
   `SessionService` access-control state, `GitHubTokenStore`, `RateLimiter` counters,
   `TicketStore`. Until `SessionService` is externalized, a restarted/other node has the
   durable *document* but not the *access list*, so a member can't be re-admitted there.
2. **`RoomBroadcaster` over Redis pub/sub** — publish op/presence/admin events on a
   per-room channel; each node delivers to its local sockets. This is the forwarding +
   failover path that complements room affinity.
3. **Spring Session Data Redis** so the GitHub OAuth callback and push can land on
   different nodes.
4. **Room affinity at the LB** (sticky by `sessionId`) so the proven single-writer
   `DocumentStore.commit()` stays authoritative per room; a **distributed reaper**
   coordinates via a Redis lock to avoid double-eviction.
5. **Composite `DocumentRepository`** — Redis (live) in front of Postgres (durable),
   instead of the current either/or profile selection.
