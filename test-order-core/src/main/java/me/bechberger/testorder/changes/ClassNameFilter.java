package me.bechberger.testorder.changes;

/**
 * Shared class-name filter shared by static analysis (this module) and the
 * agent's runtime instrumentation. Both need to identify classes that are JDK
 * or framework infrastructure and therefore never part of the project's
 * changed-classes set.
 *
 * <p>
 * Kept dependency-free so it can also be used from the agent (which shades its
 * own copy of ASM and avoids depending on test-order-core at runtime). The
 * agent's {@code IntelligentClassFilter} duplicates this prefix list — keep the
 * two in sync.
 *
 * <p>
 * Operates on either internal slash form ({@code java/lang/Object}) or dotted
 * FQCN ({@code java.lang.Object}) — both are checked against both variants of
 * each prefix.
 */
public final class ClassNameFilter {

	/** Internal-slash-form prefixes to always skip. */
	public static final String[] ALWAYS_SKIP_INTERNAL_PREFIXES = {"java/", "jdk/", "sun/", "com/sun/", "javax/",
			"jakarta/", "kotlin/", "kotlinx/", "scala/", "me/bechberger/testorder/agent/"};

	/**
	 * Dotted-FQCN prefixes to always skip (mirror of
	 * {@link #ALWAYS_SKIP_INTERNAL_PREFIXES}).
	 */
	public static final String[] ALWAYS_SKIP_DOTTED_PREFIXES;

	static {
		ALWAYS_SKIP_DOTTED_PREFIXES = new String[ALWAYS_SKIP_INTERNAL_PREFIXES.length];
		for (int i = 0; i < ALWAYS_SKIP_INTERNAL_PREFIXES.length; i++) {
			ALWAYS_SKIP_DOTTED_PREFIXES[i] = ALWAYS_SKIP_INTERNAL_PREFIXES[i].replace('/', '.');
		}
	}

	private ClassNameFilter() {
	}

	/**
	 * Returns {@code true} if the given fully-qualified class name (dotted or slash
	 * form) is part of the JDK / known framework namespaces and should be skipped
	 * by static analysis. Empty/null inputs are treated as skip.
	 */
	public static boolean isLibraryType(String name) {
		if (name == null || name.isEmpty()) {
			return true;
		}
		boolean slash = name.indexOf('/') >= 0;
		String[] prefixes = slash ? ALWAYS_SKIP_INTERNAL_PREFIXES : ALWAYS_SKIP_DOTTED_PREFIXES;
		for (String p : prefixes) {
			if (name.startsWith(p)) {
				return true;
			}
		}
		return false;
	}
}
