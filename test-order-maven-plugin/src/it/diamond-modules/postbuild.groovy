// Diamond-dependency IT — verifies that:
//   1. Two unrelated consumer modules (consumer-x, consumer-y) both depending
//      on the same shared module each record their cross-module edges to
//      SharedLib without ID collision.
//   2. Same-simple-name classes (me.bechberger.it.dx.Util vs
//      me.bechberger.it.dy.Util) are NOT confused — proves the reactor-wide
//      ID space prevents silent mis-attribution.
//   3. Inner-class edges to SharedLib$Token survive on both consumers.

import java.nio.file.Files
import java.nio.file.Path

def fail(String msg) { throw new AssertionError("[diamond-modules IT] " + msg) }

Path root = basedir.toPath()
Path buildLog = root.resolve("build.log")
Path indexFile = root.resolve(".test-order/test-dependencies.lz4")
Path dumpFile = root.resolve("dependency-dump.txt")

if (!Files.exists(indexFile)) {
    fail("reactor-level shared index missing at ${indexFile}")
}
if (!Files.exists(dumpFile)) {
    fail("dump output missing at ${dumpFile}")
}

String dump = Files.readString(dumpFile)

// Both consumers' tests must appear in the dump
if (!dump.contains("me.bechberger.it.dx.UtilTest")) {
    fail("dump does not record dx.UtilTest. Dump:\n${dump}")
}
if (!dump.contains("me.bechberger.it.dy.UtilTest")) {
    fail("dump does not record dy.UtilTest. Dump:\n${dump}")
}

// Both consumers must reach SharedLib (same shared dep, two independent forks)
def dxLine = dump.readLines().find { it.startsWith("me.bechberger.it.dx.UtilTest\t") }
def dyLine = dump.readLines().find { it.startsWith("me.bechberger.it.dy.UtilTest\t") }
if (dxLine == null || !dxLine.contains("me.bechberger.it.dshared.SharedLib")) {
    fail("dx.UtilTest does not record edge to SharedLib. Line:\n${dxLine}")
}
if (dyLine == null || !dyLine.contains("me.bechberger.it.dshared.SharedLib")) {
    fail("dy.UtilTest does not record edge to SharedLib. Line:\n${dyLine}")
}

// Inner-class edge from BOTH consumers
if (!dxLine.contains("SharedLib\$Token")) {
    fail("dx.UtilTest does not record edge to SharedLib\$Token. Line:\n${dxLine}")
}
if (!dyLine.contains("SharedLib\$Token")) {
    fail("dy.UtilTest does not record edge to SharedLib\$Token. Line:\n${dyLine}")
}

// Same-simple-name edges: dx.UtilTest -> dx.Util, dy.UtilTest -> dy.Util
// They must NOT be mixed up.
if (!dxLine.contains("me.bechberger.it.dx.Util")) {
    fail("dx.UtilTest does not record edge to dx.Util. Line:\n${dxLine}")
}
if (dxLine.contains("me.bechberger.it.dy.Util")) {
    fail("dx.UtilTest INCORRECTLY records edge to dy.Util — same-simple-name " +
         "classes were mis-attributed. This is the primary bug the reactor-wide " +
         "ID space prevents. Line:\n${dxLine}")
}
if (!dyLine.contains("me.bechberger.it.dy.Util")) {
    fail("dy.UtilTest does not record edge to dy.Util. Line:\n${dyLine}")
}
if (dyLine.contains("me.bechberger.it.dx.Util")) {
    fail("dy.UtilTest INCORRECTLY records edge to dx.Util — same-simple-name " +
         "classes were mis-attributed. Line:\n${dyLine}")
}

String log = Files.readString(buildLog)
if (log.contains("NoClassDefFoundError") && log.contains("UsageStore")) {
    fail("UsageStore NoClassDefFoundError in build log — realm injection regressed")
}
if (log.contains("StackOverflowError") && log.contains("loadClassFromImport")) {
    fail("StackOverflowError in classloader import chain — realm injector regressed")
}

println "[diamond-modules IT] all assertions passed"
return true
