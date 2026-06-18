package org.example.petclinic;

import java.time.LocalDate;

public class Pet {
    private final String name;
    private final String species;   // "cat", "dog", …
    private final LocalDate birthDate;

    public Pet(String name, String species, LocalDate birthDate) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name required");
        this.name      = name;
        this.species   = species;
        this.birthDate = birthDate;
    }

    public String    getName()      { return name; }
    public String    getSpecies()   { return species; }
    public LocalDate getBirthDate() { return birthDate; }

    /** Age in full years (0 for less than one year old). */
    public int getAge() {
        if (birthDate == null) return 0;
        return (int) java.time.temporal.ChronoUnit.YEARS.between(birthDate, java.time.LocalDate.now());
    }
}
