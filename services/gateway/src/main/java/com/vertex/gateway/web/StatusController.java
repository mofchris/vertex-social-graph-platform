package com.vertex.gateway.web;

import com.vertex.gateway.config.GatewayProperties;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Public, unauthenticated platform status: the gateway asks every routed service for its own
 * {@code /actuator/health} and reports what each said and how long it took. The web UI uses it to
 * tell a visitor, truthfully, whether the backend is up, partly up, or still starting.
 *
 * <p>The result is cached for a few seconds so this endpoint can't be used to fan a flood of
 * requests out to every service; each probe has a short timeout so one hung service can't stall it.
 */
@RestController
public class StatusController {

    static final Duration CACHE_FOR = Duration.ofSeconds(5);
    private static final Duration PROBE_TIMEOUT = Duration.ofMillis(1500);

    private final GatewayProperties props;
    private final RestClient probe;
    private final Clock clock;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private volatile Snapshot cached;

    public StatusController(GatewayProperties props) {
        this.props = props;
        this.clock = Clock.systemUTC();
        HttpClient http = HttpClient.newBuilder().connectTimeout(PROBE_TIMEOUT).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(PROBE_TIMEOUT);
        this.probe = RestClient.builder().requestFactory(factory).build();
    }

    @GetMapping("/api/status")
    public ResponseEntity<PlatformStatus> status() {
        Snapshot snapshot = cached;
        if (isStale(snapshot)) {
            synchronized (this) { // single-flight: concurrent callers share one round of probes
                snapshot = cached;
                if (isStale(snapshot)) {
                    Instant now = clock.instant();
                    snapshot = new Snapshot(now, check(now));
                    cached = snapshot;
                }
            }
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(snapshot.status());
    }

    private boolean isStale(Snapshot snapshot) {
        return snapshot == null || snapshot.takenAt().plus(CACHE_FOR).isBefore(clock.instant());
    }

    private PlatformStatus check(Instant now) {
        Map<String, String> routes = new TreeMap<>(props.routes());
        List<CompletableFuture<ServiceStatus>> probes = new ArrayList<>();
        routes.forEach((name, baseUrl) ->
                probes.add(CompletableFuture.supplyAsync(() -> probe(name, baseUrl), executor)));
        List<ServiceStatus> services = probes.stream().map(CompletableFuture::join).toList();

        long up = services.stream().filter(s -> "UP".equals(s.status())).count();
        String overall = up == services.size() ? "UP" : (up == 0 ? "DOWN" : "DEGRADED");
        return new PlatformStatus(overall, now, services);
    }

    private ServiceStatus probe(String name, String baseUrl) {
        long start = System.nanoTime();
        String status;
        try {
            Map<?, ?> body = probe.get().uri(baseUrl + "/actuator/health").retrieve().body(Map.class);
            Object reported = body == null ? null : body.get("status");
            status = reported == null ? "UNKNOWN" : reported.toString();
        } catch (RuntimeException unreachable) {
            // A starting Spring Boot app refuses connections, then answers 503 until it's ready —
            // both mean "not serving yet", which is exactly what the visitor needs to know.
            status = "DOWN";
        }
        long latencyMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
        return new ServiceStatus(name, status, latencyMs);
    }

    public record PlatformStatus(String status, Instant checkedAt, List<ServiceStatus> services) {
    }

    public record ServiceStatus(String name, String status, long latencyMs) {
    }

    private record Snapshot(Instant takenAt, PlatformStatus status) {
    }
}
