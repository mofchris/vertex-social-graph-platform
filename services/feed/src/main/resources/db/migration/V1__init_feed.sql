-- Feed service schema.
-- Portable SQL: runs on both H2 (PostgreSQL mode, dev) and PostgreSQL (docker / prod-like).

-- The source of truth for posts.
CREATE TABLE posts (
    id         UUID          PRIMARY KEY,
    author_id  UUID          NOT NULL,
    content    VARCHAR(500)  NOT NULL,
    created_at TIMESTAMP     NOT NULL
);
CREATE INDEX idx_posts_author_created ON posts (author_id, created_at);

-- Materialized home-timeline entries (fan-out-on-write for non-celebrity authors).
-- Stores a *reference* to the post (post_id), not a copy of its content, so edits/deletes
-- don't leave stale copies (see EDGE_CASES.md: mutating content after fan-out).
CREATE TABLE timeline_entries (
    id         UUID       PRIMARY KEY,
    owner_id   UUID       NOT NULL,
    post_id    UUID       NOT NULL,
    author_id  UUID       NOT NULL,
    created_at TIMESTAMP  NOT NULL
);
ALTER TABLE timeline_entries ADD CONSTRAINT uq_timeline_owner_post UNIQUE (owner_id, post_id);
CREATE INDEX idx_timeline_owner_created ON timeline_entries (owner_id, created_at);

-- Authors discovered to be celebrities (followers over the threshold). Their posts are
-- pulled on read instead of fanned out on write.
CREATE TABLE celebrities (
    user_id        UUID       PRIMARY KEY,
    follower_count BIGINT     NOT NULL,
    updated_at     TIMESTAMP  NOT NULL
);
