package com.example.nested;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Traverses the full chain: Company → Department → Team → Employee.
 * Should depend on ALL four classes.
 */
class DeepChainTest {

    private Company company;

    @BeforeEach
    void setUp() {
        Employee alice = new Employee("Alice", "Tech Lead", 5);
        Team frontend = new Team("Frontend");
        frontend.setLead(alice);
        Department eng = new Department("Engineering");
        eng.setFrontendTeam(frontend);
        company = new Company("Acme");
        company.setEngineering(eng);
    }

    @Test
    void traverseFullChain() {
        String leadName = company.getEngineering()
                .getFrontendTeam()
                .getLead()
                .getName();
        assertEquals("Alice", leadName);
    }

    @Test
    void promoteViaDeepAccess() {
        Employee lead = company.getEngineering().getFrontendTeam().getLead();
        lead.promote();
        assertEquals(6, lead.getLevel());
    }
}
