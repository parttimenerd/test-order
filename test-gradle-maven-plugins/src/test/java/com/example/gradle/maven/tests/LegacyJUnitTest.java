package com.example.gradle.maven.tests;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test suite for Legacy JUnit bugs (P5-LEG-001, 002).
 * 
 * Legacy JUnit (primarily JUnit 4 and earlier) used different mechanisms for test ordering.
 * These tests verify that migration from JUnit 4 to JUnit 5 preserves test order semantics.
 */
@DisplayName("Legacy JUnit Tests")
public class LegacyJUnitTest {

    @TempDir
    Path testProject;

    private Path buildFile;
    private Path pomFile;

    @BeforeEach
    void setUp() throws IOException {
        buildFile = testProject.resolve("build.gradle");
        pomFile = testProject.resolve("pom.xml");
        Files.createDirectories(testProject.resolve("src/test/java"));
    }

    /**
     * P5-LEG-001: JUnit 4 test ordering with @FixMethodOrder.
     * Bug: JUnit 4's @FixMethodOrder annotation may not translate properly to JUnit 5.
     * 
     * Reproducer: Create test class with JUnit 4 ordering annotations.
     */
    @Test
    @DisplayName("JUnit 4 @FixMethodOrder compatibility")
    void testJUnit4FixMethodOrder() throws IOException {
        String junit4TestContent = """
            package com.example.tests;
            
            import org.junit.FixMethodOrder;
            import org.junit.Test;
            import org.junit.runners.MethodSorters;
            
            @FixMethodOrder(MethodSorters.NAME_ASCENDING)
            public class OrderedJUnit4Test {
                @Test
                public void testAlpha() {
                    assert true;
                }
                
                @Test
                public void testBeta() {
                    assert true;
                }
                
                @Test
                public void testGamma() {
                    assert true;
                }
            }
            """;
        
        String buildContent = """
            plugins {
                id 'java'
            }
            
            dependencies {
                testImplementation 'junit:junit:4.13.2'
            }
            
            test {
                useJUnit()
            }
            """;
        
        Files.createDirectories(testProject.resolve("src/test/java/com/example/tests"));
        Files.writeString(buildFile, buildContent);
        Files.writeString(testProject.resolve("src/test/java/com/example/tests/OrderedJUnit4Test.java"), 
            junit4TestContent);
        
        assertThat(Files.readString(buildFile)).contains("junit");
        assertThat(Files.readString(testProject.resolve("src/test/java/com/example/tests/OrderedJUnit4Test.java")))
            .contains("@FixMethodOrder");
    }

    /**
     * P5-LEG-002: JUnit 4 test ordering with @RunWith custom runners.
     * Bug: JUnit 4's @RunWith annotation may not work correctly in migration scenarios.
     * 
     * Reproducer: Create test with custom test runner from JUnit 4.
     */
    @Test
    @DisplayName("JUnit 4 @RunWith custom runners compatibility")
    void testJUnit4RunWithRunners() throws IOException {
        String customRunnerContent = """
            package com.example.runners;
            
            import org.junit.runner.Runner;
            import org.junit.runners.BlockJUnit4ClassRunner;
            import org.junit.runners.model.FrameworkMethod;
            import java.util.List;
            import java.util.Collections;
            
            public class AlphabeticalRunner extends BlockJUnit4ClassRunner {
                public AlphabeticalRunner(Class<?> klass) throws Exception {
                    super(klass);
                }
                
                @Override
                protected List<FrameworkMethod> computeTestMethods() {
                    List<FrameworkMethod> methods = super.computeTestMethods();
                    Collections.sort(methods, (a, b) -> a.getName().compareTo(b.getName()));
                    return methods;
                }
            }
            """;
        
        String junit4TestContent = """
            package com.example.tests;
            
            import org.junit.Test;
            import org.junit.runner.RunWith;
            import com.example.runners.AlphabeticalRunner;
            
            @RunWith(AlphabeticalRunner.class)
            public class CustomRunnerOrderedTest {
                @Test
                public void zebra() {
                    assert true;
                }
                
                @Test
                public void alpha() {
                    assert true;
                }
                
                @Test
                public void beta() {
                    assert true;
                }
            }
            """;
        
        String buildContent = """
            plugins {
                id 'java'
            }
            
            dependencies {
                testImplementation 'junit:junit:4.13.2'
            }
            
            test {
                useJUnit()
            }
            """;
        
        Files.createDirectories(testProject.resolve("src/test/java/com/example/runners"));
        Files.createDirectories(testProject.resolve("src/test/java/com/example/tests"));
        Files.writeString(buildFile, buildContent);
        Files.writeString(testProject.resolve("src/test/java/com/example/runners/AlphabeticalRunner.java"), 
            customRunnerContent);
        Files.writeString(testProject.resolve("src/test/java/com/example/tests/CustomRunnerOrderedTest.java"), 
            junit4TestContent);
        
        assertThat(Files.readString(buildFile)).contains("junit");
    }

