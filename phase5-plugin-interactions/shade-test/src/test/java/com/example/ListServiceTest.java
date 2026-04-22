package com.example;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.assertEquals;

public class ListServiceTest {
    private ListService service = new ListService();
    
    @Test
    public void testCreateAndSize() {
        List<String> list = service.createList("a", "b", "c");
        assertEquals(3, service.getSize(list));
    }
    
    @Test
    public void testEmptyList() {
        List<Integer> list = service.createList();
        assertEquals(0, service.getSize(list));
    }
    
    @Test
    public void testSingleElement() {
        List<String> list = service.createList("test");
        assertEquals(1, service.getSize(list));
    }
}
