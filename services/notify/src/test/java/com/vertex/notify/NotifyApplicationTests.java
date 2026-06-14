package com.vertex.notify;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test: the context loads against the default (embedded H2) profile, so it runs with
 * only a JDK. To run against a real Postgres container, launch {@code TestNotifyApplication}.
 */
@SpringBootTest
class NotifyApplicationTests {

	@Test
	void contextLoads() {
	}

}
