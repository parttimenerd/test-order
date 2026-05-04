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
 * Runs {@link Validator} assertions across a matrix of (name, age) profiles
 * using {@code @ClassTemplate}.  Each invocation context carries one profile
 * and the expected validation outcomes.
 */
@ClassTemplate
@ExtendWith(ValidatorClassTemplateTest.ProfileProvider.class)
class ValidatorClassTemplateTest {

    private String name;
    private int age;
    private boolean expectedNameValid;
    private boolean expectedAgeValid;

    private final Validator validator = new Validator();

    @Test
    void nameValidation() {
        assertEquals(expectedNameValid, validator.isValidName(name),
                () -> "isValidName(\"%s\") expected %s".formatted(name, expectedNameValid));
    }

    @Test
    void ageValidation() {
        assertEquals(expectedAgeValid, validator.isValidAge(age),
                () -> "isValidAge(%d) expected %s".formatted(age, expectedAgeValid));
    }

    // ---- provider ----

    public static class ProfileProvider implements ClassTemplateInvocationContextProvider {

        private record Profile(String name, int age, boolean nameValid, boolean ageValid) {}

        private static final List<Profile> PROFILES = List.of(
                new Profile("Alice", 30, true, true),
                new Profile("", 0, false, true),
                new Profile(null, -5, false, false),
                new Profile("A".repeat(101), 200, false, false)
        );

        @Override
        public boolean supportsClassTemplate(ExtensionContext context) {
            return true;
        }

        @Override
        public Stream<ClassTemplateInvocationContext> provideClassTemplateInvocationContexts(
                ExtensionContext context) {
            return PROFILES.stream().map(this::invocationContext);
        }

        private ClassTemplateInvocationContext invocationContext(Profile profile) {
            return new ClassTemplateInvocationContext() {
                @Override
                public String getDisplayName(int invocationIndex) {
                    return "name=%s, age=%d".formatted(
                            profile.name() == null ? "null" : "\"" + profile.name() + "\"",
                            profile.age());
                }

                @Override
                public List<Extension> getAdditionalExtensions() {
                    return List.of((TestInstancePostProcessor) (instance, ctx) -> {
                        var test = (ValidatorClassTemplateTest) instance;
                        test.name = profile.name();
                        test.age = profile.age();
                        test.expectedNameValid = profile.nameValid();
                        test.expectedAgeValid = profile.ageValid();
                    });
                }
            };
        }
    }
}
