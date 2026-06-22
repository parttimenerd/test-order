package com.myapp.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class FormatterTest {
    @Test
    void formatWrapsInBrackets() {
        assertEquals("[hello]", Formatter.format("hello"));
    }
}
