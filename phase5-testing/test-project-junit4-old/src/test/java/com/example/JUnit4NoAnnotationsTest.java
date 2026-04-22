package com.example;

public class JUnit4NoAnnotationsTest {
    
    public void testMethodOne() {
        // This looks like a test method but has no @Test annotation
        assert true;
    }
    
    public void testMethodTwo() {
        int result = 1 + 1;
        assert result == 2;
    }
    
    public void notATestMethod() {
        // Should not run as test
    }
}
