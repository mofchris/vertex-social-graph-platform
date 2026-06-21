# Notify Service

The fifth microservice of the Vertex platform: **notifications** with **coalescing** and
**real-time delivery** over Server-Sent Events.

Built with **Spring Boot 4.1 (Java 21)**, Spring Data JPA, Flyway, Spring Security
(resource-server style), Spring MVC SSE, and Micrometer/Prometheus. Listens on **port 8084**.

## How it relates to the other services

Notify verifies Identity-issued JWTs (shared `APP_JWT_SECRET`). It owns its own database.
It ingests events two ways:

- **`POST /v1/events`** — a synchronous HTTP hook, the acting user being the caller. Always on;
  the default JDK-only run and the demo seed use this.
- **Kafka `social.events`** — under the `kafka` profile, Notify consumes the events the Graph
  service publishes (its transactional outbox → Kafka). This is the asynchronous, decoupled path
  a real deployment uses; see below.

## Coalescing — the core idea

A naive system inserts one notification row per event, so 1,000 likes become 1,000 rows and
1,000 buzzes. Notify **coalesces**: while a notification is unread, every further event of the
same **type** about the same **target** for the same recipient folds into the existing row —
incrementing `actorCount` and updating `latestActorId` — instead of creating a new row. The
client renders "**X and N others** liked your post". See `EDGE_CASES.md`: notification storms.

- Grouping key: `type + ":" + (targetId or "self")`.
- Coalescing window: while unread. Once read, the next event starts a fresh notification.
- Done as find-or-create in a transaction. (Production would add a Postgres partial UNIQUE
  index `WHERE read = FALSE` for race safety; H2 has no partial indexes, so it's app-level here.)

## Event ingestion over Kafka (the `kafka` profile)

Kafka is **at-least-once**: a consumer crash between handling a record and committing its offset
makes the record redeliver, and a rebalance can replay a partition. So the consumer is built to be
**idempotent** and **poison-pill-proof** (see `EDGE_CASES.md`):

- **Idempotent consumer** — every event carries a unique `eventId`. The consumer records it in a
  `processed_events` ledger **in the same transaction** as the notification it produces; a
  redelivered event finds its id already there and is skipped, so 1,000 redeliveries never become
  1,000 notifications or double-bump a coalesced count.
- **Dead-letter queue** — a record the consumer can't parse is left to throw; a `DefaultErrorHandler`
  retries twice for a transient fault, then republishes it to `social.events.DLT` instead of
  crash-looping the partition and starving every other recipient. A record whose *type* we don't
  model yet is dropped quietly (forward-compatible), not dead-lettered.
- **Bounded ledger** — a scheduled purge drops ledger rows past a retention window longer than any
  realistic redelivery (default 7 days), so the dedupe table can't grow without bound.

Off by default: the consumer, DLQ handler, and purge are all `@Profile("kafka")`. Activate with
`SPRING_PROFILES_ACTIVE=kafka` (or `postgres,kafka`). The repo-root `docker-compose.yml` boots
Graph → Kafka → Notify end-to-end.

## Real-time delivery

`GET /v1/notifications/stream` returns a **Server-Sent Events** stream. When an event is
ingested for a user, the resulting (coalesced) notification is pushed to their open streams
immediately. Dead streams are removed on completion/timeout/error, so reconnects don't leak
emitters. Single-node here; a multi-instance deployment would fan out through a shared pub/sub.

## Run it

```bash
./mvnw spring-boot:run            # embedded H2, http://localhost:8084
docker compose up --build         # Postgres + the service
./mvnw test
```

## API

All endpoints require `Authorization: Bearer <token>`.

| Method | Path                              | Description                                        |
|--------|-----------------------------------|----------------------------------------------------|
| POST   | `/v1/events`                      | Report an event → coalesced notification (202)     |
| GET    | `/v1/notifications?cursor=&limit=`| The caller's notifications, newest first           |
| GET    | `/v1/notifications/unread-count`  | `{ "unread": N }`                                  |
| POST   | `/v1/notifications/read`          | Mark all the caller's notifications read (204)     |
| GET    | `/v1/notifications/stream`        | Real-time SSE stream of the caller's notifications |

`POST /v1/events` body: `{ "recipientId": "...", "type": "FOLLOW", "targetId": "..." }`.
Types: `FOLLOW`, `FRIEND_REQUEST`, `FRIEND_ACCEPT`, `POST_LIKE`, `MENTION`. `targetId` is
optional (null = "about you"). Observability: `GET /actuator/health`, `/actuator/prometheus`.

### Example

```bash
# In one terminal: subscribe to the live stream
curl -N localhost:8084/v1/notifications/stream -H "Authorization: Bearer <recipient-token>"

# In another: 3 people follow the recipient -> one coalesced notification, actorCount 3
for i in 1 2 3; do
  curl -s -X POST localhost:8084/v1/events -H "Authorization: Bearer <actor$i-token>" \
    -H 'Content-Type: application/json' \
    -d '{"recipientId":"<recipientId>","type":"FOLLOW"}'
done
curl -s localhost:8084/v1/notifications -H "Authorization: Bearer <recipient-token>"
```

## Layout

```
src/main/java/com/vertex/notify/
├── config/      JwtProperties, SecurityConfig
├── domain/      Notification, NotificationType
├── repository/  NotificationRepository
├── security/    JwtService (verify-only), JwtAuthenticationFilter
├── service/     NotificationService (coalescing), SseEmitters (real-time)
├── web/         NotificationController, DTOs, error handling
src/main/resources/
├── application.yml
└── db/migration/V1__init_notify.sql
```
