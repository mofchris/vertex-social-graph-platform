package com.vertex.profile;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test: the context loads against the default (embedded H2 + simple cache) profile,
 * so it runs with only a JDK. To run against real Postgres + Redis containers instead,
 * launch {@code TestProfileApplication}.
 */
@SpringBootTest
class ProfileApplicationTests {

	@Test
	void contextLoads() {
	}

}
