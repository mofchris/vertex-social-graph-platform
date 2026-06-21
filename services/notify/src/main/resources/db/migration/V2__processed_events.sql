-- Dedupe ledger for the at-least-once Kafka consumer (see EDGE_CASES.md: idempotent consumers).
-- Each social event carries a unique event_id; we record it as we handle the event, in the same
-- transaction as the notification it produces. A redelivered event collides on this primary key
-- and is skipped, so processing the stream is idempotent.
-- Portable SQL: runs on both H2 (PostgreSQL mode, dev) and PostgreSQL.
CREATE TABLE processed_events (
    event_id     UUID      PRIMARY KEY,
    processed_at TIMESTAMP NOT NULL
);
