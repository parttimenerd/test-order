package me.bechberger.testorder.agent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Pattern;

/**
 * Intelligent, configurable class filtering for instrumentation decisions.
 *
 * Supports multiple filtering strategies:
 * <ul>
 * <li><b>Whitelist</b>: Only instrument classes matching include patterns</li>
 * <li><b>Blacklist</b>: Skip classes matching exclude patterns</li>
 * <li><b>Heuristics</b>: Skip known non-instrumentable patterns (proxies,
 * mocks, generated)</li>
 * <li><b>Smart</b>: Combination of all above with caching</li>
 * </ul>
 *
 * Caches filter decisions with configurable cache settings for performance.
 */
public class IntelligentClassFilter {

	/** Filtering strategy to use */
	public enum Strategy {
		/** Only include explicitly listed packages */
		WHITELIST,
		/** Include all except explicitly excluded packages */
		BLACKLIST,
		/** Combination of whitelist + exclude patterns + heuristics */
		SMART,
		/** Whitelist + heuristics only (recommended) */
		WHITELIST_SMART
	}

	private static final String[] ALWAYS_SKIP_PREFIXES = {"java/", "jdk/", "sun/", "com/sun/", "javax/", "jakarta/",
			"me/bechberger/testorder/agent/"};

	/**
	 * Index from first-char → offsets into ALWAYS_SKIP_PREFIXES that share that
	 * char.
	 */
	private static final int[][] SKIP_PREFIX_INDEX = buildSkipPrefixIndex();

	private static int[][] buildSkipPrefixIndex() {
		int[][] idx = new int[128][];
		// Group prefix positions by their first character
		java.util.Map<Character, java.util.List<Integer>> groups = new java.util.HashMap<>();
		for (int i = 0; i < ALWAYS_SKIP_PREFIXES.length; i++) {
			char c = ALWAYS_SKIP_PREFIXES[i].charAt(0);
			groups.computeIfAbsent(c, k -> new java.util.ArrayList<>()).add(i);
		}
		for (var e : groups.entrySet()) {
			idx[e.getKey()] = e.getValue().stream().mapToInt(Integer::intValue).toArray();
		}
		return idx;
	}

	// Markers that only appear in class names containing '$' — skip for clean
	// names.
	private static final String[] DOLLAR_MARKERS = {"$$", "$Proxy", "$Lambda$"};
	// Markers that can appear even without '$' in the name.
	private static final String[] NON_DOLLAR_MARKERS = {"CGLIB", "ByteBuddy", "MockitoMock", "SpringCGLIB",
			"EnhancerBySpringCGLIB", "FastClass", "MethodAccessor"};

	private final Strategy strategy;
	private final List<Pattern> includePatterns;
	private final List<Pattern> excludePatterns;
	private final Set<String> explicitIncludes;
	private final Set<String> explicitExcludes;
	private final boolean skipTestClasses;
	private final boolean useHeuristics;

	// Two keysets replace ConcurrentHashMap<String, Boolean> to avoid Boolean
	// boxing
	// on every cache lookup/store. The total count is bounded at maxCacheSize.
	private final Set<String> cachedTrue;
	private final Set<String> cachedFalse;
	private final int maxCacheSize;
	private final AtomicInteger cacheSize = new AtomicInteger();
	private final LongAdder cacheHits = new LongAdder();
	private final LongAdder cacheMisses = new LongAdder();

	public static class Builder {
		private Strategy strategy = Strategy.SMART;
		private final List<String> includePatterns = new ArrayList<>();
		private final List<String> excludePatterns = new ArrayList<>();
		private final Set<String> explicitIncludes = new HashSet<>();
		private final Set<String> explicitExcludes = new HashSet<>();
		private boolean skipTestClasses = true;
		private boolean useHeuristics = true;
		private int maxCacheSize = 50_000;

		public Builder strategy(Strategy strategy) {
			this.strategy = strategy;
			return this;
		}

		public Builder includePattern(String regex) {
			this.includePatterns.add(regex);
			return this;
		}

		public Builder includePatterns(List<String> regexes) {
			this.includePatterns.addAll(regexes);
			return this;
		}

		public Builder excludePattern(String regex) {
			this.excludePatterns.add(regex);
			return this;
		}

		public Builder excludePatterns(List<String> regexes) {
			this.excludePatterns.addAll(regexes);
			return this;
		}

		public Builder explicitInclude(String packageName) {
			this.explicitIncludes.add(packageName.replace('.', '/'));
			return this;
		}

		public Builder explicitExclude(String packageName) {
			this.explicitExcludes.add(packageName.replace('.', '/'));
			return this;
		}

		public Builder skipTestClasses(boolean skip) {
			this.skipTestClasses = skip;
			return this;
		}

		public Builder useHeuristics(boolean use) {
			this.useHeuristics = use;
			return this;
		}

		public Builder maxCacheSize(int size) {
			this.maxCacheSize = size;
			return this;
		}

		public IntelligentClassFilter build() {
			return new IntelligentClassFilter(this);
		}
	}

	private IntelligentClassFilter(Builder builder) {
		this.strategy = builder.strategy;
		this.includePatterns = builder.includePatterns.stream().map(Pattern::compile).toList();
		this.excludePatterns = builder.excludePatterns.stream().map(Pattern::compile).toList();
		this.explicitIncludes = new HashSet<>(builder.explicitIncludes);
		this.explicitExcludes = new HashSet<>(builder.explicitExcludes);
		this.skipTestClasses = builder.skipTestClasses;
		this.useHeuristics = builder.useHeuristics;
		this.maxCacheSize = builder.maxCacheSize;
		this.cachedTrue = ConcurrentHashMap.newKeySet();
		this.cachedFalse = ConcurrentHashMap.newKeySet();
	}

