import org.junit.Test;
import static org.junit.Assert.*;

public class JUnit4TestA {
    @Test
    public void testOne() {
        System.out.println("JUnit4TestA.testOne");
        assertTrue(true);
    }
    @Test
    public void testTwo() {
        System.out.println("JUnit4TestA.testTwo");
        assertTrue(true);
    }
}
