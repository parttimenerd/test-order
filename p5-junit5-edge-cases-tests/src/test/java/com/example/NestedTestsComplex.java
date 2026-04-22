package com.example;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for @Nested nested test classes with complex hierarchies.
 * Tests edge cases in discovery and ordering of nested tests.
 */
@DisplayName("Nested Test Classes")
public class NestedTestsComplex {

    @Test
    @DisplayName("Outer Level Test 1")
    public void outerTest01() {
        assert true;
    }

    @Nested
    @DisplayName("First Level Nested - A")
    class NestedLevelA {
        @Test
        @DisplayName("A.1 - First test in A")
        public void a01() {
            assert true;
        }

        @Test
        @DisplayName("A.2 - Second test in A")
        public void a02() {
            assert true;
        }

        @Nested
        @DisplayName("Second Level Nested - A.i")
        class NestedLevelAi {
            @Test
            @DisplayName("A.i.1 - Deep nested test")
            public void ai01() {
                assert true;
            }

            @Test
            @DisplayName("A.i.2 - Another deep test")
            public void ai02() {
                assert true;
            }
        }

        @Test
        @DisplayName("A.3 - Test after deep nesting")
        public void a03() {
            assert true;
        }
    }

    @Test
    @DisplayName("Outer Level Test 2")
    public void outerTest02() {
        assert true;
    }

    @Nested
    @DisplayName("Second Level Nested - B")
    class NestedLevelB {
        @Test
        @DisplayName("B.1 - First test in B")
        public void b01() {
            assert true;
        }

        @Nested
        @DisplayName("Second Level Nested - B.i")
        class NestedLevelBi {
            @Test
            @DisplayName("B.i.1 - Nested in B")
            public void bi01() {
                assert true;
            }

            @Nested
            @DisplayName("Third Level Nested - B.i.α")
            class NestedLevelBia {
                @Test
                @DisplayName("B.i.α.1 - Triple nested")
                public void bia01() {
                    assert true;
                }
            }

            @Test
            @DisplayName("B.i.2 - After triple nesting")
            public void bi02() {
                assert true;
            }
        }

        @Test
        @DisplayName("B.2 - Test after B.i nesting")
        public void b02() {
            assert true;
        }
    }

    @Test
    @DisplayName("Outer Level Test 3")
    public void outerTest03() {
        assert true;
    }

    @Nested
    @DisplayName("Third Level Nested - C")
    class NestedLevelC {
        @Test
        @DisplayName("C.1 - Test in C")
        public void c01() {
            assert true;
        }
    }
}
