-- Make the outbox inspectable: who caused each row, what it carries, and why it exists. The web UI
-- uses this to show a visitor what really happened to their action (staged -> published) and to let
-- them replay an event (same event_id, to exercise Notify's dedupe) or enqueue a poison message (to
-- exercise the dead-letter topic).
-- All new columns are nullable (or defaulted) so rows written before this migration stay valid.
-- Portable SQL: runs on both H2 (PostgreSQL mode, dev) and PostgreSQL. One column per ALTER keeps it
-- portable across both dialects.

-- The user whose action produced the row. NULL for rows staged before this migration.
ALTER TABLE outbox_events ADD COLUMN actor_id UUID;

-- Why the row exists: EVENT (a real relationship change), REPLAY (a re-enqueue of an earlier
-- event's identical payload), or POISON (a deliberately malformed payload).
ALTER TABLE outbox_events ADD COLUMN kind VARCHAR(16) DEFAULT 'EVENT' NOT NULL;

-- Denormalized from the payload so the trace can be listed and looked up without parsing JSON.
-- NULL on POISON rows (the payload is not a valid event) and on pre-migration rows.
ALTER TABLE outbox_events ADD COLUMN event_id UUID;
ALTER TABLE outbox_events ADD COLUMN event_type VARCHAR(30);
ALTER TABLE outbox_events ADD COLUMN recipient_id UUID;

-- "My recent events, newest first" and "find my event by id" (for replay).
CREATE INDEX idx_outbox_actor_created ON outbox_events (actor_id, created_at);
CREATE INDEX idx_outbox_event_id ON outbox_events (event_id);
