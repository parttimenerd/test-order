import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TestJUnit5Mixed {
    @Test
    public void test_junit5_01() {
        assertTrue(true);
    }
    
    @Test
    public void test_junit5_02() {
        assertEquals(2, 2);
    }
}
