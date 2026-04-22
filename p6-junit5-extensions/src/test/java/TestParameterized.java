import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

public class TestParameterized {
    @ParameterizedTest
    @ValueSource(ints = { 1, 2, 3, 4, 5 })
    public void testIntValues(int value) {
        assertTrue(value > 0);
    }
    
    @ParameterizedTest
    @ValueSource(strings = { "apple", "banana", "cherry" })
    public void testStringValues(String fruit) {
        assertNotNull(fruit);
        assertTrue(fruit.length() > 0);
    }
}
