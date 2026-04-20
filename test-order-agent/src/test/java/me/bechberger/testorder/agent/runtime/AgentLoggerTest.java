package me.bechberger.testorder.agent.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.io.PrintWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentLoggerTest {

    @AfterEach
    void resetLogger() throws Exception {
        setWriter(null, false);
    }

    @Test
    void missingDirIsHandledGracefully(@TempDir Path dir) {
        Path missingParent = dir.resolve("missing/logs/agent.log");

        assertDoesNotThrow(() -> AgentLogger.setVerboseFile(missingParent.toString()));
        assertFalse(AgentLogger.isVerbose());
        assertFalse(Files.exists(missingParent));
    }

    @Test
    void concurrentVerboseFileSwitchesDoNotCrash(@TempDir Path dir) throws Exception {
        Path first = dir.resolve("first.log");
        Path second = dir.resolve("second.log");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Void>> tasks = List.of(
                    () -> {
                        AgentLogger.setVerboseFile(first.toString());
                        return null;
                    },
                    () -> {
                        AgentLogger.setVerboseFile(second.toString());
                        return null;
                    });
            List<Future<Void>> futures = executor.invokeAll(tasks);
            for (Future<Void> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        AgentLogger.log("hello");

        long totalHits = 0;
        if (Files.exists(first)) {
            totalHits += Files.readAllLines(first).stream().filter("hello"::equals).count();
        }
        if (Files.exists(second)) {
            totalHits += Files.readAllLines(second).stream().filter("hello"::equals).count();
        }
        assertEquals(1, totalHits);
        assertTrue(AgentLogger.isVerbose());
    }

    @Test
    void writeFailureDisablesVerboseLogging() throws Exception {
        PrintWriter broken = new PrintWriter(new Writer() {
            @Override
            public void write(char[] cbuf, int off, int len) throws java.io.IOException {
                throw new java.io.IOException("boom");
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        setWriter(broken, true);

        assertDoesNotThrow(() -> AgentLogger.log("broken"));
        assertFalse(AgentLogger.isVerbose());
    }

    private static void setWriter(PrintWriter writer, boolean enabled) throws Exception {
        Field writerField = AgentLogger.class.getDeclaredField("writer");
        writerField.setAccessible(true);
        writerField.set(null, writer);

        Field enabledField = AgentLogger.class.getDeclaredField("enabled");
        enabledField.setAccessible(true);
        enabledField.setBoolean(null, enabled);
    }
}
