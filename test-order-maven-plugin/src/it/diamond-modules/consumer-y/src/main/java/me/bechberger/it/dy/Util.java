package me.bechberger.it.dy;

import me.bechberger.it.dshared.SharedLib;

/**
 * Consumer Y has a class with the SAME simple name (Util) as consumer-X — but
 * in a DIFFERENT package. Per-module ID space would have both at ID 0 in their
 * own maps; reactor-wide IDs guarantee unique IDs.
 */
public final class Util {
    private Util() {
    }

    public static int negate(int x) {
        return -SharedLib.compute(x);
    }

    public static int tokenId(int seed) {
        return new SharedLib.Token(seed * 2).id();
    }
}
