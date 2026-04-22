import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.Collection;

@RunWith(Parameterized.class)
public class TestParameterizedMath {
    
    private int a;
    private int b;
    private int expected;
    
    public TestParameterizedMath(int a, int b, int expected) {
        this.a = a;
        this.b = b;
        this.expected = expected;
    }
    
    @Parameterized.Parameters
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][] {
            { 1, 2, 3 },
            { 2, 3, 5 },
            { 3, 4, 7 }
        });
    }
    
    @Test
    public void testSum() {
        assertEquals(expected, a + b);
    }
}
