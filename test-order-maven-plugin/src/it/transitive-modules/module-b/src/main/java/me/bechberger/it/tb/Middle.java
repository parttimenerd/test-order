package me.bechberger.it.tb;

import me.bechberger.it.ta.Bottom;

/** Middle of the transitive A -&gt; B -&gt; C chain. Calls module-a's Bottom. */
public final class Middle {
    private Middle() {
    }

    public static int relay(int x) {
        return Bottom.leaf(x) * 2;
    }

    public static int markerValue(int x) {
        return new Bottom.Marker(x).get();
    }
}
