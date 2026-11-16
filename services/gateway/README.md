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

Token buckets (burst up to a capacity, then paced to a refill rate) run at the very front of the
chain, before authentication, so they also shield the public sign-in endpoints. Buckets checked:

| Bucket | Key | Default |
|---|---|---|
| auth | client IP, `/api/identity/v1/auth/**` only | 10, then 1 every 5 s |
| general | client IP, always | 100, then 50/s |
| general | user, when the token is valid | 100, then 50/s |
| lab | user, `POST /api/graph/v1/events/**` (replay, poison) | 6, then 3 a minute |
| writes | user, POST/PUT/PATCH/DELETE | 30, then 1 every 2 s |

Any empty bucket returns `429` with `Retry-After`; allowed responses carry
`X-RateLimit-Remaining` (the tightest bucket that applied). Each decision is one **atomic Redis Lua
script** (`scripts/token_bucket.lua`) that reads the Redis server clock, so instances with skewed
clocks agree. If Redis is unreachable the limiter degrades to a per-instance in-memory bucket
rather than failing requests. Off-Redis by default; the distributed path runs under the `redis`
profile. All limits are `app.ratelimit.*` / environment overridable.

**Client IP.** Behind a tunnel every request arrives from the same peer, so per-IP limits would be
shared by everyone. `app.gateway.client-ip-header` (env `CLIENT_IP_HEADER`) names a header set by a
trusted edge; production uses `X-Vertex-Client-IP`, set by the Cloudflare Worker. Values that are
not IP literals are ignored.

## Streaming, status and timing

- Responses are streamed, not buffered, so Notify's Server-Sent Events pass through; requests that
  accept `text/event-stream` use a client with no read timeout. Virtual threads keep long-lived
  streams cheap.
- `GET /api/status` (public) asks every routed service for its own health and reports status and
  latency per service, cached for 5 s and computed single-flight.
- Every proxied response carries `Server-Timing: gateway;dur=…, upstream;desc="<service>";dur=…`.
- `app.gateway.blocked-paths` are refused with 404 before forwarding: each service's actuator and
  Notify's raw `POST /v1/events`.

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
