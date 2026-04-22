import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TestSecondJUnit5 {
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
