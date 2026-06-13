# Edge Cases — Distributed Social Graph & Identity Platform

A reference of the edge cases worth handling (and being able to talk about) for this
project, grouped by service. Each entry has **what goes wrong** and a one-line
**how you'd handle it**.

> You do not need to implement all of these. Pick a handful, handle them properly,
> and be ready to explain them. A few done well beats every box checked.

## Highest-signal in interviews

If you only prep a few, prep these — they're the ones a backend interviewer will dig into:

1. **Celebrity / supernode** fan-out and hot shards.
2. **Symmetric relationships across shards** (where does a friendship/block edge live).
3. **Read replica lag** breaking read-your-own-writes.
4. **At-least-once delivery + idempotent consumers** (Kafka redelivers).
5. **Account deletion** across a denormalized, event-driven system (GDPR / Kafka retention).
6. **Refresh-token rotation race** (legitimate retry vs. token replay).
7. **Event ordering** (rapid unfollow → refollow).
8. **Zero-downtime schema migrations** during rolling deploys.

---

## Identity & Authentication

- **Refresh-token rotation race** — Client uses a refresh token, you rotate it, but the
  client's network drops before it receives the new one; its retry with the old token now
  looks like a stolen-token replay. *Handle:* short grace window or reuse-detection that
  distinguishes a retry from an actual replay, so you don't lock real users out.
- **Revocation vs. stateless JWT** — A revoked access token stays valid until its TTL
  unless every request checks a denylist. *Handle:* short access-token TTL + Redis denylist;
  decide explicitly whether to fail open (insecure) or closed (auth outage) when the denylist
  store is down.
- **Signup race on duplicate email/username** — Two concurrent signups for the same
  identifier both pass an app-level check. *Handle:* enforce uniqueness with a DB constraint,
  not application logic.
- **Account deletion across the system** — A user's data lives in Postgres, Redis caches,
  millions of fanned-out feed entries, recommendation caches, and the append-only Kafka log
  (which retains PII for days). *Handle:* immutable user IDs + soft-delete + tombstone events;
  lazy cleanup of denormalized copies; a documented retention/erasure story for the Kafka log.
- **Username recycling** — User A deletes and frees a username; user B takes it; stale
  references now attribute old data to the wrong person. *Handle:* key everything on an
  immutable internal user ID; treat usernames as mutable labels, optionally with a cool-down.
- **OAuth ↔ existing account collision** — Someone signs up by email, later "Sign in with
  Google" using the same email — two accounts for one person. *Handle:* link identities on a
  verified email; define an account-merge or block-duplicate policy.
- **Email-change race** — Email is changed while old reset/verification tokens are still
  valid. *Handle:* invalidate all outstanding tokens on email change; re-verify the new one.
- **Unicode / homoglyph impersonation** — Usernames using look-alike characters (Cyrillic
  "а"), zero-width characters, or differing normalization forms (NFC vs NFD) impersonate
  others or collide. *Handle:* Unicode-normalize on input, reject confusable/zero-width
  characters, compare on normalized form.
- **Clock skew on token validation** — Node clocks drift, so `exp`/`nbf` checks misbehave
  across services. *Handle:* sync clocks (NTP), allow a small leeway, keep TTLs not too tight.

---

## Profiles & Caching

- **Invalidation ordering / stale repopulation** — With a read-through cache, a concurrent
  read can repopulate the cache with the *old* DB value in the window between a write and the
  cache delete, leaving stale data cached indefinitely. *Handle:* short TTL as a backstop,
  versioned/CAS writes, or write-through with care about ordering.
- **Cache stampede** — A hot profile's entry expires and thousands of requests miss at once,
  hammering Postgres. *Handle:* request coalescing (single-flight) and/or lock-and-refresh,
  plus jittered TTLs.
- **Cache penetration** — Requests for IDs that don't exist (random or malicious) miss every
  time and hit the DB. *Handle:* negative caching of "not found" — and invalidate that entry
  the moment the user actually signs up.
- **Redis durability** — Redis isn't durable by default; a restart loses sessions,
  rate-limit counters, and presence, logging everyone out or resetting limits. *Handle:*
  decide what must survive restart (AOF/persistence or rebuildable design) and document
  recovery.
- **Redis eviction under memory pressure** — The wrong `maxmemory` policy evicts session
  tokens or rate-limit keys as if disposable. *Handle:* separate volatile cache from durable
  state (different instances/policies); never store auth state under an evict-anything policy.
