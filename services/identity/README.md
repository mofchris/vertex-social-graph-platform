# Identity Service

The first microservice of the Vertex platform. Owns **accounts, authentication, and
tokens**: signup, login, JWT access tokens, refresh-token rotation with reuse detection,
and the authenticated profile endpoint.

Built with **Spring Boot 4.1 (Java 21)**, Spring Security, Spring Data JPA, Flyway, and
Micrometer/Prometheus.

## Run it

### Option A — embedded (just a JDK, no Docker)

The default profile uses an in-memory H2 database in PostgreSQL-compatibility mode, so the
service starts with nothing else installed.

```bash
./mvnw spring-boot:run
# → http://localhost:8080
```

### Option B — Postgres via Docker (prod-like)

```bash
docker compose up --build
# Postgres + the service, on http://localhost:8080
```

### Tests

```bash
./mvnw test
```

Unit tests cover the JWT service; an integration test drives the full auth lifecycle
against H2 (no Docker required).

## API

Base path `/v1`. All bodies are JSON.

| Method | Path             | Auth        | Description                                  |
|--------|------------------|-------------|----------------------------------------------|
| POST   | `/auth/signup`   | none        | Create an account, returns tokens (201)      |
| POST   | `/auth/login`    | none        | Authenticate, returns tokens                 |
| POST   | `/auth/refresh`  | none        | Rotate refresh token, returns new tokens     |
| POST   | `/auth/logout`   | none        | Revoke the token family (204)                |
| GET    | `/me`            | Bearer JWT  | Profile of the authenticated user            |

Observability: `GET /actuator/health`, `GET /actuator/prometheus`.

### Example

```bash
# Sign up
curl -s -X POST localhost:8080/v1/auth/signup \
  -H 'Content-Type: application/json' \
  -d '{"email":"alice@example.com","username":"alice","password":"password123","displayName":"Alice"}'

# → {"accessToken":"...","refreshToken":"...","tokenType":"Bearer","expiresIn":900,"user":{...}}

# Call an authenticated endpoint
curl -s localhost:8080/v1/me -H "Authorization: Bearer <accessToken>"

# Rotate the refresh token
curl -s -X POST localhost:8080/v1/auth/refresh \
  -H 'Content-Type: application/json' \
  -d '{"refreshToken":"<refreshToken>"}'
```

## Design notes

- **Stateless access tokens.** Access tokens are short-lived HS256 JWTs (15 min). The
  filter verifies the signature and sets the user id as the principal — no server session.
- **Refresh-token rotation + reuse detection.** Refresh tokens are opaque random strings,
  stored only as SHA-256 hashes and grouped into a *family*. Each use rotates the token
  (old one revoked, new one issued in the same family). Presenting an already-rotated token
  is treated as theft and revokes the whole family.
- **Uniqueness at the database.** Email/username uniqueness is enforced by DB constraints,
  not just an application check, so concurrent signups can't both win.
- **Immutable user id.** Everything keys on an immutable UUID; email and username are
  mutable labels. Accounts are soft-deleted (status tombstone), never hard-deleted.

These map directly to the failure modes in the repo's
[`EDGE_CASES.md`](../../EDGE_CASES.md).

## Layout

```
src/main/java/com/vertex/identity/
├── config/      JwtProperties, SecurityConfig
├── domain/      User, RefreshToken, UserStatus
├── repository/  Spring Data JPA repositories
├── security/    JwtService, JwtAuthenticationFilter
├── service/     AuthService (signup, login, refresh, logout)
├── web/         controllers, DTOs, error handling
src/main/resources/
├── application.yml
└── db/migration/V1__init_identity.sql
```
