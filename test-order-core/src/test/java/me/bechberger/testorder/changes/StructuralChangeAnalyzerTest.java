package me.bechberger.testorder.changes;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("StructuralChangeAnalyzer")
class StructuralChangeAnalyzerTest {

	// ──────────────────────────────────────────────────────────────
	// Test fixtures: helper methods to create diffs
	// ──────────────────────────────────────────────────────────────

	private static StructuralDiff.Change createChange(StructuralDiff.Change.Kind kind,
			StructuralDiff.Change.Category category, String fqcn, String name, String detail) {
		return new StructuralDiff.Change(kind, category, fqcn, name, detail);
	}

	private static StructuralDiff.FileDiff createFileDiff(Path file, List<StructuralDiff.Change> changes) {
		return new StructuralDiff.FileDiff(file, changes);
	}

	// ──────────────────────────────────────────────────────────────
	// Nested test classes for organization
	// ──────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("fromDiffs() - Basic Functionality")
	class FromDiffsBasic {

		@Test
		@DisplayName("E49: empty diffs list returns EMPTY singleton")
		void emptyDiffsReturnsEmpty() {
			List<StructuralDiff.FileDiff> diffs = List.of();
			StructuralChangeAnalyzer.ChangedMembers result = StructuralChangeAnalyzer.fromDiffs(diffs);

			assertSame(result, StructuralChangeAnalyzer.ChangedMembers.EMPTY,
					"Empty diffs should return EMPTY singleton");
			assertTrue(result.changedClasses().isEmpty());
			assertTrue(result.changedMemberKeys().isEmpty());
			assertTrue(result.membersByClass().isEmpty());
			assertTrue(result.classesWithTypeChanges().isEmpty());
			assertTrue(result.changedStaticFieldKeys().isEmpty());
		}

		@Test
		@DisplayName("E50: TYPE-level change marks class in classesWithTypeChanges")
		void typeLevelChangeMarksTypeChanged() {
			StructuralDiff.Change typeChange = createChange(StructuralDiff.Change.Kind.MODIFIED,
					StructuralDiff.Change.Category.TYPE, "com.example.Foo", "Foo", "superclass changed");
			List<StructuralDiff.FileDiff> diffs = List.of(createFileDiff(Path.of("Foo.java"), List.of(typeChange)));

			StructuralChangeAnalyzer.ChangedMembers result = StructuralChangeAnalyzer.fromDiffs(diffs);

			assertTrue(result.classesWithTypeChanges().contains("com.example.Foo"));
			assertTrue(result.changedClasses().contains("com.example.Foo"));
			// TYPE changes should not produce member keys (they return null from
			// resolveMemberName)
			assertTrue(result.changedMemberKeys().isEmpty());
		}

		@Test
		@DisplayName("E48: <clinit> (static initializer) change detected and classified")
		void staticInitializerChangeDetected() {
			StructuralDiff.Change clinitChange = createChange(StructuralDiff.Change.Kind.MODIFIED,
					StructuralDiff.Change.Category.INITIALIZER, "com.example.Config", "<clinit>", "static { ... }");
			List<StructuralDiff.FileDiff> diffs = List
					.of(createFileDiff(Path.of("Config.java"), List.of(clinitChange)));

			StructuralChangeAnalyzer.ChangedMembers result = StructuralChangeAnalyzer.fromDiffs(diffs);

			assertTrue(result.changedClasses().contains("com.example.Config"));
			assertTrue(result.changedMemberKeys().contains("com.example.Config#<clinit>"));
			assertTrue(result.membersByClass().get("com.example.Config").contains("<clinit>"));
			// <clinit> is not a field, so no static field entry
			assertTrue(result.changedStaticFieldKeys().isEmpty());
		}

		@Test
		@DisplayName("<init> (constructor) change detected")
		void constructorChangeDetected() {
			StructuralDiff.Change constructorChange = createChange(StructuralDiff.Change.Kind.ADDED,
					StructuralDiff.Change.Category.METHOD, "com.example.MyClass", "MyClass",
					"constructor public MyClass(int x)");
			List<StructuralDiff.FileDiff> diffs = List
					.of(createFileDiff(Path.of("MyClass.java"), List.of(constructorChange)));

			StructuralChangeAnalyzer.ChangedMembers result = StructuralChangeAnalyzer.fromDiffs(diffs);

			assertTrue(result.changedMemberKeys().contains("com.example.MyClass#<init>"));
			assertTrue(result.membersByClass().get("com.example.MyClass").contains("<init>"));
		}

		@Test
		@DisplayName("Regular method change recorded with method name")
		void regularMethodChange() {
			StructuralDiff.Change methodChange = createChange(StructuralDiff.Change.Kind.MODIFIED,
					StructuralDiff.Change.Category.METHOD, "com.example.Helper", "compute", "void compute() { ... }");
			List<StructuralDiff.FileDiff> diffs = List
					.of(createFileDiff(Path.of("Helper.java"), List.of(methodChange)));

			StructuralChangeAnalyzer.ChangedMembers result = StructuralChangeAnalyzer.fromDiffs(diffs);

			assertTrue(result.changedMemberKeys().contains("com.example.Helper#compute"));
			assertTrue(result.membersByClass().get("com.example.Helper").contains("compute"));
		}

