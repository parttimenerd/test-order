package me.bechberger.it.moduleb;

import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;

import me.bechberger.it.modulea.Library;

/** Module B's class that exercises module A. */
public final class Service {
    private Service() {
    }

    public static int compute() {
        return Library.add(5, 6);
    }

    /** Touches module-A's inner class — exercises a cross-module inner-class edge. */
    public static int countTo(int n) {
        Library.Counter counter = new Library.Counter();
        for (int i = 0; i < n; i++) {
            counter.increment();
        }
        return counter.get();
    }

    /**
     * Touches module-A's anonymous inner class via {@link Library#doubler()}.
     * The actual class loaded at runtime is {@code Library$1}.
     */
    public static int doubleIt(int n) {
        IntUnaryOperator op = Library.doubler();
        return op.applyAsInt(n);
    }

    /**
     * Touches module-A's lambda — runtime materializes a synthetic class
     * with a name like {@code Library$$Lambda$N} or
     * {@code Library$$Lambda/0x...}.
     */
    public static int tripleIt(int n) {
        IntUnaryOperator op = Library.tripler();
        return op.applyAsInt(n);
    }

    /**
     * Touches the doubly-nested inner class {@code Library$Counter$Snapshot}
     * — verifies reactor-wide ID assignment handles arbitrary nesting depth
     * across module boundaries.
     */
    public static int snapshot(int n) {
        Library.Counter c = new Library.Counter();
        for (int i = 0; i < n; i++) {
            c.increment();
        }
        return new Library.Counter.Snapshot(c.get()).value();
    }

    /**
     * Touches module-A's generic-bridge anonymous class
     * {@code Library$2} — exercising a cross-module synthetic-bridge edge.
     */
    public static String greet(String name) {
        Supplier<String> s = Library.stringSupplier("Hi " + name);
        return s.get();
    }

    /**
     * Touches the deepest level of nesting: {@code Library$Counter$Snapshot$1}
     * — anonymous class declared inside a doubly-nested inner class. Verifies
     * reactor-wide IDs work for arbitrary-depth synthetic classes across
     * module boundaries.
     */
    public static int deepestAnon(int n) {
        Library.Counter c = new Library.Counter();
        for (int i = 0; i < n; i++) {
            c.increment();
        }
        return new Library.Counter.Snapshot(c.get()).asSupplier().get();
    }
}
