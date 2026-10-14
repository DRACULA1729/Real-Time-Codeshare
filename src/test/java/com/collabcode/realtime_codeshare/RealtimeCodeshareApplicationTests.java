package com.collabcode.realtime_codeshare;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// RANDOM_PORT boots a real embedded container so the WebSocket
// ServletServerContainerFactoryBean can resolve its ServerContainer.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RealtimeCodeshareApplicationTests {

	@Test
	void contextLoads() {
	}

}
