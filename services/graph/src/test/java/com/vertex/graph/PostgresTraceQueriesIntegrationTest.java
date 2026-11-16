package com.vertex.graph;

import com.vertex.graph.events.OutboxEvent;
import com.vertex.graph.events.OutboxEventWriter;
import com.vertex.graph.events.OutboxKind;
import com.vertex.graph.events.SocialEvent;
import com.vertex.graph.events.SocialEventType;
import com.vertex.graph.repository.BlockRepository;
import com.vertex.graph.repository.FollowRepository;
import com.vertex.graph.repository.OutboxRepository;
import com.vertex.graph.service.GraphService;
import com.vertex.graph.web.dto.PopularUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Limit;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The trace migrations and queries against a real PostgreSQL (Testcontainers), not just H2's
 * PostgreSQL mode: V3 must apply cleanly on the database production runs, and the popular /
 * outbox-trace queries must behave the same there.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PostgresTraceQueriesIntegrationTest {

    @Autowired private GraphService graph;
    @Autowired private FollowRepository follows;
    @Autowired private BlockRepository blocks;
    @Autowired private OutboxRepository outbox;

    @BeforeEach
    void reset() {
        follows.deleteAll();
        blocks.deleteAll();
        outbox.deleteAll();
    }

    @Test
    void popularRanksAndExcludesOnPostgres() {
        UUID viewer = UUID.randomUUID();
        UUID top = UUID.randomUUID();
        UUID blocker = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            graph.follow(UUID.randomUUID(), top);
        }
        for (int i = 0; i < 2; i++) {
            graph.follow(UUID.randomUUID(), blocker);
        }
        graph.follow(UUID.randomUUID(), viewer);
        graph.block(blocker, viewer);

        List<PopularUser> items = graph.popular(viewer, 12).items();
        assertThat(items).containsExactly(new PopularUser(top, 3));
    }

    @Test
    void outboxTraceQueriesWorkOnPostgres() {
        UUID actor = UUID.randomUUID();
        SocialEvent event = SocialEvent.of(SocialEventType.FOLLOW, actor, UUID.randomUUID(), null);
        OutboxEvent original = outbox.save(OutboxEvent.of(OutboxEventWriter.TOPIC, event, "{\"x\":1}"));
        outbox.save(OutboxEvent.replayOf(original));
        outbox.save(OutboxEvent.poison(OutboxEventWriter.TOPIC, actor, "{\"vertexPoison\":true"));
        outbox.save(OutboxEvent.of(OutboxEventWriter.TOPIC,
                SocialEvent.of(SocialEventType.FOLLOW, UUID.randomUUID(), actor, null), "{}")); // someone else's

        List<OutboxEvent> mine = outbox.findRecentByActor(actor, Limit.of(10));
        assertThat(mine).extracting(OutboxEvent::getKind)
                .containsExactly(OutboxKind.POISON, OutboxKind.REPLAY, OutboxKind.EVENT); // newest first

        assertThat(outbox.findFirstByEventIdAndActorIdOrderByCreatedAtAsc(event.eventId(), actor))
                .get().extracting(OutboxEvent::getId).isEqualTo(original.getId());
        assertThat(outbox.findFirstByEventIdAndActorIdOrderByCreatedAtAsc(event.eventId(), UUID.randomUUID()))
                .isEmpty();
    }
}
