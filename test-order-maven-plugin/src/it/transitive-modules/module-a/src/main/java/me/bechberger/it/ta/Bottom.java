package me.bechberger.it.ta;

/**
 * Bottom of the transitive chain. Used by module-b's Middle, which is used by
 * module-c's Top, which is exercised by module-c's TopTest. The IT verifies
 * that the recorded edge {@code TopTest -> Bottom} appears in the dump despite
 * Bottom being two modules away.
 */
public final class Bottom {
    private Bottom() {
    }

    public static int leaf(int x) {
        return x + 1;
    }

    /** Inner class — exercises a 2-module-deep cross-module inner-class edge. */
    public static final class Marker {
        private final int value;

        public Marker(int value) {
            this.value = value;
        }

        public int get() {
            return value;
        }
    }
}
