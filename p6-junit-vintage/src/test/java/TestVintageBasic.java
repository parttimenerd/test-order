import org.junit.Test;
import static org.junit.Assert.*;

public class TestVintageBasic {
    @Test
    public void testOne() {
        assertTrue(true);
    }
    
    @Test
    public void testTwo() {
        assertEquals(2, 1 + 1);
    }
    
    @Test
    public void testThree() {
        assertNotNull("test");
    }
}
