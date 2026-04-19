package com.example.nested;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests only the leaf Employee class — should NOT depend on Company or Department.
 */
class EmployeeOnlyTest {

    @Test
    void createAndPromote() {
        Employee e = new Employee("Bob", "Developer", 3);
        e.promote();
        assertEquals(4, e.getLevel());
        assertEquals("Bob", e.getName());
    }

    @Test
    void titleIsPreserved() {
        Employee e = new Employee("Carol", "Designer", 2);
        assertEquals("Designer", e.getTitle());
    }
}
