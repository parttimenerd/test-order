import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.junit.jupiter.api.Assertions.*;

class Ext1 implements BeforeTestExecutionCallback {
    @Override
    public void beforeTestExecution(ExtensionContext context) {
        System.out.println("Ext1 Before: " + context.getDisplayName());
    }
}

class Ext2 implements BeforeTestExecutionCallback {
    @Override
    public void beforeTestExecution(ExtensionContext context) {
        System.out.println("Ext2 Before: " + context.getDisplayName());
    }
}

class Ext3 implements AfterTestExecutionCallback {
    @Override
    public void afterTestExecution(ExtensionContext context) {
        System.out.println("Ext3 After: " + context.getDisplayName());
    }
}

@ExtendWith({Ext1.class, Ext2.class, Ext3.class})
public class TestMultipleExtensions {
    @Test
    public void testA() {
        assertTrue(true);
    }
    
    @Test
    public void testB() {
        assertTrue(true);
    }
}
