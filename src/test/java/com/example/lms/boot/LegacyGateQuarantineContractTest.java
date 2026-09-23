package com.example.lms.boot;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyGateQuarantineContractTest {

    private static final List<Path> SPRING_DUPLICATE_COMPONENTS = List.of(
            Path.of("main/java/com/abandonware/ai/agent/orchestrator/FinalSigmoidGate.java"),
            Path.of("main/java/com/abandonware/ai/addons/synthesis/MatrixTransformer.java")
    );

    @Test
    void springAnnotatedLegacyGateAndMatrixDuplicatesAreOptInOnly() throws IOException {
        for (Path path : SPRING_DUPLICATE_COMPONENTS) {
            assertTrue(Files.exists(path), "expected legacy duplicate source to exist: " + path);
            String source = Files.readString(path);

            assertTrue(source.contains("@Component"), "source must remain a Spring component when opted in: " + path);
            assertTrue(source.contains("ConditionalOnProperty"), "legacy component must be property quarantined: " + path);
            assertTrue(source.contains("legacy.overlay"), "legacy component must use legacy.overlay opt-in: " + path);
        }
    }

    @Test
    void legacyMatrixBeanFactoryIsAlsoOptInOnly() throws IOException {
        String source = Files.readString(Path.of("main/java/com/abandonware/ai/addons/config/AddonsAutoConfiguration.java"));

        assertTrue(source.contains("@Bean(name = \"addonsMatrixTransformer\")"));
        assertTrue(source.contains("ConditionalOnProperty"));
        assertTrue(source.contains("legacy.overlay"));
    }

    @Test
    void legacyFinalSigmoidFallbackThresholdsAreNotUnsafe010() throws IOException {
        try (var files = Files.walk(Path.of("main/java"))) {
            List<Path> finalSigmoidFiles = files
                    .filter(path -> path.getFileName().toString().equals("FinalSigmoidGate.java"))
                    .filter(path -> !path.toString().replace('\\', '/')
                            .equals("main/java/com/example/lms/guard/FinalSigmoidGate.java"))
                    .toList();

            assertFalse(finalSigmoidFiles.isEmpty(), "expected legacy FinalSigmoidGate duplicates");
            for (Path path : finalSigmoidFiles) {
                String source = Files.readString(path);
                if (source.contains("@Component")) {
                    assertFalse(source.contains("gate.finalSigmoid.threshold:0.10"),
                            "legacy Spring gate fallback threshold must not be 0.10: " + path);
                    assertTrue(source.contains("gate.finalSigmoid.threshold:0.70"),
                            "legacy Spring gate fallback threshold should be 0.70: " + path);
                }
            }
        }
    }
}
