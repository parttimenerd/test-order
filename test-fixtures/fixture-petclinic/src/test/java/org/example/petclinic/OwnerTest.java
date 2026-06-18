package org.example.petclinic;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OwnerTest {

    @Test
    void fullNameCombinesFirstAndLast() {
        var owner = new Owner("James", "Carter", "555-1234");
        assertEquals("James Carter", owner.getFullName());
    }

    @Test
    void initialsFormatCorrectly() {
        var owner = new Owner("Helen", "Leary", "555-0000");
        assertEquals("H.L.", owner.getInitials());
    }

    @Test
    void blankFirstNameIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new Owner("", "Carter", "555-1234"));
    }

    @Test
    void addPetAppearsInList() {
        var owner = new Owner("James", "Carter", "555-1234");
        var pet   = new Pet("Leo", "cat", java.time.LocalDate.of(2020, 3, 1));
        owner.addPet(pet);
        assertEquals(1, owner.getPets().size());
        assertSame(pet, owner.getPets().get(0));
    }
}
