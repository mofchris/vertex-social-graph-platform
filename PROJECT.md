# Vertex: design notes

Vertex is a social platform split into services the way a large one would be: identity, profiles,
the social graph, feeds, notifications and recommendations behind one gateway. It is **designed
for millions of users** (that is the design target the boundaries, data ownership and event
pipeline are chosen for); it is **demonstrated on one small server**, and nothing here claims a
measured scale it hasn't reached.

## Goals

- Clear service boundaries, each service owning its data and schema.
- Correct behaviour under the failures that matter: duplicate delivery, poison messages, a broker
  that is down, a cache that is shared, a token that is replayed.
- Every interesting mechanism observable from outside, so it can be shown rather than described
  (the live demo traces real events through the system).
- Runs anywhere with one command, and costs nothing to keep online.

## Stack (what is actually in the repo)

| Layer | Choice |
|---|---|
| Services | Java 21, Spring Boot 4.1 (Spring MVC), seven services |
| Service-to-service | REST over HTTP with the caller's token propagated; Kafka for events |
| Data | PostgreSQL 16 (one database per service, migrated by Flyway), Redis 7, Apache Kafka 3.8 (KRaft) |
| Edge | A hand-written gateway: routing, JWT check, token-bucket rate limits in Redis Lua |
| Auth | HS256 JWT access tokens (15 min), rotating refresh tokens with reuse detection, bcrypt |
| Observability | Spring Boot Actuator health and Prometheus metrics endpoints on every service; W3C Server-Timing from the gateway |
| Tests | JUnit 5, MockMvc, Testcontainers (Postgres, Redis, Kafka) |
| Web | Next.js 16 static export, TypeScript, GSAP; served by a Cloudflare Worker |
| Runtime | Docker Compose; production on one PC under WSL 2 behind a Cloudflare Tunnel, with a recorded real run as the offline fallback |
| CI | GitHub Actions: every service's tests, the web build and lint |

## Services

| Service | Owns | Notes |
|---|---|---|
| Gateway | routing, edge auth, rate limits | per-IP, per-user, sign-in, write and lab buckets; streams SSE; `/api/status` |
| Identity | users, credentials, refresh tokens | rotation with family revocation on reuse |
| Profile | profiles, privacy | Redis read-through cache, visibility applied at serve time, batch reads |
| Graph | follows, friendships, blocks | transactional outbox to Kafka; popular users; event trace endpoints |
| Feed | posts, timelines | hybrid fan-out: push on write, pull for accounts over 20 followers |
| Notify | notifications | idempotent Kafka consumer, dead-letter topic, coalescing, SSE |
| Recommend | suggestions | friends of friends with supernode caps, popular fallback for new users |

## Built

- Signup, login, logout, refresh rotation, reuse detection.
- Profile CRUD, privacy levels (public, friends, private), cache with invalidation on write.
- Follow, unfollow, friend requests (accept, crossing requests auto-accept), unfriend, block, unblock, counts,
  cursor-paginated lists.
- Posts and home feed with hybrid fan-out.
- Notifications from Kafka events: exactly one per event id, coalesced per type, pushed live.
- People you may know.
- Gateway with routing, edge auth, rate limiting, streaming and a public status endpoint.
- Versioned REST API (`/v1`), consistent error format, pagination on list endpoints.
- Health checks, restart policies, memory limits, log rotation, nightly demo reset.

## Not built (honest roadmap)

- Kubernetes manifests, autoscaling and Terraform: the demo runs on Docker Compose on one machine.
- Sharding and read replicas: one Postgres instance with a database per service.
- Grafana dashboards, distributed tracing and alerting: metrics are exposed, not collected.
- OAuth / social login, password reset, presence.
- A load test with published results.

## What this project demonstrates

- **Distributed systems:** independent services, an asynchronous event pipeline, and the failure
  handling it needs (outbox, idempotency, dead letters).
- **Scalable design:** stateless services, caching at the right layer, fan-out chosen per author,
  bounded graph traversal.
- **Security at the edge:** token verification, refresh rotation, rate limits keyed on the real
  client, nothing exposed but one tunnel.
- **Operating it:** health-gated startup, self-restart, resource limits that fit a free server,
  and a reset that keeps a public demo clean.

The [edge cases](./EDGE_CASES.md) document lists the failure modes, and which of them the code
handles today.
