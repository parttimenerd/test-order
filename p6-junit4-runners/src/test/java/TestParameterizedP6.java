import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.Collection;

@RunWith(Parameterized.class)
public class TestParameterizedP6 {
    private int x;
    private int y;
    private int sum;
    
    public TestParameterizedP6(int x, int y, int sum) {
        this.x = x;
        this.y = y;
        this.sum = sum;
    }
    
    @Parameterized.Parameters(name = "Test {index}: {0} + {1} = {2}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][] {
            { 1, 1, 2 },
            { 2, 2, 4 },
            { 0, 0, 0 },
            { -1, 1, 0 }
        });
    }
    
    @Test
    public void testAdd() {
        assertEquals(sum, x + y);
    }
}
