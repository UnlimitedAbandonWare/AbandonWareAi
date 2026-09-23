package com.example.lms.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ActiveResourceArtifactPolicyTest {

    @Test
    void activeResourcesDoNotContainBackupArtifacts() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Path root : List.of(Path.of("main/resources"), Path.of("app/src/main/resources"))) {
            if (!Files.exists(root)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(root)) {
                paths.filter(Files::isRegularFile)
                        .filter(ActiveResourceArtifactPolicyTest::isBackupArtifact)
                        .map(Path::toString)
                        .forEach(offenders::add);
            }
        }

        assertTrue(offenders.isEmpty(),
                "Backup artifacts must not live in active resource roots: " + offenders);
    }

    private static boolean isBackupArtifact(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith("~")
                || name.endsWith(".orig")
                || name.endsWith(".old")
                || name.contains(".bak");
    }
}
