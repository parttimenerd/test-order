// Cross-module dependency tracking IT — verifies that:
//   1. Both module-a's LibraryTest and module-b's ServiceTest ran.
//   2. The reactor-level shared index was created.
//   3. The dump output records LibraryTest -> Library (proves the learn pass
//      worked for module-a) and ServiceTest is present in module-b's index.
//   4. No NoClassDefFoundError appeared in the build log (UsageStore visibility
//      regression — this is the primary thing this IT guards against).
//   5. No StackOverflowError in classloader import chain (circular-import guard).

import java.nio.file.Files
import java.nio.file.Path

def fail(String msg) { throw new AssertionError("[cross-module-tracking IT] " + msg) }

Path root = basedir.toPath()
Path buildLog = root.resolve("build.log")
Path indexFile = root.resolve(".test-order/test-dependencies.lz4")
Path dumpFile = root.resolve("dependency-dump.txt")
Path moduleAReports = root.resolve("module-a/target/surefire-reports")
Path moduleBReports = root.resolve("module-b/target/surefire-reports")

// --- 1. Both tests ran ----------------------------------------------------
def hasReport = { Path dir, String simpleName ->
    Files.isDirectory(dir) && Files.list(dir).filter { it.fileName.toString().contains(simpleName) }.findFirst().isPresent()
}
if (!hasReport(moduleAReports, "LibraryTest")) {
    fail("module-a/LibraryTest surefire report missing — test did not run")
}
if (!hasReport(moduleBReports, "ServiceTest")) {
    fail("module-b/ServiceTest surefire report missing — test did not run")
}

// --- 2. Reactor index created ---------------------------------------------
if (!Files.exists(indexFile)) {
    fail("reactor-level shared index missing at ${indexFile}")
}
if (Files.size(indexFile) < 16) {
    fail("reactor-level shared index suspiciously small (${Files.size(indexFile)} bytes)")
}

// --- 3. Intra-module edge recorded (LibraryTest -> Library) ---------------
if (!Files.exists(dumpFile)) {
    fail("dump output missing at ${dumpFile} — test-order:dump did not run")
}
String dump = Files.readString(dumpFile)
if (!dump.contains("LibraryTest")) {
    fail("dump does not mention LibraryTest. Dump:\n${dump}")
}
if (!dump.contains("me.bechberger.it.modulea.Library")) {
    fail("dump does not record edge LibraryTest -> Library. Dump:\n${dump}")
}

// --- 3b. Cross-module edge recorded (ServiceTest -> Library) -------------
// This is the primary validation of the reactor-wide unified class-id-map.
// Without it, the classId baked into module-A's instrumented Library bytecode
// would mean a different FQN in module-B's fork, so the edge would be
// silently mis-attributed (or dropped if the ID is past the module's local
// max). With Option A's reactor-wide ID space, classId N always means the
// same FQN regardless of which module's fork is recording it.
if (!dump.contains("ServiceTest\tme.bechberger.it.modulea.Library")
        && !dump.contains("me.bechberger.it.moduleb.ServiceTest\tme.bechberger.it.modulea.Library")) {
    fail("dump does not record cross-module edge ServiceTest -> Library. " +
         "This is the primary regression guard for the reactor-wide class-id-map. Dump:\n${dump}")
}

// --- 3c. Cross-module INNER-class edge recorded ---------------------------
// Inner-class FQNs (Library$Counter) are NOT visible to the source-root scanner
// (which only walks .java files) but ARE assigned IDs by the per-module prepare
// when it instruments Library.class. The reactor-wide ID space must extend to
// inner classes too, otherwise module-B's fork wouldn't be able to resolve
// their IDs.
if (!dump.contains("Library\$Counter")) {
    fail("dump does not record edge to inner class Library\$Counter. " +
         "Inner-class FQNs registered during instrumentation must be visible to " +
         "cross-module test forks. Dump:\n${dump}")
}

// --- 3d. Cross-module ANONYMOUS class edge recorded -----------------------
// The compiler synthesizes Library\$1 for the anonymous IntUnaryOperator in
// Library.doubler(). The source-root scanner cannot see it, but instrumentation
// assigns it an ID under the reactor-wide ID space. ServiceTest exercises it
// via Service.doubleIt() — the resulting edge must appear in the dump.
if (!dump.contains("Library\$1")) {
    fail("dump does not record edge to anonymous class Library\$1. " +
         "Anonymous-class FQNs from the compiler must be visible to cross-module " +
         "test forks. Dump:\n${dump}")
}

// --- 3e. Doubly-nested inner-class edge recorded --------------------------
// Library\$Counter\$Snapshot exercises arbitrary nesting depth.
if (!dump.contains("Library\$Counter\$Snapshot")) {
    fail("dump does not record edge to doubly-nested inner class " +
         "Library\$Counter\$Snapshot. Reactor-wide ID assignment must handle " +
         "arbitrary nesting depth across module boundaries. Dump:\n${dump}")
}

// --- 3f. Generic-bridge anonymous class edge recorded ---------------------
// Library\$2 is the second compiler-synthesized anonymous class — its body
// implements Supplier<String>, forcing a synthetic bridge method
// (Object get() → String get()). The anonymous class itself must be tracked
// across the module boundary.
if (!dump.contains("Library\$2")) {
    fail("dump does not record edge to generic-bridge anonymous class " +
         "Library\$2. Synthetic-bridge anonymous classes must be visible " +
         "to cross-module test forks. Dump:\n${dump}")
}

// --- 3g. Deepest-level nested anonymous class edge recorded ---------------
// Library\$Counter\$Snapshot\$1 is an anonymous class declared INSIDE a
// doubly-nested inner class. The reactor-wide ID space must extend to
// arbitrary-depth synthetic classes.
if (!dump.contains("Library\$Counter\$Snapshot\$1")) {
    fail("dump does not record edge to deepest-level anonymous " +
         "Library\$Counter\$Snapshot\$1. Arbitrary-depth nested anonymous " +
         "classes must work across module boundaries. Dump:\n${dump}")
}

// --- 4. No UsageStore visibility regression --------------------------------
String log = Files.readString(buildLog)
if (log.contains("NoClassDefFoundError") && log.contains("UsageStore")) {
    fail("UsageStore NoClassDefFoundError in build log — realm injection regressed")
}
// --- 5. No circular-import StackOverflow in realm injector ----------------
if (log.contains("StackOverflowError") && log.contains("loadClassFromImport")) {
    fail("StackOverflowError in classloader import chain — realm injector regressed (circular imports?)")
}

println "[cross-module-tracking IT] all assertions passed"
return true
