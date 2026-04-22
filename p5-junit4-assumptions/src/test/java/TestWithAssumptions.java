import org.junit.Assume;
import org.junit.Test;
import static org.junit.Assert.*;

public class TestWithAssumptions {
    @Test
    public void testOnlyOnJava11() {
        Assume.assumeTrue(System.getProperty("java.version").contains("11"));
        assertTrue(true);
    }
    
    @Test
    public void testUnconditional() {
        assertTrue(true);
    }
    
    @Test
    public void testWithAssumption() {
        Assume.assumeTrue("Running on Unix", System.getProperty("os.name").startsWith("Unix") || 
                         System.getProperty("os.name").startsWith("Mac"));
        assertTrue(true);
    }
}
