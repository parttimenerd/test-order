package com.myapp;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.ClassTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ClassTemplateInvocationContext;
import org.junit.jupiter.api.extension.ClassTemplateInvocationContextProvider;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestInstancePostProcessor;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates {@code @ClassTemplate} by running all test methods once per
 * operand pair.  Each invocation injects different (a, b) values.
 */
@ClassTemplate
@ExtendWith(MathServiceClassTemplateTest.OperandProvider.class)
class MathServiceClassTemplateTest {

    private int a;
    private int b;

    private final MathService math = new MathService();

    @Test
    void additionIsCommutative() {
        assertEquals(math.add(a, b), math.add(b, a));
    }

    @Test
    void multiplicationIsCommutative() {
        assertEquals(math.multiply(a, b), math.multiply(b, a));
    }

    @Test
    void divisionByNonZero() {
        if (b != 0) {
            double result = math.divide(a, b);
            assertEquals(a, result * b, 1e-9);
        }
    }

    // ---- provider ----

    public static class OperandProvider implements ClassTemplateInvocationContextProvider {

        private record Operands(int a, int b) {}

        private static final List<Operands> CASES = List.of(
                new Operands(2, 3),
                new Operands(0, 5),
                new Operands(-4, 7)
        );

        @Override
        public boolean supportsClassTemplate(ExtensionContext context) {
            return true;
        }

        @Override
        public Stream<ClassTemplateInvocationContext> provideClassTemplateInvocationContexts(
                ExtensionContext context) {
            return CASES.stream().map(this::invocationContext);
        }

        private ClassTemplateInvocationContext invocationContext(Operands ops) {
            return new ClassTemplateInvocationContext() {
                @Override
                public String getDisplayName(int invocationIndex) {
                    return "a=%d, b=%d".formatted(ops.a(), ops.b());
                }

                @Override
                public List<Extension> getAdditionalExtensions() {
                    return List.of((TestInstancePostProcessor) (instance, ctx) -> {
                        var test = (MathServiceClassTemplateTest) instance;
                        test.a = ops.a();
                        test.b = ops.b();
                    });
                }
            };
        }
    }
}