		@Test
		@DisplayName("Field change recorded with field name")
		void fieldChange() {
			StructuralDiff.Change fieldChange = createChange(StructuralDiff.Change.Kind.ADDED,
					StructuralDiff.Change.Category.FIELD, "com.example.Data", "value", "private int value");
			List<StructuralDiff.FileDiff> diffs = List.of(createFileDiff(Path.of("Data.java"), List.of(fieldChange)));

			StructuralChangeAnalyzer.ChangedMembers result = StructuralChangeAnalyzer.fromDiffs(diffs);

			assertTrue(result.changedMemberKeys().contains("com.example.Data#value"));
			assertTrue(result.membersByClass().get("com.example.Data").contains("value"));
			// Instance field, not static
			assertTrue(result.changedStaticFieldKeys().isEmpty());
		}

		@Test
		@DisplayName("Static field change recorded in changedStaticFieldKeys")
		void staticFieldChange() {
			StructuralDiff.Change staticFieldChange = createChange(StructuralDiff.Change.Kind.MODIFIED,
					StructuralDiff.Change.Category.FIELD, "com.example.Constants", "VERSION",
					"static final int VERSION");
			List<StructuralDiff.FileDiff> diffs = List
					.of(createFileDiff(Path.of("Constants.java"), List.of(staticFieldChange)));

			StructuralChangeAnalyzer.ChangedMembers result = StructuralChangeAnalyzer.fromDiffs(diffs);

			assertTrue(result.changedMemberKeys().contains("com.example.Constants#VERSION"));
			assertTrue(result.changedStaticFieldKeys().contains("com.example.Constants#VERSION"));
		}

		@Test
		@DisplayName("Instance initializer (<init>) recorded with member name")
		void instanceInitializerChange() {
			StructuralDiff.Change initChange = createChange(StructuralDiff.Change.Kind.ADDED,
					StructuralDiff.Change.Category.INITIALIZER, "com.example.Lazy", "<init>",
					"{ lazy = new Object(); }");
			List<StructuralDiff.FileDiff> diffs = List.of(createFileDiff(Path.of("Lazy.java"), List.of(initChange)));

			StructuralChangeAnalyzer.ChangedMembers result = StructuralChangeAnalyzer.fromDiffs(diffs);

			assertTrue(result.changedMemberKeys().contains("com.example.Lazy#<init>"));
			assertTrue(result.membersByClass().get("com.example.Lazy").contains("<init>"));
		}

		@Test
		@DisplayName("Multiple changes in single class consolidated by class")
		void multipleChangesInOneClass() {
			List<StructuralDiff.Change> changes = List.of(
					createChange(StructuralDiff.Change.Kind.ADDED, StructuralDiff.Change.Category.METHOD,
							"com.example.Service", "start", "void start()"),
					createChange(StructuralDiff.Change.Kind.ADDED, StructuralDiff.Change.Category.METHOD,
							"com.example.Service", "stop", "void stop()"),
					createChange(StructuralDiff.Change.Kind.MODIFIED, StructuralDiff.Change.Category.FIELD,
							"com.example.Service", "state", "private int state"));
			List<StructuralDiff.FileDiff> diffs = List.of(createFileDiff(Path.of("Service.java"), changes));

			StructuralChangeAnalyzer.ChangedMembers result = StructuralChangeAnalyzer.fromDiffs(diffs);

			assertEquals(1, result.changedClasses().size());
			assertTrue(result.changedClasses().contains("com.example.Service"));
			assertEquals(3, result.changedMemberKeys().size());
			Set<String> classMembers = result.membersByClass().get("com.example.Service");
			assertEquals(3, classMembers.size());
			assertTrue(classMembers.containsAll(Set.of("start", "stop", "state")));
		}

		@Test
		@DisplayName("Multiple files with different classes")
		void multipleFilesMultipleClasses() {
			List<StructuralDiff.Change> file1Changes = List.of(createChange(StructuralDiff.Change.Kind.MODIFIED,
					StructuralDiff.Change.Category.METHOD, "com.example.Foo", "bar", "void bar()"));
			List<StructuralDiff.Change> file2Changes = List.of(createChange(StructuralDiff.Change.Kind.ADDED,
					StructuralDiff.Change.Category.METHOD, "com.example.Baz", "qux", "void qux()"));
			List<StructuralDiff.FileDiff> diffs = List.of(createFileDiff(Path.of("Foo.java"), file1Changes),
					createFileDiff(Path.of("Baz.java"), file2Changes));

			StructuralChangeAnalyzer.ChangedMembers result = StructuralChangeAnalyzer.fromDiffs(diffs);

			assertEquals(2, result.changedClasses().size());
			assertTrue(result.changedClasses().containsAll(Set.of("com.example.Foo", "com.example.Baz")));
			assertEquals(2, result.changedMemberKeys().size());
		}
	}

	@Nested
	@DisplayName("fromDiffs() - Static Field Detection")
	class FromDiffsStaticFields {

