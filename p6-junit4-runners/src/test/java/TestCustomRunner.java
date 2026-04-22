import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(CustomReverseRunner.class)
public class TestCustomRunner {
    @Test
    public void testZ_shouldRunLast() {
        assertTrue(true);
    }
    
    @Test
    public void testA_shouldRunFirst() {
        assertTrue(true);
    }
    
    @Test
    public void testM_shouldRunMiddle() {
        assertTrue(true);
    }
}
