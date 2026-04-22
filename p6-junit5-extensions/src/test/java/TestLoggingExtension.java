import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.junit.jupiter.api.Assertions.*;

class LoggingExtension implements BeforeEachCallback {
    @Override
    public void beforeEach(ExtensionContext context) {
        System.out.println("Running: " + context.getDisplayName());
    }
}

@ExtendWith(LoggingExtension.class)
public class TestLoggingExtension {
    @Test
    public void testOne() {
        assertTrue(true);
    }
    
    @Test
    public void testTwo() {
        assertEquals(2, 1 + 1);
    }
    
    @Test
    public void testThree() {
        assertNotNull("test");
    }
}
