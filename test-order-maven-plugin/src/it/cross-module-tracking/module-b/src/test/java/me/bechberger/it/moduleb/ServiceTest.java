package me.bechberger.it.moduleb;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Module B's test that calls Service, which in turn calls module A's Library.
 * The cross-module IT verifies the learn pass records the
 * {@code ServiceTest -> Library} edge across module boundaries — including
 * the {@code ServiceTest -> Library$Counter} edge for the cross-module inner
 * class (which the source-root pre-pass cannot see, but instrumentation
 * registers under the reactor-wide ID space), the cross-module ANONYMOUS
 * class {@code Library$1}, the lambda-synthetic class
 * {@code Library$$Lambda$N}, the doubly-nested
 * {@code Library$Counter$Snapshot}, the generic-bridge anonymous
 * {@code Library$2}, and the deepest-level
 * {@code Library$Counter$Snapshot$1}.
 */
class ServiceTest {
    @Test
    void callsAcrossModules() {
        assertEquals(11, Service.compute());
    }

    @Test
    void touchesCrossModuleInnerClass() {
        assertEquals(3, Service.countTo(3));
    }

    @Test
    void touchesCrossModuleAnonymousClass() {
        assertEquals(14, Service.doubleIt(7));
    }

    @Test
    void touchesCrossModuleLambda() {
        assertEquals(15, Service.tripleIt(5));
    }

    @Test
    void touchesDoublyNestedInnerClass() {
        assertEquals(4, Service.snapshot(4));
    }

    @Test
    void touchesGenericBridgeAnonymousClass() {
        assertEquals("Hi World!", Service.greet("World"));
    }

    @Test
    void touchesDeepestNestedAnonymousClass() {
        assertEquals(5, Service.deepestAnon(5));
    }
}
