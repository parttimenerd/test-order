package com.example.nested;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests only the middle layer (Department + Team), not Company or Employee directly.
 */
class MiddleLayerTest {

    @Test
    void departmentHoldsTeam() {
        Department dept = new Department("Sales");
        Team team = new Team("Enterprise");
        dept.setFrontendTeam(team);
        assertEquals("Enterprise", dept.getFrontendTeam().getName());
    }

    @Test
    void teamNameIsCorrect() {
        Team team = new Team("Backend");
        assertEquals("Backend", team.getName());
    }
}
