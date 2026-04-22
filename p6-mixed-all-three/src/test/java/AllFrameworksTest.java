import static org.junit.Assert.*;

public class AllFrameworksTest {
    @org.junit.Test
    public void testJunit4() {
        System.out.println("AllFrameworksTest.testJunit4");
        assertTrue(true);
    }
    
    @org.junit.jupiter.api.Test
    void testJunit5() {
        System.out.println("AllFrameworksTest.testJunit5");
        assertTrue(true);
    }
    
    @org.testng.annotations.Test
    public void testTestNG() {
        System.out.println("AllFrameworksTest.testTestNG");
        assertTrue(true);
    }
}
