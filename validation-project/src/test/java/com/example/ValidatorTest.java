package com.example;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ValidatorTest {
    private Calculator calculator = new Calculator();
    private Parser parser = new Parser();
    private Validator validator = new Validator(calculator, parser);

    @Test
    public void testValidateSum() {
        assertTrue(validator.validateSum("1, 2, 3", 6));
        assertTrue(validator.validateSum("10, 20", 30));
        assertFalse(validator.validateSum("1, 2, 3", 10));
    }

    @Test
    public void testCalculateProduct() {
        assertEquals(6, validator.calculateProduct("1, 2, 3"));
        assertEquals(0, validator.calculateProduct("1, 0, 5"));
        assertEquals(24, validator.calculateProduct("2, 3, 4"));
    }

    @Test
    public void testIntegrationWithComplexInput() {
        assertTrue(validator.validateSum("100, 200, 300", 600));
        assertEquals(120000, validator.calculateProduct("10, 20, 30, 2"));
    }
}
