package com.example.lms.uaw.thumbnail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UawThumbnailPlanLoaderRedactionContractTest {

    @Test
    void missingPlanLogUsesPathHashInsteadOfRawPath() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/uaw/thumbnail/UawThumbnailPlanLoader.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("plan not found: {} -> using defaults\", path"));
        assertTrue(source.contains("plan not found pathHash={} pathLength={} -> using defaults"));
        assertTrue(source.contains("com.example.lms.trace.SafeRedactor.hashValue(path), path.length()"));
    }

    @Test
    void thumbnailPlanKeepsLoadContract() throws Exception {
        UawThumbnailPlanSpec plan = new ObjectMapper(new YAMLFactory())
                .findAndRegisterModules()
                .readValue(Path.of("main/resources/plans/UAW_thumbnail.v1.yaml").toFile(), UawThumbnailPlanSpec.class);

        assertEquals("UAW_thumbnail.v1", plan.id());
        assertEquals("uaw_thumbnail", plan.kind());
        assertEquals(6, plan.evidence().finalK());
        assertEquals("UAW_THUMB", plan.persist().domain());
    }
}
