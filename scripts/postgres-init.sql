-- One Postgres instance, one database per stateful service (recommend is stateless, no DB).
-- Runs once on first container start via /docker-entrypoint-initdb.d. Each service still owns and
-- migrates its own schema (Flyway) inside its database — the single instance is a local-dev
-- convenience, not a shared schema.
CREATE DATABASE identity;
CREATE DATABASE profile;
CREATE DATABASE graph;
CREATE DATABASE feed;
CREATE DATABASE notify;
