package com.vertex.feed;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test: the context loads against the default (embedded H2) profile, so it runs with
 * only a JDK. To run against a real Postgres container, launch {@code TestFeedApplication}.
 */
@SpringBootTest
class FeedApplicationTests {

	@Test
	void contextLoads() {
	}

}
