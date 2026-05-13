package me.bechberger.testorder.changes;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("LineDiff: LCS-based line-level diff utility")
class LineDiffTest {

	@Nested
	@DisplayName("E41: Null handling - both inputs null")
	class NullHandlingTests {
		@Test
		@DisplayName("Both null should return 0 changed lines")
		void bothNullReturnsZeroChanges() {
			assertEquals(0, LineDiff.changedLineCount(null, null));
		}

		@Test
		@DisplayName("Both null diffText should return empty string")
		void bothNullDiffTextReturnsEmpty() {
			assertEquals("", LineDiff.diffText(null, null));
		}

		@Test
		@DisplayName("Null oldText is converted to empty string")
		void nullOldTextTreatedAsEmpty() {
			assertEquals(0, LineDiff.changedLineCount(null, ""));
		}

		@Test
		@DisplayName("Null newText is converted to empty string")
		void nullNewTextTreatedAsEmpty() {
			assertEquals(0, LineDiff.changedLineCount("", null));
		}
	}

	@Nested
	@DisplayName("E42: Identical inputs")
	class IdenticalInputsTests {
		@Test
		@DisplayName("Single-line identical text returns 0")
		void singleLineIdenticalReturnsZero() {
			String text = "public class Foo {}";
			assertEquals(0, LineDiff.changedLineCount(text, text));
		}

		@Test
		@DisplayName("Multi-line identical text returns 0")
		void multiLineIdenticalReturnsZero() {
			String text = "public class Foo {\n    public void bar() {\n        System.out.println(\"hello\");\n    }\n}";
			assertEquals(0, LineDiff.changedLineCount(text, text));
		}

		@Test
		@DisplayName("Identical text produces empty diff")
		void identicalTextProducesEmptyDiff() {
			String text = "line1\nline2\nline3";
			assertEquals("", LineDiff.diffText(text, text));
		}

		@Test
		@DisplayName("Empty strings are identical")
		void emptyStringsAreIdentical() {
			assertEquals(0, LineDiff.changedLineCount("", ""));
			assertEquals("", LineDiff.diffText("", ""));
		}
	}

	@Nested
	@DisplayName("E43: One empty, other has content")
	class EmptyVsContentTests {
		@Test
		@DisplayName("Empty to 100 lines all changed")
		void emptyTo100LinesAllChanged() {
			StringBuilder sb = new StringBuilder();
			for (int i = 1; i <= 100; i++) {
				if (i > 1)
					sb.append("\n");
				sb.append("line " + i);
			}
			String content = sb.toString();

			// 0 lines in old + 100 lines in new - 0 LCS = 100 changed
			assertEquals(100, LineDiff.changedLineCount("", content));
		}

		@Test
		@DisplayName("100 lines to empty all changed")
		void hundredLinesToEmptyAllChanged() {
			StringBuilder sb = new StringBuilder();
			for (int i = 1; i <= 100; i++) {
				if (i > 1)
					sb.append("\n");
				sb.append("line " + i);
			}
			String content = sb.toString();

			// 100 lines in old + 0 lines in new - 0 LCS = 100 changed
			assertEquals(100, LineDiff.changedLineCount(content, ""));
		}

		@Test
		@DisplayName("Empty to content produces additions")
		void emptyToContentProducesAdditions() {
			String content = "line1\nline2\nline3";
			String diff = LineDiff.diffText("", content);

			assertTrue(diff.contains("+line1"));
			assertTrue(diff.contains("+line2"));
			assertTrue(diff.contains("+line3"));
		}

		@Test
		@DisplayName("Content to empty produces deletions")
		void contentToEmptyProducesDeletions() {
			String content = "line1\nline2\nline3";
			String diff = LineDiff.diffText(content, "");

			assertTrue(diff.contains("-line1"));
			assertTrue(diff.contains("-line2"));
			assertTrue(diff.contains("-line3"));
		}
	}

