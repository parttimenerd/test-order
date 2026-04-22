package com.example;

import org.junit.jupiter.api.*;

/**
 * Tests specifically for test-order plugin integration with JUnit 5.
 * Tests how test-order handles discovery, ordering, and caching of advanced features.
 */
@DisplayName("Test Order Integration Tests")
public class TestOrderIntegrationTests {

    @Test
    @DisplayName("A: First test in alphabetical order")
    public void aaa_firstAlpha() {
        assert true;
    }

    @Test
    @DisplayName("Z: Last test in alphabetical order")
    public void zzz_lastAlpha() {
        assert true;
    }

    @Test
    @DisplayName("M: Middle test in alphabetical order")
    public void mmm_middleAlpha() {
        assert true;
    }

    @Test
    @Disabled("Skipped for test-order integration")
    @DisplayName("D: Disabled test that should be skipped")
    public void ddd_disabledForOrder() {
        assert false;
    }

    @Test
    @DisplayName("B: Second test")
    public void bbb_secondTest() {
        assert true;
    }

    @Test
    @DisplayName("C: Third test")
    public void ccc_thirdTest() {
        assert true;
    }
}
