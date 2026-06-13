-- Graph service schema.
-- Portable SQL: runs on both H2 (PostgreSQL mode, dev) and PostgreSQL (docker / prod-like).
-- All ids are the immutable user ids minted by the Identity service.

-- Directed follow edges.
CREATE TABLE follows (
    id          UUID       PRIMARY KEY,
    follower_id UUID       NOT NULL,
    followee_id UUID       NOT NULL,
    created_at  TIMESTAMP  NOT NULL
);
-- One follow per (follower, followee): the constraint makes duplicate follows a no-op.
ALTER TABLE follows ADD CONSTRAINT uq_follows UNIQUE (follower_id, followee_id);
CREATE INDEX idx_follows_follower ON follows (follower_id);
CREATE INDEX idx_follows_followee ON follows (followee_id);

-- Undirected friendships with a directed request lifecycle.
-- pair_key = "minUserId:maxUserId" so there is exactly one row per unordered pair,
-- which is what lets two crossing requests collapse into one friendship.
CREATE TABLE friendships (
    id           UUID         PRIMARY KEY,
    requester_id UUID         NOT NULL,
    addressee_id UUID         NOT NULL,
    status       VARCHAR(20)  NOT NULL,
    pair_key     VARCHAR(80)  NOT NULL,
    created_at   TIMESTAMP    NOT NULL,
    updated_at   TIMESTAMP    NOT NULL
);
ALTER TABLE friendships ADD CONSTRAINT uq_friendships_pair UNIQUE (pair_key);
CREATE INDEX idx_friendships_requester ON friendships (requester_id);
CREATE INDEX idx_friendships_addressee ON friendships (addressee_id);

-- Directed blocks.
CREATE TABLE blocks (
    id         UUID       PRIMARY KEY,
    blocker_id UUID       NOT NULL,
    blocked_id UUID       NOT NULL,
    created_at TIMESTAMP  NOT NULL
);
ALTER TABLE blocks ADD CONSTRAINT uq_blocks UNIQUE (blocker_id, blocked_id);
CREATE INDEX idx_blocks_blocker ON blocks (blocker_id);
CREATE INDEX idx_blocks_blocked ON blocks (blocked_id);
