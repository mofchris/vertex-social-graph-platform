package com.vertex.graph.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on the {@code @Scheduled} outbox relay — but only under the {@code kafka} profile, so the
 * default JDK-only run starts no scheduler and stages no events.
 */
@Configuration
@Profile("kafka")
@EnableScheduling
public class OutboxConfig {
}
