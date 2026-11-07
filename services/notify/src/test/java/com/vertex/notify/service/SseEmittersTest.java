package com.vertex.notify.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-level check of the SSE heartbeat: it pings every open stream and drops the ones that can no
 * longer be written to, so a client that vanished without a clean close doesn't leak an emitter.
 */
class SseEmittersTest {

    @Test
    void heartbeatPingsLiveStreamsAndDropsDeadOnes() {
        SseEmitters sse = new SseEmitters(60_000);
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        SseEmitter aliceTab1 = sse.register(alice);
        sse.register(alice); // a second tab
        SseEmitter bobStream = sse.register(bob);
        assertThat(sse.connectionCount()).isEqualTo(3);

        assertThat(sse.heartbeat()).isEqualTo(3);

        // Two streams die (the connection went away); writing to them now fails.
        aliceTab1.complete();
        bobStream.complete();

        assertThat(sse.heartbeat()).isEqualTo(1);
        assertThat(sse.connectionCount()).isEqualTo(1);
        assertThat(sse.heartbeat()).isEqualTo(1); // steady state: the survivor keeps getting pinged
    }

    @Test
    void heartbeatWithNoStreamsIsANoOp() {
        assertThat(new SseEmitters(60_000).heartbeat()).isZero();
    }
}
