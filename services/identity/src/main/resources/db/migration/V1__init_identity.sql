-- Identity service schema.
-- Written in portable SQL so the same migration runs on both
-- H2 (PostgreSQL mode, dev) and PostgreSQL (docker / prod-like).

CREATE TABLE users (
    id            UUID          PRIMARY KEY,
    email         VARCHAR(255)  NOT NULL,
    username      VARCHAR(50)   NOT NULL,
    password_hash VARCHAR(255)  NOT NULL,
    display_name  VARCHAR(100),
    status        VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMP     NOT NULL,
    updated_at    TIMESTAMP     NOT NULL
);

-- Uniqueness enforced at the database, not in application code:
-- this is what actually wins the concurrent-signup race.
ALTER TABLE users ADD CONSTRAINT uq_users_email UNIQUE (email);
ALTER TABLE users ADD CONSTRAINT uq_users_username UNIQUE (username);

CREATE TABLE refresh_tokens (
    id          UUID          PRIMARY KEY,
    user_id     UUID          NOT NULL,
    token_hash  VARCHAR(255)  NOT NULL,
    family_id   UUID          NOT NULL,
    expires_at  TIMESTAMP     NOT NULL,
    revoked     BOOLEAN       NOT NULL DEFAULT FALSE,
    replaced_by UUID,
    created_at  TIMESTAMP     NOT NULL,
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users (id)
);

-- Only the SHA-256 hash of a refresh token is stored; the raw token never hits the DB.
ALTER TABLE refresh_tokens ADD CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash);

CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens (family_id);
