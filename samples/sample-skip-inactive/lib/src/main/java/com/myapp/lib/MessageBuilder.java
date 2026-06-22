package com.myapp.lib;

import com.myapp.core.Formatter;

public class MessageBuilder {
    public static String build(String key, String value) {
        return key + "=" + Formatter.format(value);
    }
}