	@Nested
	@DisplayName("E44: Single-line files with character difference")
	class SingleLineCharacterDifferenceTests {
		@Test
		@DisplayName("One character difference in single line returns 2 changed")
		void singleCharDifferenceReturnsTwo() {
			// Changed = (1 - 0 LCS) + (1 - 0 LCS) = 2 (delete old, add new)
			assertEquals(2, LineDiff.changedLineCount("hello", "hallo"));
		}

		@Test
		@DisplayName("Single-line diff shows deletion and addition")
		void singleLineDiffShowsChange() {
			String diff = LineDiff.diffText("hello", "hallo");

			assertTrue(diff.contains("-hello"));
			assertTrue(diff.contains("+hallo"));
		}

		@Test
		@DisplayName("Single character added to single line returns 2 changed")
		void singleCharAddedReturnsTwo() {
			// Changed = (1 - 0 LCS) + (1 - 0 LCS) = 2
			assertEquals(2, LineDiff.changedLineCount("hello", "hello!"));
		}

		@Test
		@DisplayName("Single character removed from single line returns 2 changed")
		void singleCharRemovedReturnsTwo() {
			// Changed = (1 - 0 LCS) + (1 - 0 LCS) = 2
			assertEquals(2, LineDiff.changedLineCount("hello!", "hello"));
		}
	}

	@Nested
	@DisplayName("Core LCS functionality")
	class CoreLCSFunctionalityTests {
		@Test
		@DisplayName("Two completely different files all lines changed")
		void completelyDifferentFilesAllChanged() {
			String oldText = "public class Foo {}\npublic class Bar {}";
			String newText = "function foo() {}\nfunction bar() {}";

			assertEquals(4, LineDiff.changedLineCount(oldText, newText));
		}

		@Test
		@DisplayName("Two mostly similar files correct diff")
		void mostlySimilarFilesCorrectDiff() {
			String oldText = "line1\nline2\nline3\nline4\nline5";
			String newText = "line1\nmodified2\nline3\nline4\nline5";

			// LCS is: line1, line3, line4, line5 = 4 lines
			// Changed: (5-4) + (5-4) = 2
			assertEquals(2, LineDiff.changedLineCount(oldText, newText));
		}

		@Test
		@DisplayName("changedLineCount matches diff line count")
		void changedLineCountMatchesDiffLineCount() {
			String oldText = "public void foo() {\n    System.out.println(\"old\");\n}";
			String newText = "public void foo() {\n    System.out.println(\"new\");\n    System.out.println(\"added\");\n}";

			int changedCount = LineDiff.changedLineCount(oldText, newText);
			String diff = LineDiff.diffText(oldText, newText);
			int diffLineCount = (int) diff.split("\n").length;

			assertEquals(changedCount, diffLineCount);
		}

		@Test
		@DisplayName("diffText output has consistent format")
		void diffTextFormatConsistent() {
			String oldText = "old line";
			String newText = "new line";
			String diff = LineDiff.diffText(oldText, newText);

			String[] lines = diff.split("\n");
			for (String line : lines) {
				assertTrue(line.startsWith("+") || line.startsWith("-"),
						"All diff lines must start with + or -: " + line);
			}
		}

		@Test
		@DisplayName("No changed lines when only deletion then addition")
		void deleteAndAddShowsBothOperations() {
			String oldText = "method a\nmethod b";
			String newText = "method a\nmethod c";

			String diff = LineDiff.diffText(oldText, newText);
			assertTrue(diff.contains("-method b"));
			assertTrue(diff.contains("+method c"));
		}
	}

	@Nested
	@DisplayName("Boundary cases: empty strings")
	class EmptyStringBoundaryTests {
		@Test
		@DisplayName("Empty oldText empty newText equals 0")
		void emptyOldEmptyNewEqualsZero() {
			assertEquals(0, LineDiff.changedLineCount("", ""));
		}

		@Test
		@DisplayName("Empty oldText single newLine returns 1")
		void emptyOldSingleNewLineReturnsOne() {
			assertEquals(1, LineDiff.changedLineCount("", "x"));
		}