	/**
	 * Determine if a class should be instrumented. Uses cache for performance.
	 */
	public boolean shouldInstrument(String className) {
		if (cachedTrue.contains(className)) {
			cacheHits.increment();
			return true;
		}
		if (cachedFalse.contains(className)) {
			cacheHits.increment();
			return false;
		}

		cacheMisses.increment();
		boolean result = evaluateFilter(className);

		// Atomically claim a cache slot; roll back if over capacity
		int slot = cacheSize.getAndIncrement();
		if (slot < maxCacheSize) {
			boolean added = (result ? cachedTrue : cachedFalse).add(className);
			if (!added) {
				cacheSize.decrementAndGet(); // duplicate key — slot unused
			}
		} else {
			cacheSize.decrementAndGet(); // over capacity, release slot
		}

		return result;
	}

	private boolean evaluateFilter(String className) {
		// Dispatch skip-prefix check by first char so each branch only tests
		// the prefixes that can actually match — at most 5 instead of all 9.
		if (matchesSkipPrefix(className)) {
			return false;
		}

		// Skip explicit exclusions
		if (!explicitExcludes.isEmpty()) {
			for (String excluded : explicitExcludes) {
				if (className.startsWith(excluded)) {
					return false;
				}
			}
		}

		// Apply heuristics before regex patterns (cheaper string checks first)
		if (useHeuristics) {
			if (isGeneratedClass(className)) {
				return false;
			}
			if (skipTestClasses && isTestClass(className)) {
				return false;
			}
		}

		// Apply exclude patterns (regex — most expensive, so last)
		for (Pattern pattern : excludePatterns) {
			if (pattern.matcher(className).find()) {
				return false;
			}
		}

		// Apply strategy-specific logic
		return switch (strategy) {
			case WHITELIST -> {
				if (explicitIncludes.isEmpty() && includePatterns.isEmpty()) {
					yield true; // no includes specified, accept all (after heuristics)
				}
				// Check explicit includes
				for (String included : explicitIncludes) {
					if (className.startsWith(included)) {
						yield true;
					}
				}
				// Check include patterns
				for (Pattern pattern : includePatterns) {
					if (pattern.matcher(className).find()) {
						yield true;
					}
				}
				yield false;
			}
			case BLACKLIST -> {
				// Already filtered by excludes above
				yield true;
			}
			case SMART -> {
				// Prefer whitelist if specified, fallback to blacklist
				if (!explicitIncludes.isEmpty() || !includePatterns.isEmpty()) {
					for (String included : explicitIncludes) {
						if (className.startsWith(included)) {
							yield true;
						}
					}
					for (Pattern pattern : includePatterns) {
						if (pattern.matcher(className).find()) {
							yield true;
						}
					}
					yield false;
				}
				yield true;
			}
			case WHITELIST_SMART -> {
				if (explicitIncludes.isEmpty() && includePatterns.isEmpty()) {
					yield false; // strict: no includes specified, reject all
				}
				for (String included : explicitIncludes) {
					if (className.startsWith(included)) {
						yield true;
					}
				}
				for (Pattern pattern : includePatterns) {
					if (pattern.matcher(className).find()) {
						yield true;
					}
				}
				yield false;
			}
		};
	}

	/**
	 * Check if className matches any ALWAYS_SKIP_PREFIXES entry. Uses a precomputed
	 * first-char index so only the relevant subset is tested.
	 */
	private static boolean matchesSkipPrefix(String className) {
		char c0 = className.charAt(0);
		if (c0 >= SKIP_PREFIX_INDEX.length)
			return false;
		int[] indices = SKIP_PREFIX_INDEX[c0];
		if (indices == null)
			return false;
		for (int i : indices) {
			if (className.startsWith(ALWAYS_SKIP_PREFIXES[i])) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Check if a class is generated/synthetic (no real source).
	 */
	private boolean isGeneratedClass(String className) {
		// DOLLAR_MARKERS only appear in names containing '$' — skip the scan for
		// clean names, which is the common case for instrumented application classes.
		if (className.indexOf('$') >= 0) {
			for (String marker : DOLLAR_MARKERS) {
				if (className.contains(marker)) {
					return true;
				}
			}
		}
		for (String marker : NON_DOLLAR_MARKERS) {
			if (className.contains(marker)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Check if a class is a test class.
	 */
	private boolean isTestClass(String className) {
		String simpleName = className.substring(className.lastIndexOf('/') + 1);
		return simpleName.startsWith("Test") || simpleName.endsWith("Test") || simpleName.endsWith("Tests")
				|| simpleName.endsWith("TestCase");
	}

	/**
	 * Clear the filter cache.
	 */
	public void clearCache() {
		cachedTrue.clear();
		cachedFalse.clear();
		cacheSize.set(0);
	}

	/**
	 * Get cache statistics.
	 */
	public CacheStats getCacheStats() {
		return new CacheStats(cacheHits.sum(), cacheMisses.sum(), cachedTrue.size() + cachedFalse.size(), maxCacheSize);
	}

	/**
	 * Cache statistics record.
	 */
	public record CacheStats(long hits, long misses, int currentSize, int maxSize) {
		public double hitRate() {
			long total = hits + misses;
			return total == 0 ? 0 : (double) hits / total * 100;
		}

		@Override
		public String toString() {
			return String.format(Locale.US, "CacheStats{hits=%d, misses=%d, hitRate=%.1f%%, size=%d/%d}", hits, misses,
					hitRate(), currentSize, maxSize);
		}
	}
}
