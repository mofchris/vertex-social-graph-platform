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

## Run it

```bash
./mvnw spring-boot:run     # http://localhost:8088 (expects the services on 8080-8085)
./mvnw test
```

Observability: `GET /actuator/health`, `GET /actuator/prometheus`.

## Layout

```
src/main/java/com/vertex/gateway/
├── config/    GatewayProperties (routes), ProxyConfig (HTTP client), JwtProperties, SecurityConfig
├── security/  JwtService (verify-only), JwtAuthenticationFilter
├── web/       ProxyController (reverse proxy)
src/main/resources/
└── application.yml
```
