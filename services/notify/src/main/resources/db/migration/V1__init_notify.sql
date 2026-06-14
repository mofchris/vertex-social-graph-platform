-- Notify service schema.
-- Portable SQL: runs on both H2 (PostgreSQL mode, dev) and PostgreSQL (docker / prod-like).

-- One row per *coalesced* notification. Repeated events of the same kind for the same
-- recipient and target collapse into a single unread row with an incrementing count,
-- so 1,000 likes are one "X and 999 others", not 1,000 rows (see EDGE_CASES.md:
-- notification storms). Coalescing is find-or-create inside a transaction.
CREATE TABLE notifications (
    id              UUID          PRIMARY KEY,
    recipient_id    UUID          NOT NULL,
    type            VARCHAR(30)   NOT NULL,
    target_id       UUID,
    -- "type:targetOrSelf" — the grouping key for coalescing.
    coalesce_key    VARCHAR(80)   NOT NULL,
    actor_count     BIGINT        NOT NULL,
    latest_actor_id UUID          NOT NULL,
    is_read         BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP     NOT NULL,
    updated_at      TIMESTAMP     NOT NULL
);

CREATE INDEX idx_notifications_recipient_updated ON notifications (recipient_id, updated_at);
-- Speeds the "find the existing unread row to coalesce into" lookup.
-- (Production would make this a Postgres partial UNIQUE index WHERE read = FALSE for
--  race safety; H2 doesn't support partial indexes, so coalescing is done in a tx here.)
CREATE INDEX idx_notifications_coalesce ON notifications (recipient_id, coalesce_key, is_read);
