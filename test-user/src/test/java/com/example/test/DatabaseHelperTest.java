package com.example.test;

import com.example.DatabaseHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

public class DatabaseHelperTest {
    private DatabaseHelper db = new DatabaseHelper();

    @BeforeEach
    public void setUp() {
        db.clear();
    }

    @Test
    public void testSaveAndGet() {
        db.save("key1", "value1");
        assertThat(db.get("key1")).isEqualTo("value1");
    }

    @Test
    public void testDelete() {
        db.save("key2", "value2");
        db.delete("key2");
        assertThat(db.get("key2")).isNull();
    }

    @Test
    public void testSize() {
        db.save("key3", "value3");
        db.save("key4", "value4");
        assertThat(db.size()).isEqualTo(2);
    }
}