		@Test
		@DisplayName("Detects 'static' modifier in field detail")
		void detectsStaticModifier() {
			StructuralDiff.Change change = createChange(StructuralDiff.Change.Kind.ADDED,
					StructuralDiff.Change.Category.FIELD, "com.example.Statics", "INSTANCE",
					"public static final Statics INSTANCE");
			List<StructuralDiff.FileDiff> diffs = List.of(createFileDiff(Path.of("Statics.java"), List.of(change)));

			StructuralChangeAnalyzer.ChangedMembers result = StructuralChangeAnalyzer.fromDiffs(diffs);

			assertTrue(result.changedStaticFieldKeys().contains("com.example.Statics#INSTANCE"));
		}

		@Test
		@DisplayName("Non-static field not in staticFieldKeys")
		void nonStaticFieldNotIncluded() {
			StructuralDiff.Change change = createChange(StructuralDiff.Change.Kind.MODIFIED,
					StructuralDiff.Change.Category.FIELD, "com.example.Instance", "field", "private String field");
			List<StructuralDiff.FileDiff> diffs = List.of(createFileDiff(Path.of("Instance.java"), List.of(change)));

			StructuralChangeAnalyzer.ChangedMembers result = StructuralChangeAnalyzer.fromDiffs(diffs);

			assertTrue(result.changedStaticFieldKeys().isEmpty());
			// But regular field is still in memberKeys
			assertTrue(result.changedMemberKeys().contains("com.example.Instance#field"));
		}

		@Test
		@DisplayName("Modified static field detected")
		void modifiedStaticField() {
			StructuralDiff.Change change = createChange(StructuralDiff.Change.Kind.MODIFIED,
					StructuralDiff.Change.Category.FIELD, "com.example.Config", "counter",
					"was: static int counter = 0; now: static int counter = 10");
			List<StructuralDiff.FileDiff> diffs = List.of(createFileDiff(Path.of("Config.java"), List.of(change)));

			StructuralChangeAnalyzer.ChangedMembers result = StructuralChangeAnalyzer.fromDiffs(diffs);

			assertTrue(result.changedStaticFieldKeys().contains("com.example.Config#counter"));
		}

		@Test
		@DisplayName("Null detail treated as non-static")
		void nullDetailTreatedAsNonStatic() {
			StructuralDiff.Change change = createChange(StructuralDiff.Change.Kind.ADDED,
					StructuralDiff.Change.Category.FIELD, "com.example.Weird", "field", null);
			List<StructuralDiff.FileDiff> diffs = List.of(createFileDiff(Path.of("Weird.java"), List.of(change)));

			StructuralChangeAnalyzer.ChangedMembers result = StructuralChangeAnalyzer.fromDiffs(diffs);

			assertTrue(result.changedStaticFieldKeys().isEmpty());
		}

		@Test
		@DisplayName("Blank detail treated as non-static")
		void blankDetailTreatedAsNonStatic() {
			StructuralDiff.Change change = createChange(StructuralDiff.Change.Kind.REMOVED,
					StructuralDiff.Change.Category.FIELD, "com.example.Empty", "field", "   ");
			List<StructuralDiff.FileDiff> diffs = List.of(createFileDiff(Path.of("Empty.java"), List.of(change)));

			StructuralChangeAnalyzer.ChangedMembers result = StructuralChangeAnalyzer.fromDiffs(diffs);

			assertTrue(result.changedStaticFieldKeys().isEmpty());
		}
	}

	@Nested
	@DisplayName("resolveMemberName()")
	class ResolveMemberName {

		@Test
		@DisplayName("METHOD with 'constructor' prefix returns <init>")
		void constructorReturnsInit() {
			StructuralDiff.Change change = createChange(StructuralDiff.Change.Kind.ADDED,
					StructuralDiff.Change.Category.METHOD, "com.example.Foo", "Foo",
					"constructor public Foo(String name)");
			String result = StructuralChangeAnalyzer.resolveMemberName(change);
			assertEquals("<init>", result);
		}

		@Test
		@DisplayName("Regular METHOD returns method name")
		void regularMethodReturnsName() {
			StructuralDiff.Change change = createChange(StructuralDiff.Change.Kind.MODIFIED,
					StructuralDiff.Change.Category.METHOD, "com.example.Helper", "compute", "int compute(int x)");
			String result = StructuralChangeAnalyzer.resolveMemberName(change);
			assertEquals("compute", result);
		}

		@Test
		@DisplayName("FIELD returns field name")
		void fieldReturnsName() {
			StructuralDiff.Change change = createChange(StructuralDiff.Change.Kind.ADDED,
					StructuralDiff.Change.Category.FIELD, "com.example.Data", "count", "private int count");
			String result = StructuralChangeAnalyzer.resolveMemberName(change);
			assertEquals("count", result);
		}

		@Test
		@DisplayName("INITIALIZER with <clinit> returns <clinit>")
		void staticInitializerReturnsClinit() {
			StructuralDiff.Change change = createChange(StructuralDiff.Change.Kind.MODIFIED,
					StructuralDiff.Change.Category.INITIALIZER, "com.example.Config", "<clinit>", "static { ... }");
			String result = StructuralChangeAnalyzer.resolveMemberName(change);
			assertEquals("<clinit>", result);
		}

