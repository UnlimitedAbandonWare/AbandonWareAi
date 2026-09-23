package com.example.lms.service.guard;

import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CitationGateNamespaceContractTest {

    @Test
    void activeCitationGateSimpleNameDuplicatesHaveExplicitOwnership() throws Exception {
        assertFalse(CitationGate.class.isAnnotationPresent(Deprecated.class));
        assertTrue(com.example.lms.guard.CitationGate.class.isAnnotationPresent(Component.class));
        assertFalse(Files.exists(Path.of("main/java/com/example/lms/nova/gate/CitationGate.java")));
        assertFalse(com.nova.protocol.guard.CitationGate.class.isAnnotationPresent(Component.class));
    }
}
