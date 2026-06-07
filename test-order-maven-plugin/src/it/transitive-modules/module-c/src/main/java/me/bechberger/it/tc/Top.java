package me.bechberger.it.tc;

import me.bechberger.it.tb.Middle;

/** Top of the transitive chain. Calls module-b's Middle, which calls module-a's Bottom. */
public final class Top {
    private Top() {
    }

    public static int call(int x) {
        return Middle.relay(x);
    }

    public static int markerCall(int x) {
        return Middle.markerValue(x);
    }
}
