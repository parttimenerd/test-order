import org.junit.Test;
import org.junit.experimental.categories.Category;
import static org.junit.Assert.*;

@Category(FastTests.class)
public class TestCategorizedFast {
    @Test
    public void test_fast_01() {
        assertTrue(true);
    }
    
    @Test
    public void test_fast_02() {
        assertEquals(1, 1);
    }
}
