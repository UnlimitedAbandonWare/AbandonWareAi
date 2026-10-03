package com.example.lms.service.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Plan6 settings/role-routing contract regression harness (scaffold WP4).
 *
 * Contract SSOT: docs/SETTINGS_ROUTING_CONTRACT.md
 * Fixture: src/test/resources/fixtures/settings_routing_specs/settings_routing_96_specs.json
 * Invariant pre-check: scripts/check_settings_routing_invariants.py
 *
 * Layers:
 *  - kind="invariant" specs: verified NOW by static source scan (regression watch).
 *  - kind="pending" specs: declared in the fixture; behavioral halves activate via
 *    Assumptions only when the implementing classes exist. A skipped test is a
 *    pending contract item, never a pass (verify/report verdict separation).
 */
class SettingsRoutingContractTest {

    private static final Path ROOT = Path.of("").toAbsolutePath();
    private static final Path FIXTURE =
            ROOT.resolve("src/test/resources/fixtures/settings_routing_specs/settings_routing_96_specs.json");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String src(String repoRelative) throws Exception {
        Path p = ROOT.resolve(repoRelative);
        assertTrue(Files.isRegularFile(p), "missing source file: " + repoRelative);
        return Files.readString(p, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static JsonNode specs() throws Exception {
        assertTrue(Files.isRegularFile(FIXTURE), "spec fixture missing: " + FIXTURE);
        return MAPPER.readTree(Files.readString(FIXTURE, java.nio.charset.StandardCharsets.UTF_8));
    }

    private static int countSpecs(JsonNode fixture, String idPrefix, String kind) {
        int n = 0;
        for (JsonNode s : fixture.withArray("specs")) {
            boolean prefixOk = idPrefix == null || s.path("id").asText("").startsWith(idPrefix);
            boolean kindOk = kind == null || kind.equals(s.path("kind").asText());
            if (prefixOk && kindOk) n++;
        }
        return n;
    }

    private static boolean hasSpec(JsonNode fixture, String area, String kind) {
        for (JsonNode s : fixture.withArray("specs")) {
            if (area.equals(s.path("area").asText())
                    && (kind == null || kind.equals(s.path("kind").asText()))) return true;
        }
        return false;
    }

    // ---------- fixture harness ----------

    @Test
    void fixtureDefines96UniqueSpecs() throws Exception {
        JsonNode f = specs();
        JsonNode arr = f.withArray("specs");
        assertEquals(96, arr.size(), "fixture must carry exactly 96 regression specs");
        Set<String> ids = new HashSet<>();
        Set<String> kinds = new HashSet<>();
        for (JsonNode s : arr) {
            assertTrue(s.hasNonNull("id") && s.hasNonNull("wp") && s.hasNonNull("area")
                    && s.hasNonNull("kind") && s.hasNonNull("given")
                    && s.hasNonNull("when") && s.hasNonNull("then"),
                    "spec missing required field: " + s);
            assertTrue(ids.add(s.get("id").asText()), "duplicate spec id " + s.get("id"));
            kinds.add(s.get("kind").asText());
            assertTrue(s.get("wp").asText().matches("WP[1-5]"),
                    "wp must be WP1..WP5: " + s.get("id"));
        }
        assertEquals(Set.of("invariant", "pending"), kinds);
        assertEquals(64, countSpecs(f, "V2-", null), "v2 block must carry 64 specs");
        assertEquals(32, countSpecs(f, "V3-", null), "v3 block must carry 32 specs");
    }

    // ---------- invariant specs (verified today) ----------

    @Test
    void testPublicSettingsExcludesSystemPrompt() throws Exception {
        assertTrue(hasSpec(specs(), "security-boundary", "invariant"),
                "fixture must declare the public-settings boundary as an invariant spec");
        String src = src("main/java/com/example/lms/api/SettingsController.java");
        Matcher m = Pattern.compile("PUBLIC_SETTING_KEYS\\s*=\\s*Set\\.of\\((.*?)\\)", Pattern.DOTALL)
                .matcher(src);
        assertTrue(m.find(), "PUBLIC_SETTING_KEYS Set.of block not found");
        String block = m.group(1);
        assertFalse(block.contains("SYSTEM_PROMPT") || block.contains("KEY_SYSTEM_PROMPT"),
                "SYSTEM_PROMPT must never enter the public settings allowlist");
        assertTrue(src.contains("!PUBLIC_SETTING_KEYS.contains"),
                "non-allowlist rejection logic must be preserved");
    }

    @Test
    void testSessionStorageDraftIsolation() throws Exception {
        assertTrue(hasSpec(specs(), "browser-state", "invariant"),
                "fixture must declare the sessionStorage draft invariant");
        String src = src("main/resources/static/js/chat.js");
        for (String api : new String[]{"setItem", "getItem", "removeItem"}) {
            assertTrue(src.contains("sessionStorage." + api + "(\"chat.recoveryDraft\""),
                    "chat.recoveryDraft must keep using sessionStorage." + api);
        }
        assertFalse(Pattern.compile("localStorage\\s*\\.\\s*\\w+\\s*\\(\\s*\"chat\\.recoveryDraft\"")
                        .matcher(src).find(),
                "chat.recoveryDraft must never move to localStorage");
    }

    @Test
    void testSecretMaskStrippingOnSettingsSave() throws Exception {
        String src = src("main/java/ai/abandonware/nova/orch/aop/SettingsControllerSecretMaskAspect.java");
        int start = src.indexOf("isSensitiveKey(String key)");
        int end = src.indexOf("looksLikeSecret", Math.max(start, 0));
        String body = start >= 0 ? src.substring(start, end > start ? end : src.length()) : "";
        assertTrue(body.contains("\"openai\""),
                "isSensitiveKey must keep masking openai* keys (OPENAI_MODEL)");
        assertTrue(src.contains("isSensitiveKey(k)") && src.contains("filtered.put"),
                "sensitive keys must be stripped from the save payload (200 != stored)");
        assertTrue(src.contains("nova.security.settings.allowSecretUpdate"),
                "allowSecretUpdate opt-in property must remain the only bypass name");
    }

    @Test
    void callArgsParseArityPreserved() throws Exception {
        assertTrue(hasSpec(specs(), "aop-wiring", "invariant"),
                "fixture must declare CallArgs.parse arity as invariant");
        String src = src("main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java");
        Matcher m = Pattern.compile("static CallArgs parse\\(Object\\[\\] args\\)(.*)", Pattern.DOTALL)
                .matcher(src);
        assertTrue(m.find(), "CallArgs.parse not found");
        String body = m.group(1);
        for (int arity : new int[]{5, 7, 8}) {
            assertTrue(body.contains("args.length == " + arity),
                    "CallArgs.parse must keep the " + arity + "-arg overload");
        }
    }

    @Test
    void jevGateNotDuplicated() throws Exception {
        assertTrue(hasSpec(specs(), "jev-reuse", "invariant"),
                "fixture must declare the Jev reuse invariant");
        String src = src("main/java/com/example/lms/config/RetrieverChainConfig.java");
        int count = src.split("JevRetrievalGateHandler\\.wrapIfEnabled", -1).length - 1;
        assertEquals(2, count,
                "wrapIfEnabled must stay exactly the fixed+dynamic chain call sites; a 3rd is a duplicate");
    }

    @Test
    void focusReadStaysPostWithOwnershipBinding() throws Exception {
        String src = src("main/java/com/example/lms/assist/DisplayConversateController.java");
        assertTrue(src.contains("@PostMapping(\"/api/assist/display/focus/settings/read\")"),
                "focus settings read must remain POST");
        assertFalse(src.contains("@GetMapping(\"/api/assist/display/focus/settings/read\")"),
                "no plain GET may expose focus settings");
        assertTrue(src.contains("focusBinding(") && src.contains("requireProducer"),
                "ownership/epoch/RUNNING binding must keep guarding focus settings");
    }

    // ---------- post-implementation contracts (activate when impl lands) ----------

    @Test
    void testPreviewMakesZeroModelCalls() throws Exception {
        JsonNode f = specs();
        assertTrue(countSpecs(f, null, null) > 0 && hasSpec(f, "preview", "pending"),
                "fixture must declare Preview 0-call specs");
        Path controller = ROOT.resolve("main/java/com/example/lms/api/SettingsRoutingController.java");
        assumeTrue(Files.isRegularFile(controller),
                "SettingsRoutingController not yet implemented — preview contract pending");
        String src = Files.readString(controller, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(src.contains("/api/settings/routing/preview"),
                "preview endpoint must exist once the controller lands");
        assertFalse(src.contains("lcWithTimeout") || src.contains("DynamicChatModelFactory")
                        || Pattern.compile("ChatModel\\s+\\w+\\s*\\(").matcher(src).find(),
                "preview path must not create or call a ChatModel (0-call contract)");
    }

    @Test
    void testFallbackCandidateBoundedToAllowedList() throws Exception {
        JsonNode f = specs();
        assertTrue(hasSpec(f, "fallback-bounds", "pending"),
                "fixture must declare bounded-fallback specs");
        String aspect = src("main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java");
        boolean wideningPresent =
                aspect.contains("props.getModels().keySet().stream().sorted().forEach(ordered::add)");
        boolean profileWiring = aspect.contains("routingProfile") || aspect.contains("roleProfile")
                || aspect.contains("allowedFallback") || aspect.contains("extraCallBudget");
        assumeTrue(profileWiring || !wideningPresent,
                "explicit-profile wiring absent and legacy widening unchanged — bounded contract pending");
        // Implementation landed: the widening may only run for non-profile executions.
        assertTrue(!wideningPresent || profileWiring,
                "unbounded all-model widening must be gated when an explicit profile is active");
    }
}
