package com.example.petshop;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class PetShopIntegrationTest {
    @Autowired
    private PetRepository petRepository;

    @Autowired
    private PetController petController;

    @Test
    public void testIntegration() {
        assertNotNull(petRepository);
        assertNotNull(petController);
    }

    @Test
    public void testFullWorkflow() {
        Pet pet = new Pet("Mittens", "Cat");
        Pet saved = petRepository.save(pet);
        assertNotNull(saved.getId());

        Pet retrieved = petRepository.findById(saved.getId()).orElse(null);
        assertNotNull(retrieved);
        assertEquals("Mittens", retrieved.getName());
    }

    @Test
    public void testMultipleOperations() {
        for (int i = 0; i < 5; i++) {
            Pet pet = new Pet("Pet" + i, "Type" + i);
            petRepository.save(pet);
        }
        assertTrue(petRepository.count() >= 5);
    }
}
