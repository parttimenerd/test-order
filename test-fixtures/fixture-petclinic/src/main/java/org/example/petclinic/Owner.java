package org.example.petclinic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Owner {
    private final String firstName;
    private final String lastName;
    private final String phone;
    private final List<Pet> pets = new ArrayList<>();

    public Owner(String firstName, String lastName, String phone) {
        if (firstName == null || firstName.isBlank()) throw new IllegalArgumentException("firstName required");
        if (lastName  == null || lastName.isBlank())  throw new IllegalArgumentException("lastName required");
        this.firstName = firstName;
        this.lastName  = lastName;
        this.phone     = phone;
    }

    public void addPet(Pet pet) { pets.add(pet); }
    public List<Pet> getPets()  { return Collections.unmodifiableList(pets); }
    public String getFullName() { return firstName + " " + lastName; }
    public String getPhone()    { return phone; }

    /** Returns the owner's initials, e.g. "J.D." for John Doe. */
    public String getInitials() {
        return Character.toUpperCase(firstName.charAt(0)) + "." +
               Character.toUpperCase(lastName.charAt(0))  + ".";
    }
}
