package com.example.petshop;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/pets")
public class PetController {
    @Autowired
    private PetRepository petRepository;

    @GetMapping("/{id}")
    public Pet getPet(@PathVariable Long id) {
        return petRepository.findById(id).orElse(null);
    }

    @PostMapping
    public Pet createPet(@RequestBody Pet pet) {
        return petRepository.save(pet);
    }
}
