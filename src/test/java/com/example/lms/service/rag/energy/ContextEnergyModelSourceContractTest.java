package com.example.lms.service.rag.energy;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

class ContextEnergyModelSourceContractTest {

    @Test
    void contextEnergyModelDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/rag/energy/ContextEnergyModel.java"));

        assertFalse(source.matches("(?s).*catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}.*"),
                "ContextEnergyModel fail-soft paths need fixed-stage breadcrumbs instead of exact empty catches");
    }
}
