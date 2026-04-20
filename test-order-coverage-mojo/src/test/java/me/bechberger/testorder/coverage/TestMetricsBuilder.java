package me.bechberger.testorder.coverage;

import java.util.ArrayList;

/**
 * Helper class for creating test fixtures
 */
public class TestMetricsBuilder {
    public static ClassMetrics create(String fqcn, String module, int lineCoverage, int methodCoverage, int branchCoverage, int testCount) {
        String pkg = fqcn.contains(".") ? fqcn.substring(0, fqcn.lastIndexOf(".")) : "";
        String className = fqcn.contains(".") ? fqcn.substring(fqcn.lastIndexOf(".") + 1) : fqcn;
        
        return new ClassMetrics(
                fqcn,
                module,
                pkg,
                className,
                lineCoverage,
                methodCoverage,
                branchCoverage,
                lineCoverage,  // statementsCovered
                100,           // statementsTotal
                methodCoverage,// methodsCovered
                10,            // methodsTotal
                testCount,
                new ArrayList<>(),
                false,
                false,
                false
        );
    }
}
