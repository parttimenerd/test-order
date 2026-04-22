package com.example.advanced;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Pattern 3: Custom Test Extensions
 * Testing @RegisterExtension with custom extensions
 * Extensions that modify test lifecycle
 */
class P3CustomExtensionTests {

    static class LoggingExtension implements BeforeEachCallback {
        @Override
        public void beforeEach(ExtensionContext context) {
            System.out.println("Before: " + context.getDisplayName());
        }
    }

    @RegisterExtension
    static LoggingExtension loggingExt = new LoggingExtension();

    @Test
    void testWithExtension1() {
        assert true;
    }

    @Test
    void testWithExtension2() {
        assert true;
    }

    @Test
    void testWithExtension3() {
        assert true;
    }

    // Total: 3 tests with extension
}
