package org.example.petclinic;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

class PetTest {

    @Test
    void ageIsComputedFromBirthDate() {
        // born exactly 3 years ago
        var pet = new Pet("Basil", "cat", LocalDate.now().minusYears(3));
        assertEquals(3, pet.getAge());
    }

    @Test
    void youngPetHasAgeZero() {
        var pet = new Pet("Pip", "rabbit", LocalDate.now().minusMonths(6));
        assertEquals(0, pet.getAge());
    }

    @Test
    void nullBirthDateGivesAgeZero() {
        var pet = new Pet("Max", "dog", null);
        assertEquals(0, pet.getAge());
    }

    @Test
    void blankNameIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new Pet("  ", "dog", LocalDate.now()));
    }
}
