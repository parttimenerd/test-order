package com.example.petshop;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PetController.class)
public class PetControllerWebMvcTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PetRepository petRepository;

    @Test
    public void testGetPetEndpoint() throws Exception {
        Pet pet = new Pet("Max", "Dog");
        pet.setId(1L);
        when(petRepository.findById(1L)).thenReturn(java.util.Optional.of(pet));

        mockMvc.perform(get("/pets/1"))
                .andExpect(status().isOk());
    }

    @Test
    public void testCreatePetEndpoint() throws Exception {
        Pet pet = new Pet("Spot", "Dog");
        pet.setId(2L);
        when(petRepository.save(any())).thenReturn(pet);

        mockMvc.perform(post("/pets")
                        .contentType("application/json")
                        .content("{\"name\":\"Spot\",\"breed\":\"Dog\"}"))
                .andExpect(status().isOk());
    }

    @Test
    public void testGetPetNotFound() throws Exception {
        when(petRepository.findById(999L)).thenReturn(java.util.Optional.empty());

        mockMvc.perform(get("/pets/999"))
                .andExpect(status().isOk());
    }
}
