package me.bechberger.testorder.changes;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AnnotationMethodDebugTest {

    @Test
    void debugAnnotationMethods() throws IOException {
        Path sourceFile = Path.of("").toAbsolutePath();
        // Navigate up to workspace root
        while (sourceFile != null && !Files.isDirectory(sourceFile.resolve("java_files"))) {
            sourceFile = sourceFile.getParent();
        }
        assertNotNull(sourceFile, "Could not find workspace root");
        Path file = sourceFile.resolve("java_files/Java8_RepeatingAnnotations.java");
        assertTrue(Files.isRegularFile(file), "File not found: " + file);

        String source = Files.readString(file);
        String stripped = SourceFileModel.stripCommentsAndStrings(source);

        System.out.println("=== Stripped source ===");
        System.out.println(stripped);

        SourceFileModel.Model model = SourceFileModel.parse(source, "", SourceFileModel.Detail.METHODS);

        System.out.println("\n=== Types ===");
        for (var t : model.types()) {
            System.out.println("  " + t.kind() + " " + t.fqcn() + " bodyStart=" + t.bodyStart() + " bodyEnd=" + t.bodyEnd());
        }

        System.out.println("\n=== Methods ===");
        for (var m : model.methods()) {
            System.out.println("  " + m.enclosingFqcn() + "#" + m.name() + " ctor=" + m.isConstructor() + " abstract=" + m.isAbstract());
        }
    }
}
