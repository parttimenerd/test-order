package org.example.petclinic;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VetTest {

    @Test
    void vetWithNoSpecialtiesCanTreatAnything() {
        var vet = new Vet("Dr. Stone");
        var pet = new Pet("Max", "dog", java.time.LocalDate.of(2020, 1, 1));
        assertTrue(vet.canTreat(pet));
    }

    @Test
    void vetCanTreatMatchingSpecialty() {
        var vet = new Vet("Dr. Adams", "dog", "rabbit");
        var pet = new Pet("Max", "dog", java.time.LocalDate.of(2020, 1, 1));
        assertTrue(vet.canTreat(pet));
    }

    @Test
    void vetCannotTreatUnknownSpecies() {
        var vet = new Vet("Dr. Adams", "dog");
        var cat = new Pet("Basil", "cat", java.time.LocalDate.of(2019, 1, 1));
        assertFalse(vet.canTreat(cat));
    }

    @Test
    void hasSpecialtyIsCaseInsensitive() {
        var vet = new Vet("Dr. Adams", "Dog");
        assertTrue(vet.hasSpecialty("dog"));
        assertTrue(vet.hasSpecialty("DOG"));
    }
}
