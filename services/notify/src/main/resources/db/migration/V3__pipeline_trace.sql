-- Make the consumer side of the event pipeline inspectable (the web UI's system trace).
-- Portable SQL: runs on both H2 (PostgreSQL mode, dev) and PostgreSQL. One column per ALTER keeps it
-- portable across both dialects. New ledger columns are nullable (or defaulted) so entries written
-- before this migration stay valid.

-- Where each processed event came from and who it concerns. actor/recipient are what the trace
-- endpoint authorizes against: only the two people an event is about may look it up.
ALTER TABLE processed_events ADD COLUMN recipient_id UUID;
ALTER TABLE processed_events ADD COLUMN actor_id UUID;
ALTER TABLE processed_events ADD COLUMN event_type VARCHAR(30);
ALTER TABLE processed_events ADD COLUMN kafka_partition INTEGER;
ALTER TABLE processed_events ADD COLUMN kafka_offset BIGINT;

-- The dedupe ledger as evidence, not just a gate: every redelivery of an already-processed event id
-- bumps this counter (atomically, in SQL) instead of producing a second notification.
ALTER TABLE processed_events ADD COLUMN duplicates_ignored INTEGER DEFAULT 0 NOT NULL;
ALTER TABLE processed_events ADD COLUMN last_duplicate_at TIMESTAMP;

-- The purge scans by age.
CREATE INDEX idx_processed_events_processed_at ON processed_events (processed_at);

-- Records captured off social.events.DLT by a dedicated listener, so the UI can show a visitor where
-- their poison message ended up and why. The original coordinates are the natural key: a record that
-- is dead-lettered (or whose DLT copy is redelivered to us) more than once is stored once.
CREATE TABLE dead_letters (
    id                 UUID          PRIMARY KEY,
    msg_key            VARCHAR(255),
    payload            VARCHAR(1000),
    error_message      VARCHAR(1000),
    original_topic     VARCHAR(255),
    original_partition INTEGER,
    original_offset    BIGINT,
    received_at        TIMESTAMP     NOT NULL
);
ALTER TABLE dead_letters ADD CONSTRAINT uq_dead_letters_origin
    UNIQUE (original_topic, original_partition, original_offset);
CREATE INDEX idx_dead_letters_key_received ON dead_letters (msg_key, received_at);
CREATE INDEX idx_dead_letters_received ON dead_letters (received_at);