		@Test
		@DisplayName("INITIALIZER with <init> returns <init>")
		void instanceInitializerReturnsInit() {
			StructuralDiff.Change change = createChange(StructuralDiff.Change.Kind.ADDED,
					StructuralDiff.Change.Category.INITIALIZER, "com.example.Lazy", "<init>", "{ ... }");
			String result = StructuralChangeAnalyzer.resolveMemberName(change);
			assertEquals("<init>", result);
		}

		@Test
		@DisplayName("TYPE returns null")
		void typeReturnsNull() {
			StructuralDiff.Change change = createChange(StructuralDiff.Change.Kind.MODIFIED,
					StructuralDiff.Change.Category.TYPE, "com.example.Foo", "Foo", "superclass changed");
			String result = StructuralChangeAnalyzer.resolveMemberName(change);
			assertNull(result);
		}
	}

	@Nested
	@DisplayName("computeOverlap() - Class-Level")
	class ComputeOverlapClassLevel {

		@Test
		@DisplayName("Empty deps and empty changed classes → 0")
		void emptyDepsAndChanges() {
			Set<String> testDeps = Set.of();
			Set<String> testMemberDeps = null;
			StructuralChangeAnalyzer.ChangedMembers changedMembers = null;
			Set<String> changedClasses = Set.of();

			int overlap = StructuralChangeAnalyzer.computeOverlap(testDeps, testMemberDeps, changedMembers,
					changedClasses);

			assertEquals(0, overlap);
		}

		@Test
		@DisplayName("No overlap between deps and changes → 0")
		void noOverlap() {
			Set<String> testDeps = Set.of("com.example.A", "com.example.B");
			Set<String> changedClasses = Set.of("com.example.X", "com.example.Y");

			int overlap = StructuralChangeAnalyzer.computeOverlap(testDeps, null, null, changedClasses);

			assertEquals(0, overlap);
		}

		@Test
		@DisplayName("Partial overlap counted correctly")
		void partialOverlap() {
			Set<String> testDeps = Set.of("com.example.A", "com.example.B", "com.example.C");
			Set<String> changedClasses = Set.of("com.example.B", "com.example.C", "com.example.D");

			int overlap = StructuralChangeAnalyzer.computeOverlap(testDeps, null, null, changedClasses);

			assertEquals(2, overlap);
		}

		@Test
		@DisplayName("Full overlap counted correctly")
		void fullOverlap() {
			Set<String> testDeps = Set.of("com.example.A", "com.example.B");
			Set<String> changedClasses = Set.of("com.example.A", "com.example.B");

			int overlap = StructuralChangeAnalyzer.computeOverlap(testDeps, null, null, changedClasses);

			assertEquals(2, overlap);
		}
	}

	@Nested
	@DisplayName("computeOverlapClasses() - Class-Level")
	class ComputeOverlapClassesClassLevel {

		@Test
		@DisplayName("Returns set of overlapping classes")
		void returnsOverlappingSet() {
			Set<String> testDeps = Set.of("com.example.A", "com.example.B", "com.example.C");
			Set<String> changedClasses = Set.of("com.example.B", "com.example.C", "com.example.D");

			Set<String> result = StructuralChangeAnalyzer.computeOverlapClasses(testDeps, null, null, changedClasses);

			assertEquals(2, result.size());
			assertTrue(result.containsAll(Set.of("com.example.B", "com.example.C")));
		}
	}

	@Nested
	@DisplayName("computeOverlap() - Member-Level (with StructuralChangeAnalyzer.ChangedMembers)")
	class ComputeOverlapMemberLevel {

		@Test
		@DisplayName("Without member deps: falls back to class-level")
		void noMemberDeps() {
			Set<String> testDeps = Set.of("com.example.A", "com.example.B");
			Set<String> changedClasses = Set.of("com.example.A", "com.example.X");

			StructuralChangeAnalyzer.ChangedMembers changedMembers = new StructuralChangeAnalyzer.ChangedMembers(
					changedClasses, Set.of(), Map.of(), Set.of());

			int overlap = StructuralChangeAnalyzer.computeOverlap(testDeps, null, changedMembers, changedClasses);

			assertEquals(1, overlap);
		}

		@Test
		@DisplayName("Type-level change affects all class deps")
		void typeLevelChangeAffectsAll() {
			Set<String> testDeps = Set.of("com.example.Foo");
			Set<String> testMemberDeps = Set.of(); // Empty, no member-level deps

			StructuralChangeAnalyzer.ChangedMembers changedMembers = new StructuralChangeAnalyzer.ChangedMembers(
					Set.of("com.example.Foo"), Set.of(), Map.of(), Set.of("com.example.Foo") // Type-level change
			);

			int overlap = StructuralChangeAnalyzer.computeOverlap(testDeps, testMemberDeps, changedMembers, Set.of());

			assertEquals(1, overlap);
		}

