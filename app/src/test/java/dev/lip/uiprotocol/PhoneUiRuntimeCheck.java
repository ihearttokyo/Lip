package dev.lip.uiprotocol;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import static org.junit.Assert.*;

/** Explicit bytecode regression check; does not load or execute Android classes. */
public final class PhoneUiRuntimeCheck {
    public static void main(String[] arguments) throws Exception {
        assertEquals("Supply compiled adapter directory or baseline DEX", 1, arguments.length);
        Path target = Path.of(arguments[0]);
        List<Path> bytecode;
        if (Files.isDirectory(target)) {
            assertTrue("Missing compiled adapter", Files.isRegularFile(target.resolve("dev/lip/PhoneUiRunner.class")));
            assertTrue("Missing compiled protocol", Files.isRegularFile(target.resolve("dev/lip/uiprotocol/PhoneUiProtocol.class")));
            try (Stream<Path> files = Files.walk(target)) {
                bytecode = files.filter(path -> path.toString().endsWith(".class")).sorted().toList();
            }
        } else {
            bytecode = List.of(target);
        }
        assertFalse("No compiled bytecode", bytecode.isEmpty());
        for (Path file : bytecode) {
            String bytes = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
            assertFalse("Kotlin linkage in " + file, bytes.contains("kotlin/"));
        }
        System.out.println("PASS: no Kotlin references in " + bytecode.size() + " bytecode files");
    }
}
