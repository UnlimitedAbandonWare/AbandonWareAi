package com.example.lms.governance;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelfAskPlannerOwnershipContractTest {

    private static final String LEGACY_SELF_ASK_FQCN = "service.rag.planner.SelfAskPlanner";
    private static final String CANONICAL_SOURCE =
            "main/java/com/example/lms/service/rag/SelfAskPlanner.java";
    private static final String REMOVED_LEGACY_ROOT_SOURCE =
            "main/java/service/rag/planner/SelfAskPlanner.java";
    private static final String APP_SOURCE =
            "app/src/main/java_clean/service/rag/planner/SelfAskPlanner.java";
    private static final Pattern LEGACY_SELF_ASK_PACKAGE = Pattern.compile(
            "(?m)^\\s*package\\s+service\\.rag\\.planner\\s*;");
    private static final Pattern SELF_ASK_CLASS_DECLARATION = Pattern.compile(
            "\\bclass\\s+SelfAskPlanner\\b");
    private static final Pattern SELF_ASK_SIMPLE_REFERENCE =
            Pattern.compile("\\bSelfAskPlanner\\b");

    @Test
    void canonicalSelfAskPlannerIsTheOnlySelfAskPlannerSource() throws Exception {
        Path workspace = workspaceRoot();
        assertTrue(
                Files.isRegularFile(workspace.resolve(CANONICAL_SOURCE)),
                "canonical SelfAskPlanner source must exist: " + CANONICAL_SOURCE);
        assertFalse(
                Files.exists(workspace.resolve(REMOVED_LEGACY_ROOT_SOURCE)),
                "removed legacy compatibility source must stay absent");
        assertFalse(
                Files.exists(workspace.resolve(APP_SOURCE)),
                "app compatibility source must be removed");

        Path mainSourceRoot = workspace.resolve("main/java");
        List<String> declarations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(mainSourceRoot)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .map(Path::normalize)
                    .filter(SelfAskPlannerOwnershipContractTest::declaresSelfAskPlannerClass)
                    .map(workspace::relativize)
                    .map(path -> path.toString().replace('\\', '/'))
                    .sorted()
                    .forEach(declarations::add);
        }

        assertEquals(
                List.of(CANONICAL_SOURCE),
                declarations,
                "exactly one SelfAskPlanner class may exist under main/java; found=" + declarations);
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

    private static boolean declaresSelfAskPlannerClass(Path sourcePath) {
        final String source;
        try {
            source = Files.readString(sourcePath);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to inspect source: " + sourcePath, exception);
        }
        return SELF_ASK_CLASS_DECLARATION.matcher(source).find();
    }

    private static boolean directlyReferencesCompatibilityCopy(Path sourcePath) {
        final String source;
        try {
            source = Files.readString(sourcePath);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to inspect app source: " + sourcePath, exception);
        }

        if (source.contains(LEGACY_SELF_ASK_FQCN)) {
            return true;
        }
        return LEGACY_SELF_ASK_PACKAGE.matcher(source).find()
                && SELF_ASK_SIMPLE_REFERENCE.matcher(source).find();
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
