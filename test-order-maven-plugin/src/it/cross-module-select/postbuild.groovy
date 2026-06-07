// Cross-module select IT — verifies that:
//   1. Phase 1 (learn): dep index is created and non-empty.
//   2. Phase 1: both library and consumer tests ran.
//   3. Phase 2 (select): when Calculator (library module) is reported as changed,
//      CalculatorTest (consumer module) is included in the selected test list.
//   4. The selection happens across module boundaries — confirming that a change
//      in a library module propagates to tests in the dependent consumer module.

import java.nio.file.Files
import java.nio.file.Path

def fail(String msg) { throw new AssertionError("[cross-module-select IT] " + msg) }

Path root = basedir.toPath()
Path buildLog = root.resolve("build.log")
Path indexFile = root.resolve(".test-order/test-dependencies.lz4")
Path consumerSelectedFile = root.resolve("consumer/target/test-order-selected.txt")
Path consumerSurefireReports = root.resolve("consumer/target/surefire-reports")
Path librarySurefireReports = root.resolve("library/target/surefire-reports")

// --- 1. Dep index created in phase 1 ----------------------------------------
if (!Files.exists(indexFile)) {
    fail("dep index not created at ${indexFile} — phase 1 (learn) did not run successfully")
}
if (Files.size(indexFile) < 16) {
    fail("dep index suspiciously small (${Files.size(indexFile)} bytes) — learn may have recorded no deps")
}

// --- 2. Library and consumer tests ran in phase 1 ----------------------------
def hasReport = { Path dir, String simpleName ->
    Files.isDirectory(dir) && Files.list(dir).filter { it.fileName.toString().contains(simpleName) }.findFirst().isPresent()
}
if (!hasReport(librarySurefireReports, "CalculatorSanityTest")) {
    fail("library/CalculatorSanityTest surefire report missing — library tests did not run in phase 1")
}
if (!hasReport(consumerSurefireReports, "CalculatorTest")) {
    fail("consumer/CalculatorTest surefire report missing — consumer tests did not run in phase 1")
}

// --- 3. CalculatorTest was selected in phase 2 --------------------------------
// The affected goal writes target/test-order-selected.txt listing selected tests.
if (!Files.exists(consumerSelectedFile)) {
    fail("consumer/target/test-order-selected.txt not found — 'affected' goal did not run in phase 2")
}
String selected = Files.readString(consumerSelectedFile)
if (selected.isBlank()) {
    fail("test-order-selected.txt is empty — no tests were selected despite Calculator being reported as changed. " +
         "Cross-module dep tracking is broken: changing the library should trigger consumer tests.")
}
if (!selected.contains("CalculatorTest")) {
    fail("CalculatorTest not found in selected tests despite Calculator being explicitly changed. " +
         "Selected tests: ${selected.trim()}. " +
         "This means the cross-module dep edge (CalculatorTest -> Calculator) was not recorded " +
         "or was not used during selection.")
}

// --- 4. CalculatorTest actually ran in phase 2 --------------------------------
if (!hasReport(consumerSurefireReports, "CalculatorTest")) {
    fail("CalculatorTest surefire report missing — test was selected but did not run")
}

// --- 5. No plugin errors in build log ----------------------------------------
String log = Files.readString(buildLog)
if (log.contains("NullPointerException") && log.contains("me.bechberger.testorder")) {
    fail("NPE in test-order plugin stack trace found in build log")
}

println "[cross-module-select IT] all assertions passed"
println "[cross-module-select IT] selected tests: ${selected.trim()}"
return true
