package com.example.lms.service.prompt;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptAssetServicePublicContractTest {

    @Test
    void publicResolverLoadsOnlyAssetIds() {
        PromptAssetService service = new PromptAssetService(new DefaultResourceLoader());

        String asset = service.resolveSystemPromptText("projection.final");

        assertTrue(asset != null && asset.contains("projection.final"));
        assertNull(service.resolveSystemPromptText("You are a raw public system prompt"));
        assertNull(service.resolveSystemPromptText("missing.prompt.id"));
        assertNull(service.resolveSystemPromptText("../projection.final"));
    }

    @Test
    void publicTraitResolverRejectsTraversalOutsideTraitAssets() {
        PromptAssetService service = new PromptAssetService(new DefaultResourceLoader());

        assertTrue(service.resolveTraitText("stuff11_ko").contains("stuff11"));
        assertNull(service.resolveTraitText("../system/projection.final"));
    }

    @Test
    void trustedResolverKeepsLiteralFallbackForInternalPlansOnly() {
        PromptAssetService service = new PromptAssetService(new DefaultResourceLoader());

        String literal = "Internal checked-in plan prompt";

        assertTrue(service.resolveTrustedSystemPromptText(literal).contains(literal));
        assertTrue(service.resolveTrustedSystemPromptText("projection.final").contains("projection.final"));
    }

    @Test
    void assetReadFailureLeavesStageBreadcrumb() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/prompt/PromptAssetService.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("log.debug(\"[PromptAssetService] fail-soft stage={}\", \"tryRead\")"));
    }
}
