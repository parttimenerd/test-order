import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import static org.junit.Assert.*;

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class TestFixedOrder {
    @Test
    public void test_01_first() {
        assertTrue(true);
    }
    
    @Test
    public void test_02_second() {
        assertEquals(1, 1);
    }
    
    @Test
    public void test_03_third() {
        assertNotNull("test");
    }
}
