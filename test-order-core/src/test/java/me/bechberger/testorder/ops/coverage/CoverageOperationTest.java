package me.bechberger.testorder.ops.coverage;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.ops.PluginLog;

class CoverageOperationTest {

    private static final PluginLog NO_OP_LOG = new PluginLog() {
        @Override public void info(String msg) {}
        @Override public void warn(String msg) {}
        @Override public void error(String msg) {}
        @Override public void debug(String msg) {}
    };

    @Test
    void analyzeSortsByTestCountAscending() {
        DependencyMap depMap = new DependencyMap();
        depMap.put("com.example.FooTest", Set.of("com.example.Foo", "com.example.Util"));
        depMap.put("com.example.BarTest", Set.of("com.example.Bar"));
        depMap.put("com.example.BazTest", Set.of("com.example.Foo", "com.example.Bar", "com.example.Baz"));

        CoverageAnalysis result = CoverageOperation.analyze(depMap, NO_OP_LOG);

        // Foo is exercised by FooTest + BazTest (2), Bar by BarTest + BazTest (2),
        // Util by FooTest (1), Baz by BazTest (1)
        var entries = result.entries();
        // Sorted least-tested first
        assertTrue(entries.get(0).testCount() <= entries.get(entries.size() - 1).testCount());
    }

    @Test
    void analyzeExcludesTestClassesFromProductionEntries() {
        DependencyMap depMap = new DependencyMap();
        // FooTest depends on itself (e.g. a test that calls static methods on itself) AND on Foo
        depMap.put("com.example.FooTest", Set.of("com.example.Foo", "com.example.FooTest"));

        CoverageAnalysis result = CoverageOperation.analyze(depMap, NO_OP_LOG);

        // FooTest is a test class — it must not appear as a production class in coverage
        boolean hasFooTest = result.entries().stream()
                .anyMatch(c -> c.fullyQualifiedName().equals("com.example.FooTest"));
        assertFalse(hasFooTest, "Test class must not appear in production coverage entries");

        // Foo is a production class — it should appear
        boolean hasFoo = result.entries().stream()
                .anyMatch(c -> c.fullyQualifiedName().equals("com.example.Foo"));
        assertTrue(hasFoo, "Production class Foo should appear in coverage entries");
    }

    @Test
    void noMemberCoveragePercentWhenAllMembersUnknown() {
        // BUG: when allMembers == exercisedMembers (from instrumentation data only),
        // memberCoveragePercent() was returning 100% — which is misleading because
        // we don't know the total member count.
        DependencyMap depMap = new DependencyMap();
        depMap.put("com.example.FooTest", Set.of("com.example.Foo"));
        // Add member-level deps for FooTest → Foo#method1 and Foo#method2
        depMap.putMemberDeps("com.example.FooTest", Set.of("com.example.Foo#method1", "com.example.Foo#method2"));

        CoverageAnalysis result = CoverageOperation.analyze(depMap, NO_OP_LOG);

        var fooEntry = result.entries().stream()
                .filter(c -> c.fullyQualifiedName().equals("com.example.Foo"))
                .findFirst();
        assertTrue(fooEntry.isPresent());
        ClassCoverage foo = fooEntry.get();

        // hasMemberCoverage() should be true (we do have exercised members)
        assertTrue(foo.hasMemberCoverage(), "should report member coverage when exercised members known");

        // exercisedMembers should contain method1 and method2
        assertEquals(Set.of("method1", "method2"), foo.exercisedMembers());

        // memberCoveragePercent() must NOT return 100% — it should return -1
        // because we don't know the total member count (only instrumented ones)
        assertEquals(-1, foo.memberCoveragePercent(),
                "memberCoveragePercent must be -1 when allMembers is unknown (not 100% as before the fix)");
    }

    @Test
    void generatedReportDoesNotShowMisleading100Percent() {
        DependencyMap depMap = new DependencyMap();
        depMap.put("com.example.FooTest", Set.of("com.example.Foo"));
        depMap.putMemberDeps("com.example.FooTest", Set.of("com.example.Foo#bar"));

        CoverageAnalysis analysis = CoverageOperation.analyze(depMap, NO_OP_LOG);
        String report = CoverageOperation.generateReport(analysis, 2);

        // Must not contain "100%" as member coverage
        assertFalse(report.contains("| Member coverage | 100%"),
                "Report must not claim 100% member coverage when total member count is unknown");
    }

    @Test
    void printSummaryDoesNotShowMisleadingFractionWhenTotalUnknown() {
        // BUG: printSummary() was printing "2989/0 (-1%)" when allMembers was unknown
        // (only instrumentation data, no static analysis). This is fixed to show
        // "N exercised (total unknown)" instead.
        DependencyMap depMap = new DependencyMap();
        depMap.put("com.example.FooTest", Set.of("com.example.Foo"));
        depMap.putMemberDeps("com.example.FooTest", Set.of("com.example.Foo#bar", "com.example.Foo#baz"));

        CoverageAnalysis analysis = CoverageOperation.analyze(depMap, NO_OP_LOG);

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        CoverageOperation.printSummary(analysis, 2, new PrintStream(buf));
        String output = buf.toString();

        assertFalse(output.contains("/0"), "printSummary must not show '/0' fraction when totalMembers is unknown");
        assertFalse(output.contains("(-1%)"), "printSummary must not show '(-1%)' sentinel value to users");
        assertTrue(output.contains("exercised"), "printSummary should use 'exercised' phrasing when total unknown");
    }

    @Test
    void emptyCoverageAnalysis() {
        DependencyMap depMap = new DependencyMap();
        CoverageAnalysis result = CoverageOperation.analyze(depMap, NO_OP_LOG);
        assertTrue(result.entries().isEmpty());
        assertEquals(0, result.stats().totalClasses());
    }

    @Test
    void untestedClassesCountIsCorrect() {
        DependencyMap depMap = new DependencyMap();
        depMap.put("com.example.FooTest", Set.of("com.example.Foo"));
        // Bar is not depended upon by any test — won't appear in the coverage at all
        // (coverage only includes production classes that appear in the dep map)
        CoverageAnalysis result = CoverageOperation.analyze(depMap, NO_OP_LOG);
        // Only Foo appears (it's in the dep map); no untested classes in this case
        assertEquals(0, result.stats().untestedClasses());
    }

    @Test
    void classCoverageConstructorWithAllMembersKnown() {
        // Verify that when allMembers IS provided (future use case),
        // memberCoveragePercent() returns correct value
        ClassCoverage c = new ClassCoverage("com.example.Foo",
                List.of("FooTest"),
                Set.of("method1", "method2", "method3"),
                Set.of("method1", "method2"),
                Map.of("method1", List.of("FooTest"), "method2", List.of("FooTest")));

        assertTrue(c.hasMemberCoverage());
        assertEquals(66, c.memberCoveragePercent());
        assertEquals(Set.of("method3"), c.uncoveredMembers());
    }
}
