import org.junit.runners.BlockJUnit4ClassRunner;

public class CustomReverseRunner extends BlockJUnit4ClassRunner {
    public CustomReverseRunner(Class<?> testClass) throws Throwable {
        super(testClass);
    }
}
