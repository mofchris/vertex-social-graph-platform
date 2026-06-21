# Graph Service

The third microservice of the Vertex platform and the heart of it: the **social graph**.
Owns **follows**, **friendships** (with a request lifecycle), and **blocks**, plus
relationship lookups, counts, and a cursor-paginated friends list.

Built with **Spring Boot 4.1 (Java 21)**, Spring Data JPA, Flyway, Spring Security
(resource-server style), and Micrometer/Prometheus. Listens on **port 8082**.

## How it relates to the other services

Like Profile, Graph is a **resource server**: it verifies Identity-issued JWTs (shared
`APP_JWT_SECRET`, same issuer) and acts as the authenticated user (`sub`). Every node id is
an Identity user id. It owns its own database. Profile's `FRIENDS` visibility, Feed's fan-out,
and Recommend's traversal all call this service. When a relationship changes, Graph also emits
a social event (see below) that Notify turns into a notification.

## Event publishing (Kafka, transactional outbox)

A follow / friend-request / friend-accept is worth telling someone about. Under the `kafka`
profile, Graph publishes those as `social.events` to Kafka, where the Notify service consumes
them. The wiring is the **transactional outbox** pattern, the textbook fix for the
"partial-failure multi-service write" in [`EDGE_CASES.md`](../../EDGE_CASES.md):

- The service publishes an in-process `SocialEvent`. A `@TransactionalEventListener(BEFORE_COMMIT)`
  stages it as an `outbox_events` row **in the same transaction as the edge**, so the edge and
  the pending message commit or roll back together — no lost-event window, no phantom event.
- A `@Scheduled` **relay** drains unpublished rows to Kafka oldest-first and stamps each
  published once the broker acks (`acks=all`). A broker blip just leaves the row for the next
  tick; delivery is **at-least-once**, and the consumer dedupes on the event id.
- Off by default: with no profile (just a JDK) the listener and relay don't exist, so there's
  no broker dependency. Activate with `SPRING_PROFILES_ACTIVE=kafka` (or `postgres,kafka`).

The whole stack — Graph → Kafka → Notify — boots from the repo-root `docker-compose.yml`.

## Run it

```bash
# Embedded H2 (just a JDK):
./mvnw spring-boot:run            # → http://localhost:8082

# Postgres via Docker:
docker compose up --build
```

```bash
./mvnw test
```

Tests run against embedded H2 (no Docker) and cover the edge cases below.

## API

All endpoints require `Authorization: Bearer <token>` and act as the token's subject.
Mutations return `204 No Content`.

| Method | Path                            | Description                                        |
|--------|---------------------------------|----------------------------------------------------|
| POST   | `/v1/follow/{userId}`           | Follow a user (idempotent)                         |
| DELETE | `/v1/follow/{userId}`           | Unfollow                                           |
| POST   | `/v1/friends/{userId}/request`  | Send a friend request (auto-accepts if crossing)   |
| POST   | `/v1/friends/{userId}/accept`   | Accept an incoming request                         |
| DELETE | `/v1/friends/{userId}`          | Cancel / reject a request, or unfriend             |
| POST   | `/v1/block/{userId}`            | Block a user (drops friendship + follows)          |
| DELETE | `/v1/block/{userId}`            | Unblock                                            |
| GET    | `/v1/relationship/{userId}`     | Your relationship to a user (see below)            |
| GET    | `/v1/counts/{userId}`           | `{ followers, following, friends }`                |
| GET    | `/v1/friends?cursor=&limit=`    | Your friends, cursor-paginated (default limit 20)  |

`GET /v1/relationship/{userId}` returns:

```json
{
  "userId": "...",
  "following": true,
  "followedBy": false,
  "friendStatus": "FRIENDS",            // NONE | PENDING_OUTGOING | PENDING_INCOMING | FRIENDS
  "blocking": false,
  "blockedBy": false
}
```

Observability: `GET /actuator/health`, `GET /actuator/prometheus`.

### Example

```bash
A="Bearer <alice-token>"; B="Bearer <bob-token>"

curl -s -X POST localhost:8082/v1/follow/<bobId>        -H "$A"   # Alice follows Bob
curl -s -X POST localhost:8082/v1/friends/<bobId>/request -H "$A" # Alice -> Bob friend request
curl -s -X POST localhost:8082/v1/friends/<aliceId>/accept -H "$B" # Bob accepts -> friends
curl -s localhost:8082/v1/relationship/<bobId>          -H "$A"   # { friendStatus: "FRIENDS", ... }
curl -s localhost:8082/v1/counts/<bobId>                -H "$A"
curl -s -X POST localhost:8082/v1/block/<bobId>         -H "$A"   # block drops the friendship + follows
```

## Data model

| Table         | Shape                                                                 |
|---------------|-----------------------------------------------------------------------|
| `follows`     | directed `(follower_id, followee_id)`, unique — duplicate = no-op      |
| `friendships` | one row per **unordered pair** (`pair_key = "minId:maxId"`, unique), with a directed `requester/addressee` and `status` PENDING/ACCEPTED |
| `blocks`      | directed `(blocker_id, blocked_id)`, unique                           |

The single-row-per-pair friendship design is what lets two crossing requests collapse into
one friendship instead of creating two competing edges.

## Edge cases handled

These map directly to the repo's [`EDGE_CASES.md`](../../EDGE_CASES.md):

- **Self-edges** — following/friending/blocking yourself is rejected (400).
- **Duplicate follow / request** — idempotent; the unique constraint wins concurrent races.
- **Crossing friend requests** — A→B while B→A collapses into an accepted friendship.
- **Accept rules** — only the addressee can accept; accepting your own request is 409;
  accepting an already-accepted request is a no-op.
- **Block wins** — blocking removes any existing friendship and follows in both directions,
  and prevents new ones.
- **Silent block failures** — actions against someone in a block relationship return **404**
  (`user not available`), never revealing that a block exists.
- **Cursor (keyset) pagination** — the friends list pages by a stable key, so a mutating
  list never skips or duplicates rows the way offset pagination would.

### Known simplifications (next steps)

- **Counts** are computed with `COUNT` queries (always correct). At scale these would be
  denormalized counters kept eventually-consistent via a reconciliation job — see
  `EDGE_CASES.md` ("count drift").
- **Cross-shard symmetry** isn't modelled (single DB); the `pair_key` design is the
  single-node analogue of picking a canonical owner for an edge.

## Layout

```
src/main/java/com/vertex/graph/
├── config/      JwtProperties, SecurityConfig
├── domain/      Follow, Friendship, Block, FriendshipStatus
├── repository/  Follow/Friendship/Block repositories
├── security/    JwtService (verify-only), JwtAuthenticationFilter
├── service/     GraphService (all relationship logic)
├── web/         GraphController, DTOs, error handling
src/main/resources/
├── application.yml
└── db/migration/V1__init_graph.sql
```