- **Hot key** — The celebrity profile key saturates a single Redis shard. *Handle:* local
  in-process cache layer for the hottest keys, or key replication/fan-out.
- **Privacy-setting change race** — User flips to private but an in-flight request already
  passed the visibility check. *Handle:* check privacy at read/serve time, not just at request
  entry; keep the window small.

---

## Social Graph

- **Self-edges** — Friending, following, or blocking yourself. *Handle:* reject `actor == target`
  at the service boundary.
- **Crossing friend requests** — A requests B while B is simultaneously requesting A.
  *Handle:* detect the reciprocal pending request and auto-accept rather than create two edges.
- **Duplicate requests** — Repeated requests for an already-pending edge. *Handle:* idempotent
  upsert on (actor, target) so a duplicate is a no-op.
- **Accept an already-resolved request** — Concurrent accept + cancel/reject. *Handle:* model
  the request as a state machine and apply transitions atomically (conditional update).
- **Block while a request is pending** — A blocks B who has an inflight friend request to A.
  *Handle:* block wins and cleans up the pending edge atomically.
- **Block semantics** — Decide whether blocking *removes* an existing friendship/follow or
  only prevents new ones, and whether it cascades to feed, notifications, and recommendations.
  *Handle:* pick one, apply it consistently everywhere a relationship is read.
- **Symmetric relationships across shards** — Sharding by user ID puts A→B on A's shard and
  B→A on B's; a single friendship is a cross-shard write with no distributed transaction.
  *Handle:* store both halves and reconcile drift, or pick a canonical owner; decide
  explicitly where a block edge lives so both directions can enforce it.
- **Double block** — A and B block each other. *Handle:* idempotent, symmetric handling so
  counts and reads stay correct.
- **Count drift** — Cached friend/follower counts diverge permanently if an event is lost or
  double-counted, and can go negative. *Handle:* treat counts as eventually consistent with a
  periodic reconciliation job against the source of truth; clamp at zero.
- **Cursor vs. offset pagination over a mutating graph** — Offset pagination duplicates or
  skips rows when the list changes between pages. *Handle:* cursor/keyset pagination on a
  stable sort key.
