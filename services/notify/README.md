# Notify Service

The fifth microservice of the Vertex platform: **notifications** with **coalescing** and
**real-time delivery** over Server-Sent Events.

Built with **Spring Boot 4.1 (Java 21)**, Spring Data JPA, Flyway, Spring Security
(resource-server style), Spring MVC SSE, and Micrometer/Prometheus. Listens on **port 8084**.

## How it relates to the other services

Notify verifies Identity-issued JWTs (shared `APP_JWT_SECRET`). It owns its own database.
Other services (or, in production, a Kafka consumer) report events to `POST /v1/events`; the
acting user is the caller. The demo seed drives this endpoint directly.

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
