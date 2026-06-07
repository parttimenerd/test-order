package me.bechberger.testorder;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TestOrderConfigResolverTest {

	private static final String CHANGED_CLASSES_KEY = "testorder.changed.classes";
	private static final String CHANGED_TEST_CLASSES_KEY = "testorder.changed.test.classes";

	@AfterEach
	void clearProperties() {
		System.clearProperty(CHANGED_CLASSES_KEY);
		System.clearProperty(CHANGED_TEST_CLASSES_KEY);
	}

	private TestOrderConfigResolver resolver() {
		return new TestOrderConfigResolver(getClass().getClassLoader());
	}

	@Test
	void resolveChangedClasses_commaDelimited() {
		System.setProperty(CHANGED_CLASSES_KEY, "com.example.Foo,com.example.Bar");
		assertEquals(Set.of("com.example.Foo", "com.example.Bar"), resolver().resolveChangedClasses());
	}

	@Test
	void resolveChangedClasses_semicolonFallback() {
		System.setProperty(CHANGED_CLASSES_KEY, "com.example.Foo;com.example.Bar");
		assertEquals(Set.of("com.example.Foo", "com.example.Bar"), resolver().resolveChangedClasses(),
				"semicolon-only input must be treated as separator fallback");
	}

	@Test
	void resolveChangedClasses_commaBeatseSemicolon() {
		// If both delimiters present, commas win (semicolon is part of a class name —
		// unlikely but we should not split on semicolons when commas are already
		// present)
		System.setProperty(CHANGED_CLASSES_KEY, "com.example.Foo,com.example.Bar;Baz");
		Set<String> result = resolver().resolveChangedClasses();
		assertTrue(result.contains("com.example.Foo"));
		assertTrue(result.contains("com.example.Bar;Baz"));
	}

	@Test
	void resolveChangedClasses_empty_returnsEmpty() {
		System.setProperty(CHANGED_CLASSES_KEY, "");
		assertTrue(resolver().resolveChangedClasses().isEmpty());
	}

	@Test
	void resolveChangedTestClasses_commaDelimited() {
		System.setProperty(CHANGED_TEST_CLASSES_KEY, "com.example.FooTest,com.example.BarTest");
		assertEquals(Set.of("com.example.FooTest", "com.example.BarTest"), resolver().resolveChangedTestClasses());
	}

	@Test
	void resolveChangedTestClasses_semicolonFallback() {
		System.setProperty(CHANGED_TEST_CLASSES_KEY, "com.example.FooTest;com.example.BarTest");
		assertEquals(Set.of("com.example.FooTest", "com.example.BarTest"), resolver().resolveChangedTestClasses(),
				"semicolon-only input must be treated as separator fallback");
	}

	@Test
	void resolveChangedClasses_absent_returnsEmpty() {
		// no property set → empty
		assertTrue(resolver().resolveChangedClasses().isEmpty());
	}
}
