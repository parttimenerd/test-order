package com.windows;

import org.junit.Test;
import static org.junit.Assert.*;

public class WindowsReservedNamesTest {
    
    @Test
    public void testWithConInPath() {
        // Simulates Windows CON reserved name
        String path = "test/CON/file.txt";
        assertTrue(!path.isEmpty());
    }
    
    @Test
    public void testWithPrnInPath() {
        // Simulates Windows PRN reserved name
        String path = "test/PRN/file.txt";
        assertTrue(!path.isEmpty());
    }
    
    @Test
    public void testWithAuxInPath() {
        // Simulates Windows AUX reserved name
        String path = "test/AUX/file.txt";
        assertTrue(!path.isEmpty());
    }
    
    @Test
    public void testWithCom1InPath() {
        // Simulates Windows COM port name
        String path = "test/COM1/file.txt";
        assertTrue(!path.isEmpty());
    }
}
