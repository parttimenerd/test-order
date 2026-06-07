package me.bechberger.it.dx;

import me.bechberger.it.dshared.SharedLib;

/**
 * Consumer X has a class with the SAME simple name (Util) as consumer-Y.
 * If the per-module ID space were still in use, both Utils would have ID 0 in
 * their respective maps. Reactor-wide IDs guarantee they're distinct.
 */
public final class Util {
    private Util() {
    }

    public static int wrap(int x) {
        return SharedLib.compute(x);
    }

    public static int tokenId(int seed) {
        return new SharedLib.Token(seed).id();
    }
}
