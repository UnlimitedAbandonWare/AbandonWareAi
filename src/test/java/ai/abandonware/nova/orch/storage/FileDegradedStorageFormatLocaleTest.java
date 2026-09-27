package ai.abandonware.nova.orch.storage;

import ai.abandonware.nova.config.NovaOrchestrationProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileDegradedStorageFormatLocaleTest {

    @TempDir
    Path tempDir;

    @Test
    void uppercaseDirFormatSelectsDirectoryModeUnderTurkishLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            Path uppercaseDir = tempDir.resolve("explicit-uppercase.jsonl");
            Path lowercaseDir = tempDir.resolve("explicit-lowercase.jsonl");
            Path autoJsonl = tempDir.resolve("auto.jsonl");

            storage(uppercaseDir, "DIR");
            storage(lowercaseDir, "dir");
            storage(autoJsonl, "auto");

            assertAll(
                    () -> assertTrue(Files.isDirectory(uppercaseDir)),
                    () -> assertTrue(Files.isDirectory(lowercaseDir)),
                    () -> assertFalse(Files.isDirectory(autoJsonl)));
        } finally {
            Locale.setDefault(previous);
        }
    }

    private static FileDegradedStorage storage(Path path, String format) {
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        props.getDegradedStorage().setPath(path.toString());
        props.getDegradedStorage().setFormat(format);
        return new FileDegradedStorage(props, new ObjectMapper());
    }
}
