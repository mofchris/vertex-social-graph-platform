package com.vertex.notify.events;

import com.vertex.notify.config.NotifyKafkaConfig;
import com.vertex.notify.domain.DeadLetter;
import com.vertex.notify.repository.DeadLetterRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Records every message that lands on {@code social.events.DLT} into the {@code dead_letters} table,
 * so the system trace can show a visitor where their poison message went and why — with the error
 * and original coordinates the dead-letter recoverer stamped on it as {@code kafka_dlt-*} headers.
 * Runs only under the {@code kafka} profile, in its own consumer group so it never competes with (or
 * moves the offsets of) anything else reading the DLT.
 *
 * <p>This listener must never become a poison pill itself: if it threw, the shared error handler
 * would retry it and then dead-letter the dead letter. So it swallows everything. A record already
 * captured (the DLT copy redelivered, or the same original dead-lettered twice) hits the unique
 * original-coordinates constraint and is skipped as a duplicate; anything else unexpected is logged
 * and the record is dropped from the table — the DLT topic itself still has it.
 */
@Component
@Profile("kafka")
public class DeadLetterCapture {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterCapture.class);
    private static final String LISTENER_WRAPPER = "Listener method '";
    private static final String WRAPPER_SUFFIX = "' threw exception; ";

    private final DeadLetterRepository deadLetters;

    public DeadLetterCapture(DeadLetterRepository deadLetters) {
        this.deadLetters = deadLetters;
    }

    @KafkaListener(topics = NotifyKafkaConfig.SOCIAL_EVENTS_DLT, groupId = "notify-dlt-capture")
    public void onDeadLetter(ConsumerRecord<String, String> record) {
        try {
            Headers headers = record.headers();
            deadLetters.save(new DeadLetter(
                    record.key(),
                    record.value(),
                    errorOf(headers),
                    string(headers, KafkaHeaders.DLT_ORIGINAL_TOPIC),
                    int32(headers, KafkaHeaders.DLT_ORIGINAL_PARTITION),
                    int64(headers, KafkaHeaders.DLT_ORIGINAL_OFFSET)));
        } catch (DataIntegrityViolationException duplicate) {
            log.debug("dead letter at DLT offset {} already captured", record.offset());
        } catch (RuntimeException unexpected) {
            log.error("could not capture dead letter at {}-{}@{}; it remains on the DLT topic",
                    record.topic(), record.partition(), record.offset(), unexpected);
        }
    }

    /**
     * The failure as the recoverer described it: the exception message, else its class name. Spring
     * wraps listener failures as "Listener method '&lt;full signature&gt;' threw exception; &lt;root
     * cause&gt;"; the wrapper is noise to a reader, so only the root-cause part is kept.
     */
    static String errorOf(Headers headers) {
        String message = string(headers, KafkaHeaders.DLT_EXCEPTION_MESSAGE);
        if (message != null && !message.isBlank()) {
            int wrapperEnd = message.startsWith(LISTENER_WRAPPER) ? message.indexOf(WRAPPER_SUFFIX) : -1;
            return wrapperEnd >= 0 ? message.substring(wrapperEnd + WRAPPER_SUFFIX.length()) : message;
        }
        String cause = string(headers, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN);
        return cause != null ? cause : string(headers, KafkaHeaders.DLT_EXCEPTION_FQCN);
    }

    private static String string(Headers headers, String name) {
        Header header = headers.lastHeader(name);
        return (header == null || header.value() == null)
                ? null
                : new String(header.value(), StandardCharsets.UTF_8);
    }

    // The recoverer writes the original partition as a 4-byte int and the offset as an 8-byte long.

    private static Integer int32(Headers headers, String name) {
        Header header = headers.lastHeader(name);
        return (header == null || header.value() == null || header.value().length != Integer.BYTES)
                ? null
                : ByteBuffer.wrap(header.value()).getInt();
    }

    private static Long int64(Headers headers, String name) {
        Header header = headers.lastHeader(name);
        return (header == null || header.value() == null || header.value().length != Long.BYTES)
                ? null
                : ByteBuffer.wrap(header.value()).getLong();
    }
}
