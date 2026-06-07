package me.bechberger.it.dshared;

/**
 * Shared module exercised by two unrelated consumer modules. The diamond IT
 * verifies that the reactor index records BOTH consumer-X's and consumer-Y's
 * tests as touching SharedLib, without ID confusion between the two parallel
 * forks.
 */
public final class SharedLib {
    private SharedLib() {
    }

    public static int compute(int x) {
        return x + 100;
    }

    public static final class Token {
        private final int id;

        public Token(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }
    }
}