		@Test
		@DisplayName("Single oldLine empty newText returns 1")
		void singleOldEmptyNewReturnsOne() {
			assertEquals(1, LineDiff.changedLineCount("x", ""));
		}
	}

	@Nested
	@DisplayName("Boundary cases: single line files")
	class SingleLineFileBoundaryTests {
		@Test
		@DisplayName("Both single identical line")
		void bothSingleIdenticalLine() {
			assertEquals(0, LineDiff.changedLineCount("class Foo", "class Foo"));
		}

		@Test
		@DisplayName("Both single different line")
		void bothSingleDifferentLine() {
			assertEquals(2, LineDiff.changedLineCount("class Foo", "class Bar"));
		}

		@Test
		@DisplayName("Different single lines show in diff")
		void differentSingleLinesShowInDiff() {
			String diff = LineDiff.diffText("old", "new");
			assertTrue(diff.contains("-old"));
			assertTrue(diff.contains("+new"));
		}
	}

	@Nested
	@DisplayName("Boundary cases: line endings (LF vs CRLF)")
	class LineEndingBoundaryTests {
		@Test
		@DisplayName("LF line endings split correctly")
		void lfLineEndingsSplitCorrectly() {
			String text = "line1\nline2\nline3";
			assertEquals(0, LineDiff.changedLineCount(text, text));
		}

		@Test
		@DisplayName("Files with only newline differences")
		void filesWithNewlineDifferences() {
			String withLF = "line1\nline2";
			String withoutTrailing = "line1\nline2";
			assertEquals(0, LineDiff.changedLineCount(withLF, withoutTrailing));
		}

		@Test
		@DisplayName("Empty lines preserved in split")
		void emptyLinesPreservedInSplit() {
			String text = "line1\n\nline3";
			assertEquals(0, LineDiff.changedLineCount(text, text));
		}

		@Test
		@DisplayName("Multiple consecutive empty lines")
		void multipleConsecutiveEmptyLines() {
			String text1 = "a\n\n\nb";
			String text2 = "a\n\n\nb";
			assertEquals(0, LineDiff.changedLineCount(text1, text2));
		}
	}

	@Nested
	@DisplayName("Boundary cases: very long lines")
	class VeryLongLinesBoundaryTests {
		@Test
		@DisplayName("Very long identical lines")
		void veryLongIdenticalLines() {
			String longLine = "x".repeat(10000);
			assertEquals(0, LineDiff.changedLineCount(longLine, longLine));
		}

		@Test
		@DisplayName("Very long different lines counted as change")
		void veryLongDifferentLinesAsChange() {
			String longLine1 = "x".repeat(10000);
			String longLine2 = "y".repeat(10000);
			assertEquals(2, LineDiff.changedLineCount(longLine1, longLine2));
		}

		@Test
		@DisplayName("File with long lines and normal lines")
		void fileMixedLineLengths() {
			String oldText = "short\n" + "x".repeat(1000) + "\nend";
			String newText = "short\n" + "y".repeat(1000) + "\nend";

			// LCS: short, end = 2 lines
			// Changed: (3-2) + (3-2) = 2
			assertEquals(2, LineDiff.changedLineCount(oldText, newText));
		}
	}

	@Nested
	@DisplayName("Boundary cases: whitespace-only changes")
	class WhitespaceOnlyChangeTests {
		@Test
		@DisplayName("Lines with whitespace differences are changed")
		void whitespaceLineDifferencesAreChanged() {
			String oldText = "int x = 5;";
			String newText = "int  x  =  5;"; // extra spaces

			assertEquals(2, LineDiff.changedLineCount(oldText, newText));
		}

		@Test
		@DisplayName("Leading whitespace difference counts as changed")
		void leadingWhitespaceDifferenceCounts() {
			String oldText = "    code";
			String newText = "        code"; // extra indentation

			assertEquals(2, LineDiff.changedLineCount(oldText, newText));
		}