		@Test
		@DisplayName("<clinit> change affects all class deps")
		void clinitChangeAffectsAll() {
			Set<String> testDeps = Set.of("com.example.Config");
			Set<String> testMemberDeps = Set.of(); // No member deps

			StructuralChangeAnalyzer.ChangedMembers changedMembers = new StructuralChangeAnalyzer.ChangedMembers(
					Set.of("com.example.Config"), Set.of("com.example.Config#<clinit>"),
					Map.of("com.example.Config", Set.of("<clinit>")), Set.of());

			int overlap = StructuralChangeAnalyzer.computeOverlap(testDeps, testMemberDeps, changedMembers, Set.of());

			assertEquals(1, overlap);
		}

		@Test
		@DisplayName("Method change affects class only if test uses that method")
		void methodChangeOnlyAffectsIfUsed() {
			Set<String> testDeps = Set.of("com.example.Helper");
			Set<String> testMemberDeps = Set.of("com.example.Helper#compute" // Test uses compute
			);

			StructuralChangeAnalyzer.ChangedMembers changedMembers = new StructuralChangeAnalyzer.ChangedMembers(
					Set.of("com.example.Helper"), Set.of("com.example.Helper#compute", "com.example.Helper#unused"),
					Map.of("com.example.Helper", Set.of("compute", "unused")), Set.of());

			int overlap = StructuralChangeAnalyzer.computeOverlap(testDeps, testMemberDeps, changedMembers, Set.of());

			assertEquals(1, overlap);
		}

		@Test
		@DisplayName("Method change does not affect class if test doesn't use it")
		void methodChangeDoesNotAffectIfNotUsed() {
			Set<String> testDeps = Set.of("com.example.Helper");
			Set<String> testMemberDeps = Set.of("com.example.Helper#other" // Test uses other, not compute
			);

			StructuralChangeAnalyzer.ChangedMembers changedMembers = new StructuralChangeAnalyzer.ChangedMembers(
					Set.of("com.example.Helper"), Set.of("com.example.Helper#compute"),
					Map.of("com.example.Helper", Set.of("compute")), Set.of());

			int overlap = StructuralChangeAnalyzer.computeOverlap(testDeps, testMemberDeps, changedMembers, Set.of());

			assertEquals(0, overlap);
		}

		@Test
		@DisplayName("Field change affects class only if test uses that field")
		void fieldChangeOnlyAffectsIfUsed() {
			Set<String> testDeps = Set.of("com.example.Data");
			Set<String> testMemberDeps = Set.of("com.example.Data#count" // Test accesses count
			);

			StructuralChangeAnalyzer.ChangedMembers changedMembers = new StructuralChangeAnalyzer.ChangedMembers(
					Set.of("com.example.Data"), Set.of("com.example.Data#count"),
					Map.of("com.example.Data", Set.of("count")), Set.of());

			int overlap = StructuralChangeAnalyzer.computeOverlap(testDeps, testMemberDeps, changedMembers, Set.of());

			assertEquals(1, overlap);
		}

		@Test
		@DisplayName("<init> change affects class only if test constructs objects")
		void initChangeOnlyAffectsIfConstructed() {
			Set<String> testDeps = Set.of("com.example.MyClass");
			Set<String> testMemberDeps = Set.of("com.example.MyClass#<init>" // Test constructs MyClass
			);

			StructuralChangeAnalyzer.ChangedMembers changedMembers = new StructuralChangeAnalyzer.ChangedMembers(
					Set.of("com.example.MyClass"), Set.of("com.example.MyClass#<init>"),
					Map.of("com.example.MyClass", Set.of("<init>")), Set.of());

			int overlap = StructuralChangeAnalyzer.computeOverlap(testDeps, testMemberDeps, changedMembers, Set.of());

			assertEquals(1, overlap);
		}

		@Test
		@DisplayName("Multiple members affected with partial overlap")
		void multipleAffectedMembers() {
			Set<String> testDeps = Set.of("com.example.Service");
			Set<String> testMemberDeps = Set.of("com.example.Service#start", "com.example.Service#state");

			StructuralChangeAnalyzer.ChangedMembers changedMembers = new StructuralChangeAnalyzer.ChangedMembers(
					Set.of("com.example.Service"),
					Set.of("com.example.Service#start", "com.example.Service#stop", "com.example.Service#state"),
					Map.of("com.example.Service", Set.of("start", "stop", "state")), Set.of());

			int overlap = StructuralChangeAnalyzer.computeOverlap(testDeps, testMemberDeps, changedMembers, Set.of());

			assertEquals(1, overlap);
		}
	}

	@Nested
	@DisplayName("computeStaticFieldOverlap()")
	class ComputeStaticFieldOverlap {

		@Test
		@DisplayName("No member deps returns 0")
		void noMemberDeps() {
			int overlap = StructuralChangeAnalyzer.computeStaticFieldOverlap(null,
					new StructuralChangeAnalyzer.ChangedMembers(Set.of("com.example.Config"), Set.of(), Map.of(),
							Set.of(), Set.of("com.example.Config#VERSION")));

			assertEquals(0, overlap);
		}

