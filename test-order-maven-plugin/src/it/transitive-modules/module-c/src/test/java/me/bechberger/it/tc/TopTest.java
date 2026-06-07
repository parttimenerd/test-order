package me.bechberger.it.tc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The 3-module transitive IT's only test. {@link Top#call(int)} calls module-b's
 * Middle, which calls module-a's Bottom. The reactor index must record the
 * full transitive set of edges: {@code TopTest -> Top, Middle, Bottom,
 * Bottom$Marker}.
 */
class TopTest {
    @Test
    void transitiveChainAcrossThreeModules() {
        assertEquals(8, Top.call(3));
    }

    @Test
    void transitiveInnerClass() {
        assertEquals(7, Top.markerCall(7));
    }
}
