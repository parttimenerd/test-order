package com.example.circular;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Exercises NotificationService (not part of the circular chain). */
class NotificationServiceTest {

	@Test
	void sendAndRetrieve() {
		var svc = new NotificationService();
		svc.notify("hello");
		assertEquals(1, svc.getMessageCount());
		assertEquals("hello", svc.getSentMessages().get(0));
	}
}
