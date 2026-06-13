-- Profile service schema.
-- Portable SQL: runs on both H2 (PostgreSQL mode, dev) and PostgreSQL (docker / prod-like).
--
-- Keyed on user_id, the immutable id minted by the Identity service. Profile owns only
-- its own fields; identity data (email, username) stays in the Identity service.

CREATE TABLE profiles (
    user_id      UUID          PRIMARY KEY,
    display_name VARCHAR(100),
    bio          VARCHAR(500),
    avatar_url   VARCHAR(500),
    location     VARCHAR(100),
    visibility   VARCHAR(20)   NOT NULL DEFAULT 'PUBLIC',
    created_at   TIMESTAMP     NOT NULL,
    updated_at   TIMESTAMP     NOT NULL
);