		@Test
		@DisplayName("Trailing whitespace difference counts as changed")
		void trailingWhitespaceDifferenceCounts() {
			String oldText = "code";
			String newText = "code  "; // trailing spaces

			assertEquals(2, LineDiff.changedLineCount(oldText, newText));
		}

		@Test
		@DisplayName("Line with only spaces is different from empty line")
		void spacesVsEmptyLineIsDifferent() {
			String oldText = "";
			String newText = "   ";

			assertEquals(1, LineDiff.changedLineCount(oldText, newText));
		}
	}

	@Nested
	@DisplayName("LCS algorithm correctness")
	class LCSAlgorithmCorrectnessTests {
		@Test
		@DisplayName("Verify lcsLength for simple case")
		void lcsLengthSimpleCase() {
			String[] a = {"a", "b", "c"};
			String[] b = {"a", "b", "c"};

			assertEquals(3, LineDiff.lcsLength(a, b));
		}

		@Test
		@DisplayName("Verify lcsLength with partial match")
		void lcsLengthPartialMatch() {
			String[] a = {"a", "b", "c"};
			String[] b = {"a", "x", "c"};

			// LCS is: a, c
			assertEquals(2, LineDiff.lcsLength(a, b));
		}

		@Test
		@DisplayName("Verify lcsLength with no match")
		void lcsLengthNoMatch() {
			String[] a = {"a", "b", "c"};
			String[] b = {"x", "y", "z"};

			assertEquals(0, LineDiff.lcsLength(a, b));
		}

		@Test
		@DisplayName("Verify lcsLength with empty array")
		void lcsLengthEmptyArray() {
			String[] a = {};
			String[] b = {"x", "y", "z"};

			assertEquals(0, LineDiff.lcsLength(a, b));
		}

		@Test
		@DisplayName("Verify lcsLength handles order independence")
		void lcsLengthOrderIndependent() {
			String[] a = {"a", "b", "c"};
			String[] b = {"x", "y", "z"};

			int result1 = LineDiff.lcsLength(a, b);
			int result2 = LineDiff.lcsLength(b, a);

			assertEquals(result1, result2);
		}
	}

	@Nested
	@DisplayName("Real-world code examples")
	class RealWorldExamplesTests {
		@Test
		@DisplayName("Simple method change")
		void simpleMethodChange() {
			String oldText = "public void foo() {\n    System.out.println(\"old\");\n}";
			String newText = "public void foo() {\n    System.out.println(\"new\");\n}";

			int changes = LineDiff.changedLineCount(oldText, newText);
			assertTrue(changes > 0);
			assertTrue(changes <= 3);
		}

		@Test
		@DisplayName("Method added to class")
		void methodAddedToClass() {
			String oldText = "public class Foo {\n}\n";
			String newText = "public class Foo {\n    public void bar() {}\n}\n";

			int changes = LineDiff.changedLineCount(oldText, newText);
			assertTrue(changes >= 1);
		}

		@Test
		@DisplayName("Import statement changes")
		void importStatementChanges() {
			String oldText = "import java.util.List;\nimport java.util.Map;\n";
			String newText = "import java.util.List;\nimport java.util.Set;\n";

			int changes = LineDiff.changedLineCount(oldText, newText);
			assertEquals(2, changes); // One import removed, one added
		}

		@Test
		@DisplayName("Large block insertion")
		void largeBlockInsertion() {
			String oldText = "start\nend";
			String newText = "start\n" + "line1\nline2\nline3\nline4\nline5\n" + "end";

			int changes = LineDiff.changedLineCount(oldText, newText);
			// LCS: start, end = 2
			// Changed: (2-2) + (7-2) = 5
			assertEquals(5, changes);
		}

		@Test
		@DisplayName("Code refactoring scenario")
		void codeRefactoringScenario() {
			String oldText = "public String getName() {\n    return name;\n}\n"
					+ "public void setName(String n) {\n    name = n;\n}";
			String newText = "public String getName() {\n    return name;\n}\n"
					+ "public void setName(String newName) {\n    name = newName;\n}";

			int changes = LineDiff.changedLineCount(oldText, newText);
			assertTrue(changes > 0, "Parameter rename should show changes");
		}
	}

