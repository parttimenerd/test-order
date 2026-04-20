package com.example.petshop;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
public class PetRepositoryDataJpaTest {
    @Autowired
    private PetRepository petRepository;

    @Test
    public void testSavePet() {
        Pet pet = new Pet("Fluffy", "Cat");
        Pet saved = petRepository.save(pet);
        assertNotNull(saved.getId());
    }

    @Test
    public void testFindByName() {
        Pet pet = new Pet("Buddy", "Dog");
        petRepository.save(pet);
        Pet found = petRepository.findByName("Buddy");
        assertEquals("Dog", found.getBreed());
    }

    @Test
    public void testDeletePet() {
        Pet pet = new Pet("Whiskers", "Cat");
        Pet saved = petRepository.save(pet);
        petRepository.deleteById(saved.getId());
        assertTrue(petRepository.findById(saved.getId()).isEmpty());
    }
}
