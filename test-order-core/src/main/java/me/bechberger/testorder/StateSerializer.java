package me.bechberger.testorder;

import me.bechberger.util.json.JSONParser;
import me.bechberger.util.json.PrettyPrinter;
import net.jpountz.lz4.LZ4BlockInputStream;
import net.jpountz.lz4.LZ4BlockOutputStream;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

final class StateSerializer {

    private StateSerializer() {}

    static void save(Path file, TestOrderState state) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        byte[] jsonBytes = PrettyPrinter.compactPrint(state.toPersistedRoot()).getBytes(StandardCharsets.UTF_8);
        LZ4Compressor compressor = LZ4Factory.fastestInstance().highCompressor(17);
        Path tempFile = PersistenceSupport.temporarySibling(file);
        try (var out = new LZ4BlockOutputStream(Files.newOutputStream(tempFile), 1 << 16, compressor)) {
            out.write(jsonBytes);
        }
        PersistenceSupport.moveIntoPlace(tempFile, file);
        state.afterSave();
    }

    static TestOrderState load(Path file) throws IOException {
        Path loadPath = PersistenceSupport.resolveLoadPath(file);
        if (!Files.exists(loadPath)) {
            return new TestOrderState();
        }
        byte[] raw = readRaw(file, loadPath);
        if (raw.length == 0) {
            return new TestOrderState();
        }
        String json = decode(raw);
        if (json.isEmpty()) {
            return new TestOrderState();
        }
        try {
            return TestOrderState.fromPersistedRoot(TestOrderState.safeMap(JSONParser.parse(json), "root"));
        } catch (IOException | RuntimeException primaryFailure) {
            Path tempFile = PersistenceSupport.temporarySibling(file);
            if (!loadPath.equals(tempFile) && Files.exists(tempFile)) {
                return TestOrderState.fromPersistedRoot(
                        TestOrderState.safeMap(JSONParser.parse(decode(Files.readAllBytes(tempFile))), "root"));
            }
            if (primaryFailure instanceof IOException ioe) throw ioe;
            throw new IOException("Failed to load state: " + primaryFailure.getMessage(), primaryFailure);
        }
    }

    private static byte[] readRaw(Path file, Path loadPath) throws IOException {
        try {
            return Files.readAllBytes(loadPath);
        } catch (IOException primaryFailure) {
            Path tempFile = PersistenceSupport.temporarySibling(file);
            if (!loadPath.equals(tempFile) && Files.exists(tempFile)) {
                return Files.readAllBytes(tempFile);
            }
            throw primaryFailure;
        }
    }

    private static String decode(byte[] raw) throws IOException {
        if (raw.length == 0) {
            return "";
        }
        if (raw[0] == '{' || raw[0] == ' ' || raw[0] == '\n' || raw[0] == '\r' || raw[0] == '\t') {
            return new String(raw, StandardCharsets.UTF_8).strip();
        }
        try (var in = new LZ4BlockInputStream(new ByteArrayInputStream(raw))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        }
    }
}
