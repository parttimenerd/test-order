import org.junit.Test;
import static org.junit.Assert.*;

public class TestSuiteA {
    @Test
    public void testA1() {
        assertTrue(true);
    }
    
    @Test
    public void testA2() {
        assertEquals(2, 1 + 1);
    }
}
