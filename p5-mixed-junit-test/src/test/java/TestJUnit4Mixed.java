import org.junit.Test;
import static org.junit.Assert.*;

public class TestJUnit4Mixed {
    @Test
    public void test_junit4_01() {
        assertTrue(true);
    }
    
    @Test
    public void test_junit4_02() {
        assertEquals(2, 2);
    }
}
