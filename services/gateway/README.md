# Gateway Service

The single front door to the Vertex platform: one entry point that **routes** to every backend
service, verifies auth at the edge, and enforces **rate limits** before traffic reaches a service.

Built with **Spring Boot 4.1 (Java 21)**, Spring MVC, and Micrometer/Prometheus. Listens on
**port 8088**. Hand-rolled rather than Spring Cloud Gateway — the platform runs on plain Spring
Boot, and owning the proxy keeps routing, edge auth, and limiting in one place with no
release-train coupling to a Spring Cloud version.

## Routing

A request to `/api/<service>/<rest>` is forwarded to the downstream base URL configured for
`<service>`, preserving method, path, query, headers, and body; the downstream response is
streamed back unchanged. Hop-by-hop headers (`Connection`, `Transfer-Encoding`, …) are stripped on
both legs. The route table is `app.gateway.routes` (env-overridable):

| Prefix | Downstream |
|--------|------------|
| `/api/identity/**`  | Identity (8080) |
| `/api/profile/**`   | Profile (8081)  |
| `/api/graph/**`     | Graph (8082)    |
| `/api/feed/**`      | Feed (8083)     |
| `/api/notify/**`    | Notify (8084)   |
| `/api/recommend/**` | Recommend (8085)|

Example: `GET /api/graph/v1/relationship/{id}` → `http://graph:8082/v1/relationship/{id}`.

An unknown service is `404`; an unreachable downstream is `502` (the gateway is healthy, the
upstream isn't) with a bounded connect/read timeout so a dead service can't pin a gateway thread.

## Edge authentication

The gateway verifies the Identity-issued JWT (shared HS256 secret, issuer check) **at the edge**:
an invalid or missing token is rejected with `401` before any downstream call is made. The
original `Authorization` header is then forwarded, and each service re-verifies it independently
(defense in depth — the gateway is an optimization, not the only line of defense).

Public (no token required): the auth endpoints `/api/identity/v1/auth/**`
(signup / login / refresh / logout — you can't have a token before you log in) and the health /
metrics probes. Everything else requires a valid token.

## Rate limiting

A **token bucket** (burst up to `capacity`, then paced to `refill-per-second`) runs at the very
front of the chain — *before* authentication — so it also shields the public login/signup endpoints
from brute force. Two buckets are checked (EDGE_CASES.md: combine per-account and per-IP):

- **per-IP** always — bounds anonymous traffic; and
- **per-user** when the request carries a valid token — so one heavy account can't drain a shared
  NAT/CGNAT's IP budget for everyone behind it, and is still bounded on its own.

Either bucket being empty returns `429 Too Many Requests` with a `Retry-After`. The decision is an
**atomic Redis Lua script** (`scripts/token_bucket.lua`), so the read-modify-write can't race across
gateway instances and there's no fixed-window boundary burst. If **Redis is unreachable** the
limiter degrades to a per-instance in-memory bucket rather than failing requests — a limiter-store
outage stays an outage of *sharing*, not of the gateway (EDGE_CASES.md: "limiter store down").

Off-Redis by default (in-process limiter, no dependency); the distributed path activates under the
`redis` profile (`SPRING_PROFILES_ACTIVE=redis`). Tunable via `app.ratelimit.{enabled,capacity,
refill-per-second}`.

## Run it

```bash
./mvnw spring-boot:run     # http://localhost:8088 (expects the services on 8080-8085)
./mvnw test
```

Observability: `GET /actuator/health`, `GET /actuator/prometheus`.

## Layout

```
src/main/java/com/vertex/gateway/
├── config/     GatewayProperties, ProxyConfig, JwtProperties, SecurityConfig,
│               RateLimitProperties, RateLimitConfig
├── ratelimit/  RateLimiter, LocalRateLimiter, RedisRateLimiter (atomic Lua token bucket)
├── security/   JwtService (verify-only), JwtAuthenticationFilter
├── web/        ProxyController (reverse proxy), RateLimitFilter
src/main/resources/
├── application.yml
└── scripts/token_bucket.lua
```
