package com.example.test;

import com.example.Calculator;
import com.example.StringProcessor;
import com.example.DatabaseHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

public class IntegrationTest {
    private Calculator calc = new Calculator();
    private StringProcessor processor = new StringProcessor();
    private DatabaseHelper db = new DatabaseHelper();

    @BeforeEach
    public void setUp() {
        db.clear();
    }

    @Test
    public void testCalcResultStoredInDb() {
        int result = calc.add(5, 3);
        db.save("result", String.valueOf(result));
        assertThat(db.get("result")).isEqualTo("8");
    }

    @Test
    public void testStringProcessedAndStored() {
        String processed = processor.toUpperCase("test");
        db.save("processed", processed);
        assertThat(db.get("processed")).isEqualTo("TEST");
    }

    @Test
    public void testComplexWorkflow() {
        int num = calc.multiply(3, 4);
        String text = processor.reverse("abc");
        db.save("number", String.valueOf(num));
        db.save("text", text);
        assertThat(db.size()).isEqualTo(2);
    }
}
