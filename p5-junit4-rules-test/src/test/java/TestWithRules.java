import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import static org.junit.Assert.*;

public class TestWithRules {
    @Rule
    public TestName testName = new TestName();
    
    @Test
    public void test_01_rule_testname() {
        assertEquals("test_01_rule_testname", testName.getMethodName());
    }
    
    @Test
    public void test_02_another() {
        assertTrue(true);
    }
}
