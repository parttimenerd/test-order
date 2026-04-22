import org.junit.Test;
import static org.junit.Assert.*;

public class TestSecondJUnit4 {
    @Test
    public void test_01_other_test() {
        assertTrue(1 == 1);
    }
    
    @Test
    public void test_02_arrays() {
        int[] arr = {1, 2, 3};
        assertEquals(3, arr.length);
    }
}
