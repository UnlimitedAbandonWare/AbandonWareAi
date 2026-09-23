package com.example.lms.api;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatApiControllerRestoreProbeContractTest {

    @Test
    void restoreProbeSoftensOnlyBrowserRestoreMissingSession() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/api/ChatApiController.java"),
                StandardCharsets.UTF_8);

        int method = source.indexOf("public ResponseEntity<?> getSession(");
        int restoreParam = source.indexOf(
                "@RequestParam(name = \"restoreProbe\", defaultValue = \"false\") boolean restoreProbe",
                method);
        int sessionNull = source.indexOf("if (session == null) {", restoreParam);
        int restoreBranch = source.indexOf("if (restoreProbe) {", sessionNull);
        int missingSoft = source.indexOf("restoreProbeReset(\"SESSION_UNAVAILABLE\")", restoreBranch);
        int strict404 = source.indexOf("return ResponseEntity.status(HttpStatus.NOT_FOUND)", missingSoft);
        int forbiddenSoft = source.indexOf("restoreProbeReset(\"SESSION_UNAVAILABLE\")", strict404);
        int helper = source.indexOf("private static ResponseEntity<Map<String, Object>> restoreProbeReset", forbiddenSoft);
        int foundFalse = source.indexOf("\"found\", false", helper);

        assertTrue(method > 0, "session detail endpoint should exist");
        assertTrue(restoreParam > method, "restore probe should be an explicit opt-in query parameter");
        assertTrue(sessionNull > restoreParam, "restore probe should only affect missing sessions");
        assertTrue(restoreBranch > sessionNull, "missing restore probes should use the soft branch");
        assertTrue(missingSoft > restoreBranch, "missing restore probes should call the soft reset helper");
        assertTrue(strict404 > missingSoft, "ordinary missing session detail calls must remain strict 404");
        assertTrue(forbiddenSoft > strict404, "restore probes should also soften stale inaccessible sessions");
        assertTrue(forbiddenSoft > missingSoft,
                "missing and inaccessible probes should use one non-enumerating public error code");
        assertTrue(foundFalse > helper, "soft restore probe should return found=false");
    }
}
