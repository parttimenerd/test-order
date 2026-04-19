package me.bechberger.testorder.plugin.it;

import me.bechberger.testorder.DependencyMap;
import org.assertj.core.api.AbstractAssert;

import java.util.Set;

/**
 * AssertJ assertion for {@link DependencyMap}.
 * <p>
 * Usage: {@code assertThat(depMap).hasTestClass("com.example.CalculatorTest").hasDependency("com.example.CalculatorTest", "com.example.Calculator")}
 */
public class DependencyMapAssert extends AbstractAssert<DependencyMapAssert, DependencyMap> {

    private DependencyMapAssert(DependencyMap actual) {
        super(actual, DependencyMapAssert.class);
    }

    public static DependencyMapAssert assertThat(DependencyMap depMap) {
        return new DependencyMapAssert(depMap);
    }

    /** Assert the index is not null. */
    public DependencyMapAssert isLoaded() {
        if (actual == null) {
            failWithMessage("Expected dependency index to exist but it was null");
        }
        return this;
    }

    /** Assert the index has exactly N test classes. */
    public DependencyMapAssert hasSize(int expected) {
        isNotNull();
        if (actual.size() != expected) {
            failWithMessage("Expected %d test classes but found %d: %s",
                    expected, actual.size(), actual.testClasses());
        }
        return this;
    }

    /** Assert the index contains a specific test class. */
    public DependencyMapAssert hasTestClass(String testClass) {
        isNotNull();
        if (!actual.testClasses().contains(testClass)) {
            failWithMessage("Expected test class '%s' in index but found: %s",
                    testClass, actual.testClasses());
        }
        return this;
    }

    /** Assert the index does NOT contain a specific test class. */
    public DependencyMapAssert doesNotHaveTestClass(String testClass) {
        isNotNull();
        if (actual.testClasses().contains(testClass)) {
            failWithMessage("Expected test class '%s' to NOT be in index but it was", testClass);
        }
        return this;
    }

    /** Assert a test class has a specific dependency recorded. */
    public DependencyMapAssert hasDependency(String testClass, String dependency) {
        isNotNull();
        hasTestClass(testClass);
        Set<String> deps = actual.get(testClass);
        if (deps == null || !deps.contains(dependency)) {
            failWithMessage("Expected '%s' to depend on '%s' but its deps are: %s",
                    testClass, dependency, deps);
        }
        return this;
    }

    /** Assert a test class does NOT depend on a specific class. */
    public DependencyMapAssert doesNotHaveDependency(String testClass, String dependency) {
        isNotNull();
        Set<String> deps = actual.get(testClass);
        if (deps != null && deps.contains(dependency)) {
            failWithMessage("Expected '%s' to NOT depend on '%s' but it does. Deps: %s",
                    testClass, dependency, deps);
        }
        return this;
    }

    /** Assert a test class has at least N dependencies. */
    public DependencyMapAssert hasAtLeastDeps(String testClass, int minDeps) {
        isNotNull();
        hasTestClass(testClass);
        Set<String> deps = actual.get(testClass);
        int count = deps == null ? 0 : deps.size();
        if (count < minDeps) {
            failWithMessage("Expected '%s' to have at least %d deps but found %d: %s",
                    testClass, minDeps, count, deps);
        }
        return this;
    }

    /** Assert the changed classes affect a specific test (via getAffectedTests). */
    public DependencyMapAssert changesAffect(Set<String> changedClasses, String testClass) {
        isNotNull();
        Set<String> affected = actual.getAffectedTests(changedClasses);
        if (!affected.contains(testClass)) {
            failWithMessage("Expected change to %s to affect '%s' but affected tests are: %s",
                    changedClasses, testClass, affected);
        }
        return this;
    }

    /** Assert the changed classes do NOT affect a specific test. */
    public DependencyMapAssert changesDoNotAffect(Set<String> changedClasses, String testClass) {
        isNotNull();
        Set<String> affected = actual.getAffectedTests(changedClasses);
        if (affected.contains(testClass)) {
            failWithMessage("Expected change to %s to NOT affect '%s' but it did. Affected: %s",
                    changedClasses, testClass, affected);
        }
        return this;
    }
}
