package com.example.lms.routing;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Env-name inventory parsing must handle both inline ({@code env: [A, B]})
 * and block ({@code env:} + indented {@code - NAME}) YAML array forms while
 * ignoring neighbouring route keys.
 */
class ApiRoutingInventoryLoggerTest {

    private static Set<String> parse(String yaml) throws Exception {
        Set<String> names = new LinkedHashSet<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(yaml))) {
            ApiRoutingInventoryLogger.parseEnvNames(reader, names);
        }
        return names;
    }

    @Test
    void inlineArrayNamesAreCollected() throws Exception {
        Set<String> names = parse(
                "routes:\n"
                        + "  - id: brave\n"
                        + "    env: [BRAVE_API_KEY_FREE, BRAVE_API_KEY]\n"
                        + "  - id: tavily\n"
                        + "    env: [TAVILY_API_KEY] # trailing comment\n");
        assertTrue(names.contains("BRAVE_API_KEY_FREE"));
        assertTrue(names.contains("BRAVE_API_KEY"));
        assertTrue(names.contains("TAVILY_API_KEY"));
    }

    @Test
    void blockArrayNamesAreCollected() throws Exception {
        Set<String> names = parse(
                "routes:\n"
                        + "  - id: deepgram\n"
                        + "    env:\n"
                        + "      - DEEPGRAM_API_KEY\n"
                        + "      - DEEPGRAM_API_KEY_FALLBACK\n"
                        + "    enabled: true\n");
        assertTrue(names.contains("DEEPGRAM_API_KEY"));
        assertTrue(names.contains("DEEPGRAM_API_KEY_FALLBACK"));
        assertFalse(names.contains("enabled"));
    }

    @Test
    void nonEnvListItemsAndKeysAreIgnored() throws Exception {
        Set<String> names = parse(
                "routes:\n"
                        + "  - id: naver\n"
                        + "    seams: [naver.search]\n"
                        + "    env: [NAVER_CLIENT_ID]\n"
                        + "    notenv: [SHOULD_NOT_MATCH]\n"
                        + "  - id: other\n");
        assertTrue(names.contains("NAVER_CLIENT_ID"));
        assertFalse(names.contains("SHOULD_NOT_MATCH"));
        assertFalse(names.contains("naver.search"));
        assertFalse(names.contains("id"));
    }

    @Test
    void quotedAndEmptyInlineFormsAreHandled() throws Exception {
        Set<String> names = parse(
                "routes:\n"
                        + "  - id: a\n"
                        + "    env: ['QUOTED_ENV', \"DOUBLE_QUOTED_ENV\"]\n"
                        + "  - id: b\n"
                        + "    env: []\n");
        assertTrue(names.contains("QUOTED_ENV"));
        assertTrue(names.contains("DOUBLE_QUOTED_ENV"));
    }

    @Test
    void liveApiRoutingYamlProducesEnvNames() throws Exception {
        Path yaml = Path.of("main/resources/configs/api-routing.yaml");
        assertTrue(Files.exists(yaml), "live api-routing.yaml must exist");
        Set<String> names = new LinkedHashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(yaml, StandardCharsets.UTF_8)) {
            ApiRoutingInventoryLogger.parseEnvNames(reader, names);
        }
        assertTrue(names.size() > 10, "expected many env names, got " + names.size());
        assertTrue(names.contains("BRAVE_API_KEY_FREE"), "inline env arrays must parse");
        for (String name : names) {
            assertTrue(name.matches("[A-Z][A-Z0-9_]+"), "only env names may be collected: " + name);
        }
    }
}
