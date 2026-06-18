package org.example.petclinic;

import java.time.LocalDate;

public class Visit {
    private final Pet       pet;
    private final LocalDate date;
    private final String    description;

    public Visit(Pet pet, LocalDate date, String description) {
        if (pet  == null) throw new IllegalArgumentException("pet required");
        if (date == null) throw new IllegalArgumentException("date required");
        this.pet         = pet;
        this.date        = date;
        this.description = description == null ? "" : description;
    }

    public Pet       getPet()         { return pet; }
    public LocalDate getDate()        { return date; }
    public String    getDescription() { return description; }

    public boolean isToday() { return LocalDate.now().equals(date); }
}
