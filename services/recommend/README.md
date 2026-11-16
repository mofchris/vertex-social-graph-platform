# Recommend Service

The sixth microservice of the Vertex platform: **"people you may know"** via
friends-of-friends graph traversal.

Built with **Spring Boot 4.1 (Java 21)**, Spring Security (resource-server style), and
Micrometer/Prometheus. It is **stateless — no database**: it computes recommendations live
from the Graph service. Listens on **port 8085**.

## How it works

For the requesting user U:

1. Fetch U's friends from Graph.
2. Expand each friend to *their* friends (friends-of-friends).
3. Count mutual friends per candidate; drop U and U's existing friends.
4. Rank by mutual-friend count.
5. Confirm eligibility for the top candidates (not already a friend, not in a block
   relationship) and keep the first `limit`.
6. **Cold-start fallback:** if that gives fewer than `limit` (e.g. a brand-new user with no
   friends), top up from Graph's most-followed users (`GET /v1/popular`, which already
   excludes the caller and blocks). Anyone already suggested, already a friend, the caller,
   or rejected in step 5 is skipped locally; the rest must pass a relationship check (not
   followed, no friend request either way, no block) before being suggested.

### Supernode safety (the headline edge case)

Friends-of-friends from a high-degree node explodes combinatorially. Recommend bounds the
work with two caps (`app.recommend.*`):

- `max-seed-friends` — expand at most this many of U's friends.
- `max-sample-per-friend` — sample at most this many friends of each (one page, not the
  whole list).

So a celebrity in the traversal contributes a bounded sample instead of millions of edges.
See `EDGE_CASES.md`: celebrity/supernode traversal, and leaking ineligible users.

## Run it

```bash
./mvnw spring-boot:run            # http://localhost:8085 (needs Graph reachable)
docker compose up --build
./mvnw test                       # Graph mocked (plus a local stub server for the client)
```

## API

| Method | Path                          | Description                                  |
|--------|-------------------------------|----------------------------------------------|
| GET    | `/v1/recommendations?limit=`  | People you may know, ranked by mutual friends, topped up with popular users |

Requires `Authorization: Bearer <token>`. Response:

```json
{ "items": [
    { "userId": "...", "mutualFriends": 4, "reason": "MUTUAL_FRIENDS" },
    { "userId": "...", "mutualFriends": 0, "reason": "POPULAR" }
] }
```

`reason` says why someone is suggested so the UI can be honest about it: `MUTUAL_FRIENDS`
(friends-of-friends, ranked first) or `POPULAR` (cold-start fallback, in Graph's
most-followed order, always `mutualFriends: 0`).

Observability: `GET /actuator/health`, `GET /actuator/prometheus`.

## Design notes

- **Eligibility at result time.** Already-friends are dropped during traversal; blocks (either
  direction) and any missed friendships are filtered by a Graph `relationship` check on the
  top candidates only — keeping the call count bounded.
- **Graceful degradation.** Graph errors yield empty lists, and an unverifiable candidate is
  treated as ineligible (fail closed) rather than recommended.
- **Cold start.** A user with no friends has nothing to traverse, so the popular fallback
  fills the list. `app.recommend.popular-fetch-limit` (default 50) is how many popular users
  are fetched; the headroom covers ones filtered out locally.
- **Best-effort fallback.** If `/v1/popular` (or a check during the fallback) fails, a
  warning is logged and the friends-of-friends results are returned as they are. The
  fallback never turns into a 500.
- **Precompute (next step).** Live FoF is fine at demo scale; at real scale these would be
  precomputed for hot users and cached.

## Layout

```
src/main/java/com/vertex/recommend/
├── client/      GraphClient (friends, eligibility, popular users)
├── config/      JwtProperties, SecurityConfig
├── security/    JwtService (verify-only), JwtAuthenticationFilter
├── service/     RecommendationService (friends-of-friends)
├── web/         RecommendationController, DTOs
└── resources/   application.yml
```
