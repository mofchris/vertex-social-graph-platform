# Profile Service

The second microservice of the Vertex platform. Owns **user profile data** (display name,
bio, avatar, location, visibility) and demonstrates a **Redis read-through cache** with
serve-time privacy enforcement.

Built with **Spring Boot 4.1 (Java 21)**, Spring Cache, Spring Data JPA, Flyway, Spring
Security (resource-server style), and Micrometer/Prometheus. Listens on **port 8081** so it
can run beside the Identity service (8080).

## How it relates to Identity

Profile does not share a database with Identity (services own their data). It is a
**resource server**: it verifies the JWT access tokens that Identity issues, using the same
shared HS256 secret (`APP_JWT_SECRET`) and issuer. The user id comes from the verified
token's `sub` claim, so a profile is keyed by Identity's immutable user id.

> Get a token from Identity, then call Profile with it.

## Run it

### Option A — embedded (just a JDK, no Docker)

In-memory H2 + an in-process cache. Starts with nothing else installed.

```bash
./mvnw spring-boot:run
# → http://localhost:8081
```

### Option B — Postgres + Redis via Docker (prod-like)

```bash
docker compose up --build
# Postgres + Redis + the service, on http://localhost:8081
```

### Tests

```bash
./mvnw test
```

A unit test proves the read-through cache (second read served from cache); an integration
test drives the full API against H2 + the simple cache (no Docker), including cache
eviction on update.

## API

Base path `/v1`. JSON bodies. The `Authorization: Bearer <token>` header carries an
Identity-issued access token.

| Method | Path                  | Auth         | Description                                       |
|--------|-----------------------|--------------|---------------------------------------------------|
| PUT    | `/me/profile`         | required     | Create or replace the caller's own profile        |
| GET    | `/me/profile`         | required     | The caller's own profile                          |
| GET    | `/profiles/{userId}`  | optional     | A user's profile, subject to its visibility       |

Observability: `GET /actuator/health`, `GET /actuator/prometheus`.

### Visibility

`visibility` is one of `PUBLIC`, `FRIENDS`, `PRIVATE`. It is enforced **at serve time**, per
viewer — never baked into the cached value.

| Visibility | Owner | Other authenticated user | Anonymous |
|------------|-------|--------------------------|-----------|
| PUBLIC     | ✅    | ✅                       | ✅        |
| FRIENDS    | ✅    | ❌ (until Graph exists)  | ❌        |
| PRIVATE    | ✅    | ❌                       | ❌        |

Hidden profiles return **404** (not 403), so the API doesn't reveal that a profile exists.
`FRIENDS` currently resolves to owner-only; it will consult the Graph service once that
service is built.

### Example

```bash
# 1. Get a token from the Identity service (running on :8080)
TOKEN=$(curl -s -X POST localhost:8080/v1/auth/signup \
  -H 'Content-Type: application/json' \
  -d '{"email":"alice@example.com","username":"alice","password":"password123"}' \
  | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')

# 2. Create my profile
curl -s -X PUT localhost:8081/v1/me/profile \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"displayName":"Alice","bio":"building Vertex","visibility":"PUBLIC"}'

# 3. Read it back (anyone, since it's public)
curl -s localhost:8081/v1/profiles/<userId>
```

## Caching

Profiles are read through a **cache-aside** layer (`ProfileCache.load`, annotated
`@Cacheable`). Writes (`ProfileService.upsert`) are annotated `@CacheEvict`, so the next
read repopulates from the database with fresh data.

- **Store by profile:** `simple` in-process cache in dev; **Redis** in the
  `postgres`/docker profile (`spring.cache.type=redis`). The caching code is identical
  either way — only the backing store changes.
- **TTL:** `spring.cache.redis.time-to-live` (default 5m) is a backstop against staleness.
- **Privacy at serve time:** the cache stores the full profile (including `visibility`);
  visibility is applied per-viewer after the cache read, so one cached entry serves every
  viewer correctly.
- **Not-found is not cached** today. Negative caching (to defend against cache penetration)
  is a deliberate next step — see [`EDGE_CASES.md`](../../EDGE_CASES.md).

## Layout

```
src/main/java/com/vertex/profile/
├── config/      JwtProperties, SecurityConfig, CacheConfig
├── domain/      Profile, ProfileVisibility
├── repository/  ProfileRepository
├── security/    JwtService (verify-only), JwtAuthenticationFilter
├── service/     ProfileCache (@Cacheable), ProfileService, CachedProfile
├── web/         ProfileController, DTOs, error handling
src/main/resources/
├── application.yml
└── db/migration/V1__init_profile.sql
```
