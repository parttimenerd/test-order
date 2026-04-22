import org.junit.Test;
import static org.junit.Assert.*;

public class TestBasicJUnit4 {
    @Test
    public void test_01_basic_assertion() {
        assertTrue(true);
    }
    
    @Test
    public void test_02_equality() {
        assertEquals(1, 1);
    }
    
    @Test
    public void test_03_not_null() {
        assertNotNull("value");
    }
}
