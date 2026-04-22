import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.Collection;

@RunWith(Parameterized.class)
public class TestVintageParameterized {
    private int a;
    private int b;
    private int expected;
    
    public TestVintageParameterized(int a, int b, int expected) {
        this.a = a;
        this.b = b;
        this.expected = expected;
    }
    
    @Parameterized.Parameters(name = "{0} + {1} = {2}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][] {
            { 10, 20, 30 },
            { 5, 5, 10 }
        });
    }
    
    @Test
    public void testAdd() {
        assertEquals(expected, a + b);
    }
}
