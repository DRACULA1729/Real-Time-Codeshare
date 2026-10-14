-- Durable document snapshots (durable half of P0-2). One row per room; the OT
-- engine upserts on empty/periodic save and reloads on a cold-start cache miss.
CREATE TABLE IF NOT EXISTS document_snapshot (
    session_id  VARCHAR(64) PRIMARY KEY,
    text        TEXT        NOT NULL,
    version     INTEGER     NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
