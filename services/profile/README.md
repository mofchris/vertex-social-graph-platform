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

A unit test proves the read-through cache (second read served from cache, batch reads share
the same entries and load only misses); an integration test drives the full API against H2 +
the simple cache (no Docker), including cache eviction on update and the batch endpoint's
privacy rules, 100-id cap and malformed-id handling.

## API

Base path `/v1`. JSON bodies. The `Authorization: Bearer <token>` header carries an
Identity-issued access token.

| Method | Path                  | Auth         | Description                                       |
|--------|-----------------------|--------------|---------------------------------------------------|
| PUT    | `/me/profile`         | required     | Create or replace the caller's own profile        |
| GET    | `/me/profile`         | required     | The caller's own profile                          |
| GET    | `/profiles/{userId}`  | optional     | A user's profile, subject to its visibility       |
| GET    | `/profiles?ids=a,b,…` | optional     | Up to 100 profiles at once, same visibility rules |

Observability: `GET /actuator/health`, `GET /actuator/prometheus`.

### Visibility

`visibility` is one of `PUBLIC`, `FRIENDS`, `PRIVATE`. It is enforced **at serve time**, per
viewer — never baked into the cached value.

| Visibility | Owner | Friend | Other authenticated user | Anonymous |
|------------|-------|--------|--------------------------|-----------|
| PUBLIC     | ✅    | ✅     | ✅                       | ✅        |
| FRIENDS    | ✅    | ✅     | ❌                       | ❌        |
| PRIVATE    | ✅    | ❌     | ❌                       | ❌        |

Hidden profiles return **404** (not 403), so the API doesn't reveal that a profile exists.

For `FRIENDS`, this service makes a live call to the **Graph service**: it forwards the
viewer's bearer token to `GET {graph}/v1/relationship/{ownerId}` and shows the profile only
if `friendStatus == FRIENDS`. If Graph is unreachable it **fails closed** (treats the viewer
as not-a-friend), so an outage can't leak a private-ish profile. Configure the Graph
endpoint with `app.graph.base-url` (env `GRAPH_BASE_URL`, default `http://localhost:8082`).

### Batch read

`GET /v1/profiles?ids=<uuid>,<uuid>,...` exists for UIs that only hold user ids (feeds,
notifications, suggestions). Repeated `ids=` params also work. Response:

```json
{ "items": [ { "userId": "...", "displayName": "Alice", "bio": "...", "avatarUrl": null,
               "location": null, "visibility": "PUBLIC", "updatedAt": "..." } ] }
```

- **Same rules as the single read.** Each profile goes through the same per-viewer check.
- **Hidden and missing profiles are omitted**, not stubbed. That matches the single read's
  silent 404: a caller can't tell a private profile from one that doesn't exist, and no
  hidden field (not even `visibility`) is returned. Results keep the request order, with
  duplicate ids dropped.
- **Limits.** At most 100 ids; more, a malformed id (non-canonical UUIDs and empty entries
  included), or no ids at all gives **400** `validation_failed` with `fieldErrors.ids`.
- **Efficient.** Cache hits come from the same `profiles` entries as the single read; all
  misses load in one `findAllById` query and are then cached. Graph has no batch
  relationship API, so FRIENDS profiles still need one Graph check each, but only for
  FRIENDS profiles the viewer doesn't own, run in parallel (at most 8 at a time), and never
  for anonymous viewers. Each check fails closed, as in the single read.

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
- **Batch reads** (`ProfileCache.loadAll`) use the same cache entries programmatically:
  hits from the cache, misses in one query, then cached.
- **Not-found is not cached** today. Negative caching (to defend against cache penetration)
  is a deliberate next step — see [`EDGE_CASES.md`](../../EDGE_CASES.md).

## Layout

```
src/main/java/com/vertex/profile/
├── client/      GraphClient (calls Graph for FRIENDS visibility)
├── config/      JwtProperties, SecurityConfig, CacheConfig
├── domain/      Profile, ProfileVisibility
├── repository/  ProfileRepository
├── security/    JwtService (verify-only), JwtAuthenticationFilter
├── service/     ProfileCache (@Cacheable + batch loadAll), ProfileService, CachedProfile
├── web/         ProfileController, DTOs, error handling
src/main/resources/
├── application.yml
└── db/migration/V1__init_profile.sql
```
