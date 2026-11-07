package com.vertex.notify.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on {@code @Scheduled} for every profile. The SSE heartbeat must run even on the default
 * JDK-only build (streams exist there too); the kafka-only jobs (ledger purge) are gated by their own
 * {@code @Profile}, so enabling the scheduler here doesn't start them.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
