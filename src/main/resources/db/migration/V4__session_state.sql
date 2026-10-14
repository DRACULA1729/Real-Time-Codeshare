-- Durable room access-control state (who may enter, who is admin, ended flag) so a
-- restart keeps invite links and member tokens valid. The state itself is JSON;
-- last_activity_ms and ended are lifted out for the idle reaper.
CREATE TABLE IF NOT EXISTS session_state (
    session_id       VARCHAR(64) PRIMARY KEY,
    state            TEXT        NOT NULL,
    last_activity_ms BIGINT      NOT NULL,
    ended            BOOLEAN     NOT NULL DEFAULT false,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS session_state_last_activity ON session_state (last_activity_ms);
