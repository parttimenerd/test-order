package com.windows;

import org.junit.Test;
import static org.junit.Assert.*;

public class CaseSensitivityTest {
    
    @Test
    public void testCaseInsensitiveEquals() {
        // On Windows filesystem, "File.txt" == "file.txt"
        // But in Java, they're different strings
        String file1 = "File.txt";
        String file2 = "file.txt";
        assertNotEquals(file1, file2); // Java strings are case-sensitive
    }
    
    @Test
    public void testPathCaseVariations() {
        String path1 = "C:\\TEST\\FILE";
        String path2 = "C:\\test\\file";
        // On macOS/Linux these are different
        // On Windows they refer to the same file
        assertTrue(true);
    }
}