- **Privacy leakage** — Friend request to someone who has *you* blocked should fail silently
  (don't reveal the block); cached counts should match what a given viewer can actually see.
  *Handle:* silent failures for block-protected actions; decide whether counts are viewer-aware.

---

## Recommendations

- **Celebrity / supernode traversal** — Friends-of-friends from a high-degree node explodes.
  *Handle:* cap traversal depth/fan-out, sample neighbors, precompute for hot nodes.
- **Leaking ineligible users** — Recommending people who are already friends, blocked, or who
  blocked you. *Handle:* filter against the current graph + blocklist at result time, not just
  at compute time.

---

## Real-Time, Feed & Notifications

- **Celebrity fan-out** — Fan-out-on-write can't write 50M feed entries per post, and a
  high-follower user's edges create a hot shard. *Handle:* hybrid — fan-out-on-write for normal
  users, fan-out-on-read for celebrities, with a follower-count threshold.
- **At-least-once duplicates** — Kafka redelivers on consumer restart, so feeds/notifications/
  counts can be applied twice. *Handle:* idempotent consumers keyed on a dedup ID with a
  defined TTL.
- **Out-of-order / rapid unfollow→refollow** — An older unfollow event processed after a newer
  follow leaves you wrongly unfollowed. *Handle:* per-edge version/sequence numbers; apply only
  if newer than current state (not blind last-write-wins on arrival).
- **Feed cold start** — A brand-new user has an empty feed. *Handle:* seed with popular/
  recommended content or an onboarding state.
- **Backfill on follow** — Whether a newly followed account's past posts appear is an expensive
  historical fan-out. *Handle:* decide the policy; if backfilling, bound it (last N / recent
  window).
- **Mutating content after fan-out** — Deleting or editing a post that's already in millions
  of feeds leaves stale copies. *Handle:* store references, not denormalized content, and/or
  propagate tombstones with lazy cleanup.
- **Read-time filtering** — A feed entry may reference a now-blocked, deleted, or
  newly-private user. *Handle:* filter at read time even though fan-out happened at write time.
- **Notification storms** — 1,000 likes shouldn't mean 1,000 notifications. *Handle:* coalesce
  ("X and 999 others"); batch and rate-limit per recipient.
- **Notification ordering / stale targets** — "X commented" arrives before "X followed you," or
  a notification points at deleted content by delivery time. *Handle:* tolerate reordering in
  UX; resolve/skip targets at delivery.
- **Presence stuck online** — A client crashes without a clean disconnect and shows online
  forever. *Handle:* heartbeat with TTL; presence expires if heartbeats stop.
- **Reconnection storms** — After a deploy, every WebSocket client reconnects at once.
  *Handle:* jittered reconnect backoff on the client; capacity headroom on the connection tier.

---

## Event Pipeline (Kafka)

- **Poison-pill message** — One malformed event the consumer can't process blocks its
  partition by crash-looping. *Handle:* dead-letter queue after N failed attempts; alert and
  move on.
- **Schema evolution** — A new event field breaks old consumers running alongside new
  producers mid-deploy. *Handle:* schema registry + backward/forward-compatible (additive)
  changes only.
- **Rebalance storms** — Scaling consumers reassigns partitions, causing brief duplicate
  processing or pauses. *Handle:* idempotent consumers; commit offsets carefully; cooperative
  rebalancing.
- **Partition-key skew** — Keying by user_id makes the celebrity's partition hot. *Handle:*
  sub-partition hot keys, or route high-volume producers differently.
- **"Exactly once" framing** — End-to-end exactly-once across system boundaries is effectively
  a myth. *Handle:* design for at-least-once + idempotency rather than claiming exactly-once.

---

## Reliability & Scale

- **Read replica lag → read-your-own-writes** — A user updates their profile, immediately
  reads from a lagging replica, and sees the old value. *Handle:* read from primary for a short
  window after a write, or use session/monotonic consistency.
- **Retry amplification** — Client × gateway × service retries multiply a small blip into a
  self-inflicted DDoS. *Handle:* retry budgets, jittered exponential backoff, retry at one layer
  only.
- **Partial-failure multi-service write** — Creating a friendship touches the graph DB, emits a
  Kafka event, and updates a count cache, with no distributed transaction; a crash between steps
  leaves inconsistency. *Handle:* transactional outbox pattern so the event can't be lost when
  the DB commit succeeds.
- **Idempotency-key store + TTL** — A retry after the idempotency key has expired re-applies
  the write. *Handle:* TTL the keys longer than any realistic retry window; document the bound.
- **Circuit breaker + graceful degradation** — When a breaker opens you serve stale cache — for
  how long, and what about writes? *Handle:* define a max staleness and a read-only/queued
  behavior for writes during degradation.
- **Backpressure / cascading failure** — A slow downstream backs up callers until threads
  exhaust across the chain. *Handle:* bulkheads (isolated pools), load shedding (reject some
  requests to stay alive), timeouts everywhere.
- **Cluster-wide cold start** — After a full restart everything is cold at once (empty caches,
  cold connections). *Handle:* warm critical caches on startup; ramp traffic; readiness gates.

---

## Rate Limiting & API Gateway

- **Window boundary burst** — A fixed-window limiter allows ~2× the limit across the seam
  between two windows. *Handle:* sliding-window counter/log.
- **Shared-IP punishment** — IP-based limits throttle everyone behind one corporate/carrier
  NAT (CGNAT) together. *Handle:* combine per-account and per-IP limits.
- **Limiter store down** — If the rate-limit store (Redis) is unavailable, do you fail open
  (abuse) or closed (outage)? *Handle:* make it a deliberate, documented choice; consider a
  local fallback limiter.
- **Limiter as bottleneck/SPOF** — A central limiter can itself become the saturation point.
  *Handle:* shard/replicate limiter state; cheap local pre-checks before the shared check.

---

## Cross-Cutting / Ops

- **Zero-downtime schema migrations** — Rolling deploys run old and new code simultaneously, so
  a dropped/renamed column breaks the old pods. *Handle:* expand/contract — add column, write
  both, backfill, remove later.
- **gRPC contract compatibility** — Service contracts change while two versions coexist during
  a deploy. *Handle:* additive, backward-compatible changes only; never break a field in one
  release.
- **Global clock assumptions** — There's no single clock across services; anything time-based
  (TTLs, ordering, rate windows) can drift. *Handle:* NTP, logical/sequence ordering where
  correctness depends on order, tolerate skew.
- **Tracing sampling gap** — At scale you can't trace every request, so the one you need to
  debug may not have been sampled. *Handle:* tail-based sampling that keeps slow/errored traces.
- **Input validation** — Oversized fields, malformed Unicode, injection in profile text.
  *Handle:* validate, normalize, and bound all inputs at the gateway and service boundary.
