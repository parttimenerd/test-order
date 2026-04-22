package com.example;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class IntegrationStringUtilsIT {
    
    @Test
    public void testCountVowelsSimple() {
        int count = StringUtils.countVowels("hello");
        assertEquals(2, count);
    }
    
    @Test
    public void testCountVowelsWithUppercase() {
        int count = StringUtils.countVowels("HeLLo");
        assertEquals(2, count);
    }
    
    @Test
    public void testCountVowelsNoVowels() {
        int count = StringUtils.countVowels("xyz");
        assertEquals(0, count);
    }
}
