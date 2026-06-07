package me.bechberger.it.modulea;

import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;

/**
 * Plain class in module A. The cross-module IT modifies this class to verify
 * that a test in module B which calls into it gets selected by affected mode.
 */
public final class Library {
    private Library() {
    }

    public static int add(int a, int b) {
        return a + b;
    }

    /**
     * Returns an anonymous {@link IntUnaryOperator} that doubles its input.
     * The compiler synthesizes a class named {@code Library$1} for this — the
     * cross-module IT verifies the reactor-wide ID space covers anonymous
     * inner classes too, even though the source-root scanner cannot see them.
     */
    public static IntUnaryOperator doubler() {
        return new IntUnaryOperator() {
            @Override
            public int applyAsInt(int value) {
                return value * 2;
            }
        };
    }

    /**
     * Returns a lambda — at runtime the JVM materializes a synthetic class
     * (typically {@code Library$$Lambda$N} or {@code Library$$Lambda/0x...}).
     * The cross-module IT verifies the reactor-wide ID assignment doesn't
     * choke on lambda-synthetic FQNs.
     */
    public static IntUnaryOperator tripler() {
        return value -> value * 3;
    }

    /**
     * Generic factory whose anonymous {@link Supplier} body forces the
     * compiler to emit a synthetic bridge method (Object get() →
     * String get()). The bridge lives on a nested anonymous class
     * ({@code Library$2}) — the IT verifies these synthetic-bridge inner
     * classes still get reactor-wide IDs.
     */
    public static Supplier<String> stringSupplier(String value) {
        return new Supplier<String>() {
            @Override
            public String get() {
                return value + "!";
            }
        };
    }

    /**
     * Inner class — used by the cross-module IT to verify that inner-class
     * FQNs (which the source-root scanner does NOT see) still get assigned
     * stable reactor-wide IDs at instrumentation time.
     */
    public static final class Counter {
        private int value;

        public Counter() {
            this.value = 0;
        }

        public Counter increment() {
            value++;
            return this;
        }

        public int get() {
            return value;
        }

        /**
         * Doubly-nested inner class — exercises {@code Library$Counter$Snapshot}
         * to confirm reactor-wide ID assignment handles arbitrary nesting depth.
         */
        public static final class Snapshot {
            private final int value;

            public Snapshot(int value) {
                this.value = value;
            }

            public int value() {
                return value;
            }

            /**
             * Anonymous class declared inside a doubly-nested inner —
             * compiler emits {@code Library$Counter$Snapshot$1}. Tests that
             * arbitrary-depth nested anonymous classes survive.
             */
            public Supplier<Integer> asSupplier() {
                return new Supplier<Integer>() {
                    @Override
                    public Integer get() {
                        return value;
                    }
                };
            }
        }
    }
}
