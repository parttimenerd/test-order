import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import static org.junit.Assert.*;
import java.util.concurrent.TimeUnit;

public class TestWithTimeout {
    @Rule
    public Timeout globalTimeout = Timeout.builder()
        .withTimeout(1, TimeUnit.SECONDS)
        .withLookingForStuckThread(true)
        .build();
    
    @Test
    public void test_fast() {
        assertTrue(true);
    }
}
