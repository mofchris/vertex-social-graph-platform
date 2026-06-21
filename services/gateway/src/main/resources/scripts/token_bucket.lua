-- Atomic token-bucket rate limiter. One Redis round-trip, evaluated server-side so the
-- read-modify-write can't race across gateway instances (EDGE_CASES.md: limiter as SPOF / boundary
-- burst — a token bucket has no fixed-window seam).
--
-- KEYS[1] = bucket key (e.g. rl:user:<id> or rl:ip:<addr>)
-- ARGV[1] = capacity        (max tokens)
-- ARGV[2] = refillPerSecond (tokens added per second)
-- ARGV[3] = nowMillis       (caller's clock)
-- ARGV[4] = requested       (tokens to take, normally 1)
-- returns { allowed(0|1), remaining, retryAfterSeconds }

local capacity = tonumber(ARGV[1])
local refill = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local requested = tonumber(ARGV[4])

local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(state[1])
local ts = tonumber(state[2])
if tokens == nil then
    tokens = capacity
    ts = now
end

-- Refill for the time elapsed since we last touched the bucket.
local elapsed = math.max(0, now - ts) / 1000.0
tokens = math.min(capacity, tokens + elapsed * refill)

local allowed = 0
local retry_after = 0
if tokens >= requested then
    tokens = tokens - requested
    allowed = 1
elseif refill > 0 then
    retry_after = math.ceil((requested - tokens) / refill)
else
    retry_after = 1
end

redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', now)
-- Expire an idle bucket once it would be fully refilled anyway, so cold keys don't linger.
local ttl = math.ceil(capacity / math.max(refill, 0.001)) + 1
redis.call('EXPIRE', KEYS[1], ttl)

return { allowed, math.floor(tokens), retry_after }
