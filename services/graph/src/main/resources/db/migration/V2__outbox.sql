-- Transactional outbox for the Kafka event path (see EDGE_CASES.md: "partial-failure multi-service
-- write"). A relationship change writes its event here in the same transaction that writes the edge,
-- so the two commit atomically; a relay then drains unpublished rows to Kafka and stamps published_at.
-- Portable SQL: runs on both H2 (PostgreSQL mode, dev) and PostgreSQL.
CREATE TABLE outbox_events (
    id           UUID          PRIMARY KEY,
    topic        VARCHAR(255)  NOT NULL,
    msg_key      VARCHAR(255),
    payload      VARCHAR(4000) NOT NULL,
    created_at   TIMESTAMP     NOT NULL,
    published_at TIMESTAMP
);

-- The relay polls "unpublished, oldest first"; leading published_at groups the NULLs (un-sent rows)
-- and created_at orders them. Portable (no partial index, which H2 does not support).
CREATE INDEX idx_outbox_unpublished ON outbox_events (published_at, created_at);
