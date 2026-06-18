package org.example.petclinic;

import java.util.Arrays;
import java.util.List;

public class Vet {
    private final String name;
    private final List<String> specialties;

    public Vet(String name, String... specialties) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name required");
        this.name        = name;
        this.specialties = Arrays.asList(specialties);
    }

    public String       getName()        { return name; }
    public List<String> getSpecialties() { return specialties; }

    public boolean hasSpecialty(String s) {
        return specialties.stream().anyMatch(sp -> sp.equalsIgnoreCase(s));
    }

    public boolean canTreat(Pet pet) {
        return hasSpecialty(pet.getSpecies()) || specialties.isEmpty();
    }
}
