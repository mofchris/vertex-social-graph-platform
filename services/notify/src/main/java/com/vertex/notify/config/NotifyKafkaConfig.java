package com.vertex.notify.config;

import com.vertex.notify.events.MalformedEventException;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;

/**
 * Wiring for the {@code kafka} profile: a dead-letter error handler for the social-event consumer.
 * (The scheduler that drives the ledger purge and the SSE heartbeat is enabled for every profile in
 * {@link SchedulingConfig}.)
 *
 * <p>Spring Boot applies the single {@link DefaultErrorHandler} bean to every {@code @KafkaListener}
 * container. The handler retries a few times for a transient fault (a brief DB hiccup), then routes
 * the message to {@code social.events.DLT} rather than blocking the partition forever on it — the
 * poison-pill defence from EDGE_CASES.md. A message that can't even be parsed (or parses but isn't
 * an event) will never succeed, so it is marked non-retryable and dead-lettered immediately instead
 * of burning the retry budget. The recoverer stamps the standard {@code kafka_dlt-*} headers
 * (exception message, original topic/partition/offset), which {@code DeadLetterCapture} records.
 */
@Configuration
@Profile("kafka")
public class NotifyKafkaConfig {

    public static final String SOCIAL_EVENTS = "social.events";
    public static final String SOCIAL_EVENTS_DLT = SOCIAL_EVENTS + ".DLT";

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        // Publish the failed record to "<topic>.DLT", same partition. We pin the name explicitly
        // rather than rely on the resolver default (which varies by version) so the dead-letter
        // topic is predictable: social.events -> social.events.DLT.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(record.topic() + ".DLT", record.partition()));
        // 2 retries, 500ms apart, before dead-lettering — enough to ride out a momentary blip.
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(500L, 2L));
        handler.addNotRetryableExceptions(JacksonException.class, MalformedEventException.class);
        return handler;
    }
}