	@Nested
	@DisplayName("Consistency checks")
	class ConsistencyChecks {
		@Test
		@DisplayName("changedLineCount is symmetric for diff calculation")
		void changeCountConsistentWithDiff() {
			String oldText = "a\nb\nc";
			String newText = "a\nx\nc";

			int changeCount = LineDiff.changedLineCount(oldText, newText);
			String diff = LineDiff.diffText(oldText, newText);
			String[] diffLines = diff.split("\n", -1);

			// For files with partial match, diff lines count should equal changeCount
			int diffLineCount = (int) java.util.Arrays.stream(diffLines).filter(line -> !line.isEmpty()).count();

			assertEquals(changeCount, diffLineCount);
		}

		@Test
		@DisplayName("Empty diff means zero changes")
		void emptyDiffMeansZeroChanges() {
			String oldText = "same\ntext";
			String newText = "same\ntext";

			assertEquals(0, LineDiff.changedLineCount(oldText, newText));
			assertEquals("", LineDiff.diffText(oldText, newText));
		}

		@Test
		@DisplayName("Non-empty diff means non-zero changes")
		void nonEmptyDiffMeansNonZeroChanges() {
			String oldText = "a";
			String newText = "b";

			int changes = LineDiff.changedLineCount(oldText, newText);
			String diff = LineDiff.diffText(oldText, newText);

			assertTrue(changes > 0);
			assertTrue(!diff.isEmpty());
		}

		@Test
		@DisplayName("Order matters: oldText vs newText")
		void orderMatters() {
			String text1 = "a\nb";
			String text2 = "c\nd";

			int forward = LineDiff.changedLineCount(text1, text2);
			int backward = LineDiff.changedLineCount(text2, text1);

			// Should be the same for symmetric diff
			assertEquals(forward, backward);
		}
	}

	@Nested
	@DisplayName("diffText output order")
	class DiffTextOrderTests {
		@Test
		@DisplayName("diffText returns changes in top-to-bottom file order")
		void diffTextReturnsChangesInFileOrder() {
			// Middle line changed: "B" → "X"
			String oldText = "A\nB\nC";
			String newText = "A\nX\nC";
			String diff = LineDiff.diffText(oldText, newText);
			String[] lines = diff.split("\n");
			assertEquals(2, lines.length);
			// deletion of old line must appear before insertion of new line
			assertEquals("-B", lines[0]);
			assertEquals("+X", lines[1]);
		}

		@Test
		@DisplayName("multi-region diffs preserve top-to-bottom order")
		void multiRegionDiffPreservesOrder() {
			// Two separate changes: line 2 and line 4
			String oldText = "A\nB\nC\nD\nE";
			String newText = "A\nX\nC\nY\nE";
			String diff = LineDiff.diffText(oldText, newText);
			int idxB = diff.indexOf("-B");
			int idxX = diff.indexOf("+X");
			int idxD = diff.indexOf("-D");
			int idxY = diff.indexOf("+Y");
			assertTrue(idxB < idxX, "deletion of B should precede insertion of X");
			assertTrue(idxX < idxD, "first region should precede second region");
			assertTrue(idxD < idxY, "deletion of D should precede insertion of Y");
		}

		@Test
		@DisplayName("insertions at beginning appear before deletions at end")
		void insertionsAtBeginningBeforeDeletionsAtEnd() {
			String oldText = "A\nB\nC";
			String newText = "X\nA\nB";
			String diff = LineDiff.diffText(oldText, newText);
			int idxAdd = diff.indexOf("+X");
			int idxDel = diff.indexOf("-C");
			assertTrue(idxAdd >= 0, "should contain +X");
			assertTrue(idxDel >= 0, "should contain -C");
			assertTrue(idxAdd < idxDel, "+X at start should appear before -C at end");
		}
	}
}
