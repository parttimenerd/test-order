package com.example.test;

import com.example.StringProcessor;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

public class StringProcessorTest {
    private StringProcessor processor = new StringProcessor();

    @Test
    public void testToUpperCase() {
        assertThat(processor.toUpperCase("hello")).isEqualTo("HELLO");
    }

    @Test
    public void testToLowerCase() {
        assertThat(processor.toLowerCase("WORLD")).isEqualTo("world");
    }

    @Test
    public void testReverse() {
        assertThat(processor.reverse("hello")).isEqualTo("olleh");
    }

    @Test
    public void testCountWords() {
        assertThat(processor.countWords("hello world test")).isEqualTo(3);
    }
}
