package me.bechberger.testorder;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class StateMigrationsTest {

	@Test
	void migrateV0ToV1NoOp() {
		Map<String, Object> root = new LinkedHashMap<>();
		root.put("schemaVersion", 0);
		root.put("durations", Map.of("com.example.Test", 1500));
		Map<String, Object> migrated = StateMigrations.migrate(root, 0, 1);
		assertSame(root, migrated, "v0→v1 is a no-op migration, same instance expected");
	}

	@Test
	void migrateSameVersionIsNoOp() {
		Map<String, Object> root = Map.of("schemaVersion", 1);
		Map<String, Object> result = StateMigrations.migrate(root, 1, 1);
		assertSame(root, result);
	}

	@Test
	void migrateDowngradeThrows() {
		Map<String, Object> root = Map.of("schemaVersion", 1);
		assertThrows(StateDowngradeException.class, () -> StateMigrations.migrate(root, 1, 0));
	}

	@Test
	void migrateMissingStepThrows() {
		// There's no migration registered for v1→v2 yet
		Map<String, Object> root = Map.of("schemaVersion", 1);
		assertThrows(IllegalArgumentException.class, () -> StateMigrations.migrate(root, 1, 2));
	}

	@Test
	void migrationsAreContiguous() {
		// Verify the migration list has no gaps
		for (int i = 0; i < StateMigrations.MIGRATIONS.size(); i++) {
			var m = StateMigrations.MIGRATIONS.get(i);
			assertEquals(i, m.fromVersion(), "Migration at index " + i + " should have fromVersion=" + i);
			assertEquals(i + 1, m.toVersion(), "Migration at index " + i + " should have toVersion=" + (i + 1));
		}
	}
}
