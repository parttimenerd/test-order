package com.example.circular;

import java.util.ArrayList;
import java.util.List;

/**
 * Not part of the circular chain. Used to test that non-circular classes
 * sharing a package with circular ones score correctly.
 */
public class NotificationService {

	private final List<String> sent = new ArrayList<>();

	public void notify(String message) {
		sent.add(message);
	}

	public List<String> getSentMessages() {
		return List.copyOf(sent);
	}

	public int getMessageCount() {
		return sent.size();
	}
}
