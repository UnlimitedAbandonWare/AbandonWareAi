package com.example.lms.governance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelfAskPlannerOwnershipContractTest {

    private static final String SELF_ASK_FQCN = "service.rag.planner.SelfAskPlanner";
    private static final String ROOT_SOURCE = "main/java/service/rag/planner/SelfAskPlanner.java";
    private static final String APP_SOURCE =
            "app/src/main/java_clean/service/rag/planner/SelfAskPlanner.java";
    private static final String EVIDENCE_FILE = "reports/dup-fqcn-evidence.json";
    private static final String GENERATED_EXCLUDES_FILE = "generated/dup-fqcn-excludes.txt";
    private static final String SELF_ASK_EXCLUDE_PATTERN = "service/rag/planner/SelfAskPlanner*";
    private static final Pattern SELF_ASK_PACKAGE = Pattern.compile(
            "(?m)^\\s*package\\s+service\\.rag\\.planner\\s*;");
    private static final Pattern SELF_ASK_SIMPLE_REFERENCE =
            Pattern.compile("\\bSelfAskPlanner\\b");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void selfAskCompatibilityCopyIsRemovedAndNothingIsPackagedActive() throws Exception {
        Path workspace = workspaceRoot();
        Path evidencePath = duplicateEvidencePath(workspace);
        Path generatedExcludesPath = generatedExcludesPath(workspace);

        assertTrue(
                Files.isRegularFile(evidencePath),
                "fresh :app:generateDupFqcnExcludes evidence is required: " + evidencePath);
        assertTrue(
                Files.isRegularFile(generatedExcludesPath),
                "fresh generated duplicate excludes are required: " + generatedExcludesPath);

        JsonNode evidence = OBJECT_MAPPER.readTree(evidencePath.toFile());
        assertEquals("current", evidence.path("status").asText(), "duplicate evidence must be current");
        assertEquals(
                0,
                evidence.path("duplicateFqcnPackagedActiveCount").asInt(-1),
                "no duplicate FQCN may remain packaged active");
        assertEquals(
                0,
                evidence.path("duplicateFqcnActiveCount").asInt(-1),
                "the compatibility active-count alias must also remain zero");

        List<JsonNode> selfAskRows = new ArrayList<>();
        evidence.path("collisions").forEach(row -> {
            if (SELF_ASK_FQCN.equals(row.path("fqcn").asText())) {
                selfAskRows.add(row);
            }
        });

        assertEquals(List.of(), selfAskRows, "removed compatibility source must not collide with the root owner");
        assertTrue(Files.isRegularFile(workspace.resolve(ROOT_SOURCE)), "root SelfAsk source must exist");
        assertFalse(Files.exists(workspace.resolve(APP_SOURCE)), "app compatibility source must be removed");
        long generatedSelfAskExcludes = Files.readAllLines(generatedExcludesPath).stream()
                .map(String::trim)
                .filter(SELF_ASK_EXCLUDE_PATTERN::equals)
                .count();
        assertEquals(0L, generatedSelfAskExcludes, "removed compatibility source needs no generated exclude");
    }

    @Test
    void appCompatibilityCopyHasNoDirectCallers() throws Exception {
        Path workspace = workspaceRoot();
        Path appSourceRoot = workspace.resolve("app/src/main/java_clean");
        Path compatibilityCopy = workspace.resolve(APP_SOURCE).normalize();
        List<String> callers = new ArrayList<>();

        try (Stream<Path> files = Files.walk(appSourceRoot)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .map(Path::normalize)
                    .filter(path -> !path.equals(compatibilityCopy))
                    .filter(SelfAskPlannerOwnershipContractTest::directlyReferencesCompatibilityCopy)
                    .map(workspace::relativize)
                    .map(path -> path.toString().replace('\\', '/'))
                    .sorted()
                    .forEach(callers::add);
        }

        assertEquals(
                List.of(),
                callers,
                "app compatibility SelfAsk directCallerCount must remain zero; callers=" + callers);
    }

    private static boolean directlyReferencesCompatibilityCopy(Path sourcePath) {
        final String source;
        try {
            source = Files.readString(sourcePath);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to inspect app source: " + sourcePath, exception);
        }

        if (source.contains(SELF_ASK_FQCN)) {
            return true;
        }
        return SELF_ASK_PACKAGE.matcher(source).find()
                && SELF_ASK_SIMPLE_REFERENCE.matcher(source).find();
    }

    private static Path duplicateEvidencePath(Path workspace) {
        boolean splitOutputs = isEnabled(System.getenv("AWX_SPLIT_BUILD_OUTPUTS"));
        if (!splitOutputs) {
            return workspace.resolve("app/build").resolve(EVIDENCE_FILE);
        }

        String hostId = sanitizeHostId(System.getenv("AWX_BUILD_HOST_ID"));
        String externalBuildRoot = System.getenv("AWX_BUILD_ROOT_DIR");
        if (externalBuildRoot != null && !externalBuildRoot.isBlank()) {
            return Path.of(externalBuildRoot.trim()).resolve(hostId).resolve("app").resolve(EVIDENCE_FILE);
        }
        return workspace.resolve("app/build").resolve(hostId).resolve(EVIDENCE_FILE);
    }

    private static Path generatedExcludesPath(Path workspace) {
        return duplicateEvidencePath(workspace).getParent().getParent().resolve(GENERATED_EXCLUDES_FILE);
    }

    private static boolean isEnabled(String value) {
        if (value == null) {
            return false;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "on" -> true;
            default -> false;
        };
    }

    private static String sanitizeHostId(String value) {
        String candidate = value == null ? "local" : value;
        String sanitized = candidate.replaceAll("[^A-Za-z0-9._-]+", "-")
                .replaceAll("^[._-]+|[._-]+$", "");
        return sanitized.isBlank() ? "host" : sanitized;
    }

    private static Path workspaceRoot() throws URISyntaxException {
        Path cursor = Path.of(SelfAskPlannerOwnershipContractTest.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                .toAbsolutePath()
                .normalize();
        while (cursor != null) {
            if (Files.isRegularFile(cursor.resolve("settings.gradle.kts"))
                    && Files.isDirectory(cursor.resolve("main/java"))
                    && Files.isDirectory(cursor.resolve("app/src/main/java_clean"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("unable to resolve repository root from test class location");
    }
}
