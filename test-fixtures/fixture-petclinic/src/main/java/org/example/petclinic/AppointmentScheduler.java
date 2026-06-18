package org.example.petclinic;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class AppointmentScheduler {
    private final List<Visit> visits = new ArrayList<>();

    public Visit book(Pet pet, Vet vet, LocalDate date, String reason) {
        if (!vet.canTreat(pet))
            throw new IllegalStateException(
                vet.getName() + " does not treat " + pet.getSpecies());
        Visit v = new Visit(pet, date, reason);
        visits.add(v);
        return v;
    }

    public List<Visit> getVisitsFor(Pet pet) {
        return visits.stream()
            .filter(v -> v.getPet() == pet)
            .toList();
    }

    public List<Visit> getUpcoming(LocalDate from) {
        return visits.stream()
            .filter(v -> !v.getDate().isBefore(from))
            .sorted(java.util.Comparator.comparing(Visit::getDate))
            .toList();
    }
}
