package me.bechberger.testorder.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for IntelligentClassFilter with various filtering strategies.
 */
@DisplayName("Intelligent Class Filter Tests")
public class IntelligentClassFilterTest {
    
    private IntelligentClassFilter filter;
    
    @Test
    @DisplayName("WHITELIST mode: accepts only explicitly included packages")
    public void testWhitelistMode() {
        filter = new IntelligentClassFilter.Builder()
                .strategy(IntelligentClassFilter.Strategy.WHITELIST)
                .explicitInclude("com.example")
                .explicitInclude("org.app")
                .build();
        
        assertTrue(filter.shouldInstrument("com/example/Foo"));
        assertTrue(filter.shouldInstrument("org/app/Bar"));
        assertFalse(filter.shouldInstrument("com/other/Baz"));
        assertFalse(filter.shouldInstrument("java/lang/String")); // always skipped
    }
    
    @Test
    @DisplayName("BLACKLIST mode: accepts all except excluded packages")
    public void testBlacklistMode() {
        filter = new IntelligentClassFilter.Builder()
                .strategy(IntelligentClassFilter.Strategy.BLACKLIST)
                .explicitExclude("com.test")
                .explicitExclude("org.mock")
                .build();
        
        assertTrue(filter.shouldInstrument("com/example/Foo"));
        assertFalse(filter.shouldInstrument("com/test/TestClass"));
        assertFalse(filter.shouldInstrument("org/mock/MockService"));
        assertFalse(filter.shouldInstrument("java/lang/String")); // always skipped
    }
    
    @Test
    @DisplayName("SMART mode: prefers whitelist if specified, else blacklist")
    public void testSmartMode() {
        filter = new IntelligentClassFilter.Builder()
                .strategy(IntelligentClassFilter.Strategy.SMART)
                .explicitInclude("com.example")
                .build();
        
        assertTrue(filter.shouldInstrument("com/example/Foo"));
        assertFalse(filter.shouldInstrument("com/other/Bar"));
        assertFalse(filter.shouldInstrument("java/lang/String")); // always skipped
    }
    
    @Test
    @DisplayName("Heuristics: skip generated classes")
    public void testSkipGeneratedClasses() {
        filter = new IntelligentClassFilter.Builder()
                .useHeuristics(true)
                .build();
        
        //Proxies
        assertFalse(filter.shouldInstrument("com/example/$Proxy10"));
        // CGLIB
        assertFalse(filter.shouldInstrument("com/example/Service$$EnhancerBySpringCGLIB"));
        // ByteBuddy
        assertFalse(filter.shouldInstrument("com/example/$$ByteBuddy$Mock"));
        // Lambda
        assertFalse(filter.shouldInstrument("com/example/Foo$$Lambda$123"));
        
        // Normal classes should pass
        assertTrue(filter.shouldInstrument("com/example/NormalService"));
    }
    
    @Test
    @DisplayName("Skip test classes by default")
    public void testSkipTestClasses() {
        filter = new IntelligentClassFilter.Builder()
                .skipTestClasses(true)
                .build();
        
        assertFalse(filter.shouldInstrument("com/example/UserServiceTest"));
        assertFalse(filter.shouldInstrument("com/example/MockRepository"));
        assertFalse(filter.shouldInstrument("com/example/FakeFactory"));
        
        assertTrue(filter.shouldInstrument("com/example/UserService"));
    }
    
    @Test
    @DisplayName("Always skip JDK/system classes")
    public void testAlwaysSkipJdk() {
        filter = new IntelligentClassFilter.Builder()
                .strategy(IntelligentClassFilter.Strategy.WHITELIST)
                .build();
        
        assertFalse(filter.shouldInstrument("java/lang/String"));
        assertFalse(filter.shouldInstrument("jdk/internal/Foo"));
        assertFalse(filter.shouldInstrument("sun/reflect/Bar"));
        assertFalse(filter.shouldInstrument("javax/servlet/Baz"));
        assertFalse(filter.shouldInstrument("me/bechberger/testorder/agent/Agent"));
    }
    
    @Test
    @DisplayName("Cache performance: repeated checks are cached")
    public void testCachePerformance() {
        filter = new IntelligentClassFilter.Builder()
                .explicitInclude("com.example")
                .maxCacheSize(100)
                .build();
        
        // First check: cache miss
        assertTrue(filter.shouldInstrument("com/example/Foo"));
        IntelligentClassFilter.CacheStats stats1 = filter.getCacheStats();
        assertEquals(1, stats1.misses(), "First call should be a miss");
        
        // Second check: cache hit
        assertTrue(filter.shouldInstrument("com/example/Foo"));
        IntelligentClassFilter.CacheStats stats2 = filter.getCacheStats();
        assertEquals(1, stats2.hits(), "Repeated call should hit cache");
        
        // Hit rate should be > 0
        assertTrue(stats2.hitRate() > 0, "Should have some cache hits");
    }
    
    @Test
    @DisplayName("Exclude patterns: regex support")
    public void testExcludePatterns() {
        filter = new IntelligentClassFilter.Builder()
                .excludePattern(".*Test$")           // classes ending in Test
                .excludePattern(".*Mock.*")          // classes with Mock anywhere
                .build();
        
        assertFalse(filter.shouldInstrument("com/example/UserTest"));
        assertFalse(filter.shouldInstrument("com/example/MockService"));
        assertFalse(filter.shouldInstrument("com/example/ServiceMockImpl"));
        
        assertTrue(filter.shouldInstrument("com/example/UserService"));
    }
    
    @Test
    @DisplayName("Complex scenario: whitelist + exclude + heuristics")
    public void testComplexFiltering() {
        filter = new IntelligentClassFilter.Builder()
                .strategy(IntelligentClassFilter.Strategy.SMART)
                .explicitInclude("com.example")
                .explicitInclude("org.app")
                .excludePattern(".*Config$")
                .skipTestClasses(true)
                .useHeuristics(true)
                .build();
        
        // Included, not excluded
        assertTrue(filter.shouldInstrument("com/example/UserService"));
        
        // Included but excluded pattern
        assertFalse(filter.shouldInstrument("com/example/AppConfig"));
        
        // Included but is test class
        assertFalse(filter.shouldInstrument("com/example/UserServiceTest"));
        
        // Not included
        assertFalse(filter.shouldInstrument("com/unrelated/Other"));
        
        // Generated
        assertFalse(filter.shouldInstrument("com/example/Service$$Proxy10"));
    }
    
    @Test
    @DisplayName("WHITELIST_SMART mode: strict whitelist")
    public void testWhitelistSmartMode() {
        filter = new IntelligentClassFilter.Builder()
                .strategy(IntelligentClassFilter.Strategy.WHITELIST_SMART)
                .explicitInclude("com.example")
                .build();
        
        // Included package passes
        assertTrue(filter.shouldInstrument("com/example/Foo"));
        
        // Not in whitelist → rejected (even without explicit exclude)
        assertFalse(filter.shouldInstrument("com/other/Bar"));
    }
}
