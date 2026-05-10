package com.patbaumgartner.embabel;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verifies that the Spring application context loads successfully with all registered
 * agents, controllers, and auto-configurations.
 */
@SpringBootTest
@ActiveProfiles("test")
class EmbabelApplicationContextTest {

	@Test
	void contextLoads() {
		// If this test passes, the context started without errors.
	}

}
