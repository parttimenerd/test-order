package com.myapp.lib;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class MessageBuilderTest {
    @Test
    void buildFormatsKeyValue() {
        assertEquals("k=[v]", MessageBuilder.build("k", "v"));
    }
}
