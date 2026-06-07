// Transitive 3-module IT — verifies that:
//   1. TopTest in module-c is recorded as touching Bottom in module-a
//      (two modules away through Middle in module-b).
//   2. Inner-class FQN Bottom$Marker — registered during module-a's
//      instrumentation — is reachable from module-c's test fork via the
//      reactor-wide ID space.

import java.nio.file.Files
import java.nio.file.Path

def fail(String msg) { throw new AssertionError("[transitive-modules IT] " + msg) }

Path root = basedir.toPath()
Path buildLog = root.resolve("build.log")
Path indexFile = root.resolve(".test-order/test-dependencies.lz4")
Path dumpFile = root.resolve("dependency-dump.txt")

if (!Files.exists(indexFile)) {
    fail("reactor-level shared index missing at ${indexFile}")
}
if (!Files.exists(dumpFile)) {
    fail("dump output missing at ${dumpFile} — test-order:dump did not run")
}

String dump = Files.readString(dumpFile)
if (!dump.contains("TopTest")) {
    fail("dump does not mention TopTest. Dump:\n${dump}")
}
// Direct: TopTest -> Top
if (!dump.contains("me.bechberger.it.tc.Top")) {
    fail("dump does not record same-module edge TopTest -> Top. Dump:\n${dump}")
}
// One-hop: TopTest -> Middle (module-b)
if (!dump.contains("me.bechberger.it.tb.Middle")) {
    fail("dump does not record cross-module edge TopTest -> Middle (one hop). Dump:\n${dump}")
}
// Two-hop: TopTest -> Bottom (module-a, two modules away via Middle)
if (!dump.contains("me.bechberger.it.ta.Bottom")) {
    fail("dump does not record TRANSITIVE 2-hop cross-module edge TopTest -> Bottom. " +
         "This is the primary regression guard for transitive call chains across the reactor. Dump:\n${dump}")
}
// Two-hop inner: TopTest -> Bottom$Marker (module-a inner class via Middle)
if (!dump.contains("Bottom\$Marker")) {
    fail("dump does not record cross-module edge to inner class Bottom\$Marker " +
         "two modules away. Dump:\n${dump}")
}

String log = Files.readString(buildLog)
if (log.contains("NoClassDefFoundError") && log.contains("UsageStore")) {
    fail("UsageStore NoClassDefFoundError in build log — realm injection regressed")
}
if (log.contains("StackOverflowError") && log.contains("loadClassFromImport")) {
    fail("StackOverflowError in classloader import chain — realm injector regressed")
}

println "[transitive-modules IT] all assertions passed"
return true