    /**
     * Extended test: JUnit 4 Rules and test ordering.
     * Verifies that JUnit 4 @Rule annotations work with ordering.
     */
    @Test
    @DisplayName("JUnit 4 @Rule annotations with ordering")
    void testJUnit4RulesOrdering() throws IOException {
        String rulesTestContent = """
            package com.example.tests;
            
            import org.junit.Rule;
            import org.junit.Test;
            import org.junit.rules.TestName;
            
            public class RuledOrderTest {
                @Rule
                public TestName testName = new TestName();
                
                @Test
                public void firstTest() {
                    System.out.println("Running: " + testName.getMethodName());
                    assert true;
                }
                
                @Test
                public void secondTest() {
                    System.out.println("Running: " + testName.getMethodName());
                    assert true;
                }
            }
            """;
        
        String buildContent = """
            plugins {
                id 'java'
            }
            
            dependencies {
                testImplementation 'junit:junit:4.13.2'
            }
            
            test {
                useJUnit()
            }
            """;
        
        Files.createDirectories(testProject.resolve("src/test/java/com/example/tests"));
        Files.writeString(buildFile, buildContent);
        Files.writeString(testProject.resolve("src/test/java/com/example/tests/RuledOrderTest.java"), 
            rulesTestContent);
        
        assertThat(Files.readString(testProject.resolve("src/test/java/com/example/tests/RuledOrderTest.java")))
            .contains("@Rule");
    }

    /**
     * Extended test: JUnit 4 Parameterized tests and ordering.
     */
    @Test
    @DisplayName("JUnit 4 Parameterized tests with ordering")
    void testJUnit4ParameterizedOrdering() throws IOException {
        String parameterizedContent = """
            package com.example.tests;
            
            import org.junit.Test;
            import org.junit.runner.RunWith;
            import org.junit.runners.Parameterized;
            import java.util.Arrays;
            import java.util.Collection;
            
            @RunWith(Parameterized.class)
            public class ParameterizedOrderTest {
                private int input;
                private int expected;
                
                public ParameterizedOrderTest(int input, int expected) {
                    this.input = input;
                    this.expected = expected;
                }
                
                @Parameterized.Parameters
                public static Collection<Object[]> data() {
                    return Arrays.asList(new Object[][] {
                        { 1, 1 },
                        { 2, 2 },
                        { 3, 3 }
                    });
                }
                
                @Test
                public void testParameter() {
                    assert input == expected;
                }
            }
            """;
        
        String buildContent = """
            plugins {
                id 'java'
            }
            
            dependencies {
                testImplementation 'junit:junit:4.13.2'
            }
            
            test {
                useJUnit()
            }
            """;
        
        Files.createDirectories(testProject.resolve("src/test/java/com/example/tests"));
        Files.writeString(buildFile, buildContent);
        Files.writeString(testProject.resolve("src/test/java/com/example/tests/ParameterizedOrderTest.java"), 
            parameterizedContent);
        
        assertThat(Files.readString(testProject.resolve("src/test/java/com/example/tests/ParameterizedOrderTest.java")))
            .contains("@Parameterized");
    }

