package com.vertex.notify.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory registry of Server-Sent Events streams, keyed by user. Dead streams are removed
 * on completion, timeout, or send error, so reconnects don't leak emitters. Single-node:
 * in a multi-instance deployment this would sit behind a shared pub/sub (e.g. Redis).
 */
@Component
public class SseEmitters {

    private static final Logger log = LoggerFactory.getLogger(SseEmitters.class);

    private final Map<UUID, List<SseEmitter>> byUser = new ConcurrentHashMap<>();
    private final long timeoutMs;

    public SseEmitters(@Value("${app.notify.sse-timeout-ms:1800000}") long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public SseEmitter register(UUID userId) {
        SseEmitter emitter = new SseEmitter(timeoutMs);
        byUser.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(userId, emitter));
        emitter.onTimeout(() -> {
            emitter.complete();
            remove(userId, emitter);
        });
        emitter.onError(e -> remove(userId, emitter));
        try {
            emitter.send(SseEmitter.event().name("connected").data("ok"));
        } catch (IOException e) {
            remove(userId, emitter);
        }
        return emitter;
    }

    public void push(UUID userId, Object payload) {
        List<SseEmitter> emitters = byUser.get(userId);
        if (emitters == null) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("notification").data(payload));
            } catch (IOException | IllegalStateException e) {
                log.debug("dropping dead SSE stream for {}: {}", userId, e.getMessage());
                remove(userId, emitter);
            }
        }
    }

    public int connectionCount() {
        return byUser.values().stream().mapToInt(List::size).sum();
    }

    private void remove(UUID userId, SseEmitter emitter) {
        List<SseEmitter> emitters = byUser.get(userId);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                byUser.remove(userId);
            }
        }
    }
}
