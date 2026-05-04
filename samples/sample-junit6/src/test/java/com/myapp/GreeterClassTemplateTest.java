package com.myapp;

import java.util.List;
import java.util.Locale;
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
 * Exercises {@link Greeter} and {@link MessageFormatter} across several
 * user-name contexts using {@code @ClassTemplate}.
 */
@ClassTemplate
@ExtendWith(GreeterClassTemplateTest.NameProvider.class)
class GreeterClassTemplateTest {

    private String name;

    @Test
    void greetContainsName() {
        var greeter = new Greeter();
        String greeting = greeter.greet(name);
        assertTrue(greeting.contains(name),
                () -> "Expected greeting to contain '%s', got: %s".formatted(name, greeting));
    }

    @Test
    void welcomeContainsGreetingAndWelcome() {
        var formatter = new MessageFormatter(new Greeter());
        String msg = formatter.welcome(name);
        assertTrue(msg.contains("Hello, " + name + "!"));
        assertTrue(msg.contains("Welcome aboard!"));
    }

    @Test
    void farewellContainsName() {
        var formatter = new MessageFormatter(new Greeter());
        assertEquals("Goodbye, " + name + "!", formatter.farewell(name));
    }

    // ---- provider ----

    public static class NameProvider implements ClassTemplateInvocationContextProvider {

        private static final List<String> NAMES = List.of("Alice", "Bob", "世界");

        @Override
        public boolean supportsClassTemplate(ExtensionContext context) {
            return true;
        }

        @Override
        public Stream<ClassTemplateInvocationContext> provideClassTemplateInvocationContexts(
                ExtensionContext context) {
            return NAMES.stream().map(this::invocationContext);
        }

        private ClassTemplateInvocationContext invocationContext(String param) {
            return new ClassTemplateInvocationContext() {
                @Override
                public String getDisplayName(int invocationIndex) {
                    return "name=" + param;
                }

                @Override
                public List<Extension> getAdditionalExtensions() {
                    return List.of((TestInstancePostProcessor) (instance, ctx) ->
                            ((GreeterClassTemplateTest) instance).name = param);
                }
            };
        }
    }
}