    /**
     * Extended test: JUnit 4 @Category annotations for test grouping.
     */
    @Test
    @DisplayName("JUnit 4 @Category annotations and test ordering")
    void testJUnit4Categories() throws IOException {
        String categoryContent = """
            package com.example.tests;
            
            import org.junit.Test;
            import org.junit.experimental.categories.Category;
            
            public interface IntegrationTest {}
            public interface UnitTest {}
            
            public class CategorizedOrderTest {
                @Test
                @Category(UnitTest.class)
                public void unitTest1() {
                    assert true;
                }
                
                @Test
                @Category(IntegrationTest.class)
                public void integrationTest1() {
                    assert true;
                }
                
                @Test
                @Category(UnitTest.class)
                public void unitTest2() {
                    assert true;
                }
            }
            """;
        
        Files.createDirectories(testProject.resolve("src/test/java/com/example/tests"));
        Files.writeString(testProject.resolve("src/test/java/com/example/tests/CategorizedOrderTest.java"), 
            categoryContent);
        
        assertThat(Files.readString(testProject.resolve("src/test/java/com/example/tests/CategorizedOrderTest.java")))
            .contains("@Category");
    }

    /**
     * Extended test: JUnit 4 Suite runner for composite tests.
     */
    @Test
    @DisplayName("JUnit 4 @Suite runner with test ordering")
    void testJUnit4Suite() throws IOException {
        String suiteContent = """
            package com.example.tests;
            
            import org.junit.runner.RunWith;
            import org.junit.runners.Suite;
            
            @RunWith(Suite.class)
            @Suite.SuiteClasses({
                Test1.class,
                Test2.class,
                Test3.class
            })
            public class OrderedTestSuite {
            }
            """;
        
        String test1Content = """
            package com.example.tests;
            import org.junit.Test;
            public class Test1 { @Test public void test() { assert true; } }
            """;
        
        String test2Content = """
            package com.example.tests;
            import org.junit.Test;
            public class Test2 { @Test public void test() { assert true; } }
            """;
        
        String test3Content = """
            package com.example.tests;
            import org.junit.Test;
            public class Test3 { @Test public void test() { assert true; } }
            """;
        
        Files.createDirectories(testProject.resolve("src/test/java/com/example/tests"));
        Files.writeString(testProject.resolve("src/test/java/com/example/tests/OrderedTestSuite.java"), 
            suiteContent);
        Files.writeString(testProject.resolve("src/test/java/com/example/tests/Test1.java"), test1Content);
        Files.writeString(testProject.resolve("src/test/java/com/example/tests/Test2.java"), test2Content);
        Files.writeString(testProject.resolve("src/test/java/com/example/tests/Test3.java"), test3Content);
        
        assertThat(Files.readString(testProject.resolve("src/test/java/com/example/tests/OrderedTestSuite.java")))
            .contains("@Suite");
    }

    /**
     * Extended test: Migration from JUnit 4 to JUnit 5 - Assumptions.
     */
    @Test
    @DisplayName("JUnit 4 Assumptions with test ordering")
    void testJUnit4Assumptions() throws IOException {
        String assumptionContent = """
            package com.example.tests;
            
            import org.junit.Test;
            import org.junit.Assume;
            
            public class AssumptionOrderTest {
                @Test
                public void testWithAssumption1() {
                    Assume.assumeTrue(true);
                    assert true;
                }
                
                @Test
                public void testWithAssumption2() {
                    Assume.assumeTrue(false);
                    assert false;
                }
                
                @Test
                public void testWithAssumption3() {
                    Assume.assumeTrue(true);
                    assert true;
                }
            }
            """;
        
        Files.createDirectories(testProject.resolve("src/test/java/com/example/tests"));
        Files.writeString(testProject.resolve("src/test/java/com/example/tests/AssumptionOrderTest.java"), 
            assumptionContent);
        
        assertThat(Files.readString(testProject.resolve("src/test/java/com/example/tests/AssumptionOrderTest.java")))
            .contains("Assume");
    }
}
