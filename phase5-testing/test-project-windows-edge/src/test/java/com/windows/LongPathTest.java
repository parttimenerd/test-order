package com.windows;

import org.junit.Test;
import static org.junit.Assert.*;

public class LongPathTest {
    
    @Test
    public void testWithLongPath() {
        // Windows has 260 character limit (MAX_PATH)
        StringBuilder path = new StringBuilder("a");
        for (int i = 0; i < 250; i++) {
            path.append("/b");
        }
        assertTrue(path.length() > 260);
    }
    
    @Test
    public void testPathWithBackslashes() {
        String winPath = "C:\\Users\\test\\path\\to\\file.txt";
        assertTrue(winPath.contains("\\"));
    }
    
    @Test
    public void testUNCPath() {
        String uncPath = "\\\\server\\share\\file.txt";
        assertTrue(uncPath.startsWith("\\\\"));
    }
}
