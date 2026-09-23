package com.example.lms.service.prompt;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads small prompt "assets" (traits, system prompt snippets) from the classpath.
 *
 * <p>These are intentionally lightweight and designed for composing SystemMessages.
 * This service is used by the plan/pipeline layer (e.g. projection_agent.v1) to
 * apply {@code traits} and {@code system-prompt} hints.
 */
@Service
public class PromptAssetService {

    private static final Logger log = LoggerFactory.getLogger(PromptAssetService.class);

    private final ResourceLoader resourceLoader;

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public PromptAssetService(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    /**
     * Resolve a public system prompt id. Literal prompt text is intentionally
     * rejected at this boundary; callers that own trusted internal plan text
     * must use {@link #resolveTrustedSystemPromptText(String)} explicitly.
     */
    public String resolveSystemPromptText(String idOrText) {
        if (!StringUtils.hasText(idOrText)) return null;
        String trimmed = idOrText.trim();

        if (!isSafePromptId(trimmed)) return null;

        return resolveSystemPromptAsset(trimmed);
    }

    /**
     * Resolve trusted internal system prompt text. This is for plan/admin owned
     * flows where literal text is part of a checked-in plan or an authenticated
     * internal control surface, never for direct public chat input.
     */
    public String resolveTrustedSystemPromptText(String idOrText) {
        if (!StringUtils.hasText(idOrText)) return null;
        String trimmed = idOrText.trim();

        if (isSafePromptId(trimmed)) {
            String asset = resolveSystemPromptAsset(trimmed);
            if (StringUtils.hasText(asset)) return asset;
        }
        return trimmed;
    }

    private String resolveSystemPromptAsset(String promptId) {
        if (!StringUtils.hasText(promptId)) return null;

        String key = "sys:" + promptId;
        return cache.computeIfAbsent(key, k -> {
            // Try known locations.
            String[] candidates = new String[]{
                    "classpath:prompts/system/" + promptId + ".md",
                    "classpath:prompts/system/" + promptId + ".txt",
                    "classpath:prompts/" + promptId + ".md",
                    "classpath:prompts/" + promptId + ".txt",
                    "classpath:system-prompts/" + promptId + ".md",
                    "classpath:system-prompts/" + promptId + ".txt"
            };

            for (String path : candidates) {
                String txt = tryRead(path);
                if (StringUtils.hasText(txt)) return txt;
            }
            return null;
        });
    }

    /**
     * Resolve a single trait id into a prompt snippet text.
     */
    public String resolveTraitText(String traitId) {
        if (!StringUtils.hasText(traitId)) return null;
        String trimmed = traitId.trim();
        if (!isSafePromptId(trimmed)) return null;

        String key = "trait:" + trimmed;
        return cache.computeIfAbsent(key, k -> {
            String[] candidates = new String[]{
                    "classpath:prompts/traits/" + trimmed + ".md",
                    "classpath:prompts/traits/" + trimmed + ".txt",
                    "classpath:traits/" + trimmed + ".md",
                    "classpath:traits/" + trimmed + ".txt"
            };
            for (String path : candidates) {
                String txt = tryRead(path);
                if (StringUtils.hasText(txt)) return txt;
            }
            return null;
        });
    }

    /**
     * Render multiple traits into a single system prompt snippet.
     */
    public String renderTraits(List<String> traitIds) {
        if (traitIds == null || traitIds.isEmpty()) return null;

        List<String> parts = new ArrayList<>();
        for (String id : traitIds) {
            String txt = resolveTraitText(id);
            if (StringUtils.hasText(txt)) parts.add(txt.trim());
        }

        if (parts.isEmpty()) return null;
        String joined = String.join("\n\n---\n\n", parts);

        // Safety: avoid blowing up the token budget.
        int maxChars = 20_000;
        if (joined.length() > maxChars) {
            return joined.substring(0, maxChars) + "\n\n…(traits truncated)…";
        }
        return joined;
    }

    private String tryRead(String path) {
        try {
            Resource r = resourceLoader.getResource(path);
            if (!r.exists()) return null;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(r.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                return sb.toString().trim();
            }
        } catch (IOException e) {
            log.debug("[PromptAssetService] fail-soft stage={}", "tryRead");
            return null;
        }
    }

    private static boolean isSafePromptId(String value) {
        if (!StringUtils.hasText(value) || value.length() > 128) return false;
        if (value.contains("..") || value.startsWith("/") || value.startsWith("\\")) return false;
        return value.matches("[A-Za-z0-9._/-]+");
    }
}
