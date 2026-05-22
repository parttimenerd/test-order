package com.myapp.service;

import com.other.BaseTest;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class InheritedTest extends BaseTest {

    @Test
    public void testInChild() {
        assertEquals(2, 2);
    }
}
