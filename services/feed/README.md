# Feed Service

The fourth microservice of the Vertex platform: **posts and home timelines** with **hybrid
fan-out** — the headline distributed-systems pattern for social feeds at scale.

Built with **Spring Boot 4.1 (Java 21)**, Spring Data JPA, Flyway, Spring Security
(resource-server style), and Micrometer/Prometheus. Listens on **port 8083**.

## How it relates to the other services

Feed verifies Identity-issued JWTs (shared `APP_JWT_SECRET`) and calls the **Graph service**
(forwarding the user's token) to read follower/following lists for fan-out. It owns its own
database.

## Hybrid fan-out — the core idea

Naive fan-out-on-write breaks for celebrities: one post by a 50M-follower account would mean
50M timeline inserts. Naive fan-out-on-read breaks for everyone else: every feed load would
re-query the whole follow graph. Vertex uses **both**, split by follower count:

- **Normal author** (followers ≤ `celebrity-threshold`): **fan-out-on-write**. On each post,
  insert a `timeline_entries` row for every follower. Reads are then a cheap index scan.
- **Celebrity** (followers > threshold): **no fan-out**. The author is recorded in
  `celebrities`; their followers **pull** the posts on read.
- **Home feed read** = the reader's materialized timeline (normal authors) **merged** with a
  pull of recent posts from the celebrities they follow, sorted by time.

The threshold is `app.feed.celebrity-threshold` (env `CELEBRITY_THRESHOLD`), deliberately low
(20) so the ~80-person demo dataset exercises both paths. See `EDGE_CASES.md`
("celebrity fan-out").

## Run it

```bash
./mvnw spring-boot:run            # embedded H2, http://localhost:8083
docker compose up --build         # Postgres + the service
./mvnw test                       # H2, Graph mocked
```

## API

All endpoints require `Authorization: Bearer <token>`.

| Method | Path                       | Description                                   |
|--------|----------------------------|-----------------------------------------------|
| POST   | `/v1/posts`                | Create a post (`{ "content": "..." }`) → 201  |
| GET    | `/v1/posts/{userId}`       | A user's own posts (author timeline)          |
| GET    | `/v1/feed?cursor=&limit=`  | The caller's home timeline (hybrid fan-out)    |

Lists are cursor-paginated; `nextCursor` is an epoch-millis timestamp, null on the last page.
Observability: `GET /actuator/health`, `GET /actuator/prometheus`.

## Design notes

- **Timelines store references, not copies.** A `timeline_entries` row holds `post_id`, and
  the content is resolved from `posts` at read time — so editing or deleting a post never
  leaves stale fan-out copies (`EDGE_CASES.md`: mutating content after fan-out).
- **Graceful degradation.** If Graph is unreachable, follower count reads as 0 and lists as
  empty: a post still succeeds (just no fan-out that moment) and a feed read still returns
  the materialized portion. No crash.
- **Known simplifications (next steps):** no backfill when you follow someone (their older
  posts don't retroactively appear); the merged-feed cursor keys on timestamp only (ties at
  the same millisecond could shift across pages); fan-out is synchronous (a real system would
  push events through Kafka — `EDGE_CASES.md`: at-least-once + idempotent consumers).

## Layout

```
src/main/java/com/vertex/feed/
├── client/      GraphClient (follower/following lists for fan-out)
├── config/      JwtProperties, SecurityConfig
├── domain/      Post, TimelineEntry, Celebrity
├── repository/  Post/TimelineEntry/Celebrity repositories
├── security/    JwtService (verify-only), JwtAuthenticationFilter
├── service/     FeedService (hybrid fan-out)
├── web/         FeedController, DTOs, error handling
src/main/resources/
├── application.yml
└── db/migration/V1__init_feed.sql
```
