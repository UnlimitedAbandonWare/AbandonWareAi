package com.example.lms.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class SettingsServiceRedactionContractTest {

    @Test
    void settingSaveLogDoesNotWriteRawSettingValue() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/SettingsService.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("{} = {}\", key, value"));
        assertTrue(source.contains("valueHash"));
        assertTrue(source.contains("valueLength"));
        assertTrue(source.contains("SafeRedactor.hashValue(value)"));
    }
}
