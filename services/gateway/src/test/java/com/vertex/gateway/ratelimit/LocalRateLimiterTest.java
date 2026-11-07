package com.vertex.gateway.ratelimit;

import com.vertex.gateway.ratelimit.RateLimiter.Decision;
import com.vertex.gateway.ratelimit.RateLimiter.Limit;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Token-bucket arithmetic of the in-process limiter, driven by a hand-advanced clock. */
class LocalRateLimiterTest {

    private final AtomicLong millis = new AtomicLong(1_000_000);
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis.get()); }
    };
    private final LocalRateLimiter limiter = new LocalRateLimiter(clock);

    @Test
    void burstsUpToCapacityThenDenies() {
        Limit limit = new Limit(3, 1);
        assertThat(limiter.tryAcquire("k", limit).remaining()).isEqualTo(2);
        assertThat(limiter.tryAcquire("k", limit).remaining()).isEqualTo(1);
        assertThat(limiter.tryAcquire("k", limit).remaining()).isZero();

        Decision denied = limiter.tryAcquire("k", limit);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void refillsAtTheConfiguredRateButNeverAboveCapacity() {
        Limit limit = new Limit(2, 0.5); // one token every 2 s
        limiter.tryAcquire("k", limit);
        limiter.tryAcquire("k", limit);
        assertThat(limiter.tryAcquire("k", limit).allowed()).isFalse();

        millis.addAndGet(1_000); // half a token
        Decision stillEmpty = limiter.tryAcquire("k", limit);
        assertThat(stillEmpty.allowed()).isFalse();
        assertThat(stillEmpty.retryAfterSeconds()).isEqualTo(1);

        millis.addAndGet(1_000); // a whole token now
        assertThat(limiter.tryAcquire("k", limit).allowed()).isTrue();

        millis.addAndGet(60_000); // a long idle period refills only to capacity
        assertThat(limiter.tryAcquire("k", limit).remaining()).isEqualTo(1);
    }

    @Test
    void keysAreIndependent() {
        Limit limit = new Limit(1, 0.01);
        assertThat(limiter.tryAcquire("a", limit).allowed()).isTrue();
        assertThat(limiter.tryAcquire("a", limit).allowed()).isFalse();
        assertThat(limiter.tryAcquire("b", limit).allowed()).isTrue();
    }

    @Test
    void sweepsIdleBucketsOnceTheMapIsLarge() {
        Limit limit = new Limit(1, 1); // full again one second after use
        for (int i = 0; i <= LocalRateLimiter.SWEEP_THRESHOLD; i++) {
            limiter.tryAcquire("ip-" + i, limit);
        }
        assertThat(limiter.size()).isGreaterThan(LocalRateLimiter.SWEEP_THRESHOLD);

        millis.addAndGet(5_000);
        limiter.tryAcquire("fresh", limit);
        assertThat(limiter.size()).isEqualTo(1);
    }

    @Test
    void rejectsNonsenseLimits() {
        assertThatThrownBy(() -> new Limit(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Limit(1, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
