package com.abandonware.ai.agent.contract;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidationExceptionTest {

    @Test
    void errorsAreDefensivelyCopiedAndImmutable() {
        Set<String> errors = new LinkedHashSet<>();
        errors.add("missing field");

        ValidationException exception = new ValidationException(errors);
        errors.add("mutated later");

        assertEquals(Set.of("missing field"), exception.getErrors());
        assertThrows(UnsupportedOperationException.class, () -> exception.getErrors().add("new"));
    }

    @Test
    void nullErrorsBecomeEmptySet() {
        ValidationException exception = new ValidationException(null);

        assertTrue(exception.getErrors().isEmpty());
    }
}
