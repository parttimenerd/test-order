package me.bechberger.testorder;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DashboardGenerator#deriveClassToModule(DependencyMap)} — the
 * inferential source-class → owning-module map used for cross-module dashboard
 * coloring and the crossModuleDepCount / suspectHomeModule signals.
 */
class DashboardGeneratorClassToModuleTest {

	/**
	 * BUG-159: a source class that lives in module A but is referenced by exactly
	 * one test in A and one test in B produces a tally tie. The old code resolved
	 * ties via {@code max(comparingByValue())}, which is arbitrary for equal values
	 * and mislabeled the class as B. A class must be attributed to the module of a
	 * test that shares its package when such a test exists — that is the module
	 * that actually owns the source file.
	 */
	@Test
	void tiedReferenceCount_attributesClassToSamePackageModule() {
		DependencyMap map = new DependencyMap();

		// core module: UserServiceTest (package com.myapp.core) depends on UserService.
		map.put("com.myapp.core.UserServiceTest", Set.of("com.myapp.core.UserService"));
		map.putModule("com.myapp.core.UserServiceTest", "com.myapp:core");

		// web module: UserControllerTest (package com.myapp.web) depends on its own
		// controller AND transitively on core's UserService.
		map.put("com.myapp.web.UserControllerTest",
				Set.of("com.myapp.web.UserController", "com.myapp.core.UserService"));
		map.putModule("com.myapp.web.UserControllerTest", "com.myapp:web");

		Map<String, String> result = DashboardGenerator.deriveClassToModule(map);

		// UserService is referenced once from core and once from web (a tie), but it
		// lives in package com.myapp.core, matching the core test — so it belongs to
		// core.
		assertEquals("com.myapp:core", result.get("com.myapp.core.UserService"),
				"tied source class must be attributed to the module whose test shares its package");
		// UserController is only referenced from web — unambiguous.
		assertEquals("com.myapp:web", result.get("com.myapp.web.UserController"));
	}

	/**
	 * Regression guard: a clear majority must still win regardless of package
	 * heuristics — the package tie-break only applies when counts are equal.
	 */
	@Test
	void clearMajority_winsOverPackageHeuristic() {
		DependencyMap map = new DependencyMap();

		// Two web tests reference the shared util; one core test does too.
		// Even though the util's package is com.myapp.core, web has the majority.
		map.put("com.myapp.web.ATest", Set.of("com.myapp.core.SharedUtil"));
		map.putModule("com.myapp.web.ATest", "com.myapp:web");
		map.put("com.myapp.web.BTest", Set.of("com.myapp.core.SharedUtil"));
		map.putModule("com.myapp.web.BTest", "com.myapp:web");
		map.put("com.myapp.core.CTest", Set.of("com.myapp.core.SharedUtil"));
		map.putModule("com.myapp.core.CTest", "com.myapp:core");

		Map<String, String> result = DashboardGenerator.deriveClassToModule(map);

		assertEquals("com.myapp:web", result.get("com.myapp.core.SharedUtil"),
				"a clear reference-count majority must win; package tie-break only breaks exact ties");
	}

	@Test
	void singleModule_returnsEmpty() {
		DependencyMap map = new DependencyMap();
		map.put("com.myapp.FooTest", Set.of("com.myapp.Foo"));
		map.putModule("com.myapp.FooTest", "com.myapp:only");

		assertTrue(DashboardGenerator.deriveClassToModule(map).isEmpty(),
				"single-module projects need no class→module map");
	}
}