		@Test
		@DisplayName("Empty member deps returns 0")
		void emptyMemberDeps() {
			int overlap = StructuralChangeAnalyzer.computeStaticFieldOverlap(Set.of(),
					new StructuralChangeAnalyzer.ChangedMembers(Set.of("com.example.Config"), Set.of(), Map.of(),
							Set.of(), Set.of("com.example.Config#VERSION")));

			assertEquals(0, overlap);
		}

		@Test
		@DisplayName("No changed static fields returns 0")
		void noChangedStaticFields() {
			int overlap = StructuralChangeAnalyzer.computeStaticFieldOverlap(Set.of("com.example.Config#VERSION"),
					new StructuralChangeAnalyzer.ChangedMembers(Set.of("com.example.Config"), Set.of(), Map.of(),
							Set.of(), Set.of()));

			assertEquals(0, overlap);
		}

		@Test
		@DisplayName("Null changedMembers returns 0")
		void nullChangedMembers() {
			int overlap = StructuralChangeAnalyzer.computeStaticFieldOverlap(Set.of("com.example.Config#VERSION"),
					null);

			assertEquals(0, overlap);
		}

		@Test
		@DisplayName("Matching static field counts as 1")
		void matchingStaticFieldCounts() {
			int overlap = StructuralChangeAnalyzer.computeStaticFieldOverlap(Set.of("com.example.Config#VERSION"),
					new StructuralChangeAnalyzer.ChangedMembers(Set.of("com.example.Config"), Set.of(), Map.of(),
							Set.of(), Set.of("com.example.Config#VERSION")));

			assertEquals(1, overlap);
		}

		@Test
		@DisplayName("Multiple matching static fields counted correctly")
		void multipleMatchingStaticFields() {
			int overlap = StructuralChangeAnalyzer.computeStaticFieldOverlap(
					Set.of("com.example.Config#VERSION", "com.example.Config#DEBUG", "com.example.Config#TIMEOUT"),
					new StructuralChangeAnalyzer.ChangedMembers(Set.of("com.example.Config"), Set.of(), Map.of(),
							Set.of(), Set.of("com.example.Config#VERSION", "com.example.Config#DEBUG")));

			assertEquals(2, overlap);
		}

		@Test
		@DisplayName("Non-matching static fields not counted")
		void nonMatchingStaticFieldsNotCounted() {
			int overlap = StructuralChangeAnalyzer.computeStaticFieldOverlap(Set.of("com.example.Config#OTHER"),
					new StructuralChangeAnalyzer.ChangedMembers(Set.of("com.example.Config"), Set.of(), Map.of(),
							Set.of(), Set.of("com.example.Config#VERSION")));

			assertEquals(0, overlap);
		}
	}

	@Nested
	@DisplayName("Edge Cases and Defensive Checks")
	class EdgeCases {

		@Test
		@DisplayName("Null detail in non-field change doesn't cause NPE")
		void nullDetailInMethod() {
			StructuralDiff.Change change = createChange(StructuralDiff.Change.Kind.MODIFIED,
					StructuralDiff.Change.Category.METHOD, "com.example.Foo", "bar", null // Null detail
			);
			List<StructuralDiff.FileDiff> diffs = List.of(createFileDiff(Path.of("Foo.java"), List.of(change)));

			StructuralChangeAnalyzer.ChangedMembers result = StructuralChangeAnalyzer.fromDiffs(diffs);

			assertTrue(result.changedMemberKeys().contains("com.example.Foo#bar"));
		}

		@Test
		@DisplayName("Immutable collections returned by ChangedMembers")
		void immutableCollectionsReturned() {
			StructuralDiff.Change change = createChange(StructuralDiff.Change.Kind.ADDED,
					StructuralDiff.Change.Category.METHOD, "com.example.Test", "test", "void test()");
			List<StructuralDiff.FileDiff> diffs = List.of(createFileDiff(Path.of("Test.java"), List.of(change)));

			StructuralChangeAnalyzer.ChangedMembers result = StructuralChangeAnalyzer.fromDiffs(diffs);

			// Verify collections are immutable by attempting to modify
			assertThrows(UnsupportedOperationException.class, () -> result.changedClasses().add("com.example.Extra"));
			assertThrows(UnsupportedOperationException.class,
					() -> result.changedMemberKeys().add("com.example.Extra#member"));
			assertThrows(UnsupportedOperationException.class,
					() -> result.membersByClass().put("com.example.Extra", Set.of()));
		}

		@Test
		@DisplayName("ChangedMembers.EMPTY is truly empty")
		void emptyMembersIsEmpty() {
			StructuralChangeAnalyzer.ChangedMembers empty = StructuralChangeAnalyzer.ChangedMembers.EMPTY;

			assertTrue(empty.changedClasses().isEmpty());
			assertTrue(empty.changedMemberKeys().isEmpty());
			assertTrue(empty.membersByClass().isEmpty());
			assertTrue(empty.classesWithTypeChanges().isEmpty());
			assertTrue(empty.changedStaticFieldKeys().isEmpty());
		}

