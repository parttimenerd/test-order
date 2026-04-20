package me.bechberger.testorder;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ClassNameTrieTest {

    @Test
    void insertAndLookup() {
        ClassNameTrie trie = new ClassNameTrie();
        trie.insert("com.example.Foo");
        trie.insert("com.example.Bar");
        trie.insert("com.example.FooBar");
        trie.assignIds();

        assertEquals(3, trie.size());
        assertNotEquals(trie.getId("com.example.Foo"), trie.getId("com.example.Bar"));
        assertEquals("com.example.Foo", trie.getName(trie.getId("com.example.Foo")));
        assertEquals("com.example.Bar", trie.getName(trie.getId("com.example.Bar")));
        assertEquals("com.example.FooBar", trie.getName(trie.getId("com.example.FooBar")));
    }

    @Test
    void dfsAssignsContiguousIds() {
        ClassNameTrie trie = new ClassNameTrie();
        trie.insert("org.example.a.X");
        trie.insert("org.example.a.Y");
        trie.insert("org.example.b.Z");
        trie.assignIds();

        // DFS on sorted children: a.X and a.Y should get contiguous IDs
        int idX = trie.getId("org.example.a.X");
        int idY = trie.getId("org.example.a.Y");
        assertEquals(1, Math.abs(idX - idY), "siblings should have contiguous IDs");
    }

    @Test
    void serializeDeserializeRoundTrip() throws IOException {
        ClassNameTrie trie = new ClassNameTrie();
        List<String> names = List.of(
                "org.springframework.samples.petclinic.owner.OwnerControllerTest",
                "org.springframework.samples.petclinic.owner.OwnerController",
                "org.springframework.samples.petclinic.vet.VetController",
                "org.springframework.samples.petclinic.vet.VetControllerTest",
                "com.example.Simple"
        );
        for (String n : names) {
            trie.insert(n);
        }
        trie.assignIds();

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        trie.writeTo(new DataOutputStream(bos));

        ClassNameTrie loaded = ClassNameTrie.readFrom(
                new DataInputStream(new ByteArrayInputStream(bos.toByteArray())));

        assertEquals(trie.size(), loaded.size());
        for (String n : names) {
            assertEquals(trie.getId(n), loaded.getId(n));
            assertEquals(n, loaded.getName(loaded.getId(n)));
        }
    }

    @Test
    void emptyTrie() {
        ClassNameTrie trie = new ClassNameTrie();
        trie.assignIds();
        assertEquals(0, trie.size());
    }

    @Test
    void singleEntry() throws IOException {
        ClassNameTrie trie = new ClassNameTrie();
        trie.insert("A");
        trie.assignIds();
        assertEquals(1, trie.size());
        assertEquals(0, trie.getId("A"));
        assertEquals("A", trie.getName(0));

        // round-trip
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        trie.writeTo(new DataOutputStream(bos));
        ClassNameTrie loaded = ClassNameTrie.readFrom(
                new DataInputStream(new ByteArrayInputStream(bos.toByteArray())));
        assertEquals(1, loaded.size());
        assertEquals("A", loaded.getName(0));
    }

    @Test
    void prefixIsSeparateEntry() {
        // "Foo" and "FooBar" where one is a prefix of the other
        ClassNameTrie trie = new ClassNameTrie();
        trie.insert("Foo");
        trie.insert("FooBar");
        trie.assignIds();

        assertEquals(2, trie.size());
        assertNotEquals(trie.getId("Foo"), trie.getId("FooBar"));
        assertEquals("Foo", trie.getName(trie.getId("Foo")));
        assertEquals("FooBar", trie.getName(trie.getId("FooBar")));
    }

    @Test
    void unknownNameThrows() {
        ClassNameTrie trie = new ClassNameTrie();
        trie.insert("A");
        trie.assignIds();
        assertThrows(NoSuchElementException.class, () -> trie.getId("B"));
    }

    @Test
    void varintRoundTrip() throws IOException {
        int[] values = {0, 1, 127, 128, 255, 256, 16383, 16384, Integer.MAX_VALUE};
        for (int v : values) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ClassNameTrie.writeVarInt(new DataOutputStream(bos), v);
            int read = ClassNameTrie.readVarInt(
                    new DataInputStream(new ByteArrayInputStream(bos.toByteArray())));
            assertEquals(v, read, "varint round-trip for " + v);
        }
    }

    @Test
    void trieCompactnessVsSortedList() throws IOException {
        ClassNameTrie trie = new ClassNameTrie();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            String name = "org.springframework.samples.petclinic.service.Class" + i;
            trie.insert(name);
            sb.append(name).append('\n');
        }
        trie.assignIds();

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        trie.writeTo(new DataOutputStream(bos));
        int trieSize = bos.size();
        int plainSize = sb.toString().getBytes().length;

        assertTrue(trieSize < plainSize,
                "trie (" + trieSize + " bytes) should be smaller than plain list (" + plainSize + " bytes)");
    }

    // ── Bug #86: readVarInt shift bounds check ──

    @Test
    void readVarIntRejectsOverflowContinuationBytes() {
        // 6 continuation bytes (all 0x80) followed by no terminator → shift exceeds 35
        byte[] corrupt = {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80};
        assertThrows(IOException.class, () ->
                ClassNameTrie.readVarInt(new DataInputStream(new ByteArrayInputStream(corrupt))));
    }

    @Test
    void readVarIntHandlesValidValues() throws IOException {
        // Round-trip small, medium, and large values
        for (int val : new int[]{0, 1, 127, 128, 16384, Integer.MAX_VALUE}) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ClassNameTrie.writeVarInt(new DataOutputStream(bos), val);
            int read = ClassNameTrie.readVarInt(new DataInputStream(new ByteArrayInputStream(bos.toByteArray())));
            assertEquals(val, read, "Round-trip failed for " + val);
        }
    }
}
