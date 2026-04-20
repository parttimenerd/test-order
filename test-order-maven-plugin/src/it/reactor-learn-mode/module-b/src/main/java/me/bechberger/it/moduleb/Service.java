package me.bechberger.it.moduleb;

import me.bechberger.it.modulea.Library;

public class Service {
    public static int compute() {
        return Library.add(5, 6);
    }
}