		@Test
		@DisplayName("Constructor with 3 args initializes changedStaticFieldKeys to empty")
		void constructorWith3ArgsInitializesStaticFields() {
			StructuralChangeAnalyzer.ChangedMembers members = new StructuralChangeAnalyzer.ChangedMembers(
					Set.of("com.example.Foo"), Set.of("com.example.Foo#bar"), Map.of("com.example.Foo", Set.of("bar")),
					Set.of());

			assertTrue(members.changedStaticFieldKeys().isEmpty());
		}

		@Test
		@DisplayName("Multiple diffs with same class merges member sets")
		void multipleDiffsSameClassMerged() {
			List<StructuralDiff.Change> changes1 = List.of(createChange(StructuralDiff.Change.Kind.ADDED,
					StructuralDiff.Change.Category.METHOD, "com.example.Merged", "methodA", "void methodA()"));
			List<StructuralDiff.Change> changes2 = List.of(createChange(StructuralDiff.Change.Kind.ADDED,
					StructuralDiff.Change.Category.METHOD, "com.example.Merged", "methodB", "void methodB()"));
			List<StructuralDiff.FileDiff> diffs = List.of(createFileDiff(Path.of("Merged.java"), changes1),
					createFileDiff(Path.of("Merged2.java"), changes2));

			StructuralChangeAnalyzer.ChangedMembers result = StructuralChangeAnalyzer.fromDiffs(diffs);

			Set<String> classMembers = result.membersByClass().get("com.example.Merged");
			assertEquals(2, classMembers.size());
			assertTrue(classMembers.containsAll(Set.of("methodA", "methodB")));
		}

		@Test
		@DisplayName("Static field detail with 'was/now' syntax detected")
		void staticFieldModifiedWithWasNowSyntax() {
			StructuralDiff.Change change = createChange(StructuralDiff.Change.Kind.MODIFIED,
					StructuralDiff.Change.Category.FIELD, "com.example.Config", "timeout",
					"was: static int timeout = 5000; now: static int timeout = 10000");
			List<StructuralDiff.FileDiff> diffs = List.of(createFileDiff(Path.of("Config.java"), List.of(change)));

			StructuralChangeAnalyzer.ChangedMembers result = StructuralChangeAnalyzer.fromDiffs(diffs);

			assertTrue(result.changedStaticFieldKeys().contains("com.example.Config#timeout"));
		}

		@Test
		@DisplayName("Whitespace in 'static' keyword not required to be literal")
		void staticKeywordWithSurroundingContext() {
			StructuralDiff.Change change = createChange(StructuralDiff.Change.Kind.ADDED,
					StructuralDiff.Change.Category.FIELD, "com.example.Test", "field",
					"public\nstatic\nfinal String field");
			List<StructuralDiff.FileDiff> diffs = List.of(createFileDiff(Path.of("Test.java"), List.of(change)));

			StructuralChangeAnalyzer.ChangedMembers result = StructuralChangeAnalyzer.fromDiffs(diffs);

			assertTrue(result.changedStaticFieldKeys().contains("com.example.Test#field"));
		}
	}

	@Nested
	@DisplayName("memberLevelOverlapClasses() - Detailed Precision Tests")
	class MemberLevelOverlapClassesPrecision {

		@Test
		@DisplayName("No changed members in class returns empty set")
		void noChangedMembersInClass() {
			Set<String> testDeps = Set.of("com.example.Foo");
			Set<String> testMemberDeps = Set.of("com.example.Foo#bar");

			StructuralChangeAnalyzer.ChangedMembers changedMembers = new StructuralChangeAnalyzer.ChangedMembers(
					Set.of(), Set.of(), Map.of(), Set.of());

			Set<String> result = StructuralChangeAnalyzer.computeOverlapClasses(testDeps, testMemberDeps,
					changedMembers, Set.of());

			assertTrue(result.isEmpty());
		}

		@Test
		@DisplayName("Changed class not in deps ignored")
		void changedClassNotInDeps() {
			Set<String> testDeps = Set.of("com.example.Foo");
			Set<String> testMemberDeps = Set.of();

			StructuralChangeAnalyzer.ChangedMembers changedMembers = new StructuralChangeAnalyzer.ChangedMembers(
					Set.of("com.example.Bar"), // Different class
					Set.of(), Map.of("com.example.Bar", Set.of("method")), Set.of());

			Set<String> result = StructuralChangeAnalyzer.computeOverlapClasses(testDeps, testMemberDeps,
					changedMembers, Set.of());

			assertTrue(result.isEmpty());
		}

		@Test
		@DisplayName("When membersByClass has no entry but class is changed: conservative assumption")
		void changedClassNoMemberEntry() {
			Set<String> testDeps = Set.of("com.example.Foo");
			Set<String> testMemberDeps = Set.of();

			StructuralChangeAnalyzer.ChangedMembers changedMembers = new StructuralChangeAnalyzer.ChangedMembers(
					Set.of("com.example.Foo"), // Class is changed
					Set.of(), Map.of(), // But no member entry
					Set.of());

			Set<String> result = StructuralChangeAnalyzer.computeOverlapClasses(testDeps, testMemberDeps,
					changedMembers, Set.of());

			// Conservative: assume class is affected
			assertTrue(result.contains("com.example.Foo"));
		}

