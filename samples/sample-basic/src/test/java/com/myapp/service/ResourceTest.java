package com.myapp.service;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Paths;
import static org.junit.jupiter.api.Assertions.*;

public class ResourceTest {

    @Test
    void testMissingResource() throws Exception {
        String content = Files.readString(Paths.get("src/test/resources/missing-file.txt"));
        assertNotNull(content);
    }

    @Test
    void testNormal() {
        assertEquals(1, 1);
    }
}
