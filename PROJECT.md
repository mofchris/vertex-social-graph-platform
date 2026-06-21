# Distributed Social Graph & Identity Platform

A scalable backend platform that manages user identities, relationships, and real-time
social interactions for millions of users. Built as a microservices system to demonstrate
distributed-systems design, caching strategy, cloud-native deployment, and operating
services reliably at scale.

---

## Goal

Build a production-grade, horizontally scalable backend that:

- Manages user **identity** (accounts, authentication, profiles).
- Manages the **social graph** (friend/follow relationships, mutuals, recommendations).
- Processes **real-time social interactions** with low latency.
- Stays **highly available** and **observable** under heavy, bursty traffic.

---

## Tech Stack

### Languages & Frameworks
- **Java (Spring Boot)** — primary backend services (identity, graph).
- **Python (FastAPI)** — supporting services (recommendations, async workers, tooling).
- **gRPC** for internal service-to-service calls; **REST** for public/client-facing APIs.

### Data & Caching
- **PostgreSQL** — source of truth for users, profiles, and relationships (sharded by user ID).
- **Redis** — caching (hot profiles, session tokens), rate limiting, and pub/sub for fan-out.
- **Kafka** — event streaming / async pipeline (relationship events, activity feed, audit log).

### Infrastructure & Ops
- **Docker** — containerized services.
- **Kubernetes** — orchestration, autoscaling (HPA), rolling deploys, self-healing.
- **AWS / GCP** — managed Postgres (RDS / Cloud SQL), managed Redis, object storage, load balancers.
- **Terraform** — infrastructure as code.
- **GitHub Actions** — CI/CD (build, test, image push, deploy).

### Observability
- **Prometheus** — metrics collection.
- **Grafana** — dashboards.
- **OpenTelemetry / Jaeger** — distributed tracing across services.
- **Loki / ELK** — centralized logging.
- **Alertmanager / PagerDuty-style alerts** — failure and degradation detection.

### Security
- **JWT** access tokens + **refresh token** rotation.
- **OAuth2 / OIDC** support.
- **bcrypt / Argon2** password hashing.
- API gateway with **rate limiting** and **request validation**.

---

## Architecture

Client → API Gateway → Microservices → (PostgreSQL / Redis / Kafka)

### Services

| Service | Responsibility |
|---|---|
| **API Gateway** | Single entry point, auth verification, routing, rate limiting. |
| **Identity Service** | Signup, login, token issue/refresh, password reset. |
| **Profile Service** | User profile data, settings, media references. |
| **Graph Service** | Friend/follow edges, mutual lookups, blocklists. |
| **Feed/Activity Service** | Fan-out of social events, activity timeline. |
| **Recommendation Service** | "People you may know" via graph traversal. |
| **Notification Service** | Real-time + async notifications. |

Each service owns its data, scales independently, and communicates via gRPC (sync) or
Kafka (async events).

---

## Features

### Identity & Authentication
- [ ] User signup, login, logout.
- [ ] JWT access tokens with short TTL + refresh token rotation.
- [ ] Password hashing (Argon2/bcrypt).
- [ ] OAuth2 / social login (optional).
- [ ] Session management and token revocation via Redis.
- [x] Rate limiting on auth endpoints (brute-force protection) — gateway token bucket, per-IP + per-user.

### Profiles
- [ ] Create / read / update user profile.
- [ ] Profile privacy settings (public / friends-only / private).
- [ ] Hot-profile caching in Redis (read-through, TTL + invalidation on write).

### Social Graph
- [ ] Send / accept / reject friend requests.
- [ ] Follow / unfollow.
- [ ] Block / unblock (enforced across reads).
- [ ] Mutual-friends lookup.
- [ ] Friend/follower counts (cached, eventually consistent).
- [ ] Graph sharding strategy for millions of edges.

### Real-Time Interactions
- [ ] Activity feed fan-out via Kafka.
- [x] Real-time notifications (new request, new follower) — SSE + Kafka-driven, idempotent.
- [ ] Online/presence status (Redis).

### Recommendations
- [ ] "People you may know" via friends-of-friends graph traversal.
- [ ] Caching of recommendation results.

### Reliability & Scale
- [ ] Horizontal autoscaling on Kubernetes (HPA on CPU/req rate).
- [ ] Read replicas for PostgreSQL.
- [ ] Circuit breakers + retries with backoff between services.
- [ ] Graceful degradation (serve cached data if a downstream is down).
- [x] Idempotent writes for safe retries — transactional outbox (Graph) + dedupe ledger (Notify).
- [ ] Health checks + liveness/readiness probes.

### Observability & Ops
- [ ] Metrics (latency, error rate, throughput) per service → Prometheus.
- [ ] Grafana dashboards.
- [ ] Distributed tracing across requests (OpenTelemetry).
- [ ] Centralized structured logging.
- [ ] Alerts on error-rate / latency / saturation (failure + degradation detection).
- [ ] Load testing (k6 / Locust) with documented results.

### API
- [ ] Versioned REST API (`/v1/...`).
- [ ] OpenAPI / Swagger documentation.
- [ ] Pagination on list endpoints.
- [ ] Consistent error format.

---

## Why It Fits the Snap Backend Role

This project directly demonstrates the competencies Snap lists for backend engineering:

- **Distributed systems** — independent services, sharding, async event pipelines.
- **Scalable backend services** — stateless services, horizontal autoscaling, caching.
- **Microservices architecture** — clear service boundaries, gRPC + REST, per-service data.
- **Cloud infrastructure** — Kubernetes, Docker, AWS/GCP, Terraform, CI/CD.
- **Caching strategies** — Redis for hot data, sessions, rate limiting, and fan-out.
- **Production debugging** — tracing, metrics, logs, alerting to find and fix degradation.
- **System reliability** — circuit breakers, retries, graceful degradation, HA design.
- **Operating at scale** — load testing, autoscaling, read replicas, monitoring.

---

## Suggested Build Order (MVP → Scale)

1. Identity Service (signup/login/JWT) + PostgreSQL.
2. Profile Service + Redis caching.
3. Graph Service (friend/follow edges).
4. API Gateway + REST API + OpenAPI docs.
5. Dockerize all services; docker-compose for local dev.
6. Kafka + Feed/Notification services.
7. Recommendation Service.
8. Kubernetes manifests + Terraform + CI/CD.
9. Observability stack (Prometheus, Grafana, tracing).
10. Load testing + reliability hardening.