		@Test
		@DisplayName("Complex scenario: mix of type, clinit, and regular changes")
		void complexMixOfChanges() {
			Set<String> testDeps = Set.of("com.example.TypeChanged", "com.example.ClinitChanged",
					"com.example.MethodChanged", "com.example.MethodNotUsed");
			Set<String> testMemberDeps = Set.of("com.example.MethodChanged#compute");

			StructuralChangeAnalyzer.ChangedMembers changedMembers = new StructuralChangeAnalyzer.ChangedMembers(
					Set.of("com.example.TypeChanged", "com.example.ClinitChanged", "com.example.MethodChanged",
							"com.example.MethodNotUsed"),
					Set.of("com.example.ClinitChanged#<clinit>", "com.example.MethodChanged#compute",
							"com.example.MethodNotUsed#unused"),
					Map.of("com.example.ClinitChanged", Set.of("<clinit>"), "com.example.MethodChanged",
							Set.of("compute"), "com.example.MethodNotUsed", Set.of("unused")),
					Set.of("com.example.TypeChanged") // Type change
			);

			Set<String> result = StructuralChangeAnalyzer.computeOverlapClasses(testDeps, testMemberDeps,
					changedMembers, Set.of());

			// TypeChanged: always affected (type-level change)
			// ClinitChanged: always affected (static initializer)
			// MethodChanged: affected because test uses compute method
			// MethodNotUsed: NOT affected because test doesn't use unused
			assertEquals(3, result.size());
			assertTrue(result.containsAll(
					Set.of("com.example.TypeChanged", "com.example.ClinitChanged", "com.example.MethodChanged")));
			assertFalse(result.contains("com.example.MethodNotUsed"));
		}
	}

	@Nested
	@DisplayName("Non-structural change overlap (comment-only changes)")
	class NonStructuralChangeOverlap {

		@Test
		@DisplayName("Comment-only change still counts as overlap when untracked structural file exists")
		void commentOnlyChangeWithUntrackedStructuralFile() {
			// Scenario: UserService has a comment-only change (no structural diff),
			// Greeter is a new untracked file (HAS structural diff).
			// UserServiceTest depends on UserService.
			// Before fix: overlap was 0 because structural analysis narrowed to only
			// Greeter.
			Set<String> testDeps = Set.of("com.myapp.service.UserService", "com.myapp.model.User");
			Set<String> testMemberDeps = Set.of(); // No member deps

			// Structural analysis only sees Greeter (the new file) as changed
			StructuralChangeAnalyzer.ChangedMembers changedMembers = new StructuralChangeAnalyzer.ChangedMembers(
					Set.of("com.myapp.Greeter"), // Only structurally-changed class
					Set.of(), Map.of(), Set.of("com.myapp.Greeter"));

			// Git detects BOTH Greeter (untracked) and UserService (comment edit)
			Set<String> changedClasses = Set.of("com.myapp.Greeter", "com.myapp.service.UserService");

			Set<String> result = StructuralChangeAnalyzer.computeOverlapClasses(testDeps, testMemberDeps,
					changedMembers, changedClasses);

			// UserService should be in the overlap set
			assertTrue(result.contains("com.myapp.service.UserService"),
					"Comment-only changed class should still count as dependency overlap");
			assertEquals(1, result.size());
		}

		@Test
		@DisplayName("Structural-only changed classes still work when changedClasses is empty")
		void structuralOnlyWhenChangedClassesEmpty() {
			Set<String> testDeps = Set.of("com.example.Foo");
			Set<String> testMemberDeps = Set.of();

			StructuralChangeAnalyzer.ChangedMembers changedMembers = new StructuralChangeAnalyzer.ChangedMembers(
					Set.of("com.example.Foo"), Set.of(), Map.of(), Set.of("com.example.Foo"));

			Set<String> result = StructuralChangeAnalyzer.computeOverlapClasses(testDeps, testMemberDeps,
					changedMembers, Set.of());

			assertTrue(result.contains("com.example.Foo"));
			assertEquals(1, result.size());
		}
	}

	@Nested
	@DisplayName("ChangedMembers Record")
	class ChangedMembersRecord {

		@Test
		@DisplayName("Record with all 5 components accessible")
		void allComponentsAccessible() {
			Set<String> classes = Set.of("com.example.Foo");
			Set<String> memberKeys = Set.of("com.example.Foo#bar");
			Map<String, Set<String>> byClass = Map.of("com.example.Foo", Set.of("bar"));
			Set<String> typeChanged = Set.of("com.example.Foo");
			Set<String> staticFields = Set.of("com.example.Foo#CONST");

			StructuralChangeAnalyzer.ChangedMembers members = new StructuralChangeAnalyzer.ChangedMembers(classes,
					memberKeys, byClass, typeChanged, staticFields);

			assertEquals(classes, members.changedClasses());
			assertEquals(memberKeys, members.changedMemberKeys());
			assertEquals(byClass, members.membersByClass());
			assertEquals(typeChanged, members.classesWithTypeChanges());
			assertEquals(staticFields, members.changedStaticFieldKeys());
		}
	}
}
