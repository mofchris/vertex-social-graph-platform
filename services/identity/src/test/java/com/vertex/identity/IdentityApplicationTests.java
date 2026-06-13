package com.vertex.identity;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test: the application context loads against the default (embedded H2) profile,
 * so it runs with only a JDK. To run against a real Postgres container instead, launch
 * {@code TestIdentityApplication} (which imports {@code TestcontainersConfiguration}).
 */
@SpringBootTest
class IdentityApplicationTests {

	@Test
	void contextLoads() {
	}

}
