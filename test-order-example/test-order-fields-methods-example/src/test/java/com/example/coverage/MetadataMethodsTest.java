package com.example.coverage;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests only metadata-related methods (4 methods touched). Should have moderate
 * coverage score but less than ItemMethodsTest.
 */
class MetadataMethodsTest {

	private WideCoverageService service;

	@BeforeEach
	void setUp() {
		service = new WideCoverageService();
	}

	@Test
	void testSetMetadata() {
		service.setMetadata("key1", "value1");
		assertEquals("value1", service.getMetadata("key1"));
	}

	@Test
	void testRemoveMetadata() {
		service.setMetadata("key1", "value1");
		service.removeMetadata("key1");
		assertNull(service.getMetadata("key1"));
	}

	@Test
	void testGetAllMetadata() {
		service.setMetadata("key1", "value1");
		service.setMetadata("key2", "value2");
		var metadata = service.getAllMetadata();
		assertEquals(2, metadata.size());
	}

	@Test
	void testMultipleMetadataOperations() {
		service.setMetadata("name", "Alice");
		service.setMetadata("age", "30");
		var metadata = service.getAllMetadata();
		assertEquals("Alice", metadata.get("name"));
		assertEquals("30", metadata.get("age"));
	}
}
