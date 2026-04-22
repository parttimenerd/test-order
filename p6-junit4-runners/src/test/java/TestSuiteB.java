import org.junit.Test;
import static org.junit.Assert.*;

public class TestSuiteB {
    @Test
    public void testB1() {
        assertFalse(false);
    }
    
    @Test
    public void testB2() {
        assertNotNull("test");
    }
}
