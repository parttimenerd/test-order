import org.junit.Test;
import org.junit.experimental.categories.Category;
import static org.junit.Assert.*;

@Category(SlowTests.class)
public class TestCategorizedSlow {
    @Test
    public void test_slow_01() {
        try { Thread.sleep(10); } catch (InterruptedException e) {}
        assertTrue(true);
    }
}
