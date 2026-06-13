package com.vertex.graph;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test: the context loads against the default (embedded H2) profile, so it runs with
 * only a JDK. To run against a real Postgres container, launch {@code TestGraphApplication}.
 */
@SpringBootTest
class GraphApplicationTests {

	@Test
	void contextLoads() {
	}

}
