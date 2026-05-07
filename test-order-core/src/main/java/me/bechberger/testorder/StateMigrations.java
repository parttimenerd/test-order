package me.bechberger.testorder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.logging.Logger;

/**
 * Ordered chain of schema migrations for {@link TestOrderState}.
 * <p>
 * Each migration transforms a persisted root map from one schema version to the
 * next. When a state file with an older schema version is loaded,
 * {@link #migrate(Map, int, int)} applies all necessary migrations in sequence,
 * preserving user data instead of discarding it.
 * <p>
 * To add a new migration when bumping {@code CURRENT_SCHEMA_VERSION}:
 * <ol>
 * <li>Add a new entry to the {@link #MIGRATIONS} list in the static block.</li>
 * <li>Increment {@code CURRENT_SCHEMA_VERSION} in {@link TestOrderState}.</li>
 * <li>Add a test in {@code TestOrderStateTest} that verifies the migration.</li>
 * </ol>
 */
final class StateMigrations {

	private StateMigrations() {
	}

	private static final Logger LOG = Logger.getLogger(StateMigrations.class.getName());

	record Migration(int fromVersion, int toVersion, UnaryOperator<Map<String, Object>> transform) {
		Migration {
			if (toVersion != fromVersion + 1) {
				throw new IllegalArgumentException(
						"Migration must be sequential: " + fromVersion + " → " + toVersion);
			}
		}
	}

	static final List<Migration> MIGRATIONS;

	static {
		List<Migration> m = new ArrayList<>();
		// v0 → v1: no-op (seed migration — demonstrates the pattern).
		// v0 state files had the same logical structure as v1 but no schemaVersion
		// field, or schemaVersion=0.
		m.add(new Migration(0, 1, root -> root));
		MIGRATIONS = List.copyOf(m);
	}

	/**
	 * Applies migrations from {@code fromVersion} to {@code toVersion}.
	 *
	 * @throws IllegalArgumentException
	 *             if no migration path exists
	 */
	static Map<String, Object> migrate(Map<String, Object> root, int fromVersion, int toVersion) {
		if (fromVersion == toVersion) {
			return root;
		}
		if (fromVersion > toVersion) {
			throw new StateDowngradeException(
					"State file was written by a newer plugin version (schema v" + fromVersion
							+ ", current plugin expects v" + toVersion + "). "
							+ "Run 'test-order:clean' (Maven) or 'testOrderClean' (Gradle) to reset, "
							+ "or upgrade the plugin back to match the state file.");
		}
		Map<String, Object> current = root;
		for (int v = fromVersion; v < toVersion; v++) {
			Migration migration = findMigration(v);
			if (migration == null) {
				throw new IllegalArgumentException(
						"No migration registered for version " + v + " → " + (v + 1));
			}
			LOG.info("Migrating state from schema v" + v + " to v" + (v + 1));
			Map<String, Object> migrated = migration.transform().apply(current);
			if (migrated == current) {
				// Identity transform — just update schema version in-place
				current.put("schemaVersion", v + 1);
			} else {
				Map<String, Object> normalized = new java.util.LinkedHashMap<>(migrated);
				normalized.put("schemaVersion", v + 1);
				current = normalized;
			}
		}
		return current;
	}

	private static Migration findMigration(int fromVersion) {
		for (Migration m : MIGRATIONS) {
			if (m.fromVersion() == fromVersion) {
				return m;
			}
		}
		return null;
	}
}
