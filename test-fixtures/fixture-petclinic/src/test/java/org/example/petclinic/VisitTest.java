package org.example.petclinic;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

class VisitTest {

    @Test
    void visitStoresDateAndDescription() {
        var pet   = new Pet("Basil", "cat", LocalDate.of(2019, 1, 1));
        var visit = new Visit(pet, LocalDate.of(2024, 6, 10), "annual check-up");
        assertEquals(LocalDate.of(2024, 6, 10), visit.getDate());
        assertEquals("annual check-up", visit.getDescription());
    }

    @Test
    void visitIsTodayReturnsTrueForToday() {
        var pet   = new Pet("Basil", "cat", LocalDate.of(2019, 1, 1));
        var visit = new Visit(pet, LocalDate.now(), "emergency");
        assertTrue(visit.isToday());
    }

    @Test
    void nullDescriptionBecomesEmptyString() {
        var pet   = new Pet("Max", "dog", LocalDate.of(2020, 5, 5));
        var visit = new Visit(pet, LocalDate.now(), null);
        assertEquals("", visit.getDescription());
    }

    @Test
    void nullPetIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new Visit(null, LocalDate.now(), "checkup"));
    }
}
