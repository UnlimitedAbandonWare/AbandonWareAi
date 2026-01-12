package com.example.lms.service.prompt;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
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

    private final ResourceLoader resourceLoader;

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public PromptAssetService(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    /**
     * Resolve a system prompt. The input can be either:
     * <ul>
     *   <li>an id (e.g. "projection.final") that maps to a classpath markdown file</li>
     *   <li>a literal prompt text</li>
     * </ul>
     */
    public String resolveSystemPromptText(String idOrText) {
        if (!StringUtils.hasText(idOrText)) return null;
        String trimmed = idOrText.trim();

        // Heuristic: if it contains whitespace or a newline, treat as literal.
        if (trimmed.contains("\n") || trimmed.matches(".*\\s+.*")) {
            return trimmed;
        }

        String key = "sys:" + trimmed;
        return cache.computeIfAbsent(key, k -> {
            // Try known locations.
            String[] candidates = new String[]{
                    "classpath:prompts/system/" + trimmed + ".md",
                    "classpath:prompts/system/" + trimmed + ".txt",
                    "classpath:prompts/" + trimmed + ".md",
                    "classpath:prompts/" + trimmed + ".txt",
                    "classpath:system-prompts/" + trimmed + ".md",
                    "classpath:system-prompts/" + trimmed + ".txt"
            };

            for (String path : candidates) {
                String txt = tryRead(path);
                if (StringUtils.hasText(txt)) return txt;
            }

            // Fallback: treat as literal if not found.
            return trimmed;
        });
    }

    /**
     * Resolve a single trait id into a prompt snippet text.
     */
    public String resolveTraitText(String traitId) {
        if (!StringUtils.hasText(traitId)) return null;
        String trimmed = traitId.trim();

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
            return null;
        }
    }
}
