package com.myapp.service;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;

public class LifecycleTest {
    static int counter = 0;
    
    @BeforeEach
    void setup() {
        counter++;
        System.err.println("Setup called, counter=" + counter);
    }
    
    @AfterEach
    void teardown() {
        System.err.println("Teardown called");
    }
    
    @Test
    void test1() {
        assertEquals(1, counter);
    }
    
    @Test
    void test2() {
        assertEquals(2, counter);
    }
}
