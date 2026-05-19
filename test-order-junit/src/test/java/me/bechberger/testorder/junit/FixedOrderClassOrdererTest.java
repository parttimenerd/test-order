package me.bechberger.testorder.junit;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for FixedOrderClassOrderer nested class handling.
 *
 * Uses real inner classes as test subjects. Their JVM names contain '$'.
 */
class FixedOrderClassOrdererTest {

	@TempDir
	Path tempDir;

	private String origOrderFile;

	// Subject classes for ordering tests
	static class SubjectA {
		static class Inner1 {
		}

		static class Inner2 {
		}
	}

	static class SubjectB {
	}

	@BeforeEach
	void save() {
		origOrderFile = System.getProperty(FixedOrderClassOrderer.ORDER_FILE_PROPERTY);
	}

	@AfterEach
	void restore() {
		if (origOrderFile == null) {
			System.clearProperty(FixedOrderClassOrderer.ORDER_FILE_PROPERTY);
		} else {
			System.setProperty(FixedOrderClassOrderer.ORDER_FILE_PROPERTY, origOrderFile);
		}
	}

	@Test
	void nestedClassExplicitlyListedUsesItsOwnPosition() throws IOException {
		// Write order file with Inner1 BEFORE SubjectA
		String nameA = SubjectA.class.getName();
		String nameInner1 = SubjectA.Inner1.class.getName();
		String nameB = SubjectB.class.getName();

		Path orderFile = tempDir.resolve("order.txt");
		Files.writeString(orderFile, String.join("\n", nameB, nameInner1, nameA));
		System.setProperty(FixedOrderClassOrderer.ORDER_FILE_PROPERTY, orderFile.toString());

		// Input order: A, Inner1, B
		List<StubClassDescriptor> descs = new ArrayList<>(
				List.of(desc(SubjectA.class), desc(SubjectA.Inner1.class), desc(SubjectB.class)));

		FixedOrderClassOrderer orderer = new FixedOrderClassOrderer();
		orderer.orderClasses(new StubClassOrdererContext(descs));

		// Expected: B (pos 0), Inner1 (pos 1), A (pos 2) — exact match used
		assertEquals(nameB, descs.get(0).getTestClass().getName());
		assertEquals(nameInner1, descs.get(1).getTestClass().getName());
		assertEquals(nameA, descs.get(2).getTestClass().getName());
	}

	@Test
	void nestedClassNotInFileGetsMaxPosition() throws IOException {
		// Order file only lists SubjectA — Inner2 is NOT listed
		String nameA = SubjectA.class.getName();

		Path orderFile = tempDir.resolve("order.txt");
		Files.writeString(orderFile, nameA);
		System.setProperty(FixedOrderClassOrderer.ORDER_FILE_PROPERTY, orderFile.toString());

		// SubjectA.Inner2 is not in the file. Its top-level (first $) is
		// "...FixedOrderClassOrdererTest" which is also not listed.
		// So it should go to MAX_VALUE position (end).
		List<StubClassDescriptor> descs = new ArrayList<>(List.of(desc(SubjectA.Inner2.class), desc(SubjectA.class)));

		FixedOrderClassOrderer orderer = new FixedOrderClassOrderer();
		orderer.orderClasses(new StubClassOrdererContext(descs));

		// SubjectA is listed (pos 0), Inner2 falls to end
		assertEquals(nameA, descs.get(0).getTestClass().getName());
		assertEquals(SubjectA.Inner2.class.getName(), descs.get(1).getTestClass().getName());
	}

	@Test
	void singleLevelNestedInheritsParentPosition() throws IOException {
		// The JVM name for SubjectA is "...FixedOrderClassOrdererTest$SubjectA"
		// The top-level (first $) is "...FixedOrderClassOrdererTest" — our test class.
		// If the order file lists "...FixedOrderClassOrdererTest", SubjectA should
		// inherit that position (single-$ production scenario).
		String testClassName = FixedOrderClassOrdererTest.class.getName();

		Path orderFile = tempDir.resolve("order.txt");
		// Put test class itself last, some other class first
		Files.writeString(orderFile, String.join("\n", "com.example.First", testClassName));
		System.setProperty(FixedOrderClassOrderer.ORDER_FILE_PROPERTY, orderFile.toString());

		List<StubClassDescriptor> descs = new ArrayList<>(List.of(desc(SubjectA.class), desc(SubjectB.class)));

		FixedOrderClassOrderer orderer = new FixedOrderClassOrderer();
		orderer.orderClasses(new StubClassOrdererContext(descs));

		// Both SubjectA and SubjectB inherit position 1 from parent
		// FixedOrderClassOrdererTest
		// (their first $ maps to FixedOrderClassOrdererTest which is at position 1).
		// Since both get same position, stable sort preserves original order.
		assertEquals(SubjectA.class.getName(), descs.get(0).getTestClass().getName());
		assertEquals(SubjectB.class.getName(), descs.get(1).getTestClass().getName());
	}

	@Test
	void noOrderFileKeepsOriginalOrder() {
		System.clearProperty(FixedOrderClassOrderer.ORDER_FILE_PROPERTY);

		List<StubClassDescriptor> descs = new ArrayList<>(List.of(desc(SubjectB.class), desc(SubjectA.class)));

		FixedOrderClassOrderer orderer = new FixedOrderClassOrderer();
		orderer.orderClasses(new StubClassOrdererContext(descs));

		// Original order preserved
		assertEquals(SubjectB.class.getName(), descs.get(0).getTestClass().getName());
		assertEquals(SubjectA.class.getName(), descs.get(1).getTestClass().getName());
	}

	// ── Stubs ──────────────────────────────────────────────────

	private StubClassDescriptor desc(Class<?> clazz) {
		return new StubClassDescriptor(clazz);
	}

	static class StubClassDescriptor implements ClassDescriptor {
		private final Class<?> testClass;

		StubClassDescriptor(Class<?> testClass) {
			this.testClass = testClass;
		}

		@Override
		public Class<?> getTestClass() {
			return testClass;
		}

		@Override
		public String getDisplayName() {
			return testClass.getSimpleName();
		}

		@Override
		public <A extends java.lang.annotation.Annotation> Optional<A> findAnnotation(Class<A> annotationType) {
			return Optional.empty();
		}

		@Override
		public <A extends java.lang.annotation.Annotation> List<A> findRepeatableAnnotations(Class<A> annotationType) {
			return List.of();
		}

		@Override
		public boolean isAnnotated(Class<? extends java.lang.annotation.Annotation> annotationType) {
			return false;
		}
	}

	static class StubClassOrdererContext implements ClassOrdererContext {
		private final List<? extends ClassDescriptor> descriptors;

		StubClassOrdererContext(List<? extends ClassDescriptor> descriptors) {
			this.descriptors = descriptors;
		}

		@Override
		public List<? extends ClassDescriptor> getClassDescriptors() {
			return descriptors;
		}

		@Override
		public Optional<String> getConfigurationParameter(String key) {
			return Optional.empty();
		}
	}
}
